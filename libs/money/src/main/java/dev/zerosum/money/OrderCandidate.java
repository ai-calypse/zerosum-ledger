package dev.zerosum.money;

import java.util.List;

/**
 * The minimal order shape {@link ZeroSumValidator} checks (D01-5). S02 and S03 map their own DTOs into it. Every
 * field may be null: the validator reports nulls as violations. No JSON or framework annotations.
 */
public record OrderCandidate(String type, String reason, List<Entry> entries) {

    /** One signed line: debit +, credit − (ADR-0003). */
    public record Entry(String entityId, String account, String currency, Long amountMinor) {

        public static Entry of(String entityId, String account, String currency, long amountMinor) {
            return new Entry(entityId, account, currency, amountMinor);
        }
    }
}
