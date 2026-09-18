package dev.zerosum.verifier;

import dev.zerosum.evidence.DotEnv;
import dev.zerosum.evidence.Json;
import dev.zerosum.evidence.Provenance;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The cross-store verifier CLI (master §8.3, M13): connects to each service database as the read-only
 * {@code verifier} role and checks I1, I2, I3, I4, I6, I6b, I7, I10 and I12.
 *
 * <p>decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness.
 *
 * <p><strong>It exits non-zero when an invariant is violated, and names which one.</strong> That is the whole
 * contract: a verifier that always exits 0 is worse than no verifier, because a green pipeline then reports that the
 * books balance when nothing looked. Its own tests corrupt real databases and require the failure, and they check
 * that each corruption is reported under its own invariant and not as "something is wrong".
 *
 * <p><strong>A check whose database was not given is SKIPPED, never PASS.</strong> The ledger URL is required; the
 * orders, instruments and fakeproviders URLs are optional, and each one omitted skips exactly the checks that need
 * it. With no {@code --checks}, every check whose databases were given runs.
 *
 * <p><strong>It cannot write, twice over.</strong> The {@code verifier} role holds only {@code SELECT} on every
 * service database (D00-4: {@code CONNECT} per database plus default privileges from each owner), and the role
 * itself is created with {@code default_transaction_read_only = on}. The tool reports every session's read-only
 * state in its output, so a run that quietly connected as a privileged role is visible in the evidence rather than
 * assumed away.
 *
 * <p>Exit status: {@code 0} every check that ran holds, {@code 1} an invariant is violated, {@code 2} a harness
 * failure (bad arguments, missing secret, unreachable database, a query the role may not run, nothing evaluated).
 * The three are distinct on purpose — "could not connect" is not evidence that the ledger is sound.
 */
public final class VerifierMain {

    static final int PASSED = 0;
    static final int VIOLATED = 1;
    static final int HARNESS_ERROR = 2;

    /** decision: D00-8 — secrets come from the environment or .env, never from argv, which `ps` shows to everyone. */
    private static final String DEFAULT_PASSWORD_VARIABLE = "ZS_VERIFIER_DB_PASSWORD";

    /** decision: D00-4 — the read-only role that exists for exactly this tool (TB5). */
    private static final String DEFAULT_USER = "verifier";

    /** The databases, keyed by name, and the option that names each one's URL. Order is connection order. */
    private static final Map<String, String> DATABASE_OPTIONS = linked(
            "ledger", "jdbc-url", "orders", "orders-jdbc-url",
            "instruments", "instruments-jdbc-url", "providers", "providers-jdbc-url");

    private static final Set<String> OPTIONS = Set.of("jdbc-url", "orders-jdbc-url",
            "instruments-jdbc-url", "providers-jdbc-url", "checks", "user", "password-env", "env-file", "out", "label",
            "description");

    @FunctionalInterface
    private interface Evaluation {
        Check run(Map<String, Connection> databases) throws SQLException;
    }

    private record Spec(String id, String what, List<String> needs, Evaluation evaluation) {
    }

    /** Every check, in output order, with the databases it reads. */
    private static final List<Spec> SPECS = List.of(
            new Spec("I1", CrossStoreInvariants.I1, List.of("orders"),
                    db -> CrossStoreInvariants.i1(db.get("orders"))),
            new Spec("I2", LedgerInvariants.I2, List.of("ledger"), db -> LedgerInvariants.i2(db.get("ledger"))),
            new Spec("I3", LedgerInvariants.I3, List.of("ledger"), db -> LedgerInvariants.i3(db.get("ledger"))),
            new Spec("I4", LedgerInvariants.I4, List.of("ledger"), db -> LedgerInvariants.i4(db.get("ledger"))),
            new Spec("I6", CrossStoreInvariants.I6, List.of("orders", "ledger"),
                    db -> CrossStoreInvariants.i6(db.get("orders"), db.get("ledger"))),
            new Spec("I6b", CrossStoreInvariants.I6B, List.of("orders", "ledger"),
                    db -> CrossStoreInvariants.i6b(db.get("orders"), db.get("ledger"))),
            new Spec("I7", CrossStoreInvariants.I7, List.of("instruments", "providers"),
                    db -> CrossStoreInvariants.i7(db.get("instruments"), db.get("providers"))),
            new Spec("I10", CrossStoreInvariants.I10, List.of("instruments"),
                    db -> CrossStoreInvariants.i10(db.get("instruments"))),
            new Spec("I12", CrossStoreInvariants.I12, List.of("instruments", "providers"),
                    db -> CrossStoreInvariants.i12(db.get("instruments"), db.get("providers"))));

