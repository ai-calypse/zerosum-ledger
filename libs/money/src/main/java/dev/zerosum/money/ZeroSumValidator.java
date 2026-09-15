package dev.zerosum.money;

import dev.zerosum.money.OrderCandidate.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Money-order rules 1–5 of master §5.3 (D01-5): type and reason, entry count and amounts, entity ID and allowed
 * account, currency allow-list, zero-sum per currency. Rules 6–9 need database state, the principal or caller policy
 * and belong to S03. Pure, stateless and total: it never throws on malformed input.
 */
public final class ZeroSumValidator {

    private static final ZeroSumValidator DEFAULT = new ZeroSumValidator(null);

    private final CurrencyRules explicitRules;

    private ZeroSumValidator(CurrencyRules rules) {
        this.explicitRules = rules;
    }

    /** Validator using {@link CurrencyRules#defaults()}. */
    public static ZeroSumValidator defaults() {
        return DEFAULT;
    }

    public static ZeroSumValidator with(CurrencyRules rules) {
        return new ZeroSumValidator(rules);
    }

    /**
     * Which rules a consumer runs (D01-5).
     *
     * <ul>
     *   <li>{@link #FULL}: rules 1–5; order-service, the single writer of money orders (ADR-0006).</li>
     *   <li>{@link #LEDGER}: only the rules master §5.3 marks ‡ — the account allowed for the entity kind (rule 3, which
     *       needs the entity ID to parse) and zero-sum per currency (rule 5). The ledger never re-applies order-service
     *       rules such as the currency allow-list, so a configuration difference can't quarantine a valid order
     *       (change request CR-S02-02).</li>
     * </ul>
     */
    public enum RuleSet {
        FULL,
        LEDGER
    }

    /** All {@link RuleSet#FULL} violations, ordered by {@link Violation#ORDER}; empty means valid. */
    public List<Violation> validate(OrderCandidate order) {
        return validate(order, RuleSet.FULL);
    }

    /** All violations of the given rule set, ordered by {@link Violation#ORDER}; empty means valid. */
    public List<Violation> validate(OrderCandidate order, RuleSet ruleSet) {
        List<Violation> out = new ArrayList<>();
        if (order == null) {
            out.add(Violation.of(Violation.Code.ORDER_MISSING, 1));
            return out;
        }
        boolean full = ruleSet == RuleSet.FULL;
        CurrencyRules rules = explicitRules != null ? explicitRules : CurrencyRules.defaults();

        // Rule 1
        if (full && (order.type() == null || !ValidationLimits.TYPES.contains(order.type()))) {
            out.add(Violation.of(Violation.Code.TYPE_INVALID, 1));
        }
        String reason = order.reason();
        if (full && (reason == null || reason.length() > ValidationLimits.REASON_MAX_LENGTH
                || !ValidationLimits.REASON_PATTERN.matcher(reason).matches())) {
            out.add(Violation.of(Violation.Code.REASON_INVALID, 1));
        }

        // Rule 2: count first; above the maximum, per-entry work is skipped entirely.
        List<Entry> entries = order.entries();
        int count = entries == null ? 0 : entries.size();
        if (full && (count < ValidationLimits.MIN_ENTRIES || count > ValidationLimits.MAX_ENTRIES)) {
            out.add(Violation.of(Violation.Code.ENTRY_COUNT_OUT_OF_RANGE, 2));
            if (count > ValidationLimits.MAX_ENTRIES) {
                return out;
            }
        }

        Map<String, Long> sums = new TreeMap<>();
        Map<String, Boolean> overflowed = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            Entry e = entries.get(i);
            if (e == null) {
                out.add(Violation.atEntry(Violation.Code.ENTRY_MISSING, 2, i));
                continue;
            }
            Long amount = e.amountMinor();
            if (amount == null) {
                out.add(Violation.atEntry(Violation.Code.AMOUNT_MISSING, 2, i));
            } else if (full && amount == 0) {
                out.add(Violation.atEntry(Violation.Code.AMOUNT_ZERO, 2, i));
            } else if (full && (amount > ValidationLimits.MAX_ABS_AMOUNT_MINOR || amount < -ValidationLimits.MAX_ABS_AMOUNT_MINOR)) {
                out.add(Violation.atEntry(Violation.Code.AMOUNT_OUT_OF_RANGE, 2, i));
            }

            // Rule 3: an invalid entity ID skips the account check to avoid cascading noise.
            Optional<EntityKind> kind = ChartOfAccounts.kindOf(e.entityId());
            if (kind.isEmpty()) {
                out.add(Violation.atEntry(Violation.Code.ENTITY_ID_INVALID, 3, i));
            } else if (!ChartOfAccounts.isAllowed(kind.get(), e.account())) {
                out.add(Violation.atEntry(Violation.Code.ACCOUNT_NOT_ALLOWED, 3, i));
            }

            // Rule 4
            String currency = e.currency();
            if (full && (currency == null || !rules.isAllowed(currency))) {
                out.add(new Violation(Violation.Code.CURRENCY_NOT_ALLOWED, 4, i, currency));
            }

            // Rule 5 accumulation, overflow-safe.
            if (currency != null && amount != null && !overflowed.containsKey(currency)) {
                try {
                    sums.merge(currency, amount, Math::addExact);
                } catch (ArithmeticException overflow) {
                    overflowed.put(currency, Boolean.TRUE);
                    sums.remove(currency);
                }
            }
        }

        // Rule 5
        for (String currency : overflowed.keySet()) {
            out.add(new Violation(Violation.Code.ZERO_SUM_OVERFLOW, 5, null, currency));
        }
        sums.forEach((currency, sum) -> {
            if (sum != 0) {
                out.add(new Violation(Violation.Code.ZERO_SUM_VIOLATED, 5, null, currency));
            }
        });

        out.sort(Violation.ORDER);
        return out;
    }
}
