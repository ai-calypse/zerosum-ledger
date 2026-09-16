package dev.zerosum.ledger.changelog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.store.LedgerStore;
import dev.zerosum.ledger.store.LedgerStore.ChangelogRow;
import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.ledger.support.MoneyOrderPayloads;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * I5 tamper detection (D02-6, should-have S1). Each tamper class is applied to a real chain and the verifier must report
 * the first bad sequence number. The append-only triggers block every mutation, so tampering needs a superuser session
 * with triggers disabled; that is possible only inside the test container and is exactly the "malicious owner" case
 * recorded as a limitation in H.5.
 */
@Tag("integration")
class HashChainTamperIT {

    private static final String ENTITY = "rider:R1";
    private static final int ORDERS = 4;

    private LedgerTestDatabase db;
    private LedgerStore store;
    private final ChainVerifier verifier = new ChainVerifier(new ChangelogHasher());

    @BeforeEach
    void seedACleanChain() {
        db = LedgerTestDatabase.start();
        DataSource dataSource = db.dataSource(LedgerTestDatabase.APP);
        store = new LedgerStore(JdbcClient.create(dataSource), new JdbcTemplate(dataSource));
        ApplyTestDriver driver = ApplyTestDriver.create(dataSource);
        for (int i = 0; i < ORDERS; i++) {
            driver.applyOne(MoneyOrderPayloads.transfer(UUID.randomUUID(), "tamper_" + i,
                    ENTITY, "receivable", "driver:D1", "payable", "USD", 100L * (i + 1)));
        }
    }

    @AfterEach
    void stop() {
        db.close();
    }

    @Test
    void anUntamperedChainVerifies() {
        ChainVerifier.Result result = verifier.verify(store.readChangelog(ENTITY));
        assertNull(result.firstBadSeq(), result.detail());
        assertTrue(result.consistent());
        assertEquals(ORDERS, result.rowsChecked(), "one changelog row per order for this entity");
    }

    @Test
    void aModifiedFieldIsDetectedOnItsOwnRow() throws SQLException {
        tamper("UPDATE entity_changelog SET delta_minor = delta_minor + 1 WHERE entity_id = '" + ENTITY + "' AND seq = 2");

        ChainVerifier.Result result = verifier.verify(store.readChangelog(ENTITY));
        assertFalse(result.consistent());
        assertEquals(2L, result.firstBadSeq());
        assertEquals(ChainVerifier.Failure.HASH_MISMATCH, result.failure());
    }

    @Test
    void aModifiedRowWithItsOwnHashRecomputedBreaksTheFollowingLink() throws SQLException {
        // The strongest row-local modification: change a field and recompute that row's hash. The row then hashes
        // correctly on its own, so the break surfaces at the next row, whose previous-hash link is now stale.
        List<ChangelogRow> rows = store.readChangelog(ENTITY);
        ChangelogRow target = rows.get(1);
        long tamperedDelta = target.deltaMinor() + 1;
        byte[] recomputed = new ChangelogHasher().hash(target.prevHash(), target.entityId(), target.seq(),
                target.orderId(), target.accountCode(), target.currency(), tamperedDelta, target.balanceAfterMinor());

        tamper("UPDATE entity_changelog SET delta_minor = " + tamperedDelta + ", row_hash = '\\x"
                + HexFormat.of().formatHex(recomputed) + "' WHERE entity_id = '" + ENTITY + "' AND seq = 2");

        ChainVerifier.Result result = verifier.verify(store.readChangelog(ENTITY));
        assertFalse(result.consistent());
        assertEquals(3L, result.firstBadSeq(), "the tampered row verifies alone; its successor's link does not");
        assertEquals(ChainVerifier.Failure.BROKEN_LINK, result.failure());
    }

    @Test
    void aRemovedRowIsDetectedAsASequenceBreak() throws SQLException {
        tamper("DELETE FROM entity_changelog WHERE entity_id = '" + ENTITY + "' AND seq = 3");

        ChainVerifier.Result result = verifier.verify(store.readChangelog(ENTITY));
        assertFalse(result.consistent());
        assertEquals(3L, result.firstBadSeq());
        assertEquals(ChainVerifier.Failure.SEQUENCE_BREAK, result.failure());
    }

    @Test
    void aRemovedRowWithLaterRowsRenumberedIsDetectedAsABrokenLink() throws SQLException {
        tamper("DELETE FROM entity_changelog WHERE entity_id = '" + ENTITY + "' AND seq = 3",
               "UPDATE entity_changelog SET seq = 3 WHERE entity_id = '" + ENTITY + "' AND seq = 4");

        ChainVerifier.Result result = verifier.verify(store.readChangelog(ENTITY));
        assertFalse(result.consistent());
        assertEquals(3L, result.firstBadSeq(), "renumbering hides the gap but cannot restore the link");
        assertEquals(ChainVerifier.Failure.BROKEN_LINK, result.failure());
    }

    @Test
    void anInsertedRowIsDetected() throws SQLException {
        // A forged append: row 4 copied in as a new row 5.
        tamper("INSERT INTO entity_changelog (entity_id, seq, order_id, account_code, currency, delta_minor, "
                + "balance_after_minor, hash_version, prev_hash, row_hash) SELECT entity_id, 5, order_id, account_code, "
                + "currency, delta_minor, balance_after_minor, hash_version, prev_hash, row_hash FROM entity_changelog "
                + "WHERE entity_id = '" + ENTITY + "' AND seq = 4");

        ChainVerifier.Result result = verifier.verify(store.readChangelog(ENTITY));
        assertFalse(result.consistent());
        assertEquals(5L, result.firstBadSeq());
        assertEquals(ChainVerifier.Failure.BROKEN_LINK, result.failure());
    }

    @Test
    void reorderedRowsAreDetected() throws SQLException {
        // Swap the sequence numbers of rows 2 and 3, leaving every other field untouched.
        tamper("UPDATE entity_changelog SET seq = 99 WHERE entity_id = '" + ENTITY + "' AND seq = 2",
               "UPDATE entity_changelog SET seq = 2  WHERE entity_id = '" + ENTITY + "' AND seq = 3",
               "UPDATE entity_changelog SET seq = 3  WHERE entity_id = '" + ENTITY + "' AND seq = 99");

        ChainVerifier.Result result = verifier.verify(store.readChangelog(ENTITY));
        assertFalse(result.consistent());
        assertEquals(2L, result.firstBadSeq(), "detection lands on the first displaced row");
        // The row now claiming sequence 2 is the old row 3, whose previous-hash still points at the old row 2. The
        // verifier checks that link before it recomputes the hash, so a swap surfaces as a broken link rather than a
        // hash mismatch. Either way the swap is detected, at the first displaced sequence number.
        assertEquals(ChainVerifier.Failure.BROKEN_LINK, result.failure());
    }

    /** Mutates an append-only table, which needs a superuser session with the D02-2 triggers disabled. */
    private void tamper(String... statements) throws SQLException {
        try (Connection c = db.superuser(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE entity_changelog DISABLE TRIGGER ALL");
            try {
                for (String sql : statements) {
                    st.execute(sql);
                }
            } finally {
                st.execute("ALTER TABLE entity_changelog ENABLE TRIGGER ALL");
            }
        }
    }
}
