package dev.zerosum.ledger.apply;

import java.util.UUID;

/** What happened to one record (D02-3). Callers acknowledge only after the batch commits. */
public record ApplyOutcome(int index, UUID orderId, Status status, QuarantineCode errorCode, String detail) {

    public enum Status {
        /** The order was new and was applied in this batch. */
        APPLIED,
        /** The order was already applied (redelivery, or a repeat inside the batch). */
        DUPLICATE,
        /** The record was written to the quarantine table with {@link #errorCode()}. */
        QUARANTINED
    }

    static ApplyOutcome applied(int index, UUID orderId) {
        return new ApplyOutcome(index, orderId, Status.APPLIED, null, null);
    }

    static ApplyOutcome duplicate(int index, UUID orderId) {
        return new ApplyOutcome(index, orderId, Status.DUPLICATE, null, null);
    }

    static ApplyOutcome quarantined(int index, UUID orderId, QuarantineCode code, String detail) {
        return new ApplyOutcome(index, orderId, Status.QUARANTINED, code, detail);
    }
}
