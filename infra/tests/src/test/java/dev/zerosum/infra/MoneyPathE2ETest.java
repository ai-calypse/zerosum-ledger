package dev.zerosum.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The money path, end to end, against the running Compose stack: order API → outbox → Kafka → ledger apply.
 *
 * <p><strong>Why this exists.</strong> Until now {@code ./gradlew e2eTest} selected no tests at all, so CI started a
 * seven-container stack and asserted nothing about it. Every other layer is covered against Testcontainers, which is
 * not the same thing: S04 found three defects — a {@code localhost} broker bootstrap, topics that were never
 * provisioned, and tokens that never reached the containers — that were invisible to a fully green test suite and
 * only reachable in a real deployment.
 *
 * <p><strong>Credentials are read from {@code .env}</strong>, not from the environment. CI generates {@code .env},
 * starts Compose and then runs Gradle without exporting anything, so a test reading {@code System.getenv} would pass
 * on a developer's shell and fail in CI — precisely the hollowness this test is meant to remove.
 */
@Tag("e2e")
class MoneyPathE2ETest {

    private static final Path ROOT = Path.of(System.getProperty("zs.rootDir"));
    private static final String ORDERS = "http://127.0.0.1:8081";
    private static final String LEDGER = "http://127.0.0.1:8082";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Generous: the path crosses a relay poll, a Kafka round trip and a batched apply. */
    private static final Duration PATIENCE = Duration.ofSeconds(60);

    private static final long FARE_MINOR = 2_500;
    private static final long PLATFORM_FEE_MINOR = 500;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, String> env = readEnv();

    @Test
    @DisplayName("an accepted money order reaches the ledger, balances to zero, and links back to its source")
    void moneyOrderReachesTheLedger() {
        String run = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String rider = "rider:e2e" + run;
        String driver = "driver:e2e" + run;
        String platform = "platform:main";
        String idempotencyKey = "e2e-" + run;

        String created = createOrder(rider, driver, idempotencyKey, run);
        JsonNode order = JSON.readTree(created);
        String orderId = order.get("order_id").asString();
        assertNotNull(orderId, "the API returned an order id");

        // The rider's receivable is the last thing to appear, so waiting on it means the whole order was applied.
        JsonNode riderBalances = awaitBalance(rider, "receivable", FARE_MINOR);

        assertEquals(FARE_MINOR, signedMinor(riderBalances, "receivable"), "the rider owes the fare");
        assertEquals(-(FARE_MINOR - PLATFORM_FEE_MINOR),
                signedMinor(awaitBalance(driver, "payable", -(FARE_MINOR - PLATFORM_FEE_MINOR)), "payable"),
                "the driver is owed the fare less the fee");

        // Zero-sum is asserted from what the LEDGER recorded, not from the constants posted a moment ago. Summing
        // values already asserted equal to this test's own fields would be a tautology that cannot fail, and it would
        // never touch the platform entry at all — the one entry no balance assertion above covers, because
        // platform:main is shared across runs and has no fixed balance.
        long riderDelta = deltaRecordedFor(rider, orderId);
        long driverDelta = deltaRecordedFor(driver, orderId);
        long platformDelta = deltaRecordedFor(platform, orderId);

        assertEquals(FARE_MINOR, riderDelta, "the ledger recorded the rider's entry");
        assertEquals(-(FARE_MINOR - PLATFORM_FEE_MINOR), driverDelta, "the ledger recorded the driver's entry");
        assertEquals(-PLATFORM_FEE_MINOR, platformDelta, "the ledger recorded the platform's entry");
        assertEquals(0, riderDelta + driverDelta + platformDelta,
                "the three USD entries the ledger stored for this order sum to zero");

        assertChangelogLinksToOrder(rider, orderId, idempotencyKey);
        assertLedgerInvariantsHold();
    }

