package dev.zerosum.verifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.testsupport.ZsTestDatabase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cross-store checks (I1, I6, I6b, I7, I10, I12) against all four real service databases: clean, then corrupted
 * one invariant at a time.
 *
 * <p>One container, initialized by the real {@code infra/postgres} scripts, with every service's real migrations
 * applied as its owner. The verifier connects to each database as the real {@code verifier} role, so a missing
 * grant on any of them fails here as a harness error rather than in a live run.
 *
 * <p>Each corruption is surgical: it breaks the one invariant its test names and asserts that exactly that check
 * flips, with its metrics — the I6b case in particular double-applies an order the way ablation A1 would, leaving I2,
 * I3, I4 and I6 intact, which is the case I6b exists for. Corruptions accumulate in test order, so each test asserts
 * only the checks it is about.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrossStoreVerifierIT {

    private static final ZsTestDatabase DB =
            ZsTestDatabase.start("ledger", "services/ledger-service/src/main/resources/db/migration");

    private static final String ORDER_A = "11111111-1111-4111-8111-111111111111";
    private static final String ORDER_B = "22222222-2222-4222-8222-222222222222";
    private static final String ATTEMPT_1 = "a1111111-1111-4111-8111-111111111111";
    private static final String ATTEMPT_2 = "a2222222-2222-4222-8222-222222222222";
    private static final String RUN = "c0000000-0000-4000-8000-000000000001";

    @TempDir
    static Path output;

    @BeforeAll
    static void migrateEveryServiceDatabase() {
        migrate("orders", "order-service");
        migrate("instruments", "instrument-service");
        migrate("fakeproviders", "fake-providers");
    }

    @AfterAll
    static void stopDatabase() {
        DB.close();
    }

    @Test
    @Order(1)
    void aConsistentSystemPassesEveryCheckAndWritesItsEvidence() throws Exception {
        seedConsistentSystem();

        Result result = verify("--out", output.toString(), "--label", "cross-store-fixture");

        assertEquals(VerifierMain.PASSED, result.exitCode(), result.output());
        for (String id : List.of("I1", "I2", "I3", "I4", "I6", "I6b", "I7", "I10", "I12")) {
            assertTrue(line(result, id).contains(": OK"), line(result, id));
        }
        // The two injected breaks are explained by the fault log, one of them only through the attempt's provider
        // reference: a MISSING_IN_REPORT break has no report line to carry one.
        assertTrue(line(result, "I12").contains("explained_breaks=2"), line(result, "I12"));
        assertTrue(line(result, "I7").contains("provider_success_records=2"), line(result, "I7"));
        assertTrue(line(result, "I6").contains("orders_in_store=2, applied_orders=2"), line(result, "I6"));
        // Every session was read-only, on every database.
        assertTrue(result.output().contains("{ledger=on, orders=on, instruments=on, providers=on}"), result.output());

        String json = Files.readString(output.resolve("cross-store-fixture.json"));
        assertEquals(9, json.split("\"status\": \"PASS\"", -1).length - 1, json);
        assertTrue(json.contains("\"result\": \"PASS\""), json);
        for (String metric : List.of("unbalanced_orders", "nonzero_currencies", "missing_orders", "extra_applied",
                "unresolved_quarantined", "drift_abs_minor", "accounts_drifting", "duplicate_charges",
                "duplicate_minor", "success_without_provider_record", "provider_success_without_attempt",
                "unexplained_breaks", "undetected_injections", "explained_breaks")) {
            assertTrue(json.contains("\"" + metric + "\": "), metric + " in " + json);
        }
        String markdown = Files.readString(output.resolve("cross-store-fixture.md"));
        assertTrue(markdown.contains("**Overall: PASS"), markdown);
        System.out.println("ZS-VERIFY-EVIDENCE clean cross-store fixture:\n" + result.output());
    }

    @Test
    @Order(2)
    void aCheckWhoseDatabaseIsMissingIsSkippedAndNeverPasses() throws Exception {
        Result partial = run("--jdbc-url", DB.jdbcUrl(), "--checks", "I2,I7,i12", "--out", output.toString(),
                "--label", "partial");

        assertEquals(VerifierMain.PASSED, partial.exitCode(), "I2 ran and held: " + partial.output());
        assertTrue(line(partial, "I7").contains("SKIPPED (needs --instruments-jdbc-url and --providers-jdbc-url)"),
                partial.output());
        assertTrue(line(partial, "I12").contains("SKIPPED"), partial.output());
        String json = Files.readString(output.resolve("partial.json"));
        assertEquals(2, json.split("\"status\": \"SKIPPED\"", -1).length - 1, json);

        // Nothing evaluated is a harness failure, not a vacuous pass.
        Result nothing = run("--jdbc-url", DB.jdbcUrl(), "--checks", "I7");
        assertEquals(VerifierMain.HARNESS_ERROR, nothing.exitCode(), nothing.output());
        // A misspelt check or database option is refused, not silently skipped.
        assertEquals(VerifierMain.HARNESS_ERROR, run("--jdbc-url", DB.jdbcUrl(), "--checks", "I99").exitCode());
        assertEquals(VerifierMain.HARNESS_ERROR,
                run("--jdbc-url", DB.jdbcUrl(), "--order-jdbc-url", url("orders")).exitCode());
    }

    @Test
    @Order(3)
    void theVerifierRoleCannotWriteAnyServiceDatabase() throws SQLException {
        for (String[] target : List.of(new String[] {"orders", "UPDATE money_orders SET reason = 'x'"},
                new String[] {"instruments", "UPDATE payment_attempts SET status = 'FAILED'"},
                new String[] {"fakeproviders", "UPDATE card_charges SET amount_minor = 1"})) {
            try (Connection connection = connect(target[0], ZsTestDatabase.VERIFIER);
                    Statement statement = connection.createStatement()) {
                SQLException refused = assertThrows(SQLException.class, () -> statement.executeUpdate(target[1]));
                assertTrue(refused.getMessage().contains("read-only") || "42501".equals(refused.getSQLState()),
                        target[0] + ": expected a read-only or privilege refusal, got " + refused);
            }
        }
    }

    @Test
    @Order(4)
    void aDoubleAppliedOrderIsI6bAndNothingElse() throws Exception {
        // Ablation A1's signature: ORDER_A applied a second time. The changelog rows and balances agree with each
        // other (I3), the books still sum to zero (I2), seq stays gapless (I4) and applied_orders is unchanged (I6).
        exec("ledger", DB.app(), """
                INSERT INTO entity_changelog (entity_id, seq, order_id, account_code, currency, delta_minor,
                    balance_after_minor, hash_version, prev_hash, row_hash) VALUES
                  ('rider:R1', 2, '%1$s', 'receivable', 'USD', 2500, 5000, 1, decode('01','hex'), decode('11','hex')),
                  ('driver:D1', 3, '%1$s', 'payable', 'USD', -2000, -4800, 1, decode('04','hex'), decode('12','hex')),
                  ('platform:main', 3, '%1$s', 'revenue', 'USD', -500, -1200, 1, decode('06','hex'), decode('13','hex'))
                """.formatted(ORDER_A),
                "UPDATE accounts SET balance_minor = 5000 WHERE entity_id = 'rider:R1'",
                "UPDATE accounts SET balance_minor = -4800 WHERE entity_id = 'driver:D1'",
                "UPDATE accounts SET balance_minor = -1200 WHERE entity_id = 'platform:main'");

        Result result = verify();

        assertEquals(VerifierMain.VIOLATED, result.exitCode(), result.output());
        assertTrue(result.output().contains("ZS-VERIFY result FAIL violated=I6b\n"), result.output());
        assertTrue(line(result, "I6b").contains("accounts_drifting=3, drift_abs_minor=5000"), line(result, "I6b"));
        assertTrue(line(result, "I6b").contains("rider:R1/receivable/USD ledger=5000 orders=2500"), line(result, "I6b"));
        for (String intact : List.of("I2", "I3", "I4", "I6")) {
            assertTrue(line(result, intact).contains(": OK"), line(result, intact));
        }
        System.out.println("ZS-VERIFY-EVIDENCE double apply:\n" + result.output());
    }

    @Test
    @Order(5)
    void anUnappliedOrderAnUnknownAppliedOrderAndAnOpenQuarantineAreI6() throws Exception {
        inOrdersTransaction(orders -> {
            orders.executeUpdate(order("33333333-3333-4333-8333-333333333333", "trip-3", "k-3"));
            orders.executeUpdate("""
                    INSERT INTO money_order_entries (order_id, line_no, entity_id, account_code, currency, amount_minor)
                    VALUES ('33333333-3333-4333-8333-333333333333', 1, 'rider:R2', 'receivable', 'USD', 100),
                           ('33333333-3333-4333-8333-333333333333', 2, 'platform:main', 'revenue', 'USD', -100)""");
        });
        exec("ledger", DB.app(), """
                INSERT INTO applied_orders (order_id, order_group_id, source_system, idempotency_key, order_created_at)
                VALUES ('44444444-4444-4444-8444-444444444444', 'trip-4', 'fixture', 'k-4', now())""",
                "INSERT INTO quarantined_orders (payload, error_code) VALUES (decode('00','hex'), 'undecodable')");

        Result result = verify();

        assertEquals(VerifierMain.VIOLATED, result.exitCode(), result.output());
        assertTrue(line(result, "I6").contains("VIOLATED, 3:"), line(result, "I6"));
        assertTrue(line(result, "I6").contains("missing_orders=1, extra_applied=1, unresolved_quarantined=1"),
                line(result, "I6"));
        assertTrue(line(result, "I6").contains("not applied: 33333333-3333-4333-8333-333333333333"), line(result, "I6"));
        assertTrue(line(result, "I6").contains("applied, not in orders DB: 44444444-4444-4444-8444-444444444444"),
                line(result, "I6"));
    }

    @Test
    @Order(6)
    void aDuplicateChargeAnOrphanChargeAndAnUnbackedSuccessAreI7() throws Exception {
        exec("fakeproviders", "fakeproviders_app", """
                INSERT INTO card_charges (charge_id, client_reference, instrument_token, amount_minor, currency, status)
                VALUES ('ch_1b', '%s', 'tok_card_ok', 2500, 'USD', 'SUCCEEDED'),
                       ('ch_9', 'not-an-attempt', 'tok_card_ok', 700, 'USD', 'SUCCEEDED')""".formatted(ATTEMPT_1));
        exec("instruments", "instruments_app", attempt("a3333333-3333-4333-8333-333333333333", "rider:R3", null, 300,
                "SUCCEEDED", "ch_never", "now()"));

        Result result = verify();

        assertEquals(VerifierMain.VIOLATED, result.exitCode(), result.output());
        assertTrue(line(result, "I7").contains("VIOLATED, 3:"), line(result, "I7"));
        assertTrue(line(result, "I7").contains("duplicate_charges=1, duplicate_minor=2500, "
                + "success_without_provider_record=1, provider_success_without_attempt=1"), line(result, "I7"));
        assertTrue(line(result, "I7").contains("CHARGE " + ATTEMPT_1 + " succeeded 2 times"), line(result, "I7"));
    }

    @Test
    @Order(7)
    void anOldUnknownAndANeedsReviewAttemptAreI10() throws Exception {
        exec("instruments", "instruments_app",
                attempt("a4444444-4444-4444-8444-444444444444", "rider:R4", null, 100, "UNKNOWN", null,
                        "now() - interval '10 minutes'"),
                attempt("a5555555-5555-4555-8555-555555555555", "rider:R5", null, 100, "NEEDS_REVIEW", null, "now()"),
                // Uncertain, but only for a minute: not yet a violation.
                attempt("a6666666-6666-4666-8666-666666666666", "rider:R6", null, 100, "UNKNOWN", null,
                        "now() - interval '1 minute'"));

        Result result = verify();

        assertTrue(line(result, "I10").contains("VIOLATED, 2:"), line(result, "I10"));
        assertTrue(line(result, "I10").contains("stuck_over_5_min=1, needs_review=1"), line(result, "I10"));
        assertTrue(line(result, "I10").contains("a4444444-4444-4444-8444-444444444444 CHARGE UNKNOWN"),
                line(result, "I10"));
    }

    @Test
    @Order(8)
    void aBreakNobodyInjectedAndAnInjectionWithNoBreakAreI12() throws Exception {
        exec("instruments", "instruments_app", """
                INSERT INTO reconciliation_breaks (run_id, break_type, status, provider_ref, client_reference, currency,
                    report_gross_minor, ledger_gross_minor, attempt_id, detail)
                VALUES ('%s', 'AMOUNT_MISMATCH', 'UNEXPLAINED', 'ch_2', '%s', 'USD', 1001, 1000, '%s', 'fixture')
                """.formatted(RUN, ATTEMPT_2, ATTEMPT_2));
        exec("fakeproviders", "fakeproviders_app", """
                INSERT INTO fault_log (fault_id, provider, fault_type, target, seed)
                VALUES ('flt_4', 'fakecard', 'report_off_by_one', 'rpt_2026_01_01:ch_1', 7)""");

        Result result = verify();

        assertTrue(line(result, "I12").contains("VIOLATED, 2:"), line(result, "I12"));
        assertTrue(line(result, "I12").contains("explained_breaks=2, unexplained_breaks=1, undetected_injections=1"),
                line(result, "I12"));
        assertTrue(line(result, "I12").contains("unexplained break: fakecard rpt_2026_01_01 AMOUNT_MISMATCH ch_2"),
                line(result, "I12"));
        assertTrue(line(result, "I12").contains("undetected injection: fakecard rpt_2026_01_01 AMOUNT_MISMATCH ch_1"),
                line(result, "I12"));
    }

    @Test
    @Order(9)
    void anUnbalancedOrderIsI1() throws Exception {
        // The deferred zero-sum triggers stop this for every role, the owner included; the owner may disable its own
        // table's triggers, which is the only way to put an unbalanced order in the store (ablation A4's hole).
        try (Connection owner = connect("orders", "orders_owner"); Statement statement = owner.createStatement()) {
            owner.setAutoCommit(false);
            statement.execute("ALTER TABLE money_orders DISABLE TRIGGER money_orders_have_entries");
            statement.execute("ALTER TABLE money_order_entries DISABLE TRIGGER money_order_entries_zero_sum");
            statement.executeUpdate(order("55555555-5555-4555-8555-555555555555", "trip-5", "k-5"));
            statement.executeUpdate("""
                    INSERT INTO money_order_entries (order_id, line_no, entity_id, account_code, currency, amount_minor)
                    VALUES ('55555555-5555-4555-8555-555555555555', 1, 'rider:R1', 'receivable', 'USD', 5)""");
            statement.execute("ALTER TABLE money_orders ENABLE TRIGGER money_orders_have_entries");
            statement.execute("ALTER TABLE money_order_entries ENABLE TRIGGER money_order_entries_zero_sum");
            owner.commit();
        }

        Result result = verify();

        assertEquals(VerifierMain.VIOLATED, result.exitCode(), result.output());
        assertTrue(line(result, "I1").contains("VIOLATED, 1: 55555555-5555-4555-8555-555555555555: USD sums to 5"),
                line(result, "I1"));
        assertTrue(line(result, "I1").contains("unbalanced_orders=1"), line(result, "I1"));
        // Every corruption so far, each under its own name; I2-I4 were never touched and still hold.
        assertTrue(result.output().contains("violated=I1,I6,I6b,I7,I10,I12\n"), result.output());
        System.out.println("ZS-VERIFY-EVIDENCE all corruptions:\n" + result.output());
    }

    // --- fixture --------------------------------------------------------------------------------------------------

    /**
     * Two COMMERCE orders in the W1 shape, applied once each; each rider's card charged once; one reconciliation run
     * whose two breaks were both injected by the provider's report knobs, as the fault log records.
     */
    private static void seedConsistentSystem() throws SQLException {
        inOrdersTransaction(orders -> {
            orders.executeUpdate(order(ORDER_A, "trip-1", "k-1"));
            orders.executeUpdate(order(ORDER_B, "trip-2", "k-2"));
            orders.executeUpdate("""
                    INSERT INTO money_order_entries (order_id, line_no, entity_id, account_code, currency, amount_minor)
                    VALUES ('%1$s', 1, 'rider:R1', 'receivable', 'USD', 2500),
                           ('%1$s', 2, 'driver:D1', 'payable', 'USD', -2000),
                           ('%1$s', 3, 'platform:main', 'revenue', 'USD', -500),
                           ('%2$s', 1, 'rider:R2', 'receivable', 'USD', 1000),
                           ('%2$s', 2, 'driver:D1', 'payable', 'USD', -800),
                           ('%2$s', 3, 'platform:main', 'revenue', 'USD', -200)""".formatted(ORDER_A, ORDER_B));
        });
        exec("ledger", DB.app(), """
                INSERT INTO entities (entity_id, kind, last_seq) VALUES
                  ('rider:R1', 'rider', 1), ('rider:R2', 'rider', 1),
                  ('driver:D1', 'driver', 2), ('platform:main', 'platform', 2)""", """
                INSERT INTO accounts (entity_id, account_code, currency, normal_side, balance_minor) VALUES
                  ('rider:R1', 'receivable', 'USD', 'DEBIT', 2500),
                  ('rider:R2', 'receivable', 'USD', 'DEBIT', 1000),
                  ('driver:D1', 'payable', 'USD', 'CREDIT', -2800),
                  ('platform:main', 'revenue', 'USD', 'CREDIT', -700)""", """
                INSERT INTO entity_changelog (entity_id, seq, order_id, account_code, currency, delta_minor,
                    balance_after_minor, hash_version, prev_hash, row_hash) VALUES
                  ('rider:R1', 1, '%1$s', 'receivable', 'USD',  2500,  2500, 1, NULL, decode('01','hex')),
                  ('rider:R2', 1, '%2$s', 'receivable', 'USD',  1000,  1000, 1, NULL, decode('02','hex')),
                  ('driver:D1', 1, '%1$s', 'payable',   'USD', -2000, -2000, 1, NULL, decode('03','hex')),
                  ('driver:D1', 2, '%2$s', 'payable',   'USD',  -800, -2800, 1, decode('03','hex'), decode('04','hex')),
                  ('platform:main', 1, '%1$s', 'revenue', 'USD', -500,  -500, 1, NULL, decode('05','hex')),
                  ('platform:main', 2, '%2$s', 'revenue', 'USD', -200,  -700, 1, decode('05','hex'), decode('06','hex'))
                """.formatted(ORDER_A, ORDER_B), """
                INSERT INTO applied_orders (order_id, order_group_id, source_system, idempotency_key, order_created_at)
                VALUES ('%s', 'trip-1', 'fixture', 'k-1', now()), ('%s', 'trip-2', 'fixture', 'k-2', now())
                """.formatted(ORDER_A, ORDER_B));
        exec("instruments", "instruments_app",
                attempt(ATTEMPT_1, "rider:R1", ORDER_A, 2500, "SUCCEEDED", "ch_1", "now()"),
                attempt(ATTEMPT_2, "rider:R2", ORDER_B, 1000, "SUCCEEDED", "ch_2", "now()"), """
                INSERT INTO reconciliation_runs (run_id, idempotency_key, request_hash, provider, report_date, report_id,
                    content_hash, status, report_lines, lines_matched, breaks_found, settled)
                VALUES ('%s', 'recon-1', decode('00','hex'), 'fakecard', '2026-01-01', 'rpt_2026_01_01', 'x',
                    'COMPLETED', '[]', 1, 2, true)""".formatted(RUN), """
                INSERT INTO reconciliation_breaks (run_id, break_type, status, provider_ref, client_reference, currency,
                    report_gross_minor, ledger_gross_minor, attempt_id, detail) VALUES
                  ('%1$s', 'MISSING_IN_REPORT', 'OPEN', NULL, '%2$s', 'USD', NULL, 2500, '%2$s', 'fixture'),
                  ('%1$s', 'DUPLICATE_LINE', 'UNEXPLAINED', 'ch_2', '%3$s', 'USD', 1000, 1000, '%3$s', 'fixture')
                """.formatted(RUN, ATTEMPT_1, ATTEMPT_2));
        exec("fakeproviders", "fakeproviders_app", """
                INSERT INTO card_charges (charge_id, client_reference, instrument_token, amount_minor, currency, status)
                VALUES ('ch_1', '%s', 'tok_card_ok', 2500, 'USD', 'SUCCEEDED'),
                       ('ch_2', '%s', 'tok_card_ok', 1000, 'USD', 'SUCCEEDED')""".formatted(ATTEMPT_1, ATTEMPT_2), """
                INSERT INTO fault_log (fault_id, provider, fault_type, target, seed) VALUES
                  ('flt_1', 'fakecard', 'report_missing_line', 'rpt_2026_01_01:ch_1', 7),
                  ('flt_2', 'fakecard', 'report_duplicate_line', 'rpt_2026_01_01:ch_2', 7),
                  ('flt_3', 'fakecard', 'timeout_after_commit', '/fakecard/v1/charges', 7)""");
    }

    private static String order(String id, String group, String key) {
        return """
                INSERT INTO money_orders (order_id, order_group_id, type, reason, source_system, idempotency_key,
                    request_hash, request_hash_version, effective_at)
                VALUES ('%s', '%s', 'COMMERCE', 'trip.completed', 'fixture', '%s', decode('00','hex'), 1, now())
                """.formatted(id, group, key);
    }

    /** A CHARGE attempt; {@code at} is a SQL expression used for both created_at and updated_at. */
    private static String attempt(String id, String rider, String sourceOrder, long amount, String status,
            String providerRef, String at) {
        return """
                INSERT INTO payment_attempts (attempt_id, kind, order_group_id, source_order_id, entity_id, provider,
                    instrument_token, currency, amount_minor, status, provider_ref, created_at, updated_at)
                VALUES ('%s', 'CHARGE', 'trip-x', %s, '%s', 'fakecard', 'tok_card_ok', 'USD', %d, '%s', %s, %s, %s)
                """.formatted(id, literal(sourceOrder), rider, amount, status, literal(providerRef), at, at);
    }

    private static String literal(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    // --- plumbing -------------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Work {
        void run(Statement statement) throws SQLException;
    }

    /** Orders need their entries in the same transaction: the zero-sum triggers are deferred to COMMIT. */
    private static void inOrdersTransaction(Work work) throws SQLException {
        try (Connection connection = connect("orders", "orders_app"); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            work.run(statement);
            connection.commit();
        }
    }

    private static void exec(String database, String role, String... statements) throws SQLException {
        try (Connection connection = connect(database, role); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.executeUpdate(sql);
            }
        }
    }

    private static Connection connect(String database, String role) throws SQLException {
        return DriverManager.getConnection(url(database), role, DB.password(role));
    }

    private static String url(String database) {
        String ledger = DB.jdbcUrl();
        return ledger.substring(0, ledger.lastIndexOf('/') + 1) + database;
    }

    private static void migrate(String database, String service) {
        Flyway.configure()
                .dataSource(url(database), database + "_owner", DB.password(database + "_owner"))
                .locations("filesystem:" + ZsTestDatabase.ROOT.resolve("services/" + service
                        + "/src/main/resources/db/migration"))
                .load()
                .migrate();
    }

    private static String line(Result result, String id) {
        return result.output().lines().filter(l -> l.startsWith("ZS-VERIFY " + id + " ")).findFirst()
                .orElse("no line for " + id + " in:\n" + result.output());
    }

    private Result verify(String... extra) {
        var args = new ArrayList<>(List.of("--jdbc-url", DB.jdbcUrl(), "--orders-jdbc-url", url("orders"),
                "--instruments-jdbc-url", url("instruments"), "--providers-jdbc-url", url("fakeproviders"),
                "--user", ZsTestDatabase.VERIFIER));
        args.addAll(List.of(extra));
        return run(args.toArray(String[]::new));
    }

    private Result run(String... args) {
        var captured = new ByteArrayOutputStream();
        var out = new PrintStream(captured, true, StandardCharsets.UTF_8);
        var full = new ArrayList<>(List.of(args));
        full.addAll(List.of("--env-file", envFile().toString()));
        int exitCode = VerifierMain.run(full.toArray(String[]::new), out);
        out.flush();
        return new Result(exitCode, captured.toString(StandardCharsets.UTF_8));
    }

    private static Path envFile() {
        try {
            Path file = output.resolve("throwaway.env");
            Files.writeString(file, "ZS_VERIFIER_DB_PASSWORD=" + DB.password(ZsTestDatabase.VERIFIER) + "\n");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Result(int exitCode, String output) {
    }
}
