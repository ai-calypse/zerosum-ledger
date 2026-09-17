package dev.zerosum.outbox;

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
 * A PostgreSQL container with the orders migrations applied, so the library's tests run against the same outbox table a
 * producing service owns (candidate A migration ownership, D03-5).
 *
 * <p>Deliberately migrates order-service's real migration directory rather than a copy: the point of the table-shape
 * fixture is that copies drift, so testing against a private copy here would defeat it.
 */
final class OutboxTestDatabase implements AutoCloseable {

    static final Path ROOT = Path.of(System.getProperty("zs.rootDir"));
    static final String OWNER = "orders_owner";
    static final String APP = "orders_app";

    private static final List<String> SERVICE_DBS = List.of("ORDERS", "LEDGER", "INSTRUMENTS", "FAKEPROVIDERS");

    private final PostgreSQLContainer container;
    private final Map<String, String> passwords = new HashMap<>();

    private OutboxTestDatabase() {
        var image = DockerImageName.parse(composeImage()).asCompatibleSubstituteFor("postgres");
        container = new PostgreSQLContainer(image)
                .withUsername("postgres")
                .withPassword(throwaway())
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
        password("verifier", "ZS_VERIFIER_DB_PASSWORD");
        password("stats_reader", "ZS_STATS_DB_PASSWORD");
    }

    static OutboxTestDatabase start() {
        OutboxTestDatabase db = new OutboxTestDatabase();
        db.container.start();
        Flyway.configure()
                .dataSource(db.jdbcUrl(), OWNER, db.passwords.get(OWNER))
                .locations("filesystem:" + ROOT.resolve("services/order-service/src/main/resources/db/migration"))
                .load()
                .migrate();
        return db;
    }

    String jdbcUrl() {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/orders";
    }

    Connection connect(String role) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), role, passwords.get(role));
    }

    javax.sql.DataSource dataSource(String role) {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                jdbcUrl(), role, passwords.get(role));
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    @Override
    public void close() {
        container.stop();
    }

    private void password(String role, String envName) {
        String value = throwaway();
        passwords.put(role, value);
        container.withEnv(envName, value);
    }

    private static String throwaway() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String composeImage() {
        var pattern = Pattern.compile("^\\s*image:\\s*(postgres:\\S+)\\s*$");
        try {
            return Files.readAllLines(ROOT.resolve("docker-compose.yml")).stream()
                    .map(pattern::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .map(m -> m.group(1))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no postgres image in docker-compose.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
