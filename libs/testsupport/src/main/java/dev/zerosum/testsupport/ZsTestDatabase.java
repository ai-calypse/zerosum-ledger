// decision: CR-S05-01 — docs/scope-decisions.md
package dev.zerosum.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A PostgreSQL container initialized exactly like Compose — the real {@code infra/postgres} scripts and the pinned
 * image (D00-3, D00-4) — with one service's database migrated by Flyway as its owner role.
 *
 * <p>Extracted per CR-S05-01. Three services had already copied this, and the copies differed only in database name,
 * role names and migration path; a change to the init-script wiring or the image lookup had to be made in three
 * places with nothing enforcing it.
 *
 * <p>Every service database is created by the real init scripts regardless of which one a test migrates, because the
 * scripts run once for the whole cluster. That is deliberate: a test that can see only its own database would not
 * catch a grant that leaks across them.
 */
public final class ZsTestDatabase implements AutoCloseable {

    public static final Path ROOT = Path.of(System.getProperty("zs.rootDir"));
    public static final String VERIFIER = "verifier";

    private static final List<String> SERVICE_DBS = List.of("ORDERS", "LEDGER", "INSTRUMENTS", "FAKEPROVIDERS");

    /** decision: D00-3 — the compose memory limit for PostgreSQL, so a study measures that allocation. */
    private static final long COMPOSE_MEMORY_LIMIT_BYTES = 1536L * 1024 * 1024;

    private final PostgreSQLContainer container;
    private final Map<String, String> passwords = new HashMap<>();
    private final String database;
    private final String migrationPath;

    private ZsTestDatabase(String database, String migrationPath, boolean fastAndUnsafe) {
        this.database = database;
        this.migrationPath = migrationPath;

        var image = DockerImageName.parse(composeImage("postgres")).asCompatibleSubstituteFor("postgres");
        // The D00-3 settings from docker-compose.yml; fsync is never disabled there.
        List<String> command = new ArrayList<>(List.of("postgres",
                "-c", "shared_preload_libraries=pg_stat_statements",
                "-c", "pg_stat_statements.track=all"));
        if (fastAndUnsafe) {
            command.addAll(List.of("-c", "fsync=off"));
        }
        container = new PostgreSQLContainer(image)
                .withUsername("postgres")
                .withPassword(throwawayPassword())
                .withDatabaseName("postgres")
                .withCommand(command.toArray(String[]::new))
                .withCreateContainerCmdModifier(cmd -> {
                    if (!fastAndUnsafe) {
                        var hostConfig = cmd.getHostConfig();
                        if (hostConfig != null) {
                            hostConfig.withMemory(COMPOSE_MEMORY_LIMIT_BYTES);
                        }
                    }
                })
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

    /**
     * Starts the container and migrates one service database. Fast, with {@code fsync=off}.
     *
     * @param database      the database name, which also names its roles ({@code <database>_owner} / {@code _app})
     * @param migrationPath the service's migration directory, relative to the repository root
     */
    public static ZsTestDatabase start(String database, String migrationPath) {
        return started(new ZsTestDatabase(database, migrationPath, true));
    }

    /**
     * A container configured as D00-3 describes, with durability intact and the compose memory limit, for timed
     * studies. Slower by design: the commit fsync is part of what such a study measures, and a number taken against
     * a relaxed database is a smoke check rather than data.
     */
    public static ZsTestDatabase startDurable(String database, String migrationPath) {
        return started(new ZsTestDatabase(database, migrationPath, false));
    }

    private static ZsTestDatabase started(ZsTestDatabase db) {
        db.container.start();
        db.flyway().migrate();
        return db;
    }

    /** {@code <database>_owner} — the migrating role. */
    public String owner() {
        return database + "_owner";
    }

    /** {@code <database>_app} — the restricted runtime role the service actually connects as. */
    public String app() {
        return database + "_app";
    }

    public Flyway flyway() {
        return Flyway.configure()
                .dataSource(jdbcUrl(), owner(), passwords.get(owner()))
                .locations("filesystem:" + ROOT.resolve(migrationPath))
                .load();
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/" + database;
    }

    public String password(String role) {
        return passwords.get(role);
    }

    /** A connection as one of the D00-4 roles. */
    public Connection connect(String role) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), role, passwords.get(role));
    }

    /** Superuser connection, for deliberate corruption in tests only. */
    public Connection superuser() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), container.getUsername(), container.getPassword());
    }

    /** The driver's own DataSource, so this module needs no Spring dependency to hand tests something poolable. */
    public DataSource dataSource(String role) {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl());
        dataSource.setUser(role);
        dataSource.setPassword(passwords.get(role));
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

    public static String postgresImage() {
        return composeImage("postgres");
    }

    /** The image pinned in docker-compose.yml, so tests never carry their own copy of the pin. */
    static String composeImage(String repository) {
        var pattern = Pattern.compile("^\\s*image:\\s*(" + Pattern.quote(repository) + ":\\S+)\\s*$");
        try {
            return Files.readAllLines(ROOT.resolve("docker-compose.yml")).stream()
                    .map(pattern::matcher)
                    .filter(Matcher::matches)
                    .map(m -> m.group(1))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no image for " + repository + " in docker-compose.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
