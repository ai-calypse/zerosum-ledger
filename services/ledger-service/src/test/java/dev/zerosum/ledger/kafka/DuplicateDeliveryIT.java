package dev.zerosum.ledger.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * M5(a) through the real pipeline: publishing every order three times leaves the ledger identical to publishing once.
 *
 * <p>Copies are interleaved rather than sent back to back, so they land in different polls and therefore different
 * batches and different transactions. Three consecutive copies could be deduplicated inside a single engine call,
 * which would prove something much weaker than redelivery after a commit.
 */
class DuplicateDeliveryIT extends LedgerPipelineTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> GOLDEN_ORDERS = List.of("O1", "O2", "O3", "O4", "O5", "O6", "O7");
    private static final int DELIVERIES = 3;

    // No @AfterAll teardown here on purpose: KAFKA and DB are statics on the shared base, so every subclass sees the
    // same containers. A per-class stop() closed them for whichever pipeline test ran second, which surfaced as
    // "Mapped port can only be obtained after the container is started". They are released when the JVM exits.
    //
    // The database is shared with LedgerListenerIT, which publishes the same golden order ids. That is harmless in
    // either running order precisely because of what this test asserts — the same order applies once no matter how
    // often it arrives — so the assertions below are written against distinct orders, never against a count of
    // deliveries.

    @Test
    void publishingEveryOrderThreeTimesAppliesEachExactlyOnce() throws SQLException {
        for (int round = 0; round < DELIVERIES; round++) {
            for (String id : GOLDEN_ORDERS) {
                String payload = GoldenPayloads.byId(id);
                publish(JSON.readTree(payload).get("order_group_id").asString(), payload);
            }
            // A pause between rounds so the listener drains one round before the next arrives, which is what puts the
            // copies in separate batches.
            sleep(750);
        }

        awaitUntil(() -> appliedCount() == GOLDEN_ORDERS.size(),
                "expected " + GOLDEN_ORDERS.size() + " applied orders, found " + appliedCount());
        // Held briefly: a duplicate that was going to create a second row would do so after the first round, not
        // before it.
        for (int i = 0; i < 6; i++) {
            assertEquals(GOLDEN_ORDERS.size(), appliedCount(), "a redelivered order must not apply twice");
            sleep(250);
        }

        TreeMap<String, Long> expected = LedgerListenerIT.expectedBalances("after_O1_O7");
        try (Connection c = DB.connect(LedgerTestDatabase.VERIFIER)) {
            assertEquals(expected, LedgerQueries.balances(c),
                    "balances after three deliveries must equal the balances after one (M5(a))");
            assertEquals(List.of(), LedgerQueries.i2Violations(c));
            assertEquals(List.of(), LedgerQueries.i3Violations(c),
                    "I3: a duplicate must not consume a changelog sequence number");
            assertEquals(List.of(), LedgerQueries.i4Violations(c));

            // The strongest dedupe check available: the changelog is where entries live (there is no separate entries
            // table), so it must hold exactly one row per entry of each DISTINCT order, however many times that order
            // was delivered. Balances alone could look right while the changelog had grown.
            long expectedChangelogRows = GOLDEN_ORDERS.stream()
                    .mapToLong(id -> JSON.readTree(GoldenPayloads.byId(id)).get("entries").size())
                    .sum();
            assertEquals(expectedChangelogRows, LedgerQueries.count(c, "SELECT count(*) FROM entity_changelog"),
                    "duplicates must add no changelog rows: " + DELIVERIES + " deliveries of "
                            + GOLDEN_ORDERS.size() + " orders must leave the entries of one delivery");
        }
    }

    private static long appliedCount() {
        try (Connection c = DB.connect(LedgerTestDatabase.VERIFIER)) {
            return LedgerQueries.count(c, "SELECT count(*) FROM applied_orders");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
