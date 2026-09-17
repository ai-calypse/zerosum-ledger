package dev.zerosum.order.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.order.support.OrderTestDatabase;
import java.net.URI;
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
 * The outbox stats endpoint (D03-7).
 *
 * <p>No broker runs here, which is the point: orders are accepted, their outbox rows stay unpublished, and the
 * endpoint reports a growing backlog. That is the degraded-mode behaviour the master describes, and it is the only
 * state in which this endpoint matters.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxStatsIT {

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
    }

    @Test
    void statsRequireAReaderRoleAndReportTheBacklogTheCreatePathProduces() {
        // An unauthenticated read is refused: the backlog is operational information, not public.
        assertEquals(401, get(null).status());

        JsonNode before = JSON.readTree(get(READER).body());
        long countBefore = before.get("unpublished_count").asLong();

        assertEquals(201, post("stats-1", body("trip_stats_1")).status());
        assertEquals(201, post("stats-2", body("trip_stats_2")).status());

        JsonNode after = JSON.readTree(get(READER).body());
        assertEquals(countBefore + 2, after.get("unpublished_count").asLong(),
                "each created order must have written an outbox row in the same transaction");
        assertTrue(after.get("oldest_unpublished_age_seconds").asDouble() >= 0,
                "the age of the oldest waiting row must be reported");

        // A writer token satisfies a reader endpoint (D03-4); the hierarchy is only ever one way.
        assertEquals(200, get(WRITER).status());
    }

    @Test
    void theResponseCarriesExactlyTheDocumentedFields() {
        JsonNode stats = JSON.readTree(get(READER).body());
        assertEquals(2, stats.size(), "unexpected fields would break the documented contract: " + stats);
        assertTrue(stats.has("unpublished_count") && stats.has("oldest_unpublished_age_seconds"), stats.toString());
    }

    private record Result(int status, String body) {
    }

    private Result get(String token) {
        var request = RestClient.create("http://localhost:" + port)
                .get()
                .uri(URI.create("http://localhost:" + port + "/v1/outbox/stats"));
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> new Result(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)), false);
    }

    private Result post(String key, String json) {
        return RestClient.create("http://localhost:" + port)
                .post()
                .uri(URI.create("http://localhost:" + port + "/v1/money-orders"))
                .header("Authorization", "Bearer " + WRITER)
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .body(json)
                .exchange((req, response) -> new Result(response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)), false);
    }

    private static String body(String group) {
        return """
                {"order_group_id":"%s","type":"COMMERCE","reason":"trip.completed","adjusts_order_id":null,
                 "entries":[{"entity_id":"rider:R1","account":"receivable","currency":"USD","amount_minor":2500},
                            {"entity_id":"driver:D1","account":"payable","currency":"USD","amount_minor":-2000},
                            {"entity_id":"platform:main","account":"revenue","currency":"USD","amount_minor":-500}],
                 "metadata":{"trip_id":"%s"},"effective_at":"2026-09-15T10:04:11.201Z"}
                """.formatted(group, group);
    }
}