    private static final String USAGE = """
            usage: verifier --jdbc-url <url> [options]

              --jdbc-url <url>              ledger database, e.g. jdbc:postgresql://127.0.0.1:5432/ledger   (required)
              --orders-jdbc-url <url>       orders database: enables I1, I6, I6b
              --instruments-jdbc-url <url>  instruments database: enables I10, and with providers I7, I12
              --providers-jdbc-url <url>    fakeproviders database: with instruments enables I7, I12
              --checks <ids>                comma-separated, e.g. I1,I2,I6b,I12   (default: all whose databases are given)
              --user <role>                 database role to connect as           (default: verifier)
              --password-env <NAME>         variable holding its password         (default: ZS_VERIFIER_DB_PASSWORD)
              --env-file <path>             .env to read the password from if unset (default: ./.env)
              --out <dir>                   write <label>.json and <label>.md here (default: stdout only)
              --label <name>                base name of the written files        (default: verifier)
              --description <text>          what this run verifies, recorded in the report

            exit status: 0 every check that ran holds · 1 an invariant is violated · 2 harness failure""";

    private VerifierMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /** Public so a test harness drives the real entry point rather than a copy of its logic. */
    public static int run(String[] args, PrintStream out) {
        Map<String, String> options;
        List<Spec> selected;
        try {
            options = parse(args);
            selected = select(options.get("checks"));
        } catch (IllegalArgumentException e) {
            out.println("verifier: " + e.getMessage());
            out.println();
            out.println(USAGE);
            return HARNESS_ERROR;
        }

        String user = options.getOrDefault("user", DEFAULT_USER);
        String label = options.getOrDefault("label", "verifier");
        Path envFile = Path.of(options.getOrDefault("env-file", ".env"));
        var targets = new LinkedHashMap<String, String>();
        DATABASE_OPTIONS.forEach((database, option) -> {
            if (options.containsKey(option)) {
                targets.put(database, options.get(option));
            }
        });
        if (selected == null) {
            // Every check: those whose databases were given run, the rest are listed SKIPPED rather than left out,
            // so a report can never be read as covering an invariant it did not look at.
            selected = SPECS;
        }

        String password;
        try {
            password = DotEnv.read(envFile).require(options.getOrDefault("password-env", DEFAULT_PASSWORD_VARIABLE), envFile);
        } catch (RuntimeException e) {
            out.println("verifier: " + e.getMessage());
            return HARNESS_ERROR;
        }

        Instant startedAt = Instant.now();
        out.printf("ZS-VERIFY start targets=%s user=%s at=%s%n", targets, user, startedAt);

        var connections = new LinkedHashMap<String, Connection>();
        var readOnly = new LinkedHashMap<String, String>();
        var checks = new ArrayList<Check>();
        Scope scope;
        String serverVersion;
        String current = "ledger";
        try {
            for (var target : targets.entrySet()) {
                current = target.getKey();
                Connection connection = DriverManager.getConnection(target.getValue(), user, password);
                connections.put(target.getKey(), connection);
                // Read-only and repeatable-read before the transaction opens: every check on one database sees one
                // snapshot, or a busy system reports violations that are only the gap between two queries. The
                // first SELECT takes the snapshot, so the databases are pinned back to back, before any check runs.
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                readOnly.put(target.getKey(), scalar(connection, "SELECT current_setting('transaction_read_only')"));
            }
            current = "ledger";
            serverVersion = scalar(connections.get("ledger"), "SELECT version()");
            scope = LedgerInvariants.scope(connections.get("ledger"));
            for (Spec spec : selected) {
                List<String> absent = spec.needs().stream().filter(db -> !connections.containsKey(db)).toList();
                if (!absent.isEmpty()) {
                    checks.add(Check.skipped(spec.id(), spec.what(), "needs " + absent.stream()
                            .map(db -> "--" + DATABASE_OPTIONS.get(db)).collect(Collectors.joining(" and "))));
                    continue;
                }
                current = String.join("+", spec.needs()) + " for " + spec.id();
                checks.add(spec.evaluation().run(connections));
            }
        } catch (SQLException e) {
            // Not a verdict on the books. Reported as a harness failure so it can never be read as "no violations".
            out.println("ZS-VERIFY harness failure: could not verify " + current + " as " + user + ": " + e.getMessage());
            return HARNESS_ERROR;
        } finally {
            for (Connection connection : connections.values()) {
                try {
                    connection.rollback();
                    connection.close();
                } catch (SQLException ignored) {
                    // Closing a read-only session; nothing was written that could be lost.
                }
            }
        }

        out.printf("ZS-VERIFY session transaction_read_only=%s server=%s%n", readOnly, serverVersion);
        out.println("ZS-VERIFY scope " + scope);
        if (scope.isEmpty()) {
            // Every invariant holds over an empty ledger. Saying so is the difference between evidence and a green tick.
            out.println("ZS-VERIFY WARNING the ledger is empty: the invariants below hold vacuously and this run "
                    + "says nothing about a populated ledger");
        }
        checks.forEach(check -> out.printf("ZS-VERIFY %s %s: %s%s%n", check.id(), check.what(), check.detail(),
                check.metrics().isEmpty() ? "" : " " + check.metrics()));

        if (checks.stream().allMatch(check -> check.status() == Check.Status.SKIPPED)) {
            out.println("ZS-VERIFY harness failure: no check was evaluated; give the databases the checks need");
            return HARNESS_ERROR;
        }
        List<String> violated = checks.stream().filter(check -> check.status() == Check.Status.FAIL)
                .map(Check::id).toList();
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
                Files.writeString(json, json(label, targets, user, readOnly, scope, checks, passed, startedAt,
                        provenance));
                Files.writeString(markdown, markdown(label, options.get("description"), targets, user, readOnly,
                        scope, checks, passed, startedAt, provenance, args));
                out.println("ZS-VERIFY wrote " + json + " and " + markdown);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return passed ? PASSED : VIOLATED;
    }

