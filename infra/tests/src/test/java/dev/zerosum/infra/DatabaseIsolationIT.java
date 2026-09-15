package dev.zerosum.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Proves the D00-4 role model (master §0.3 O3, O12; TB3, TB5) against the real init scripts in
 * {@code infra/postgres/}, the PostgreSQL image pinned in {@code docker-compose.yml}, and each service's
 * Flyway migrations.
 */
@Tag("integration")
class DatabaseIsolationIT {

    private static final Path ROOT = Path.of(System.getProperty("zs.rootDir"));
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String READ_ONLY_TRANSACTION = "25006";

    private record Service(String db, String dir) {
        String owner() {
            return db + "_owner";
        }

        String app() {
            return db + "_app";
        }
    }

    private static final List<Service> SERVICES = List.of(
            new Service("orders", "services/order-service"),
            new Service("ledger", "services/ledger-service"),
            new Service("instruments", "services/instrument-service"),
            new Service("fakeproviders", "services/fake-providers"));

    private static final Map<String, String> PASSWORDS = new HashMap<>();
    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startDatabaseAndMigrate() throws IOException {
        // A digest-pinned reference fails Testcontainers' name check although it is the official image.
        var image = DockerImageName.parse(composeImage("postgres")).asCompatibleSubstituteFor("postgres");
        postgres = new PostgreSQLContainer(image)
                .withUsername("postgres")
                .withPassword(throwawayPassword())
                .withDatabaseName("postgres")
                // Same server settings as docker-compose.yml (D00-3), plus fsync=off for test speed.
                .withCommand("postgres", "-c", "fsync=off",
                        "-c", "shared_preload_libraries=pg_stat_statements",
                        "-c", "pg_stat_statements.track=all")
                .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("infra/postgres")), "/zs/postgres")
                .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("infra/postgres/init.sh"), 0755),
                        "/docker-entrypoint-initdb.d/init.sh");
        for (Service s : SERVICES) {
            passwordFor(s.owner(), "ZS_" + s.db().toUpperCase() + "_OWNER_DB_PASSWORD");
            passwordFor(s.app(), "ZS_" + s.db().toUpperCase() + "_APP_DB_PASSWORD");
        }
        passwordFor("verifier", "ZS_VERIFIER_DB_PASSWORD");
        passwordFor("stats_reader", "ZS_STATS_DB_PASSWORD");
        postgres.start();

        for (Service s : SERVICES) {
            Flyway.configure()
                    .dataSource(jdbcUrl(s.db()), s.owner(), PASSWORDS.get(s.owner()))
                    .locations("filesystem:" + ROOT.resolve(s.dir()).resolve("src/main/resources/db/migration"))
                    .load()
                    .migrate();
        }
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void appRoleConnectsToItsOwnDatabase() throws SQLException {
        for (Service s : SERVICES) {
            try (Connection c = connect(s.db(), s.app())) {
                assertEquals(s.db(), queryString(c, "SELECT current_database()"));
            }
        }
    }

    @Test
    void ownerAndAppRolesCannotConnectToAnotherServiceDatabase() {
        for (Service s : SERVICES) {
            for (Service other : SERVICES) {
                if (other == s) {
                    continue;
                }
                for (String role : List.of(s.owner(), s.app())) {
                    assertSqlState(INSUFFICIENT_PRIVILEGE, () -> connect(other.db(), role).close(),
                            role + " -> " + other.db());
                }
            }
        }
    }

    @Test
    void verifierReadsEveryFlywayHistoryInstalledByTheOwner() throws SQLException {
        for (Service s : SERVICES) {
            try (Connection c = connect(s.db(), "verifier")) {
                assertEquals(s.owner(),
                        queryString(c, "SELECT installed_by FROM flyway_schema_history WHERE version = '1'"));
            }
        }
    }

    @Test
    void verifierCannotWriteEvenAfterLeavingReadOnlyMode() throws SQLException {
        String insert = "INSERT INTO flyway_schema_history (installed_rank, description, type, script, installed_by,"
                + " execution_time, success) VALUES (999, 'probe', 'SQL', 'probe', 'verifier', 0, true)";
        for (Service s : SERVICES) {
            try (Connection c = connect(s.db(), "verifier"); Statement st = c.createStatement()) {
                assertSqlState(READ_ONLY_TRANSACTION, () -> st.execute(insert), "default read-only in " + s.db());
                // default_transaction_read_only is defense in depth; privileges must hold on their own.
                st.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
                assertSqlState(INSUFFICIENT_PRIVILEGE, () -> st.execute(insert), "INSERT in " + s.db());
                assertSqlState(INSUFFICIENT_PRIVILEGE, () -> st.execute("CREATE TABLE zs_probe (id int)"),
                        "CREATE TABLE in " + s.db());
            }
        }
    }

    @Test
    void appRoleCannotCreateTablesInPublic() throws SQLException {
        for (Service s : SERVICES) {
            try (Connection c = connect(s.db(), s.app()); Statement st = c.createStatement()) {
                assertSqlState(INSUFFICIENT_PRIVILEGE, () -> st.execute("CREATE TABLE public.zs_probe (id int)"),
                        s.app());
            }
        }
    }

    @Test
    void statsRoleReadsOtherRolesStatementsButNoTableData() throws SQLException {
        try (Connection c = connect("orders", "stats_reader"); Statement st = c.createStatement()) {
            long visible = queryLong(c, """
                    SELECT count(*) FROM pg_stat_statements s JOIN pg_roles r ON r.oid = s.userid
                    WHERE r.rolname LIKE '%\\_owner' AND s.query <> '<insufficient privilege>'""");
            assertTrue(visible > 0, "stats_reader should see statement text of the owner roles");
            assertSqlState(INSUFFICIENT_PRIVILEGE, () -> st.executeQuery("SELECT * FROM flyway_schema_history"),
                    "stats_reader table read");
        }
    }

    private static Connection connect(String db, String role) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), role, PASSWORDS.get(role));
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + db;
    }

    private static void assertSqlState(String expected, Executable action, String context) {
        SQLException e = assertThrows(SQLException.class, action, context);
        assertEquals(expected, e.getSQLState(), context + ": " + e.getMessage());
    }

    private static String queryString(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getString(1);
        }
    }

    private static long queryLong(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getLong(1);
        }
    }

    private static void passwordFor(String role, String envName) {
        String password = throwawayPassword();
        PASSWORDS.put(role, password);
        postgres.withEnv(envName, password);
    }

    private static String throwawayPassword() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Reads the pinned image from docker-compose.yml so the test never carries its own copy of the pin. */
    private static String composeImage(String repository) throws IOException {
        var pattern = Pattern.compile("^\\s*image:\\s*(" + Pattern.quote(repository) + ":\\S+)\\s*$");
        return Files.readAllLines(ROOT.resolve("docker-compose.yml")).stream()
                .map(pattern::matcher)
                .filter(java.util.regex.Matcher::matches)
                .map(m -> m.group(1))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no image for " + repository + " in docker-compose.yml"));
    }
}
