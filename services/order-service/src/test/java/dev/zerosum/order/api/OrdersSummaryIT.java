package dev.zerosum.order.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.order.support.OrderTestDatabase;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S09: {@code GET /v1/money-orders/summary}, the dashboard's order totals and money flow.
 *
 * <p>Asserted as deltas around two orders this test creates, so it holds whatever else the database already contains.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrdersSummaryIT {

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
        registry.add("zs.consumer.enabled", () -> "false");
        // Pinned to an address nothing answers on. With the Compose stack up, localhost:9092 is the LIVE broker, and
        // the outbox relay would publish this test's orders into the running ledger.
        registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
    }

    @Test
    void createdOrdersAppearInTheTotalsAndTheFlowAndEveryOrderNetsToZero() {
        JsonNode before = summary();

        assertEquals(201, post("summary-1", trip("trip_summary_1", 2_500, 500)));
        assertEquals(201, post("summary-2", trip("trip_summary_2", 1_000, 200)));

        JsonNode after = summary();
        JsonNode commerce = type(after, "COMMERCE", "trip.completed", "USD");
        assertEquals(count(before, "COMMERCE") + 2, commerce.get("orders").asLong());
        // Gross debits: only the rider receivable lines are positive in a trip order.
        assertEquals(volume(before) + 3_500, commerce.get("volume_minor").asLong());
        for (JsonNode row : after.get("types")) {
            assertEquals(0, row.get("unbalanced_orders").asLong(), "every stored order nets to zero: " + row);
        }

        assertEquals(leg(before, "rider", "receivable") + 3_500, leg(after, "rider", "receivable"));
        assertEquals(leg(before, "driver", "payable") - 2_800, leg(after, "driver", "payable"));
        assertEquals(leg(before, "platform", "revenue") - 700, leg(after, "platform", "revenue"));
        long net = 0;
        for (JsonNode leg : after.get("legs")) {
            net += leg.get("signed_minor").asLong();
        }
        assertEquals(0, net, "the legs of zero-sum orders sum to zero");
    }

    @Test
    void theSummaryNeedsAReaderAndCarriesExactlyTheDocumentedFields() {
        assertEquals(401, get(null).status());
        post("summary-fields", trip("trip_summary_fields", 900, 100));

        JsonNode summary = summary();
        assertEquals(Set.of("types", "legs"), names(summary));
        assertEquals(Set.of("type", "reason", "currency", "orders", "volume_minor", "unbalanced_orders"),
                names(summary.get("types").get(0)));
        assertEquals(Set.of("type", "reason", "entity_kind", "account", "currency", "entries", "signed_minor"),
                names(summary.get("legs").get(0)));
    }

    private static JsonNode type(JsonNode summary, String type, String reason, String currency) {
        for (JsonNode row : summary.get("types")) {
            if (row.get("type").asString().equals(type) && row.get("reason").asString().equals(reason)
                    && row.get("currency").asString().equals(currency)) {
                return row;
            }
        }
        throw new AssertionError("no " + type + " row in " + summary);
    }

    private static long count(JsonNode summary, String type) {
        long total = 0;
        for (JsonNode row : summary.get("types")) {
            total += row.get("type").asString().equals(type) ? row.get("orders").asLong() : 0;
        }
        return total;
    }

    private static long volume(JsonNode summary) {
        long total = 0;
        for (JsonNode row : summary.get("types")) {
            total += row.get("type").asString().equals("COMMERCE") ? row.get("volume_minor").asLong() : 0;
        }
        return total;
    }

    private static long leg(JsonNode summary, String kind, String account) {
        for (JsonNode leg : summary.get("legs")) {
            if (leg.get("type").asString().equals("COMMERCE") && leg.get("entity_kind").asString().equals(kind)
                    && leg.get("account").asString().equals(account)) {
                return leg.get("signed_minor").asLong();
            }
        }
        return 0;
    }

    private static Set<String> names(JsonNode node) {
        var names = new TreeSet<String>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private JsonNode summary() {
        Result result = get(READER);
        assertEquals(200, result.status(), result.body());
        return JSON.readTree(result.body());
    }

    private record Result(int status, String body) {
    }

    private Result get(String token) {
        var request = RestClient.create().get().uri(URI.create("http://localhost:" + port + "/v1/money-orders/summary"));
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> new Result(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)), false);
    }

    private int post(String key, String json) {
        return RestClient.create().post().uri(URI.create("http://localhost:" + port + "/v1/money-orders"))
                .header("Authorization", "Bearer " + WRITER)
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .body(json)
                .exchange((req, response) -> response.getStatusCode().value(), false);
    }

    private static String trip(String group, long fare, long fee) {
        return """
                {"order_group_id":"%s","type":"COMMERCE","reason":"trip.completed","adjusts_order_id":null,
                 "entries":[{"entity_id":"rider:R1","account":"receivable","currency":"USD","amount_minor":%d},
                            {"entity_id":"driver:D1","account":"payable","currency":"USD","amount_minor":%d},
                            {"entity_id":"platform:main","account":"revenue","currency":"USD","amount_minor":%d}],
                 "metadata":{"trip_id":"%s"},"effective_at":"2026-09-15T10:04:11.201Z"}
                """.formatted(group, fare, -(fare - fee), -fee, group);
    }
}
