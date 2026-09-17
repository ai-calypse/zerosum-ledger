package dev.zerosum.verifier;

import dev.zerosum.evidence.DotEnv;
import dev.zerosum.evidence.Json;
import dev.zerosum.evidence.Provenance;
import dev.zerosum.verifier.LedgerInvariants.Check;
import dev.zerosum.verifier.LedgerInvariants.Scope;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The cross-store verifier CLI (master §8.3, M13): connects to the ledger database as the read-only {@code verifier}
 * role and checks I2, I3 and I4.
 *
 * <p>decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness.
 *
 * <p><strong>It exits non-zero when an invariant is violated, and names which one.</strong> That is the whole
 * contract: a verifier that always exits 0 is worse than no verifier, because a green pipeline then reports that the
 * books balance when nothing looked. Its own tests corrupt a real ledger and require the failure, and they check
 * that an I2-only corruption is reported as I2 and not as "something is wrong".
 *
 * <p><strong>It cannot write, twice over.</strong> The {@code verifier} role holds only {@code SELECT} (D00-4), and
 * the role itself is created with {@code default_transaction_read_only = on}. The tool reports the session's
 * read-only state in its output, so a run that quietly connected as a privileged role is visible in the evidence
 * rather than assumed away.
 *
 * <p>Exit status: {@code 0} every invariant holds, {@code 1} an invariant is violated, {@code 2} a harness failure
 * (bad arguments, missing secret, unreachable database). The three are distinct on purpose — "could not connect" is
 * not evidence that the ledger is sound, and it must never be mistaken for it.
 */
public final class VerifierMain {

    static final int PASSED = 0;
    static final int VIOLATED = 1;
    static final int HARNESS_ERROR = 2;

    /** decision: D00-8 — secrets come from the environment or .env, never from argv, which `ps` shows to everyone. */
    private static final String DEFAULT_PASSWORD_VARIABLE = "ZS_VERIFIER_DB_PASSWORD";

    /** decision: D00-4 — the read-only role that exists for exactly this tool (TB5). */
    private static final String DEFAULT_USER = "verifier";

    private static final String USAGE = """
            usage: verifier --jdbc-url <url> [options]

              --jdbc-url <url>        ledger database, e.g. jdbc:postgresql://127.0.0.1:5432/ledger   (required)
              --user <role>           database role to connect as                      (default: verifier)
              --password-env <NAME>   variable holding its password                    (default: ZS_VERIFIER_DB_PASSWORD)
              --env-file <path>       .env to read the password from if unset          (default: ./.env)
              --out <dir>             write <label>.json and <label>.md here           (default: stdout only)
              --label <name>          base name of the written files                   (default: verifier)
              --description <text>    what this run verifies, recorded in the report

            exit status: 0 invariants hold · 1 an invariant is violated · 2 harness failure""";

    private VerifierMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /** Package-private so tests drive the real entry point rather than a copy of its logic. */
    static int run(String[] args, PrintStream out) {
        Map<String, String> options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            out.println("verifier: " + e.getMessage());
            out.println();
            out.println(USAGE);
            return HARNESS_ERROR;
        }

        String jdbcUrl = options.get("jdbc-url");
        String user = options.getOrDefault("user", DEFAULT_USER);
        String label = options.getOrDefault("label", "verifier");
        Path envFile = Path.of(options.getOrDefault("env-file", ".env"));

        String password;
        try {
            password = DotEnv.read(envFile).require(options.getOrDefault("password-env", DEFAULT_PASSWORD_VARIABLE), envFile);
        } catch (RuntimeException e) {
            out.println("verifier: " + e.getMessage());
            return HARNESS_ERROR;
        }

        Instant startedAt = Instant.now();
        out.printf("ZS-VERIFY start jdbc=%s user=%s at=%s%n", jdbcUrl, user, startedAt);

