package dev.zerosum.money.generate;

import dev.zerosum.money.ChartOfAccounts;
import dev.zerosum.money.CurrencyRules;
import dev.zerosum.money.EntityKind;
import dev.zerosum.money.OrderCandidate;
import dev.zerosum.money.OrderCandidate.Entry;
import dev.zerosum.money.ValidationLimits;
import dev.zerosum.money.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * Seeded generator of valid orders and labeled invalid orders (D01-10). Deterministic for a given generator state:
 * it draws only from ordered collections, never from hash-ordered sets or maps.
 */
public final class OrderGenerator {

    /** An invalid order and the violation code its single mutation guarantees (not obtained from the validator). */
    public record Labeled(OrderCandidate order, Violation.Code expected) {
    }

    /** One mutation family per rule 1–5 failure class. */
    public static final List<Violation.Code> FAMILIES = List.of(
            Violation.Code.TYPE_INVALID, Violation.Code.REASON_INVALID, Violation.Code.ENTRY_COUNT_OUT_OF_RANGE,
            Violation.Code.AMOUNT_ZERO, Violation.Code.AMOUNT_OUT_OF_RANGE, Violation.Code.ENTITY_ID_INVALID,
            Violation.Code.ACCOUNT_NOT_ALLOWED, Violation.Code.CURRENCY_NOT_ALLOWED, Violation.Code.ZERO_SUM_VIOLATED);

    private static final int REDRAW_LIMIT = 100;
    private static final List<String> REASONS = List.of("trip.completed", "fare.adjusted", "charge.succeeded",
            "refund.succeeded", "payout.accepted", "payout.settled", "payout.returned", "settlement.received");
    private static final String SUFFIX_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";

    // Independent copies of the rules for the mutation self-check, so a wrong label can't hide behind the code under test.
    private static final Set<String> CHECK_TYPES = Set.of("COMMERCE", "COLLECTION", "REFUND", "DISBURSEMENT", "SETTLEMENT");
    private static final Pattern CHECK_REASON = Pattern.compile("[a-z_]+\\.[a-z_]+");
    private static final Pattern CHECK_ENTITY = Pattern.compile("(rider|driver|platform|provider):[A-Za-z0-9_-]{1,64}");
    private static final Set<String> CHECK_CURRENCIES = Set.of("EUR", "JPY", "KWD", "USD");
    private static final Map<String, Set<String>> CHECK_ACCOUNTS = Map.of(
            "rider", Set.of("receivable"), "driver", Set.of("payable"),
            "platform", Set.of("revenue", "cash", "processing_fees"), "provider", Set.of("clearing", "payout_clearing"));
    private static final List<String> ALL_ACCOUNTS =
            List.of("cash", "clearing", "payable", "payout_clearing", "processing_fees", "receivable", "revenue");
    private static final long CHECK_CAP = 1_000_000_000_000L;

    private final RandomGenerator rng;
    private final long seed;
    private final List<String> currencies;

    public OrderGenerator(RandomGenerator rng, long seed) {
        this.rng = rng;
        this.seed = seed;
        this.currencies = List.copyOf(CurrencyRules.defaults().allowedCodes()); // sorted
    }

    /** A valid order: 1–3 currencies, each balanced by construction; occasionally repeated lines (§0.3 C4). */
    public OrderCandidate valid() {
        for (int attempt = 0; attempt < REDRAW_LIMIT; attempt++) {
            OrderCandidate order = tryValid();
            if (order != null) {
                return order;
            }
        }
        throw new IllegalStateException("valid-order redraw bound exhausted (seed " + seed + ")");
    }

    /** A valid order with exactly one labeled mutation. */
    public Labeled invalid() {
        OrderCandidate base = valid();
        Violation.Code family = FAMILIES.get(rng.nextInt(FAMILIES.size()));
        OrderCandidate mutated = mutate(base, family);
        if (!mutationHolds(mutated, family)) {
            throw new IllegalStateException("mutation " + family + " did not hold (seed " + seed + "): " + mutated);
        }
        return new Labeled(mutated, family);
    }

    private OrderCandidate tryValid() {
        List<String> pool = new ArrayList<>(currencies);
        shuffle(pool);
        int currencyCount = 1 + rng.nextInt(Math.min(3, pool.size()));
        int maxPerCurrency = ValidationLimits.MAX_ENTRIES / currencyCount;
        long amountBound = ValidationLimits.MAX_ABS_AMOUNT_MINOR / 64; // ≤ 49 such amounts can't overflow or exceed the cap

        List<Entry> entries = new ArrayList<>();
        for (String currency : pool.subList(0, currencyCount)) {
            int firstIndex = entries.size();
            int lines = 2 + skewed(maxPerCurrency - 2);
            long sum = 0;
            for (int i = 0; i < lines - 1; i++) {
                long amount = 1 + rng.nextLong(amountBound);
                if (rng.nextBoolean()) {
                    amount = -amount;
                }
                sum += amount;
                if (i > 0 && rng.nextInt(10) == 0) {
                    Entry earlier = entries.get(firstIndex + rng.nextInt(entries.size() - firstIndex));
                    entries.add(Entry.of(earlier.entityId(), earlier.account(), currency, amount));
                } else {
                    entries.add(randomLine(currency, amount));
                }
            }
            long closing = -sum;
            if (closing == 0 || closing > ValidationLimits.MAX_ABS_AMOUNT_MINOR || closing < -ValidationLimits.MAX_ABS_AMOUNT_MINOR) {
                return null;
            }
            entries.add(randomLine(currency, closing));
        }
        return new OrderCandidate(ValidationLimits.TYPES.get(rng.nextInt(ValidationLimits.TYPES.size())),
                REASONS.get(rng.nextInt(REASONS.size())), List.copyOf(entries));
    }

