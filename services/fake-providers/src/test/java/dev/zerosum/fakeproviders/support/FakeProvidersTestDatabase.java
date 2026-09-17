package dev.zerosum.fakeproviders.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * A PostgreSQL container initialized exactly like Compose (the real {@code infra/postgres} scripts and the pinned
 * image; D00-3, D00-4), with the fakeproviders database migrated by Flyway as {@code fakeproviders_owner}.
 *
 * <p><strong>This is the third copy</strong> of a fixture that already exists as {@code LedgerTestDatabase} and
 * {@code OrderTestDatabase}. S02 recorded that a third service needing it triggers a change request to D00-2 to
 * extract a shared fixture. That request is raised as CR-S05-01 in {@code docs/scope-decisions.md}; the extraction
 * itself is deferred there, with the consequence stated, rather than being done quietly here.
 */
public final class FakeProvidersTestDatabase implements AutoCloseable {

    public static final Path ROOT = Path.of(System.getProperty("zs.rootDir"));
    public static final String OWNER = "fakeproviders_owner";
    public static final String APP = "fakeproviders_app";

    private static final List<String> SERVICE_DBS = List.of("ORDERS", "LEDGER", "INSTRUMENTS", "FAKEPROVIDERS");

    private final PostgreSQLContainer container;
    private final Map<String, String> passwords = new HashMap<>();

    private FakeProvidersTestDatabase() {
        var image = DockerImageName.parse(composeImage("postgres")).asCompatibleSubstituteFor("postgres");
        container = new PostgreSQLContainer(image)
                .withUsername("postgres")
                .withPassword(throwawayPassword())
                .withDatabaseName("postgres")
                .withCommand("postgres", "-c", "fsync=off")
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

    public static FakeProvidersTestDatabase start() {
        var db = new FakeProvidersTestDatabase();
        db.container.start();
        Flyway.configure()
                .dataSource(db.jdbcUrl(), OWNER, db.password(OWNER))
                .locations("filesystem:" + ROOT.resolve("services/fake-providers/src/main/resources/db/migration"))
                .load()
                .migrate();
        return db;
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/fakeproviders";
    }

    public String password(String role) {
        return passwords.get(role);
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
                    .filter(Matcher::matches)
                    .map(m -> m.group(1))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no image for " + repository));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
