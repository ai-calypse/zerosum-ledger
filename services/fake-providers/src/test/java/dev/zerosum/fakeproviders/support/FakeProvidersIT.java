package dev.zerosum.fakeproviders.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import dev.zerosum.testsupport.ZsTestDatabase;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for fake-provider integration tests: the real service over HTTP, against a real database.
 *
 * <p>The application connects as {@code fakeproviders_app}, the same role it uses in Compose, so a missing grant fails
 * here rather than in the running stack.
 *
 * <p>Requests go through {@link RestClient#exchange}, which hands back the raw response instead of throwing on 4xx.
 * A client that threw would make "this request is correctly refused" harder to assert than "this request succeeds",
 * and the refusals are half of what this simulator exists to prove.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
public abstract class FakeProvidersIT {

    protected static final ZsTestDatabase DB = ZsTestDatabase.start(
            "fakeproviders", "services/fake-providers/src/main/resources/db/migration");
    protected static final JsonMapper JSON = JsonMapper.builder().build();

    /** decision: D03-4 — test tokens for the admin endpoints (S05-T03); never a value any deployment would use. */
    protected static final String ADMIN_TOKEN = "test-admin-token";
    protected static final String READER_TOKEN = "test-reader-token";

    @LocalServerPort
    private int port;

    @Autowired
    protected JdbcClient db;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", DB::app);
        registry.add("spring.datasource.password", () -> DB.password(DB.app()));
        // The fixture already migrated as the owner; the application role has no DDL rights, by design.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("zs.auth.admin-token", () -> ADMIN_TOKEN);
        registry.add("zs.auth.reader-token", () -> READER_TOKEN);
    }

    protected int port() {
        return port;
    }

    /** One HTTP exchange, kept whole so a test can assert on the status as easily as on the body. */
    protected record Response(int status, String body, String replayHeader) {

        public <T> T as(Class<T> type) {
            return JSON.readValue(body, type);
        }
    }

    protected Response post(String path, Object body, String idempotencyKey) {
        return http().post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (idempotencyKey != null) {
                        headers.add("Idempotency-Key", idempotencyKey);
                    }
                })
                .body(JSON.writeValueAsString(body))
                .exchange((request, response) -> capture(response));
    }

    protected Response get(String path) {
        return get(path, null);
    }

    /** @param token a bearer token, or null to send the request unauthenticated (which the 401 cases need). */
    protected Response get(String path, String token) {
        return http().get().uri(path)
                .headers(headers -> bearer(headers, token))
                .exchange((request, response) -> capture(response));
    }

    protected Response put(String path, Object body, String token) {
        return http().put().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> bearer(headers, token))
                .body(JSON.writeValueAsString(body))
                .exchange((request, response) -> capture(response));
    }

    private static void bearer(org.springframework.http.HttpHeaders headers, String token) {
        if (token != null) {
            headers.add("Authorization", "Bearer " + token);
        }
    }

    private static Response capture(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return new Response(response.getStatusCode().value(),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                    response.getHeaders().getFirst("Idempotent-Replayed"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected RestClient http() {
        // Boot 4.1 splits auto-configuration into modules and fake-providers carries no RestClient client module, so
        // there is no builder bean to inject. The tests do not need one.
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }
}
