package dev.zerosum.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.money.OrderCandidate.Entry;
import dev.zerosum.money.Violation.Code;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ZeroSumValidatorTest {

    private final ZeroSumValidator validator = ZeroSumValidator.defaults();

    private static OrderCandidate order(String type, String reason, Entry... entries) {
        return new OrderCandidate(type, reason, Arrays.asList(entries));
    }

    private static OrderCandidate commerce(Entry... entries) {
        return order("COMMERCE", "trip.completed", entries);
    }

    private List<Code> codes(OrderCandidate order) {
        return validator.validate(order).stream().map(Violation::code).toList();
    }

    // Worked example O1–O8 built in code (docs/zerosum_ledger_mvp_plan.md#worked-example); golden JSON follows in S01-T05.
    static final Map<String, OrderCandidate> WORKED_EXAMPLE = Map.of(
            "O1", order("COMMERCE", "trip.completed", Entry.of("rider:R1", "receivable", "USD", 2500),
                    Entry.of("driver:D1", "payable", "USD", -2000), Entry.of("platform:main", "revenue", "USD", -500)),
            "O2", order("COLLECTION", "charge.succeeded", Entry.of("provider:fakecard", "clearing", "USD", 2500),
                    Entry.of("rider:R1", "receivable", "USD", -2500)),
            "O3", order("COMMERCE", "fare.adjusted", Entry.of("rider:R1", "receivable", "USD", -300),
                    Entry.of("driver:D1", "payable", "USD", 240), Entry.of("platform:main", "revenue", "USD", 60)),
            "O4", order("REFUND", "refund.succeeded", Entry.of("rider:R1", "receivable", "USD", 300),
                    Entry.of("provider:fakecard", "clearing", "USD", -300)),
            "O5", order("DISBURSEMENT", "payout.accepted", Entry.of("driver:D1", "payable", "USD", 1760),
                    Entry.of("provider:fakebank", "payout_clearing", "USD", -1760)),
            "O6", order("SETTLEMENT", "settlement.received", Entry.of("platform:main", "cash", "USD", 2098),
                    Entry.of("platform:main", "processing_fees", "USD", 102),
                    Entry.of("provider:fakecard", "clearing", "USD", -2200)),
            "O7", order("DISBURSEMENT", "payout.settled", Entry.of("provider:fakebank", "payout_clearing", "USD", 1760),
                    Entry.of("platform:main", "cash", "USD", -1760)),
            "O8", order("DISBURSEMENT", "payout.returned", Entry.of("platform:main", "cash", "USD", 1760),
                    Entry.of("driver:D1", "payable", "USD", -1760)));

    @Test
    void workedExampleOrdersO1ToO8AllValidate() {
        WORKED_EXAMPLE.forEach((id, o) -> assertEquals(List.of(), validator.validate(o), id));
    }

    @Test
    void rule1TypeAndReason() {
        Entry[] ok = {Entry.of("rider:R1", "receivable", "USD", 1), Entry.of("platform:main", "revenue", "USD", -1)};
        assertEquals(List.of(), codes(order("REFUND", "refund.succeeded", ok)));
        assertEquals(List.of(Code.TYPE_INVALID), codes(order("TRANSFER", "trip.completed", ok)));
        for (String reason : new String[] {"Trip.completed", "tripcompleted", "trip.completed.again", "trip.", ".x",
            "trip.complet3d", "a." + "b".repeat(70)}) {
            assertEquals(List.of(Code.REASON_INVALID), codes(order("COMMERCE", reason, ok)), reason);
        }
    }

    @Test
    void rule2EntryCountAndAmounts() {
        assertEquals(List.of(Code.ENTRY_COUNT_OUT_OF_RANGE, Code.ZERO_SUM_VIOLATED),
                codes(commerce(Entry.of("rider:R1", "receivable", "USD", 5))));
        long cap = ValidationLimits.MAX_ABS_AMOUNT_MINOR;
        assertEquals(List.of(), codes(commerce(Entry.of("rider:R1", "receivable", "USD", cap),
                Entry.of("platform:main", "revenue", "USD", -cap))));
        assertEquals(List.of(Code.AMOUNT_OUT_OF_RANGE, Code.AMOUNT_OUT_OF_RANGE), codes(commerce(
                Entry.of("rider:R1", "receivable", "USD", cap + 1), Entry.of("platform:main", "revenue", "USD", -cap - 1))));
    }

    @Test
    void zeroAmountsAreReportedPerEntry() {
        List<Violation> v = validator.validate(commerce(
                Entry.of("rider:R1", "receivable", "USD", 0), Entry.of("platform:main", "revenue", "USD", 0)));
        assertEquals(List.of(Violation.atEntry(Code.AMOUNT_ZERO, 2, 0), Violation.atEntry(Code.AMOUNT_ZERO, 2, 1)), v);
    }

    @Test
    void rule3EntityAndAccount() {
        assertEquals(List.of(Violation.atEntry(Code.ENTITY_ID_INVALID, 3, 0)), validator.validate(commerce(
                Entry.of("wallet:W1", "receivable", "USD", 1), Entry.of("platform:main", "revenue", "USD", -1))));
        assertEquals(List.of(Violation.atEntry(Code.ACCOUNT_NOT_ALLOWED, 3, 1)), validator.validate(commerce(
                Entry.of("rider:R1", "receivable", "USD", 1), Entry.of("platform:main", "payable", "USD", -1))));
    }

    @Test
    void rule4CurrencyAllowList() {
        assertEquals(List.of(), codes(commerce(
                Entry.of("rider:R1", "receivable", "KWD", 1), Entry.of("platform:main", "revenue", "KWD", -1))));
        // GBP is a real ISO currency but not in the MVP allow-list.
        assertEquals(List.of(new Violation(Code.CURRENCY_NOT_ALLOWED, 4, 0, "GBP"), new Violation(Code.CURRENCY_NOT_ALLOWED, 4, 1, "GBP")),
                validator.validate(commerce(
                        Entry.of("rider:R1", "receivable", "GBP", 1), Entry.of("platform:main", "revenue", "GBP", -1))));
    }

    @Test
    void rule5ZeroSumPerCurrency() {
        assertEquals(List.of(new Violation(Code.ZERO_SUM_VIOLATED, 5, null, "USD")), validator.validate(commerce(
                Entry.of("rider:R1", "receivable", "USD", 2500), Entry.of("platform:main", "revenue", "USD", -2499))));
        // Multi-currency order where each currency nets to zero is valid (A11).
        assertEquals(List.of(), codes(commerce(
                Entry.of("rider:R1", "receivable", "USD", 100), Entry.of("platform:main", "revenue", "USD", -100),
                Entry.of("rider:R2", "receivable", "EUR", 90), Entry.of("platform:main", "revenue", "EUR", -90))));
        // Two entries in two currencies: count rule passes, zero-sum fails for each currency, ordered by code.
        assertEquals(List.of(new Violation(Code.ZERO_SUM_VIOLATED, 5, null, "EUR"), new Violation(Code.ZERO_SUM_VIOLATED, 5, null, "USD")),
                validator.validate(commerce(
                        Entry.of("rider:R1", "receivable", "USD", 100), Entry.of("platform:main", "revenue", "EUR", -100))));
    }

    @Test
    void repeatedEntityAccountCurrencyLinesAreAccepted() {
        // Master §0.3 C4: each line is applied separately.
        assertEquals(List.of(), codes(commerce(
                Entry.of("rider:R1", "receivable", "USD", 100), Entry.of("rider:R1", "receivable", "USD", 150),
                Entry.of("platform:main", "revenue", "USD", -250))));
    }

    @Test
    void multipleViolationsAreOrderedByRuleThenEntryThenCurrency() {
        List<Violation> v = validator.validate(order("NOPE", "Bad",
                Entry.of("rider:R1", "payable", "GBP", 0),
                Entry.of("bogus", "revenue", "USD", 7)));
        assertEquals(List.of(
                Violation.of(Code.TYPE_INVALID, 1),
                Violation.of(Code.REASON_INVALID, 1),
                Violation.atEntry(Code.AMOUNT_ZERO, 2, 0),
                Violation.atEntry(Code.ACCOUNT_NOT_ALLOWED, 3, 0),
                Violation.atEntry(Code.ENTITY_ID_INVALID, 3, 1),
                new Violation(Code.CURRENCY_NOT_ALLOWED, 4, 0, "GBP"),
                new Violation(Code.ZERO_SUM_VIOLATED, 5, null, "USD")), v);
    }

    @Test
    void nullsProduceViolationsNeverExceptions() {
        assertEquals(List.of(Violation.of(Code.ORDER_MISSING, 1)), validator.validate(null));
        assertEquals(List.of(Code.TYPE_INVALID, Code.REASON_INVALID, Code.ENTRY_COUNT_OUT_OF_RANGE),
                codes(new OrderCandidate(null, null, null)));
        List<Entry> entries = new ArrayList<>();
        entries.add(null);
        entries.add(new Entry(null, null, null, null));
        assertEquals(List.of(Violation.atEntry(Code.ENTRY_MISSING, 2, 0), Violation.atEntry(Code.AMOUNT_MISSING, 2, 1),
                        Violation.atEntry(Code.ENTITY_ID_INVALID, 3, 1), new Violation(Code.CURRENCY_NOT_ALLOWED, 4, 1, null)),
                validator.validate(new OrderCandidate("COMMERCE", "trip.completed", entries)));
    }

    @Test
    void sumOverflowIsReportedAsAViolation() {
        List<Violation> v = validator.validate(commerce(
                Entry.of("rider:R1", "receivable", "USD", Long.MAX_VALUE), Entry.of("rider:R2", "receivable", "USD", Long.MAX_VALUE),
                Entry.of("platform:main", "revenue", "USD", -1)));
        assertTrue(v.contains(new Violation(Code.ZERO_SUM_OVERFLOW, 5, null, "USD")), v.toString());
        assertEquals(2, v.stream().filter(x -> x.code() == Code.AMOUNT_OUT_OF_RANGE).count(), v.toString());
        assertTrue(v.stream().noneMatch(x -> x.code() == Code.ZERO_SUM_VIOLATED), "overflowed currency is not also reported as unbalanced");
    }

    @Test
    void countAboveMaximumSkipsPerEntryWork() {
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i <= ValidationLimits.MAX_ENTRIES; i++) {
            many.add(Entry.of("not-an-entity", "nope", "XXX", 0));
        }
        assertEquals(List.of(Violation.of(Code.ENTRY_COUNT_OUT_OF_RANGE, 2)),
                validator.validate(new OrderCandidate("COMMERCE", "trip.completed", many)));
    }

    @Test
    void allowListNamingAnUnknownCurrencyFailsToLoad() {
        String resource = "dev/zerosum/money/test-tables/allow-unknown.txt";
        var e = assertThrows(IllegalStateException.class, () -> CurrencyRules.load(CurrencyRules.TABLE_RESOURCE, resource));
        assertTrue(e.getMessage().contains(resource) && e.getMessage().contains("ZZZ"), e.getMessage());
        assertEquals(List.of("EUR", "JPY", "KWD", "USD"), List.copyOf(CurrencyRules.defaults().allowedCodes()));
    }
}
