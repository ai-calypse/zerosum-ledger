package dev.zerosum.ledger.api;

import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Boots ledger-service against the shared {@link LedgerTestDatabase} container. The database is started once for the
 * whole class and its coordinates are bound with {@link DynamicPropertySource}, which overrides the {@code ZS_*}
 * placeholders in {@code application.yml}; {@code @ServiceConnection} is avoided because the image is digest-pinned.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class LedgerApiTestBase {

    protected static final LedgerTestDatabase DB = LedgerTestDatabase.start();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void ledgerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> LedgerTestDatabase.APP);
        registry.add("spring.datasource.password", () -> DB.password(LedgerTestDatabase.APP));
        // Flyway already migrated this database as the owner when the container started; running it again would need
        // owner credentials in the context for no benefit.
        registry.add("spring.flyway.enabled", () -> "false");
        // No broker in this test, so the money-order listener stays stopped rather than dialling one.
        registry.add("ledger.consumer.enabled", () -> "false");
        registry.add("zs.auth.reader-token", () -> "test-reader-token");
    }

    /** The running server's base URL, for tests that must send a pre-encoded path themselves. */
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * A plain client for the running server. Boot 4.1 splits auto-configuration into modules and ledger-service carries
     * no RestClient client module, so there is no {@code RestClient.Builder} bean to inject; the tests do not need one.
     */
    protected RestClient http() {
        // Every /v1 endpoint requires a reader role since S03-T03 wired libs/auth into ledger-service (§0.3 C9).
        return RestClient.builder().baseUrl(baseUrl())
                .defaultHeader("Authorization", "Bearer test-reader-token")
                .build();
    }

    /** Applies payloads through the engine directly, so API tests exercise reads against real applied state. */
    protected static ApplyTestDriver applyDriver() {
        return ApplyTestDriver.create(DB.dataSource(LedgerTestDatabase.APP));
    }
}
