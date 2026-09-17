package dev.zerosum.ledger.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pipeline's first real end: orders published to Kafka become ledger balances (D04-3).
 *
 * <p>The expected balances are the D01-9 golden scenario, not numbers written out here. They were transcribed from the
 * master's worked example and are the same figures S02 proved the engine against, so a disagreement means the
 * listener changed what reaches the engine rather than that someone's arithmetic drifted.
 */
class LedgerListenerIT extends LedgerPipelineTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> GOLDEN_ORDERS = List.of("O1", "O2", "O3", "O4", "O5", "O6", "O7");

    // No @AfterAll teardown here on purpose: KAFKA and DB are statics on the shared base, so every subclass sees the
    // same containers. A per-class stop() closed them for whichever pipeline test ran second, which surfaced as
    // "Mapped port can only be obtained after the container is started". They are released when the JVM exits.

    @Test
    void goldenOrdersPublishedToKafkaProduceTheGoldenBalances() throws SQLException {
        // Published in id order. O3 adjusts O1 and shares its group, so one key carries them in the order they were
        // created — which is the ordering ADR-0007 exists to provide.
        for (String id : GOLDEN_ORDERS) {
            String payload = GoldenPayloads.byId(id);
            publish(JSON.readTree(payload).get("order_group_id").asString(), payload);
        }

        awaitUntil(() -> appliedCount() == GOLDEN_ORDERS.size(),
                "the listener never applied all " + GOLDEN_ORDERS.size() + " orders; applied=" + appliedCount());

        TreeMap<String, Long> expected = expectedBalances("after_O1_O7");
        try (Connection c = DB.connect(LedgerTestDatabase.VERIFIER)) {
            TreeMap<String, Long> actual = LedgerQueries.balances(c);
            assertEquals(expected, actual, "balances must match the D01-9 golden scenario exactly");

            // The invariants that make the ledger trustworthy, checked on state that arrived through Kafka rather
            // than through a test driver.
            assertEquals(List.of(), LedgerQueries.i2Violations(c), "I2: per-currency sums must be zero");
            assertEquals(List.of(), LedgerQueries.i3Violations(c), "I3: changelog sequences must have no gaps");
            assertEquals(List.of(), LedgerQueries.i4Violations(c), "I4: balances must equal summed changelog deltas");
        }
    }

    @Test
    void everyAppliedOrderRecordsTheKafkaPositionItArrivedFrom() throws SQLException {
        awaitUntil(() -> appliedCount() > 0, "nothing was applied");

        try (Connection c = DB.connect(LedgerTestDatabase.VERIFIER)) {
            // The listener passes each record's source position to the engine, which stores it. Without it a
            // quarantined record could not be traced back to the offset it came from.
            long withPosition = LedgerQueries.count(c,
                    "SELECT count(*) FROM applied_orders WHERE kafka_topic IS NOT NULL AND kafka_offset IS NOT NULL");
            assertEquals(appliedCount(), withPosition,
                    "every order applied from the listener must carry its topic, partition and offset");
        }
    }

    private static long appliedCount() {
        try (Connection c = DB.connect(LedgerTestDatabase.VERIFIER)) {
            return LedgerQueries.count(c, "SELECT count(*) FROM applied_orders");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The golden scenario as entity/account/currency to signed minor units, matching LedgerQueries.balances(). */
    static TreeMap<String, Long> expectedBalances(String scenarioId) {
        JsonNode scenarios = JSON.readTree(GoldenPayloads.expectedBalances()).get("scenarios");
        for (JsonNode scenario : scenarios) {
            if (scenarioId.equals(scenario.get("id").asString())) {
                var balances = new TreeMap<String, Long>();
                for (JsonNode row : scenario.get("balances")) {
                    // Keyed exactly as LedgerQueries.balances() does: entity/account/currency. Zero balances are
                    // included — the accounts table keeps a row once an account has been touched, and an account that
                    // nets to zero is a real row, not an absent one. Filtering them here made the first run fail on
                    // presentation rather than on any disagreement about money.
                    balances.put(row.get("entity_id").asString() + "/" + row.get("account").asString() + "/"
                            + row.get("currency").asString(), row.get("signed_minor").asLong());
                }
                assertTrue(!balances.isEmpty(), "the golden scenario must contain balances");
                return balances;
            }
        }
        throw new IllegalArgumentException("no golden scenario " + scenarioId);
    }
}