        Scope scope;
        List<Check> checks;
        String serverVersion;
        String sessionReadOnly;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            // Read-only and repeatable-read before the transaction opens: every check must see one snapshot, or a
            // busy ledger reports violations that are only the gap between two queries.
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            serverVersion = scalar(connection, "SELECT version()");
            sessionReadOnly = scalar(connection, "SHOW transaction_read_only");
            scope = LedgerInvariants.scope(connection);
            checks = LedgerInvariants.checkAll(connection);
            connection.rollback();
        } catch (SQLException e) {
            // Not a verdict on the ledger. Reported as a harness failure so it can never be read as "no violations".
            out.println("ZS-VERIFY harness failure: could not verify " + jdbcUrl + " as " + user + ": " + e.getMessage());
            return HARNESS_ERROR;
        }

        out.printf("ZS-VERIFY session transaction_read_only=%s server=%s%n", sessionReadOnly, serverVersion);
        out.println("ZS-VERIFY scope " + scope);
        if (scope.isEmpty()) {
            // Every invariant holds over an empty ledger. Saying so is the difference between evidence and a green tick.
            out.println("ZS-VERIFY WARNING the ledger is empty: the invariants below hold vacuously and this run "
                    + "says nothing about a populated ledger");
        }
        checks.forEach(check -> out.printf("ZS-VERIFY %s %s: %s%n", check.id(), check.what(), check.detail()));

        List<String> violated = checks.stream().filter(check -> !check.passed()).map(Check::id).distinct().toList();
        boolean passed = violated.isEmpty();
        out.printf("ZS-VERIFY result %s%s%n", passed ? "PASS" : "FAIL",
                passed ? "" : " violated=" + String.join(",", violated));

        if (options.containsKey("out")) {
            var runtimeVersions = new LinkedHashMap<String, String>();
            runtimeVersions.put("PostgreSQL", serverVersion);
            Provenance provenance = Provenance.capture(Path.of("."), List.of(), runtimeVersions);
            try {
                Path directory = Path.of(options.get("out"));
                Files.createDirectories(directory);
                Path json = directory.resolve(label + ".json");
                Path markdown = directory.resolve(label + ".md");
                Files.writeString(json, json(label, jdbcUrl, user, sessionReadOnly, scope, checks, passed,
                        startedAt, provenance));
                Files.writeString(markdown, markdown(label, options.get("description"), jdbcUrl, user, sessionReadOnly,
                        scope, checks, passed, startedAt, provenance, args));
                out.println("ZS-VERIFY wrote " + json + " and " + markdown);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return passed ? PASSED : VIOLATED;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static String json(String label, String jdbcUrl, String user, String sessionReadOnly, Scope scope,
            List<Check> checks, boolean passed, Instant startedAt, Provenance provenance) {
        String checkJson = checks.stream()
                .map(check -> "    {\"id\": " + Json.quote(check.id())
                        + ", \"checks\": " + Json.quote(check.what())
                        + ", \"passed\": " + check.passed()
                        + ", \"violation_count\": " + check.violations().size()
                        + ", \"list_truncated\": " + check.truncated()
                        + ", \"violations\": ["
                        + check.violations().stream().map(Json::quote).collect(Collectors.joining(", ")) + "]}")
                .collect(Collectors.joining(",\n"));

        return "{\n"
                + "  \"tool\": \"tools/verifier\",\n"
                + "  \"label\": " + Json.quote(label) + ",\n"
                + "  \"started_at\": " + Json.quote(startedAt.toString()) + ",\n"
                + "  \"jdbc_url\": " + Json.quote(jdbcUrl) + ",\n"
                + "  \"connected_as\": " + Json.quote(user) + ",\n"
                + "  \"session_transaction_read_only\": " + Json.quote(sessionReadOnly) + ",\n"
                + "  \"scope\": {\"entities\": " + scope.entities() + ", \"accounts\": " + scope.accounts()
                + ", \"changelog_rows\": " + scope.changelogRows() + ", \"applied_orders\": " + scope.appliedOrders()
                + ", \"empty\": " + scope.isEmpty() + "},\n"
                + "  \"checks\": [\n" + checkJson + "\n  ],\n"
                + "  \"passed\": " + passed + ",\n"
                + "  \"provenance\": " + provenance.toJson() + "\n"
                + "}\n";
    }

    /** The result document, laid out as docs/results/TEMPLATE.md requires; no section is dropped. */
    private static String markdown(String label, String description, String jdbcUrl, String user,
            String sessionReadOnly, Scope scope, List<Check> checks, boolean passed, Instant startedAt,
            Provenance provenance, String[] args) {
        var results = new ArrayList<String>();
        for (Check check : checks) {
            results.add("| %s | %s | %s | %d |".formatted(check.id(), check.what(),
                    check.passed() ? "**PASS**" : "**VIOLATED**", check.violations().size()));
        }
        String emptyWarning = scope.isEmpty()
                ? "\n> **The ledger was empty.** Every invariant below holds vacuously. This run is not evidence "
                        + "about a populated ledger.\n"
                : "";

        return """
                # %s — ledger invariants I2, I3, I4

                <!--
                decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness
                Written by tools/verifier. Do not edit by hand: re-run the tool, which recaptures the provenance block.
                -->

                ## 1. Evidence ID and type

                | Field | Value |
                |---|---|
                | Evidence ID | %s |
                | Type | verification |
                | Owning step and task | M13 (a), M13 (c) — evidence harness |
                | Date (UTC) | %s |

                ## 2. Status

                - **Measured**

                ## 3. Provenance (master §3.1 M13 c)

                %s
                ## 4. Host and Docker allocation

                | Item | Value |
                |---|---|
                | Docker engine / Compose version | n/a — the verifier connects over JDBC and starts no containers |
                | Docker VM CPUs / memory | n/a — as above |
                | Emulated images (non-native architecture) | n/a — as above |
                | Other load on the host during the run | not captured; the verifier only reads, and reports no timings that load could distort |

                ## 5. Scenario, workload and seeds
                %s
                %s

                Target `%s`, connected as `%s`, session `transaction_read_only = %s`.

                Scope actually examined: **%s**. I2, I3 and I4 ran inside one read-only repeatable-read transaction,
                so every figure below describes the same snapshot.

                Seeds: %s.

                ## 6. Exact commands

                ```sh
                ./gradlew :tools:verifier:run --args="%s"
                ```

                ## 7. Raw data

                [%s.json](%s.json) — the same run, with the full violation list per invariant.

                ## 8. Results

                | Invariant | What it checks | Result | Violations |
                |---|---|---|---|
                %s

                **Overall: %s**

                ## 9. Gate or threshold compared against

                The invariant catalog in [master §8.3](../../zerosum_ledger_mvp_plan.md#invariants): I2, I3 and I4.
                **%s.**

                ## 10. Deviations and limitations

                - **Three invariants of twelve.** I1, I5–I12 and R1 are not evaluated. I5 (the hash chain) needs the
                  per-entity `verify` endpoint; I1 and I6–I12 are cross-store checks against the orders and instruments
                  databases, which S06 owns and which is not built.
                - **A snapshot, not a watch.** The result describes the instant the transaction opened. It is evidence
                  about a quiesced ledger; run against a live one it says only that the books balanced at that moment.
                - **The SQL is a copy** of ledger-service's `InvariantQueries` (D02-8), so that the verifier can audit a
                  database whose service is not running. The two must be changed together.
                """.formatted(label, label, startedAt.toString().substring(0, 10),
                provenance.markdownTable("../../adr/0002-stack-and-pinned-versions.md"),
                description == null ? "" : "\n" + description + "\n", emptyWarning,
                jdbcUrl, user, sessionReadOnly, scope, provenance.seedsText(),
                String.join(" ", args), label, label, String.join("\n", results),
                passed ? "PASS — every invariant checked holds" : "FAIL — "
                        + checks.stream().filter(c -> !c.passed()).map(Check::id).distinct().collect(Collectors.joining(", "))
                        + " violated",
                passed ? "Pass" : "Miss");
    }

    private static Map<String, String> parse(String[] args) {
        var options = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + argument);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(argument + " needs a value");
            }
            options.put(argument.substring(2), args[++i]);
        }
        if (!options.containsKey("jdbc-url")) {
            throw new IllegalArgumentException("--jdbc-url is required");
        }
        return options;
    }
}