    private String createOrder(String rider, String driver, String idempotencyKey, String run) {
        String body = """
                {"order_group_id":"trip_%s","type":"COMMERCE","reason":"trip.completed","adjusts_order_id":null,
                 "entries":[{"entity_id":"%s","account":"receivable","currency":"USD","amount_minor":%d},
                            {"entity_id":"%s","account":"payable","currency":"USD","amount_minor":%d},
                            {"entity_id":"platform:main","account":"revenue","currency":"USD","amount_minor":%d}],
                 "metadata":{"trip_id":"trip_%s"},"effective_at":"%s"}
                """.formatted(run, rider, FARE_MINOR, driver, -(FARE_MINOR - PLATFORM_FEE_MINOR),
                -PLATFORM_FEE_MINOR, run, Instant.now().toString());

        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(ORDERS + "/v1/money-orders"))
                .header("Authorization", "Bearer " + writerToken())
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));

        assertEquals(201, response.statusCode(), "the order was accepted: " + response.body());
        return response.body();
    }

    /** Polls until the ledger has applied the order, because the path between the two services is asynchronous. */
    private JsonNode awaitBalance(String entityId, String account, long expectedSigned) {
        Instant deadline = Instant.now().plus(PATIENCE);
        String lastSeen = "no response yet";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(
                            URI.create(LEDGER + "/v1/entities/" + entityId + "/balances"))
                    .header("Authorization", "Bearer " + readerToken())
                    .GET());
            if (response.statusCode() == 200) {
                JsonNode balances = JSON.readTree(response.body());
                if (hasAccount(balances, account) && signedMinor(balances, account) == expectedSigned) {
                    return balances;
                }
                lastSeen = response.body();
            } else {
                // 404 until the first entry for this entity is applied, which is expected rather than a failure.
                lastSeen = response.statusCode() + " " + response.body();
            }
            sleep(Duration.ofMillis(500));
        }
        return fail("ledger never applied " + account + " for " + entityId + " within " + PATIENCE
                + "; last response: " + lastSeen);
    }

    /**
     * The delta the ledger recorded for one entity under this order, read back from the changelog.
     *
     * <p>Pages forward because a shared entity such as {@code platform:main} accumulates rows across runs, so the row
     * for this order is not necessarily on the first page.
     */
    private long deltaRecordedFor(String entityId, String orderId) {
        // Read to the end at the largest page the API allows. A fixed page cap (formerly 50 x 100 rows) stopped
        // finding the row once platform:main passed 5,000 changelog rows, which the S08 volume run took it past.
        Long after = 0L;
        while (after != null) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(
                            LEDGER + "/v1/entities/" + entityId + "/changelog?limit=500&after_seq=" + after))
                    .header("Authorization", "Bearer " + readerToken())
                    .GET());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode body = JSON.readTree(response.body());
            for (JsonNode row : body.get("rows")) {
                if (orderId.equals(row.get("order_id").asString())) {
                    return row.get("delta_minor").asLong();
                }
            }
            JsonNode next = body.get("next_after_seq");
            after = next == null || next.isNull() ? null : next.asLong();
        }
        return fail("no changelog row for order " + orderId + " under " + entityId);
    }

    private void assertChangelogLinksToOrder(String entityId, String orderId, String idempotencyKey) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
                        URI.create(LEDGER + "/v1/entities/" + entityId + "/changelog"))
                .header("Authorization", "Bearer " + readerToken())
                .GET());
        assertEquals(200, response.statusCode(), response.body());

        JsonNode row = null;
        for (JsonNode candidate : JSON.readTree(response.body()).get("rows")) {
            if (orderId.equals(candidate.get("order_id").asString())) {
                row = candidate;   // the row for THIS order, not whichever happens to be first
            }
        }
        assertNotNull(row, "the entity's changelog contains a row for order " + orderId);

        // M6 (a): a row names the money order that caused it and the idempotency key it arrived under, so an audit
        // question can walk from a balance back to its source without a join through application logs.
        assertEquals(orderId, row.get("order_id").asString(), "the changelog row links to the money order");
        assertEquals(idempotencyKey, row.get("source").get("idempotency_key").asString(),
                "and to the idempotency key the order was created with");
    }

    private void assertLedgerInvariantsHold() {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(LEDGER + "/v1/invariants"))
                .header("Authorization", "Bearer " + readerToken())
                .GET());
        assertEquals(200, response.statusCode(), response.body());

        JsonNode invariants = JSON.readTree(response.body());
        assertTrue(invariants.get("consistent").asBoolean(),
                "the ledger's own invariants hold after the order: " + response.body());
        assertEquals(0, invariants.get("unresolved_quarantined_count").asLong(),
                "nothing was quarantined: " + response.body());
    }

    private static boolean hasAccount(JsonNode balances, String account) {
        for (JsonNode entry : balances.get("accounts")) {
            if (account.equals(entry.get("account").asString())) {
                return true;
            }
        }
        return false;
    }

    private static long signedMinor(JsonNode balances, String account) {
        for (JsonNode entry : balances.get("accounts")) {
            if (account.equals(entry.get("account").asString())) {
                return entry.get("signed_minor").asLong();
            }
        }
        throw new AssertionError("no " + account + " balance in " + balances);
    }

    private String writerToken() {
        // ZS_WRITER_TOKENS is "principal:token" pairs, comma separated. The first pair is enough to authenticate.
        String pair = env.get("ZS_WRITER_TOKENS").split(",")[0];
        return pair.substring(pair.indexOf(':') + 1);
    }

    private String readerToken() {
        return env.get("ZS_READER_TOKEN");
    }

    private static Map<String, String> readEnv() {
        Path envFile = ROOT.resolve(".env");
        if (!Files.exists(envFile)) {
            throw new IllegalStateException(".env is missing; run tools/dev/generate-env.sh before the e2e tests");
        }
        var values = new HashMap<String, String>();
        try {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int split = trimmed.indexOf('=');
                if (split > 0) {
                    values.put(trimmed.substring(0, split), trimmed.substring(split + 1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return values;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("is the Compose stack running? " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
