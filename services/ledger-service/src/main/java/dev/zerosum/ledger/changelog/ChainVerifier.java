package dev.zerosum.ledger.changelog;

import dev.zerosum.ledger.store.LedgerStore.ChangelogRow;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/**
 * Verifies an entity's hash chain (D02-6, should-have S1, invariant I5). One implementation serves verify (S02-T05) and
 * the invariants endpoint; apply uses the same {@link ChangelogHasher}, so there is no second copy of the canonical form.
 *
 * <p>It reports the <em>lowest</em> sequence number that fails, checking in one pass that sequence numbers are gapless
 * from 1, that the form version is one this build can verify, that each row's previous-hash link equals the prior row's
 * hash (null exactly on the first row), and that each stored hash equals the hash recomputed from the row's own fields.
 *
 * <p>The chain is tamper-evident only relative to an independently held head: an attacker who rewrites every row
 * <em>and</em> the entity's stored head produces a chain that verifies. That limitation is recorded in H.5.
 */
@Component
public class ChainVerifier {

    private final ChangelogHasher hasher;

    public ChainVerifier(ChangelogHasher hasher) {
        this.hasher = hasher;
    }

    /** Why a chain failed, or {@link #CONSISTENT} when it did not. */
    public enum Failure {
        SEQUENCE_BREAK,
        UNSUPPORTED_FORM_VERSION,
        BROKEN_LINK,
        HASH_MISMATCH
    }

    /** {@code firstBadSeq} and {@code failure} are null exactly when {@code consistent} is true. */
    public record Result(boolean consistent, Long firstBadSeq, Failure failure, String detail, long rowsChecked) {

        public static Result consistent(long rowsChecked) {
            return new Result(true, null, null, null, rowsChecked);
        }
    }

    public static final Result CONSISTENT = Result.consistent(0);

    /** Verifies rows of one entity supplied in ascending sequence order. */
    public Result verify(Iterable<ChangelogRow> rowsInSequenceOrder) {
        long expectedSeq = 1;
        long rowsChecked = 0;
        byte[] previousHash = null;

        for (ChangelogRow row : rowsInSequenceOrder) {
            if (row.seq() != expectedSeq) {
                return new Result(false, Math.min(row.seq(), expectedSeq), Failure.SEQUENCE_BREAK,
                        "expected sequence " + expectedSeq + " but found " + row.seq(), rowsChecked);
            }
            if (row.hashVersion() != ChangelogHasher.FORM_VERSION) {
                return new Result(false, row.seq(), Failure.UNSUPPORTED_FORM_VERSION,
                        "row uses canonical form version " + row.hashVersion() + ", this build verifies "
                                + ChangelogHasher.FORM_VERSION, rowsChecked);
            }
            if (!Arrays.equals(row.prevHash(), previousHash)) {
                return new Result(false, row.seq(), Failure.BROKEN_LINK,
                        row.seq() == 1
                                ? "the first row of an entity must have no previous hash"
                                : "previous-hash link does not match the prior row's hash", rowsChecked);
            }
            byte[] recomputed = hasher.hash(previousHash, row.entityId(), row.seq(), row.orderId(), row.accountCode(),
                    row.currency(), row.deltaMinor(), row.balanceAfterMinor());
            if (!Arrays.equals(recomputed, row.rowHash())) {
                return new Result(false, row.seq(), Failure.HASH_MISMATCH,
                        "stored hash does not match the hash recomputed from this row's fields", rowsChecked);
            }
            previousHash = row.rowHash();
            expectedSeq++;
            rowsChecked++;
        }
        return Result.consistent(rowsChecked);
    }
}
