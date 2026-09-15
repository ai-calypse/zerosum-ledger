package dev.zerosum.money;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * The chart of accounts as code (D01-6, ADR-0003): allowed account codes per entity <em>kind</em> and the normal side
 * of each account code. Keyed by kind only, never by full entity ID, so sub-entities of an existing kind (SP4
 * sharding) and new providers validate without chart changes. Immutable.
 */
public final class ChartOfAccounts {

    // decision: D01-6 — docs/step_01_domain_contracts.md#decisions-and-outputs (master §5.2 chart table)
    private static final Map<EntityKind, Set<String>> ALLOWED_ACCOUNTS = Map.of(
            EntityKind.RIDER, Set.of("receivable"),
            EntityKind.DRIVER, Set.of("payable"),
            EntityKind.PLATFORM, Set.of("revenue", "cash", "processing_fees"),
            EntityKind.PROVIDER, Set.of("clearing", "payout_clearing"));

    // decision: D01-6 — normal side per account code
    private static final Map<String, NormalSide> NORMAL_SIDES = Map.of(
            "receivable", NormalSide.DEBIT,
            "payable", NormalSide.CREDIT,
            "revenue", NormalSide.CREDIT,
            "cash", NormalSide.DEBIT,
            "processing_fees", NormalSide.DEBIT,
            "clearing", NormalSide.DEBIT,
            "payout_clearing", NormalSide.CREDIT);

    private ChartOfAccounts() {
    }

    /** The kind of a syntactically valid entity ID (rule 3 pattern), or empty. Never throws. */
    public static Optional<EntityKind> kindOf(String entityId) {
        if (entityId == null || entityId.length() > ValidationLimits.ENTITY_ID_MAX_LENGTH) {
            return Optional.empty();
        }
        Matcher m = ValidationLimits.ENTITY_ID_PATTERN.matcher(entityId);
        if (!m.matches()) {
            return Optional.empty();
        }
        String prefix = m.group(1);
        for (EntityKind kind : EntityKind.values()) {
            if (kind.prefix().equals(prefix)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    public static boolean isAllowed(EntityKind kind, String account) {
        return kind != null && account != null && ALLOWED_ACCOUNTS.get(kind).contains(account);
    }

    public static Set<String> allowedAccounts(EntityKind kind) {
        return ALLOWED_ACCOUNTS.get(kind);
    }

    /** The normal side of a known account code. */
    public static NormalSide normalSide(String account) {
        NormalSide side = account == null ? null : NORMAL_SIDES.get(account);
        if (side == null) {
            throw new IllegalArgumentException("unknown account code: " + account);
        }
        return side;
    }

    /**
     * Presents a signed balance (debit +, credit −) on the account's normal side (ADR-0003): unchanged for
     * debit-normal accounts, negated for credit-normal accounts. A contra balance (driver debt on {@code payable},
     * rider credit on {@code receivable}) therefore presents as a negative amount on the normal side.
     */
    public static long presentOnNormalSide(String account, long signedBalance) {
        return normalSide(account) == NormalSide.DEBIT ? signedBalance : Math.negateExact(signedBalance);
    }
}
