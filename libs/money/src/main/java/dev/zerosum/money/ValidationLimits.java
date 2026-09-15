package dev.zerosum.money;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Every money-order validation limit in one place.
 *
 * <p>decision: D01-5 — docs/step_01_domain_contracts.md#decisions-and-outputs. Values come from master §5.3 rules
 * 1–4. S02/S03 DDL CHECK constraints and the JSON Schema {@code $comment}s trace to D01-5 as well, so a change is
 * visible to all of them.
 */
public final class ValidationLimits {

    /** Rule 1: order types. */
    public static final List<String> TYPES = List.of("COMMERCE", "COLLECTION", "REFUND", "DISBURSEMENT", "SETTLEMENT");

    /** Rule 1: reason pattern; anchored, no nested quantifiers. */
    public static final Pattern REASON_PATTERN = Pattern.compile("^[a-z_]+\\.[a-z_]+$");

    /** Rule 1: reason length bound checked before the pattern (selected by D01-5; the master sets no bound). */
    public static final int REASON_MAX_LENGTH = 64;

    /** Rule 2: entry count bounds, inclusive. */
    public static final int MIN_ENTRIES = 2;

    public static final int MAX_ENTRIES = 50;

    /** Rule 2: per-entry magnitude cap, |amount_minor| ≤ 10¹². */
    public static final long MAX_ABS_AMOUNT_MINOR = 1_000_000_000_000L;

    /** Rule 3: entity ID pattern; anchored, no nested quantifiers. */
    public static final Pattern ENTITY_ID_PATTERN =
            Pattern.compile("^(rider|driver|platform|provider):[A-Za-z0-9_-]{1,64}$");

    /** Rule 3: longest possible match of {@link #ENTITY_ID_PATTERN} ("provider:" + 64), checked before the pattern. */
    public static final int ENTITY_ID_MAX_LENGTH = "provider:".length() + 64;

    private ValidationLimits() {
    }
}