    private OrderCandidate mutate(OrderCandidate o, Violation.Code family) {
        List<Entry> entries = new ArrayList<>(o.entries());
        int i = rng.nextInt(entries.size());
        Entry e = entries.get(i);
        switch (family) {
            case TYPE_INVALID -> {
                return new OrderCandidate(pick("TRANSFER", "commerce", "", null), o.reason(), entries);
            }
            case REASON_INVALID -> {
                return new OrderCandidate(o.type(), pick("Trip.completed", "tripcompleted", "trip.completed.x", "trip.",
                        null, "a." + "b".repeat(70)), entries);
            }
            case ENTRY_COUNT_OUT_OF_RANGE -> {
                if (rng.nextBoolean()) {
                    entries = new ArrayList<>(entries.subList(0, 1));
                } else {
                    while (entries.size() <= ValidationLimits.MAX_ENTRIES) {
                        entries.add(entries.get(rng.nextInt(entries.size())));
                    }
                }
            }
            case AMOUNT_ZERO -> entries.set(i, new Entry(e.entityId(), e.account(), e.currency(), 0L));
            case AMOUNT_OUT_OF_RANGE -> {
                long over = ValidationLimits.MAX_ABS_AMOUNT_MINOR + 1 + rng.nextLong(1000);
                entries.set(i, new Entry(e.entityId(), e.account(), e.currency(), rng.nextBoolean() ? over : -over));
            }
            case ENTITY_ID_INVALID -> entries.set(i, new Entry(
                    pick("wallet:W1", "rider:", "rider:R 1", "RIDER:R1", null, "rider:" + "a".repeat(65)),
                    e.account(), e.currency(), e.amountMinor()));
            case ACCOUNT_NOT_ALLOWED -> {
                String prefix = e.entityId().substring(0, e.entityId().indexOf(':'));
                List<String> disallowed = ALL_ACCOUNTS.stream().filter(a -> !CHECK_ACCOUNTS.get(prefix).contains(a)).toList();
                entries.set(i, new Entry(e.entityId(), disallowed.get(rng.nextInt(disallowed.size())), e.currency(), e.amountMinor()));
            }
            case CURRENCY_NOT_ALLOWED -> entries.set(i,
                    new Entry(e.entityId(), e.account(), pick("GBP", "XXX", "usd", null), e.amountMinor()));
            case ZERO_SUM_VIOLATED -> {
                long delta = 1 + rng.nextLong(1000);
                long amount = e.amountMinor() + delta == 0 ? e.amountMinor() + delta + 1 : e.amountMinor() + delta;
                entries.set(i, new Entry(e.entityId(), e.account(), e.currency(), amount));
            }
            default -> throw new IllegalArgumentException("no mutation family for " + family);
        }
        return new OrderCandidate(o.type(), o.reason(), List.copyOf(entries));
    }

    /** Confirms the mutation with independent checks, never by calling the validator. */
    private static boolean mutationHolds(OrderCandidate o, Violation.Code family) {
        List<Entry> es = o.entries();
        return switch (family) {
            case TYPE_INVALID -> o.type() == null || !CHECK_TYPES.contains(o.type());
            case REASON_INVALID -> o.reason() == null || o.reason().length() > 64 || !CHECK_REASON.matcher(o.reason()).matches();
            case ENTRY_COUNT_OUT_OF_RANGE -> es.size() < 2 || es.size() > 50;
            case AMOUNT_ZERO -> es.stream().anyMatch(e -> e.amountMinor() == 0);
            case AMOUNT_OUT_OF_RANGE -> es.stream().anyMatch(e -> e.amountMinor() > CHECK_CAP || e.amountMinor() < -CHECK_CAP);
            case ENTITY_ID_INVALID -> es.stream().anyMatch(e -> e.entityId() == null || !CHECK_ENTITY.matcher(e.entityId()).matches());
            case ACCOUNT_NOT_ALLOWED -> es.stream().anyMatch(e ->
                    !CHECK_ACCOUNTS.get(e.entityId().substring(0, e.entityId().indexOf(':'))).contains(e.account()));
            case CURRENCY_NOT_ALLOWED -> es.stream().anyMatch(e -> e.currency() == null || !CHECK_CURRENCIES.contains(e.currency()));
            case ZERO_SUM_VIOLATED -> {
                java.util.TreeMap<String, Long> sums = new java.util.TreeMap<>();
                es.forEach(e -> sums.merge(e.currency(), e.amountMinor(), Math::addExact));
                yield sums.values().stream().anyMatch(s -> s != 0);
            }
            default -> false;
        };
    }

    private Entry randomLine(String currency, long amount) {
        List<EntityKind> kinds = List.of(EntityKind.values());
        EntityKind kind = kinds.get(rng.nextInt(kinds.size()));
        List<String> accounts = ChartOfAccounts.allowedAccounts(kind).stream().sorted().toList();
        String account = accounts.get(rng.nextInt(accounts.size()));
        String suffix = kind == EntityKind.PLATFORM && rng.nextBoolean() ? "main" : randomSuffix();
        return Entry.of(kind.prefix() + ":" + suffix, account, currency, amount);
    }

    private String randomSuffix() {
        int length = 1 + rng.nextInt(8);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(SUFFIX_ALPHABET.charAt(rng.nextInt(SUFFIX_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Mostly small values in 0..bound. */
    private int skewed(int bound) {
        int r = rng.nextInt(bound + 1);
        return rng.nextBoolean() ? r / 4 : r;
    }

    @SafeVarargs
    private <T> T pick(T... options) {
        return options[rng.nextInt(options.length)];
    }

    private <T> void shuffle(List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }
}
