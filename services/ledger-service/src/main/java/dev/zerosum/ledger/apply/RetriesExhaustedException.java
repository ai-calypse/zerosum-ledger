package dev.zerosum.ledger.apply;

import java.util.List;

/**
 * Raised when transient failures exhaust the configured attempts (D02-4). Money is never skipped or quarantined for a
 * transient failure: S04 maps this exception to pausing the listener plus an alert (D04-4). It carries the whole batch
 * so the caller can retry it after recovery.
 */
public class RetriesExhaustedException extends RuntimeException {

    private final transient List<ApplyRecord> batch;
    private final int attempts;

    public RetriesExhaustedException(List<ApplyRecord> batch, int attempts, Throwable cause) {
        super("ledger apply gave up after " + attempts + " attempts on a batch of " + batch.size() + " records", cause);
        this.batch = List.copyOf(batch);
        this.attempts = attempts;
    }

    public List<ApplyRecord> batch() {
        return batch;
    }

    public int attempts() {
        return attempts;
    }
}
