package dev.zerosum.ledger.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A PostgreSQL container initialized exactly like Compose (the real {@code infra/postgres} scripts, the pinned image and
 * the {@code pg_stat_statements} preload; D00-3, D00-4), with the ledger database migrated by Flyway as
 * {@code ledger_owner}. Mirrors the setup of {@code infra/tests} DatabaseIsolationIT; if a third service needs it,
 * extract a shared fixture through a change request to D00-2.
 */
public final class LedgerTestDatabase implements AutoCloseable {

    public static final Path ROOT = Path.of(System.getProperty("zs.rootDir"));
    public static final String OWNER = "ledger_owner";
    public static final String APP = "ledger_app";
    public static final String VERIFIER = "verifier";

    private static final List<String> SERVICE_DBS = List.of("ORDERS", "LEDGER", "INSTRUMENTS", "FAKEPROVIDERS");

    private final PostgreSQLContainer container;
    private final Map<String, String> passwords = new HashMap<>();

    private LedgerTestDatabase() {
        var image = DockerImageName.parse(composeImage("postgres")).asCompatibleSubstituteFor("postgres");
        container = new PostgreSQLContainer(image)
                .withUsername("postgres")
                .withPassword(throwawayPassword())
                .withDatabaseName("postgres")
                .withCommand("postgres", "-c", "fsync=off",
                        "-c", "shared_preload_libraries=pg_stat_statements",
                        "-c", "pg_stat_statements.track=all")
                .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("infra/postgres")), "/zs/postgres")
                .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("infra/postgres/init.sh"), 0755),
                        "/docker-entrypoint-initdb.d/init.sh");
        for (String db : SERVICE_DBS) {
            password(db.toLowerCase() + "_owner", "ZS_" + db + "_OWNER_DB_PASSWORD");
            password(db.toLowerCase() + "_app", "ZS_" + db + "_APP_DB_PASSWORD");
        }
        password(VERIFIER, "ZS_VERIFIER_DB_PASSWORD");
        password("stats_reader", "ZS_STATS_DB_PASSWORD");
    }

    /** Starts the container and migrates the ledger database to the latest version. */
    public static LedgerTestDatabase start() {
        LedgerTestDatabase db = new LedgerTestDatabase();
        db.container.start();
        db.flyway().migrate();
        return db;
    }

    public Flyway flyway() {
        return Flyway.configure()
                .dataSource(jdbcUrl(), OWNER, passwords.get(OWNER))
                .locations("filesystem:" + ROOT.resolve("services/ledger-service/src/main/resources/db/migration"))
                .load();
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/ledger";
    }

    public String password(String role) {
        return passwords.get(role);
    }

    /** A connection to the ledger database as one of the D00-4 roles. */
    public Connection connect(String role) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), role, passwords.get(role));
    }

    /** Superuser connection, for deliberate corruption in tests only. */
    public Connection superuser() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), container.getUsername(), container.getPassword());
    }

    /** A {@link javax.sql.DataSource} for one of the D00-4 roles, for wiring the apply engine in tests. */
    public javax.sql.DataSource dataSource(String role) {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(jdbcUrl(), role, passwords.get(role));
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    @Override
    public void close() {
        container.stop();
    }

    private void password(String role, String envName) {
        String value = throwawayPassword();
        passwords.put(role, value);
        container.withEnv(envName, value);
    }

    private static String throwawayPassword() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** The image pinned in docker-compose.yml, so tests never carry their own copy of the pin. */
    static String composeImage(String repository) {
        var pattern = Pattern.compile("^\\s*image:\\s*(" + Pattern.quote(repository) + ":\\S+)\\s*$");
        try {
            return Files.readAllLines(ROOT.resolve("docker-compose.yml")).stream()
                    .map(pattern::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .map(m -> m.group(1))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no image for " + repository + " in docker-compose.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
