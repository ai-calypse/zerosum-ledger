package dev.zerosum.ledger.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.ledger.apply.ApplyRecord.SourcePosition;
import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Quarantine behaviour (D02-9, §0.3 C8/C25): undecodable payloads, ‡ violations, redelivery, and unaffected neighbours. */
@Tag("integration")
class ApplyQuarantineIT {

    private static LedgerTestDatabase db;
    private static ApplyTestDriver driver;

    @BeforeAll
    static void start() {
        db = LedgerTestDatabase.start();
        driver = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP));
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @Test
    void garbagePayloadIsQuarantinedWithoutAnOrderId() throws SQLException {
        ApplyBatchResult result = driver.applyOne("this is not json");
        ApplyOutcome outcome = result.outcomes().get(0);
        assertEquals(ApplyOutcome.Status.QUARANTINED, outcome.status());
        assertEquals(QuarantineCode.UNDECODABLE_PAYLOAD, outcome.errorCode());
        assertNull(outcome.orderId());
        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
            assertEquals(1, LedgerQueries.count(c,
                    "SELECT count(*) FROM quarantined_orders WHERE order_id IS NULL AND error_code = 'UNDECODABLE_PAYLOAD'"));
        }
    }

    @Test
    void ledgerRuleViolationIsQuarantinedWhileValidOrdersInTheSameBatchApply() throws SQLException {
        // Schema-valid, but the rider entity may not hold a payable account (‡ rule 3).
        String invalid = GoldenPayloads.byId("O1").replace("\"account\": \"receivable\"", "\"account\": \"payable\"");
        ApplyBatchResult result = driver.applyBatch(List.of(invalid, GoldenPayloads.byId("O5")));
        assertEquals(QuarantineCode.STRUCTURALLY_INVALID, result.outcomes().get(0).errorCode());
        assertEquals(ApplyOutcome.Status.APPLIED, result.outcomes().get(1).status());
        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
            assertEquals(1, LedgerQueries.count(c,
                    "SELECT count(*) FROM quarantined_orders WHERE error_code = 'STRUCTURALLY_INVALID'"));
            assertEquals(1, LedgerQueries.count(c, "SELECT count(*) FROM applied_orders"));
        }
    }

    @Test
    void redeliveringTheSameInvalidRecordIsANoOp() throws SQLException {
        SourcePosition position = new SourcePosition("payments.money-orders.v1", 2, 77L);
        ApplyRecord record = ApplyRecord.of("{\"schema\":\"zerosum.money_order.v1\"}", position);
        assertEquals(QuarantineCode.SCHEMA_INVALID, driver.apply(List.of(record)).outcomes().get(0).errorCode());
        assertEquals(QuarantineCode.SCHEMA_INVALID, driver.apply(List.of(record)).outcomes().get(0).errorCode());
        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
            assertEquals(1, LedgerQueries.count(c, "SELECT count(*) FROM quarantined_orders WHERE kafka_offset = 77"));
        }
    }

    @Test
    void amountWithAZeroFractionIsRejectedEvenThoughTheSchemaAcceptsIt() throws SQLException {
        String payload = GoldenPayloads.byId("O2").replace("\"amount_minor\": 2500", "\"amount_minor\": 2500.0");
        ApplyOutcome outcome = driver.applyOne(payload).outcomes().get(0);
        assertEquals(QuarantineCode.SCHEMA_INVALID, outcome.errorCode(), outcome.detail());
    }
}
