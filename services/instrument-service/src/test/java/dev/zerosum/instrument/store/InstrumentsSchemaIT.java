package dev.zerosum.instrument.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.zerosum.testsupport.ZsTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * S05-T07: the constraints that carry correctness, checked against a real database rather than read off the migration.
 *
 * <p>The step register names three suites — {@code InstrumentsSchemaIT}, {@code OneInflightPayoutIT} and
 * {@code AttemptUniquenessIT}. They are the three nested groups below; the mapping is recorded in the evidence note.
 */
@Tag("integration")
class InstrumentsSchemaIT {

    private static final ZsTestDatabase DB = ZsTestDatabase.start(
            "instruments", "services/instrument-service/src/main/resources/db/migration");

    /** SQLSTATE 42501, insufficient_privilege: what both the revoked grant and the trigger raise. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    /** SQLSTATE 23505, unique_violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    @AfterAll
    static void stop() {
        DB.close();
    }

    private static void exec(String role, String sql) throws SQLException {
        try (Connection connection = DB.connect(role); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void execAsOwner(String sql) throws SQLException {
        exec(DB.owner(), sql);
    }

    private static void execAsApp(String sql) throws SQLException {
        exec(DB.app(), sql);
    }

    /** Inserts an attempt as the runtime role and returns its id. */
    private static String insertAttempt(String kind, String entity, String currency, String status,
            String sourceOrderId) throws SQLException {
        String attemptId = UUID.randomUUID().toString();
        execAsApp("""
                INSERT INTO payment_attempts (attempt_id, kind, order_group_id, source_order_id, entity_id, provider,
                                              instrument_token, currency, amount_minor, status)
                VALUES ('%s', '%s', 'trip_%s', %s, '%s', 'fakecard', 'tok_card_ok', '%s', 1000, '%s')
                """.formatted(attemptId, kind, UUID.randomUUID().toString().substring(0, 8),
                sourceOrderId == null ? "NULL" : "'" + sourceOrderId + "'", entity, currency, status));
        return attemptId;
    }

    @Nested
    @DisplayName("append-only history (the InstrumentsSchemaIT case)")
    class AppendOnly {

        @Test
        @DisplayName("transition history cannot be rewritten, by the runtime role or by the owner")
        void transitionsAreAppendOnly() throws SQLException {
            String attemptId = insertAttempt("CHARGE", "rider:R1", "USD", "CREATED", UUID.randomUUID().toString());
            execAsApp("INSERT INTO attempt_transitions (attempt_id, seq, from_status, to_status, cause) "
                    + "VALUES ('" + attemptId + "', 1, NULL, 'CREATED', 'created by test')");

            // The runtime role fails at the privilege check, before any trigger runs.
            assertThatThrownBy(() -> execAsApp("UPDATE attempt_transitions SET cause = 'rewritten'"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
            assertThatThrownBy(() -> execAsApp("DELETE FROM attempt_transitions"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);

            // The owner has the privilege and is stopped by the trigger instead. Privileges alone would leave the
            // migrating role able to rewrite history, which is exactly the role an attacker or a bad script has.
            assertThatThrownBy(() -> execAsOwner("UPDATE attempt_transitions SET cause = 'rewritten'"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
            assertThatThrownBy(() -> execAsOwner("DELETE FROM attempt_transitions"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
            assertThatThrownBy(() -> execAsOwner("TRUNCATE attempt_transitions"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
        }

        @Test
        @DisplayName("provider events cannot be rewritten either, and a redelivery is recorded once")
        void providerEventsAreAppendOnlyAndDeduped() throws SQLException {
            String eventId = "evt_" + UUID.randomUUID();
            execAsApp("INSERT INTO provider_events (provider, provider_event_id, payload) "
                    + "VALUES ('fakebank', '" + eventId + "', '{}'::jsonb)");

            assertThatThrownBy(() -> execAsOwner("UPDATE provider_events SET payload = '{\"tampered\":true}'::jsonb"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);

            // A webhook redelivered five times must be recorded once; the primary key is what guarantees it.
            assertThatThrownBy(() -> execAsApp("INSERT INTO provider_events (provider, provider_event_id, payload) "
                    + "VALUES ('fakebank', '" + eventId + "', '{}'::jsonb)"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(UNIQUE_VIOLATION);
        }
    }

    @Nested
    @DisplayName("one in-flight payout per driver and currency (the OneInflightPayoutIT case, M10(a))")
    class OneInflightPayout {

        @Test
        @DisplayName("a second in-flight payout for the same driver and currency is refused by the database")
        void secondInflightPayoutIsRefused() throws SQLException {
            String driver = "driver:D" + UUID.randomUUID().toString().substring(0, 8);
            insertAttempt("PAYOUT", driver, "USD", "PENDING", null);

            // Not an application check: two concurrent payout runs would both pass a check-then-insert and both pay.
            assertThatThrownBy(() -> insertAttempt("PAYOUT", driver, "USD", "CREATED", null))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(UNIQUE_VIOLATION);
        }

        @Test
        @DisplayName("another currency is a different payout, and a terminal one frees the slot")
        void currencyAndTerminalStatusFreeTheIndex() throws SQLException {
            String driver = "driver:D" + UUID.randomUUID().toString().substring(0, 8);
            String first = insertAttempt("PAYOUT", driver, "USD", "PENDING", null);

            // A driver paid in two currencies holds two independent payouts.
            insertAttempt("PAYOUT", driver, "EUR", "PENDING", null);

            execAsApp("UPDATE payment_attempts SET status = 'SETTLED' WHERE attempt_id = '" + first + "'");
            // Settled is terminal, so it leaves the partial index and the next run may pay this driver again.
            String replacement = insertAttempt("PAYOUT", driver, "USD", "CREATED", null);

            assertThat(replacement).isNotEqualTo(first);
        }
    }

    @Nested
    @DisplayName("one attempt per order, entity and currency (the AttemptUniquenessIT case, §0.3 C22)")
    class AttemptUniqueness {

        @Test
        @DisplayName("a duplicate charge for the same order, entity and currency conflicts")
        void duplicateChargeConflicts() throws SQLException {
            String orderId = UUID.randomUUID().toString();
            insertAttempt("CHARGE", "rider:R9", "USD", "CREATED", orderId);

            // A redelivered order must not create a second charge; the caller treats this as "already created".
            assertThatThrownBy(() -> insertAttempt("CHARGE", "rider:R9", "USD", "CREATED", orderId))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(UNIQUE_VIOLATION);
        }

        @Test
        @DisplayName("the same order may charge the same rider in a second currency")
        void currencyIsPartOfTheKey() throws SQLException {
            String orderId = UUID.randomUUID().toString();
            insertAttempt("CHARGE", "rider:R9", "USD", "CREATED", orderId);

            // Without currency in the key (§0.3 C22) this second charge would collide and silently never happen.
            String other = insertAttempt("CHARGE", "rider:R9", "EUR", "CREATED", orderId);

            assertThat(other).isNotBlank();
        }

        @Test
        @DisplayName("a refund is a different kind, so it does not collide with the charge it reverses")
        void refundDoesNotCollideWithItsCharge() throws SQLException {
            String orderId = UUID.randomUUID().toString();
            insertAttempt("CHARGE", "rider:R7", "USD", "SUCCEEDED", orderId);

            String refund = insertAttempt("REFUND", "rider:R7", "USD", "CREATED", orderId);

            assertThat(refund).isNotBlank();
        }
    }
}