    private static List<Spec> select(String requested) {
        if (requested == null) {
            return null;
        }
        var byId = new LinkedHashMap<String, Spec>();
        SPECS.forEach(spec -> byId.put(spec.id().toUpperCase(), spec));
        var wanted = new LinkedHashSet<Spec>();
        for (String id : requested.split(",")) {
            Spec spec = byId.get(id.strip().toUpperCase());
            if (spec == null) {
                // Named rather than ignored: a typo would otherwise be a check that silently never ran.
                throw new IllegalArgumentException("unknown check: " + id.strip() + " (known: "
                        + SPECS.stream().map(Spec::id).collect(Collectors.joining(",")) + ")");
            }
            wanted.add(spec);
        }
        // Output order is the catalog's, whatever order the caller listed them in.
        return SPECS.stream().filter(wanted::contains).toList();
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static String json(String label, Map<String, String> targets, String user, Map<String, String> readOnly,
            Scope scope, List<Check> checks, boolean passed, Instant startedAt, Provenance provenance) {
        String checkJson = checks.stream()
                .map(check -> "    {\"id\": " + Json.quote(check.id())
                        + ", \"what\": " + Json.quote(check.what())
                        + ", \"status\": " + Json.quote(check.status().name())
                        + ", \"violations\": " + check.violations()
                        + ", \"metrics\": {" + check.metrics().entrySet().stream()
                                .map(m -> Json.quote(m.getKey()) + ": " + m.getValue())
                                .collect(Collectors.joining(", ")) + "}"
                        + ", \"sample\": [" + check.sample().stream().map(Json::quote)
                                .collect(Collectors.joining(", ")) + "]"
                        + (check.note() == null ? "" : ", \"note\": " + Json.quote(check.note())) + "}")
                .collect(Collectors.joining(",\n"));

        return "{\n"
                + "  \"tool\": \"tools/verifier\",\n"
                + "  \"label\": " + Json.quote(label) + ",\n"
                + "  \"started_at\": " + Json.quote(startedAt.toString()) + ",\n"
                + "  \"jdbc_url\": " + Json.quote(targets.get("ledger")) + ",\n"
                + "  \"targets\": " + object(targets) + ",\n"
                + "  \"connected_as\": " + Json.quote(user) + ",\n"
                + "  \"session_transaction_read_only\": " + Json.quote(readOnly.get("ledger")) + ",\n"
                + "  \"sessions_transaction_read_only\": " + object(readOnly) + ",\n"
                + "  \"scope\": {\"entities\": " + scope.entities() + ", \"accounts\": " + scope.accounts()
                + ", \"changelog_rows\": " + scope.changelogRows() + ", \"applied_orders\": " + scope.appliedOrders()
                + ", \"empty\": " + scope.isEmpty() + "},\n"
                + "  \"checks\": [\n" + checkJson + "\n  ],\n"
                + "  \"result\": " + Json.quote(passed ? "PASS" : "FAIL") + ",\n"
                + "  \"passed\": " + passed + ",\n"
                + "  \"provenance\": " + provenance.toJson() + "\n"
                + "}\n";
    }

    private static String object(Map<String, String> values) {
        return values.entrySet().stream().map(e -> Json.quote(e.getKey()) + ": " + Json.quote(e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    /** The result document, laid out as docs/results/TEMPLATE.md requires; no section is dropped. */
    private static String markdown(String label, String description, Map<String, String> targets, String user,
            Map<String, String> readOnly, Scope scope, List<Check> checks, boolean passed, Instant startedAt,
            Provenance provenance, String[] args) {
        var results = new ArrayList<String>();
        for (Check check : checks) {
            String metrics = check.metrics().entrySet().stream().map(m -> m.getKey() + "=" + m.getValue())
                    .collect(Collectors.joining(", "));
            results.add("| %s | %s | %s | %d | %s |".formatted(check.id(), check.what(),
                    switch (check.status()) {
                        case PASS -> "**PASS**";
                        case FAIL -> "**VIOLATED**";
                        case SKIPPED -> "SKIPPED — " + check.note();
                    }, check.violations(), metrics));
        }
        String ran = checks.stream().filter(c -> c.status() != Check.Status.SKIPPED).map(Check::id)
                .collect(Collectors.joining(", "));
        String skipped = checks.stream().filter(c -> c.status() == Check.Status.SKIPPED).map(Check::id)
                .collect(Collectors.joining(", "));
        String samples = checks.stream().filter(c -> !c.sample().isEmpty())
                .map(c -> "- **" + c.id() + "** (first " + c.sample().size() + " of " + c.violations() + "): "
                        + c.sample().stream().map(s -> "`" + s + "`").collect(Collectors.joining(", ")))
                .collect(Collectors.joining("\n"));
        String emptyWarning = scope.isEmpty()
                ? "\n> **The ledger was empty.** Every invariant below holds vacuously. This run is not evidence "
                        + "about a populated ledger.\n"
                : "";
        String targetList = targets.entrySet().stream()
                .map(t -> "`" + t.getKey() + "` " + t.getValue() + " (`transaction_read_only = " + readOnly.get(t.getKey())
                        + "`)")
                .collect(Collectors.joining("; "));

        return """
                # %s — invariants %s

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

                Targets, each connected as `%s`: %s.

                Ledger scope actually examined: **%s**. Each database was read inside one read-only repeatable-read
                transaction, and the transactions were opened back to back before any check ran.

                Seeds: %s.

                ## 6. Exact commands

                ```sh
                ./gradlew :tools:verifier:run --args="%s"
                ```

                ## 7. Raw data

                [%s.json](%s.json) — the same run, with every check's metrics and its sample of offending rows.

                ## 8. Results

                | Invariant | What it checks | Result | Violations | Metrics |
                |---|---|---|---|---|
                %s

                **Overall: %s**

                %s

                ## 9. Gate or threshold compared against

                The invariant catalog in [master §8.3](../../zerosum_ledger_mvp_plan.md#invariants): %s evaluated%s.
                **%s.**

                ## 10. Deviations and limitations

                - **Not evaluated by this tool:** I5 (hash chain; needs the per-entity `verify` endpoint), I8, I9, I11
                  and R1.%s
                - **One snapshot per database, not one across them.** The four transactions open milliseconds apart, so
                  the cross-store checks (I6, I6b, I7, I12) are evidence about a quiesced system only.
                - **I7 reads provider ground truth from the fakeproviders database** (the tables `/admin/truth` reads),
                  keyed by `client_reference` = attempt id; charges and refunds count when `SUCCEEDED`, payouts unless
                  `FAILED`.
                - **I12 explains a break only by an injected discrepancy** in the fault log of the mapped type, for the same
                  provider reference in the same report. A break's `OPEN` status does not explain it.
                - **The I2–I4 SQL is a copy** of ledger-service's `InvariantQueries` (D02-8), so that the verifier can
                  audit a database whose service is not running. The two must be changed together.
                """.formatted(label, ran, label, startedAt.toString().substring(0, 10),
                provenance.markdownTable("../../adr/0002-stack-and-pinned-versions.md"),
                description == null ? "" : "\n" + description + "\n", emptyWarning,
                user, targetList, scope, provenance.seedsText(),
                String.join(" ", args), label, label, String.join("\n", results),
                passed ? "PASS — every invariant checked holds" : "FAIL — "
                        + checks.stream().filter(c -> c.status() == Check.Status.FAIL).map(Check::id)
                                .collect(Collectors.joining(", ")) + " violated",
                samples.isEmpty() ? "No offending rows." : "Offending rows:\n\n" + samples,
                ran, skipped.isEmpty() ? "" : "; " + skipped + " skipped (database not given), which is not a pass",
                passed ? "Pass" : "Miss",
                skipped.isEmpty() ? "" : " Skipped in this run: " + skipped + ".");
    }

    private static Map<String, String> parse(String[] args) {
        var options = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + argument);
            }
            if (!OPTIONS.contains(argument.substring(2))) {
                // A misspelt database option would otherwise skip its checks without a word.
                throw new IllegalArgumentException("unknown option: " + argument);
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

    private static Map<String, String> linked(String... pairs) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
