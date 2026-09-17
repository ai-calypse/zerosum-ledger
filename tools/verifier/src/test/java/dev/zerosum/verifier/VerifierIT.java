package dev.zerosum.verifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.testsupport.ZsTestDatabase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * The verifier against a real ledger database: clean, then deliberately corrupted.
 *
 * <p><strong>The corruption cases are the point.</strong> A verifier is only worth running if it can fail, so these
 * tests break a real ledger three different ways and require the specific invariant to be named. The I2-only case
 * matters most: it corrupts the books while leaving I3 and I4 intact, so a tool that merely shouted "something is
 * wrong" would not pass it.
 *
 * <p>The database is the shared CR-S05-01 fixture, initialized by the real {@code infra/postgres} scripts and
 * migrated with ledger-service's real migrations, and the verifier connects as the real {@code verifier} role. A
 * missing grant therefore fails here rather than in a live run.
 *
 * <p>The fixture ledger is inserted as SQL rather than applied through the engine: what is under test is the
 * verifier's detection, and hand-built rows let the corruption be surgical. The apply engine's own correctness is
 * ledger-service's business and is covered there.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VerifierIT {

    private static final ZsTestDatabase DB =
            ZsTestDatabase.start("ledger", "services/ledger-service/src/main/resources/db/migration");

    private static final String ORDER_A = "11111111-1111-4111-8111-111111111111";
    private static final String ORDER_B = "22222222-2222-4222-8222-222222222222";

    @TempDir
    static Path output;

    @AfterAll
    static void stopDatabase() {
        DB.close();
    }

    @Test
    @Order(1)
    void anEmptyLedgerPassesButIsReportedAsEmpty() {
        Result result = verify();

        assertEquals(VerifierMain.PASSED, result.exitCode(), result.output());
        // Every invariant holds over zero rows. If the tool did not say so, a pipeline that pointed it at the wrong
        // database would report perfect books, which is the failure mode this line exists to prevent.
        assertTrue(result.output().contains("WARNING the ledger is empty"), result.output());
        assertTrue(result.output().contains("changelog_rows=0"), result.output());
    }

    @Test
    @Order(2)
    void aConsistentLedgerPassesAndWritesItsEvidence() throws Exception {
        seedTwoBalancedOrders();

        Result result = verify("--out", output.toString(), "--label", "verifier-fixture-run",
                "--description", "A two-order fixture ledger (W1 shape) built by VerifierIT.");

        assertEquals(VerifierMain.PASSED, result.exitCode(), result.output());
        assertTrue(result.output().contains("ZS-VERIFY result PASS"), result.output());
        assertTrue(result.output().contains("I2 global per-currency sum of ledger balances is 0: OK"), result.output());
        assertTrue(result.output().contains("I4 changelog seq is gapless per entity: OK"), result.output());
        assertFalse(result.output().contains("WARNING the ledger is empty"), "6 changelog rows is not empty");

        // M13 (a): the run wrote both a JSON and a Markdown file.
        String json = Files.readString(output.resolve("verifier-fixture-run.json"));
        String markdown = Files.readString(output.resolve("verifier-fixture-run.md"));

        // M13 (c): hardware, versions, git SHA and seeds are all present, and the SHA is captured, not typed.
        assertTrue(json.contains("\"git_sha\""), json);
        assertTrue(json.contains("\"hardware\""), json);
        assertTrue(json.contains("\"working_tree\""), json);
        assertTrue(json.contains("\"PostgreSQL\""), "the server's own version(), not a pin file: " + json);
        assertTrue(markdown.contains("## 3. Provenance (master §3.1 M13 c)"), markdown);
        assertTrue(markdown.contains("| Seeds | none (non-generative evidence) |"), markdown);
        assertTrue(markdown.contains("docs/adr/0002-stack-and-pinned-versions.md"), markdown);
        assertTrue(markdown.contains("**Overall: PASS"), markdown);

        // Kept outside the temp directory, and printed, so the run's real artifacts and real output can be inspected
        // and quoted as evidence rather than described from memory. build/ is git-ignored, so CI leaves no diff.
        Path sample = Path.of("build", "m13-sample");
        Files.createDirectories(sample);
        Files.copy(output.resolve("verifier-fixture-run.json"), sample.resolve("verifier-fixture-run.json"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(output.resolve("verifier-fixture-run.md"), sample.resolve("verifier-fixture-run.md"),
                StandardCopyOption.REPLACE_EXISTING);
        System.out.println("ZS-VERIFY-EVIDENCE clean ledger:\n" + result.output());
    }

    @Test
    @Order(3)
    void theVerifierRoleCannotWrite() throws SQLException {
        // TB5 / D00-4. The tool's read-only-ness is a property of the role, so it is asserted against the role.
        try (Connection connection = DB.connect(ZsTestDatabase.VERIFIER); Statement statement = connection.createStatement()) {
            SQLException refused = assertThrows(SQLException.class,
                    () -> statement.executeUpdate("UPDATE accounts SET balance_minor = 0"));
            assertTrue(refused.getMessage().contains("read-only") || "42501".equals(refused.getSQLState()),
                    "expected a read-only or insufficient-privilege refusal, got: " + refused);
        }
    }

    @Test
    @Order(4)
    void anI2OnlyCorruptionIsReportedAsI2AndNotAsI3OrI4() throws Exception {
        // Books that no longer sum to zero, while every per-account figure stays internally consistent: a new
        // changelog row for one entity plus a matching balance. This is what a lost counter-entry looks like.
        try (Connection connection = DB.connect(DB.app()); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO entity_changelog (entity_id, seq, order_id, account_code, currency, delta_minor,
                        balance_after_minor, hash_version, prev_hash, row_hash)
                    VALUES ('rider:R1', 2, '%s', 'receivable', 'USD', 1, 2501, 1, decode('01','hex'), decode('09','hex'))
                    """.formatted(ORDER_B));
            statement.executeUpdate("UPDATE accounts SET balance_minor = 2501 "
                    + "WHERE entity_id = 'rider:R1' AND account_code = 'receivable'");
        }

        Result result = verify();

        assertEquals(VerifierMain.VIOLATED, result.exitCode(),
                "a ledger whose currency no longer sums to zero must fail the process: " + result.output());
        assertTrue(result.output().contains("ZS-VERIFY result FAIL violated=I2"), result.output());
        assertTrue(result.output().contains("I2 global per-currency sum of ledger balances is 0: VIOLATED, 1: USD"),
                result.output());
        // The discrimination that makes the tool useful: the other two invariants are still intact and say so.
        assertTrue(result.output().contains("I4 changelog seq is gapless per entity: OK"), result.output());
        assertTrue(result.output().contains("balance_after_minor is a correct running sum: OK")
                        || result.output().contains("running sum: OK"),
                "I3 still holds: the balance and the deltas were corrupted together: " + result.output());
        System.out.println("ZS-VERIFY-EVIDENCE I2-only corruption:\n" + result.output());
    }

    @Test
    @Order(5)
    void aCorruptedChangelogIsReportedAsI3AndI4() throws Exception {
        // Bypassing the append-only triggers needs a superuser session, which exists only in this container (D02-2).
        try (Connection connection = DB.superuser(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE entity_changelog DISABLE TRIGGER ALL");
            try {
                // A delta silently edited: the stored balance no longer equals the sum of the deltas.
                statement.executeUpdate("UPDATE entity_changelog SET delta_minor = delta_minor + 7 "
                        + "WHERE entity_id = 'driver:D1' AND seq = 2");
                // A row removed: driver:D1 now starts at seq 2, so its sequence is no longer gapless from 1.
                statement.executeUpdate("DELETE FROM entity_changelog WHERE entity_id = 'driver:D1' AND seq = 1");
            } finally {
                statement.execute("ALTER TABLE entity_changelog ENABLE TRIGGER ALL");
            }
        }

        Result result = verify();

        assertEquals(VerifierMain.VIOLATED, result.exitCode(), result.output());
        assertTrue(result.output().contains("I3") && result.output().contains("driver:D1/payable/USD"),
                "I3 names the account whose balance no longer matches its deltas: " + result.output());
        assertTrue(result.output().contains("I4 changelog seq is gapless per entity: VIOLATED, 1: driver:D1"),
                result.output());
        // I2 is still broken from the previous case, so all three are named. The list is asserted whole: a tool that
        // reported only the first violation it found would leave an operator fixing one fault at a time.
        assertTrue(result.output().contains("violated=I2,I3,I4"), result.output());
        System.out.println("ZS-VERIFY-EVIDENCE corrupted changelog:\n" + result.output());
    }

    @Test
    @Order(6)
    void anUnreachableDatabaseIsAHarnessFailureAndNeverAPass() {
        Result result = run("--jdbc-url", "jdbc:postgresql://127.0.0.1:1/ledger", "--user", ZsTestDatabase.VERIFIER);

        // Distinct from both 0 and 1: "could not connect" is not evidence that the books balance.
        assertEquals(VerifierMain.HARNESS_ERROR, result.exitCode(), result.output());
        assertTrue(result.output().contains("harness failure"), result.output());
    }

    /**
     * Two balanced COMMERCE orders in the W1 shape: the rider owes the fare, the driver is owed the fare less the
     * commission, and the platform books the commission. Each currency sums to zero.
     */
    private void seedTwoBalancedOrders() throws SQLException {
        try (Connection connection = DB.connect(DB.app()); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO entities (entity_id, kind, last_seq) VALUES
                      ('rider:R1', 'rider', 1), ('rider:R2', 'rider', 1),
                      ('driver:D1', 'driver', 2), ('platform:main', 'platform', 2)
                    """);
            statement.executeUpdate("""
                    INSERT INTO accounts (entity_id, account_code, currency, normal_side, balance_minor) VALUES
                      ('rider:R1', 'receivable', 'USD', 'DEBIT', 2500),
                      ('rider:R2', 'receivable', 'USD', 'DEBIT', 1000),
                      ('driver:D1', 'payable', 'USD', 'CREDIT', -2800),
                      ('platform:main', 'revenue', 'USD', 'CREDIT', -700)
                    """);
            statement.executeUpdate("""
                    INSERT INTO entity_changelog (entity_id, seq, order_id, account_code, currency, delta_minor,
                        balance_after_minor, hash_version, prev_hash, row_hash) VALUES
                      ('rider:R1', 1, '%1$s', 'receivable', 'USD',  2500,  2500, 1, NULL, decode('01','hex')),
                      ('rider:R2', 1, '%2$s', 'receivable', 'USD',  1000,  1000, 1, NULL, decode('02','hex')),
                      ('driver:D1', 1, '%1$s', 'payable',   'USD', -2000, -2000, 1, NULL, decode('03','hex')),
                      ('driver:D1', 2, '%2$s', 'payable',   'USD',  -800, -2800, 1, decode('03','hex'), decode('04','hex')),
                      ('platform:main', 1, '%1$s', 'revenue', 'USD', -500,  -500, 1, NULL, decode('05','hex')),
                      ('platform:main', 2, '%2$s', 'revenue', 'USD', -200,  -700, 1, decode('05','hex'), decode('06','hex'))
                    """.formatted(ORDER_A, ORDER_B));
            statement.executeUpdate("""
                    INSERT INTO applied_orders (order_id, order_group_id, source_system, idempotency_key,
                        order_created_at) VALUES
                      ('%1$s', 'trip-1', 'trip-simulator', 'fixture-1', now()),
                      ('%2$s', 'trip-2', 'trip-simulator', 'fixture-2', now())
                    """.formatted(ORDER_A, ORDER_B));
        }
    }

    private Result verify(String... extra) {
        var args = new java.util.ArrayList<>(java.util.List.of(
                "--jdbc-url", DB.jdbcUrl(), "--user", ZsTestDatabase.VERIFIER));
        args.addAll(java.util.List.of(extra));
        return run(args.toArray(String[]::new));
    }

    /** Drives the real entry point, with the password handed over the way an operator would: in the environment. */
    private Result run(String... args) {
        var captured = new ByteArrayOutputStream();
        var out = new PrintStream(captured, true, StandardCharsets.UTF_8);
        // The container's throwaway password is not in any .env, so point the tool at a variable this JVM does hold.
        // System.getenv cannot be set from a test, so the file path is used instead: an empty .env plus an explicit
        // variable would fail, which is why the fixture writes one.
        Path envFile = writeThrowawayEnvFile();
        var full = new java.util.ArrayList<>(java.util.List.of(args));
        full.addAll(java.util.List.of("--env-file", envFile.toString()));
        int exitCode = VerifierMain.run(full.toArray(String[]::new), out);
        out.flush();
        return new Result(exitCode, captured.toString(StandardCharsets.UTF_8));
    }

    private Path writeThrowawayEnvFile() {
        try {
            Path file = output.resolve("throwaway.env");
            Files.writeString(file, "ZS_VERIFIER_DB_PASSWORD=" + DB.password(ZsTestDatabase.VERIFIER) + "\n");
            return file;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private record Result(int exitCode, String output) {
    }
}
