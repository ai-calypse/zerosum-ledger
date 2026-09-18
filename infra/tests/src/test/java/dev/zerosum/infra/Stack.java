package dev.zerosum.infra;

import dev.zerosum.evidence.DotEnv;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The running Compose stack, as the S08 failure-mode tests (recovery, volume, ablation) drive it: HTTP to the public
 * APIs, the {@code docker} CLI for crashes, and read-mostly JDBC to each service's own database for the evidence.
 *
 * <p>Credentials come from {@code .env}, never from the environment alone, for the reason {@link MoneyPathE2ETest}
 * gives. Each database is read with that service's own runtime role, so a check can see nothing the service itself
 * could not.
 */
public final class Stack {

    public static final Path ROOT = Path.of(System.getProperty("zs.rootDir"));
    public static final String ORDERS = "http://127.0.0.1:8081";
    public static final String LEDGER = "http://127.0.0.1:8082";
    public static final String INSTRUMENTS = "http://127.0.0.1:8083";
    /** Published only by docker-compose.demo.yml. */
    public static final String PROVIDERS = "http://127.0.0.1:8090";

    public static final String ORDER_SERVICE = "zerosum-ledger-order-service-1";
    public static final String KAFKA = "zerosum-ledger-kafka-1";
    public static final String POSTGRES = "zerosum-ledger-postgres-1";

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path ENV_FILE = ROOT.resolve(".env");
    private static final DotEnv ENV = DotEnv.read(ENV_FILE);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Stack() {
    }

    public static String env(String name) {
        return ENV.require(name, ENV_FILE);
    }

    public static String writerToken() {
        return ENV.requireFirstWriterToken("ZS_WRITER_TOKENS", ENV_FILE);
    }

    public static String readerToken() {
        return env("ZS_READER_TOKEN");
    }

    public static String adminToken() {
        return env("ZS_ADMIN_TOKEN");
    }

    // --- HTTP ---------------------------------------------------------------------------------------------------

    public static HttpResponse<String> post(String url, String token, String idempotencyKey, String body) {
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return send(request, Duration.ofSeconds(15));
    }

    public static HttpResponse<String> put(String url, String token, String body) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)), Duration.ofSeconds(15));
    }

    public static HttpResponse<String> get(String url, String token) {
        return send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).GET(),
                Duration.ofSeconds(15));
    }

    public static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request, Duration timeout) {
        try {
            return HTTP.send(request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("is the Compose stack running? " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A COMMERCE trip order: rider owes the fare, driver is owed fare less fee, platform books the fee. */
    public static String tripOrder(String groupId, String rider, String driver, long fare, long fee) {
        return """
                {"order_group_id":"%s","type":"COMMERCE","reason":"trip.completed","adjusts_order_id":null,
                 "entries":[{"entity_id":"%s","account":"receivable","currency":"USD","amount_minor":%d},
                            {"entity_id":"%s","account":"payable","currency":"USD","amount_minor":%d},
                            {"entity_id":"platform:main","account":"revenue","currency":"USD","amount_minor":%d}],
                 "metadata":{"trip_id":"%s"},"effective_at":"%s"}
                """.formatted(groupId, rider, fare, driver, -(fare - fee), -fee, groupId,
                java.time.Instant.now().toString());
    }

    /** The ledger's signed balance for one account of one entity, or null while the entity is unknown (404). */
    public static Long balance(String entityId, String account) {
        HttpResponse<String> response = get(LEDGER + "/v1/entities/" + entityId + "/balances", readerToken());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("balances for " + entityId + ": " + response.statusCode() + " "
                    + response.body());
        }
        for (JsonNode entry : json(response.body()).get("accounts")) {
            if (account.equals(entry.get("account").asString())) {
                return entry.get("signed_minor").asLong();
            }
        }
        return null;
    }

    public static JsonNode ledgerInvariants() {
        HttpResponse<String> response = get(LEDGER + "/v1/invariants", readerToken());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("invariants: " + response.statusCode() + " " + response.body());
        }
        return json(response.body());
    }

    // --- docker -------------------------------------------------------------------------------------------------

    /** Runs the docker CLI and returns stdout; a non-zero exit is a harness failure, never a silent pass. */
    public static String docker(String... args) {
        var command = new ArrayList<String>();
        command.add(dockerBinary());
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("docker " + String.join(" ", args) + " timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("docker " + String.join(" ", args) + " exited "
                        + process.exitValue() + ": " + output);
            }
            return output.strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String dockerBinary() {
        String configured = System.getProperty("zs.docker");
        if (configured != null) {
            return configured;
        }
        for (String candidate : List.of(System.getProperty("user.home") + "/.docker/bin/docker",
                "/usr/local/bin/docker", "/opt/homebrew/bin/docker", "/usr/bin/docker")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "docker";
    }

    // --- JDBC ---------------------------------------------------------------------------------------------------

    /** Opens the named database as that service's runtime role (orders_app, ledger_app, ...). */
    public static Connection connect(String database) {
        String role = switch (database) {
            case "orders" -> "ORDERS";
            case "ledger" -> "LEDGER";
            case "instruments" -> "INSTRUMENTS";
            case "fakeproviders" -> "FAKEPROVIDERS";
            default -> throw new IllegalArgumentException(database);
        };
        try {
            return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/" + database,
                    database + "_app", env("ZS_" + role + "_APP_DB_PASSWORD"));
        } catch (SQLException e) {
            throw new IllegalStateException("could not open " + database, e);
        }
    }

    public static List<Map<String, Object>> query(String database, String sql, Object... params) {
        try (Connection connection = connect(database)) {
            return query(connection, sql, params);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    public static List<Map<String, Object>> query(Connection connection, String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            var rows = new ArrayList<Map<String, Object>>();
            try (ResultSet rs = statement.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int c = 1; c <= columns; c++) {
                        row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    }
                    rows.add(row);
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    public static long count(String database, String sql, Object... params) {
        return ((Number) query(database, sql, params).getFirst().values().iterator().next()).longValue();
    }

    // --- evidence -----------------------------------------------------------------------------------------------

    /** Raw run output goes under build/, and is copied into docs/results/ by hand only once it has been read. */
    public static Path writeEvidence(String name, String content) {
        Path dir = Path.of(System.getProperty("zs.evidenceDir", ROOT.resolve("infra/tests/build/evidence").toString()));
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(name);
            Files.writeString(file, content);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
