package dev.zerosum.ledger.apply;

/**
 * Quarantine error codes (D02-9). Stored in {@code quarantined_orders.error_code}; S04 maps them in its DLQ policy and
 * the runbook re-publish procedure.
 */
public enum QuarantineCode {
    /** The payload is not JSON, or has no readable order ID. */
    UNDECODABLE_PAYLOAD,
    /** The payload does not validate against the money-order schema, or its schema version is unknown (D01-8). */
    SCHEMA_INVALID,
    /** A ‡ rule failed: account not allowed for the entity kind, or entries do not sum to zero per currency (D01-5). */
    STRUCTURALLY_INVALID,
    /** A balance or sequence number would overflow (D01-1 overflow policy). */
    ARITHMETIC_OVERFLOW,
    /** An unexpected non-transient database error for this record alone. */
    UNEXPECTED_DATABASE_ERROR
}
