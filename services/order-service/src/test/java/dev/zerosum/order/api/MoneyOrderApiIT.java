package dev.zerosum.order.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.ContractSchemas;
import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.order.support.OrderTestDatabase;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M3 through HTTP: idempotency, replay, validation and the role checks (D03-2, D03-4). */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MoneyOrderApiIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final OrderTestDatabase DB = OrderTestDatabase.start();
    private static final String WRITER = "writer-token";
    private static final String READER = "reader-token";

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void context(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> OrderTestDatabase.APP);
        registry.add("spring.datasource.password", () -> DB.password(OrderTestDatabase.APP));
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("zs.auth.writer-tokens", () -> "trip-simulator:" + WRITER);
        registry.add("zs.auth.reader-token", () -> READER);
        registry.add("zs.auth.admin-token", () -> "admin-token");
        // No broker in this test, so the payment-event listener stays stopped rather than dialling one.
        registry.add("zs.consumer.enabled", () -> "false");
    }

    private static String body(String group, long fare) {
        return """
                {"order_group_id":"%s","type":"COMMERCE","reason":"trip.completed","adjusts_order_id":null,
                 "entries":[{"entity_id":"rider:R1","account":"receivable","currency":"USD","amount_minor":%d},
                            {"entity_id":"driver:D1","account":"payable","currency":"USD","amount_minor":%d},
                            {"entity_id":"platform:main","account":"revenue","currency":"USD","amount_minor":-500}],
                 "metadata":{"trip_id":"%s"},"effective_at":"2026-09-15T10:04:11.201Z"}
                """.formatted(group, fare, -(fare - 500), group);
    }

    @Test
    void sameKeyAndBodyReplaysWithTheIdenticalBody() {
        // M3 (a): one row, the replay header, and a byte-identical body.
        var created = post("m3a", body("trip_m3a", 2500), WRITER);
        assertEquals(201, created.status());
        var replayed = post("m3a", body("trip_m3a", 2500), WRITER);
        assertEquals(200, replayed.status());
        assertEquals("true", replayed.replayHeader());
        assertEquals(JSON.readTree(created.body()), JSON.readTree(replayed.body()), "the identical stored order");
    }

    @Test
    void aBodyDifferingOnlyInFormattingStillReplays() {
        assertEquals(201, post("m3a-fmt", body("trip_fmt", 2500), WRITER).status());
        String reordered = """
                {"type":"COMMERCE","order_group_id":"trip_fmt","reason":"trip.completed",
                 "metadata":{"trip_id":"trip_fmt"},"adjusts_order_id":null,
                 "entries":[{"currency":"USD","entity_id":"rider:R1","account":"receivable","amount_minor":2500},
                            {"entity_id":"driver:D1","account":"payable","currency":"USD","amount_minor":-2000},
                            {"entity_id":"platform:main","account":"revenue","currency":"USD","amount_minor":-500}],
                 "effective_at":"2026-09-15T10:04:11.201Z"}
                """;
        var replayed = post("m3a-fmt", reordered, WRITER);
        assertEquals(200, replayed.status(), "key order and whitespace are not semantic");
        assertEquals("true", replayed.replayHeader());
    }

    @Test
    void sameKeyDifferentBodyIsKeyReused() {
        assertEquals(201, post("m3b", body("trip_m3b", 2500), WRITER).status());
        var reused = post("m3b", body("trip_m3b", 9900), WRITER);
        assertEquals(422, reused.status());
        assertEquals("idempotency_key_reused", codeOf(reused.body()));   // M3 (b)
    }

    @Test
    void concurrentRequestsOnOneKeyLeaveOneOrder() throws Exception {
        int callers = 20;
        var statuses = new ConcurrentLinkedQueue<Integer>();
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(callers);
        var pool = Executors.newFixedThreadPool(callers);
        for (int i = 0; i < callers; i++) {
            pool.submit(() -> {
                try {
                    go.await();
                    statuses.add(post("m3c", body("trip_m3c", 2500), WRITER).status());
                } catch (Exception e) {
                    statuses.add(-1);
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(1, statuses.stream().filter(s -> s == 201).count(), "exactly one creation");
        assertTrue(statuses.stream().allMatch(s -> s == 201 || s == 200 || s == 409),
                "every other caller replays or gets the in-progress conflict: " + statuses);
        assertEquals(1, JSON.readTree(get("/v1/money-orders?group_id=trip_m3c", READER)).size());
    }

    @Test
    void aMissingIdempotencyKeyIsRejected() {
        var response = post(null, body("trip_m3d", 2500), WRITER);
        assertEquals(400, response.status());
        assertEquals("idempotency_key_missing", codeOf(response.body()));   // M3 (d)
    }

    @Test
    void tokensAreEnforcedOnEveryEndpoint() {
        assertEquals(401, post("auth-none", body("trip_auth", 2500), null).status());
        assertEquals(403, post("auth-reader", body("trip_auth", 2500), READER).status(),
                "a reader token must not create money");
        assertEquals(401, statusOf("/v1/money-orders?group_id=trip_auth", null));
        assertEquals(200, statusOf("/v1/money-orders?group_id=trip_auth", WRITER), "writer satisfies reader");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} is rejected as {1}")
    @org.junit.jupiter.params.provider.MethodSource("malformedBodies")
    void invalidRequestsAreRejectedRatherThanCoerced(String name, String expectedCode, String body) {
        var response = post("bad-" + name, body, WRITER);
        assertNotEquals(201, response.status(), name + " must not be accepted: " + response.body());
        assertEquals(expectedCode, codeOf(response.body()), name);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> malformedBodies() {
        String base = body("trip_bad", 2500);
        return java.util.stream.Stream.of(
                // Rule 7: this endpoint accepts COMMERCE only; every other type comes from the internal mapper.
                org.junit.jupiter.params.provider.Arguments.of("type", "validation_failed",
                        base.replace("\"COMMERCE\"", "\"REFUND\"")),
                org.junit.jupiter.params.provider.Arguments.of("sum", "not_zero_sum",
                        base.replace("-500}", "-400}")),
                org.junit.jupiter.params.provider.Arguments.of("currency", "validation_failed",
                        base.replace("\"USD\"", "\"GBP\"")),
                org.junit.jupiter.params.provider.Arguments.of("entity", "validation_failed",
                        base.replace("rider:R1", "notakind:R1")),
                // A float amount is rejected, never coerced to an integer.
                org.junit.jupiter.params.provider.Arguments.of("float", "validation_failed",
                        base.replace("\"amount_minor\":2500", "\"amount_minor\":2500.5")),
                // Server-assigned fields are unknown on this contract, so a body carrying one is rejected rather than
                // ignored, which is what stops a caller believing it chose its own order id.
                org.junit.jupiter.params.provider.Arguments.of("unknown-field", "validation_failed",
                        base.replace("{\"order_group_id\"", "{\"order_id\":\"x\",\"order_group_id\"")),
                // Duplicate keys would make the hashed request ambiguous.
                org.junit.jupiter.params.provider.Arguments.of("duplicate-key", "validation_failed",
                        base.replace("\"type\":\"COMMERCE\"", "\"type\":\"COMMERCE\",\"type\":\"COMMERCE\"")));
    }

    @Test
    void aCorrectedRetryWithTheSameKeySucceeds() {
        // Validation failures are not stored (Stripe semantics), so the key is still free.
        assertEquals(422, post("retry-key", body("trip_retry", 2500).replace("-500}", "-400}"), WRITER).status());
        assertEquals(201, post("retry-key", body("trip_retry", 2500), WRITER).status());
    }

    @Test
    void commerceGoldensAreAcceptedAndFetchedOrdersMatchTheSchema() {
        int accepted = 0;
        for (int i = 1; i <= 8; i++) {
            JsonNode golden = JSON.readTree(GoldenPayloads.byId("O" + i));
            if (!"COMMERCE".equals(golden.get("type").asString()) || !golden.get("adjusts_order_id").isNull()) {
                continue;
            }
            var response = post("golden-O" + i, goldenRequest(golden), WRITER);
            assertEquals(201, response.status(), "golden O" + i + ": " + response.body());
            accepted++;

            String orderId = JSON.readTree(response.body()).get("order_id").asString();
            String fetched = get("/v1/money-orders/" + orderId, READER);
            // The fetched order is the stored money order, so it must satisfy the D01-8 contract.
            assertEquals(List.of(), ContractSchemas.validate(ContractSchemas.MONEY_ORDER_V1,
                    withSchemaEnvelope(fetched)), "fetched order must match the money-order schema");
        }
        assertTrue(accepted > 0);
    }

    private static String goldenRequest(JsonNode golden) {
        var entries = new StringBuilder();
        for (JsonNode e : golden.get("entries")) {
            entries.append(entries.isEmpty() ? "" : ",").append("""
                    {"entity_id":"%s","account":"%s","currency":"%s","amount_minor":%d}"""
                    .formatted(e.get("entity_id").asString(), e.get("account").asString(),
                            e.get("currency").asString(), e.get("amount_minor").asLong()));
        }
        return """
                {"order_group_id":"%s","type":"%s","reason":"%s","adjusts_order_id":null,"entries":[%s],
                 "metadata":%s,"effective_at":"%s"}"""
                .formatted(golden.get("order_group_id").asString(), golden.get("type").asString(),
                        golden.get("reason").asString(), entries, golden.get("metadata").toString(),
                        golden.get("effective_at").asString());
    }

    /** The API response omits the schema discriminator, which the stored-order contract requires. */
    private static String withSchemaEnvelope(String response) {
        var node = (tools.jackson.databind.node.ObjectNode) JSON.readTree(response);
        node.put("schema", "zerosum.money_order.v1");
        return node.toString();
    }

    private record Response(int status, String body, String replayHeader) {
    }

    private Response post(String idempotencyKey, String body, String token) {
        var request = RestClient.create("http://localhost:" + port).post()
                .uri("/v1/money-orders").header("Content-Type", "application/json");
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.body(body).exchange((req, response) -> new Response(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes()),
                response.getHeaders().getFirst("Idempotent-Replayed")), false);
    }

    private String get(String uri, String token) {
        var request = RestClient.create("http://localhost:" + port).get().uri(uri);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> new String(response.getBody().readAllBytes()), false);
    }

    private int statusOf(String uri, String token) {
        var request = RestClient.create("http://localhost:" + port).get().uri(uri);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> response.getStatusCode().value(), false);
    }

    private static String codeOf(String problemJson) {
        var node = JSON.readTree(problemJson);
        var code = node.get("code");
        if (code == null) {
            // Without this the assertion died with an NPE that named neither the case nor what came back.
            throw new AssertionError("response carried no problem code, so it was not rejected as expected: "
                    + problemJson);
        }
        return code.asString();
    }
}
