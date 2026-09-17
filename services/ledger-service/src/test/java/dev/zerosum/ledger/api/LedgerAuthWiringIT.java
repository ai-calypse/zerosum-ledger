package dev.zerosum.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zerosum.ledger.support.LedgerTestDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * §0.3 C9: the 401 and 403 responses ledger-service's OpenAPI already declared are now enforced by the shared auth
 * module (D03-4). Before this, the spec described a guarantee the service did not implement.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerAuthWiringIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LedgerTestDatabase DB = LedgerTestDatabase.start();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void context(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> LedgerTestDatabase.APP);
        registry.add("spring.datasource.password", () -> DB.password(LedgerTestDatabase.APP));
        registry.add("spring.flyway.enabled", () -> "false");
        // No broker in this test, so the money-order listener stays stopped rather than dialling one.
        registry.add("ledger.consumer.enabled", () -> "false");
        registry.add("zs.auth.reader-token", () -> "reader-token");
        registry.add("zs.auth.writer-tokens", () -> "trip-simulator:writer-token");
        registry.add("zs.auth.admin-token", () -> "admin-token");
    }

    @Test
    void readEndpointsRejectAMissingToken() {
        assertEquals(401, status("/v1/entities/rider:R1/balances", null));
        assertEquals("unauthorized", code("/v1/entities/rider:R1/balances", null));
        assertEquals(401, status("/v1/invariants", null));
    }

    @Test
    void readEndpointsRejectAnUnknownToken() {
        assertEquals(401, status("/v1/entities/rider:R1/balances", "not-a-real-token"));
    }

    @Test
    void aReaderTokenIsAccepted() {
        // 404 rather than 200 is fine here: the entity does not exist. What matters is that it is not 401 or 403,
        // so authorisation passed and the request reached the endpoint.
        assertEquals(404, status("/v1/entities/rider:R1/balances", "reader-token"));
        assertEquals(200, status("/v1/invariants", "reader-token"));
    }

    @Test
    void writerAndAdminTokensAlsoSatisfyReaderEndpoints() {
        // The recorded D03-4 hierarchy: both are strictly more privileged than reader.
        assertEquals(200, status("/v1/invariants", "writer-token"));
        assertEquals(200, status("/v1/invariants", "admin-token"));
    }

    private int status(String uri, String token) {
        var request = RestClient.create("http://localhost:" + port).get().uri(uri);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> response.getStatusCode().value(), false);
    }

    private String code(String uri, String token) {
        var request = RestClient.create("http://localhost:" + port).get().uri(uri);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) ->
                JSON.readTree(new String(response.getBody().readAllBytes())).get("code").asString(), false);
    }
}
