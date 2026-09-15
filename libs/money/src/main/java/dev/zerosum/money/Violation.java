package dev.zerosum.money;

import java.util.Comparator;

/**
 * One validation failure (D01-5): a stable code, the master §5.3 rule number, and the entry index or currency where
 * relevant (otherwise null).
 */
public record Violation(Code code, int rule, Integer entryIndex, String currency) {

    /** Stable codes, one per failure class. S03 maps them to API errors (D03-2); S02 stores them as quarantine codes (D02-9). */
    public enum Code {
        // rule 1
        ORDER_MISSING,
        TYPE_INVALID,
        REASON_INVALID,
        // rule 2
        ENTRY_COUNT_OUT_OF_RANGE,
        ENTRY_MISSING,
        AMOUNT_MISSING,
        AMOUNT_ZERO,
        AMOUNT_OUT_OF_RANGE,
        // rule 3
        ENTITY_ID_INVALID,
        ACCOUNT_NOT_ALLOWED,
        // rule 4
        CURRENCY_NOT_ALLOWED,
        // rule 5
        ZERO_SUM_VIOLATED,
        ZERO_SUM_OVERFLOW
    }

    /** Rule number, then entry index, then currency code; nulls first. */
    public static final Comparator<Violation> ORDER = Comparator.comparingInt(Violation::rule)
            .thenComparing(Violation::entryIndex, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Violation::currency, Comparator.nullsFirst(Comparator.naturalOrder()));

    static Violation of(Code code, int rule) {
        return new Violation(code, rule, null, null);
    }

    static Violation atEntry(Code code, int rule, int index) {
        return new Violation(code, rule, index, null);
    }
}
