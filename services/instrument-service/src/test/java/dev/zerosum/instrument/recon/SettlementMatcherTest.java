package dev.zerosum.instrument.recon;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.instrument.core.ProviderId;
import dev.zerosum.instrument.core.SettlementReport;
import dev.zerosum.instrument.recon.BreakType.BreakStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S06-T02: the matching rules and the break taxonomy (D06-2, M11(b)).
 *
 * <p><strong>Every case asserts the break TYPE, not the count.</strong> A matcher that classified everything as one
 * generic "mismatch" would pass a test asserting "a break was raised" while being useless to an operator, so the
 * weaker assertion is deliberately avoided: these tests fail if the taxonomy collapses.
 *
 * <p>Reports here are built with totals derived from their own lines, exactly as the provider serves them. That is
 * what makes the injected discrepancies findable only by comparing lines against attempts — a matcher that merely
 * re-added the provider's own column would find nothing at all.
 */
class SettlementMatcherTest {

    private static final ProviderId FAKECARD = new ProviderId("fakecard");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

    @Test
    @DisplayName("a report that agrees with our attempts produces no breaks")
    void cleanReportHasNoBreaks() {
        SettlementReport report = report(charge("ch_1", "att_1", 2_200, 94));

        SettlementMatcher.Result result = match(report, succeeded("att_1", 2_200, "ch_1"));

        assertThat(result.breaks()).isEmpty();
        assertThat(result.linesMatched()).isEqualTo(1);
        assertThat(result.blocksSettlement()).isFalse();
    }

    @Test
    @DisplayName("a missing line is its own type: we hold a success the report never mentions")
    void missingLineIsMissingInReport() {
        // What report_missing_line injects: the line is simply gone, so only the attempt side knows it existed.
        SettlementReport report = report();

        SettlementMatcher.Result result = match(report, succeeded("att_1", 2_200, "ch_1"));

        assertThat(result.breaks()).singleElement()
                .extracting(SettlementMatcher.Break::type).isEqualTo(BreakType.MISSING_IN_REPORT);
        assertThat(result.breaks().getFirst().ledgerGrossMinor()).isEqualTo(2_200);
        assertThat(result.breaks().getFirst().reportGrossMinor()).as("the report said nothing at all").isNull();
    }

    @Test
    @DisplayName("an off-by-one is an amount mismatch, and both sides' numbers are recorded")
    void offByOneIsAmountMismatch() {
        // What report_off_by_one injects: one minor unit, with the report's own totals recomputed to match, so the
        // discrepancy exists only between the report and our attempt.
        SettlementReport report = report(charge("ch_1", "att_1", 2_201, 94));

        SettlementMatcher.Result result = match(report, succeeded("att_1", 2_200, "ch_1"));

        assertThat(result.breaks()).singleElement()
                .extracting(SettlementMatcher.Break::type).isEqualTo(BreakType.AMOUNT_MISMATCH);
        SettlementMatcher.Break found = result.breaks().getFirst();
        assertThat(found.reportGrossMinor()).isEqualTo(2_201);
        assertThat(found.ledgerGrossMinor()).isEqualTo(2_200);
        assertThat(result.linesMatched()).isZero();
    }

    @Test
    @DisplayName("a duplicated line is its own type, and the first occurrence still settles")
    void duplicateLineIsDuplicate() {
        // What report_duplicate_line injects. The first line is real money we did collect; only the second is a claim
        // we never made, so flagging both would overstate the damage.
        SettlementReport report = report(charge("ch_1", "att_1", 2_200, 94), charge("ch_1", "att_1", 2_200, 94));

        SettlementMatcher.Result result = match(report, succeeded("att_1", 2_200, "ch_1"));

        assertThat(result.breaks()).singleElement()
                .extracting(SettlementMatcher.Break::type).isEqualTo(BreakType.DUPLICATE_LINE);
        assertThat(result.linesMatched()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same capture settled again on a later day is a duplicate, not a second settlement")
    void duplicateAcrossReportsIsDetected() {
        SettlementReport report = report(charge("ch_1", "att_1", 2_200, 94));

        SettlementMatcher.Result result =
                SettlementMatcher.match(report, List.of(succeeded("att_1", 2_200, "ch_1")), Set.of("ch_1"));

        assertThat(result.breaks()).singleElement()
                .extracting(SettlementMatcher.Break::type).isEqualTo(BreakType.DUPLICATE_LINE);
    }

    @Test
    @DisplayName("a line with no attempt at all is missing on our side")
    void lineWithoutAttemptIsMissingInLedger() {
        SettlementReport report = report(charge("ch_9", "att_9", 500, 44));

        SettlementMatcher.Result result = match(report, succeeded("att_1", 2_200, "ch_1"));

        assertThat(result.breaks()).extracting(SettlementMatcher.Break::type)
                .containsExactlyInAnyOrder(BreakType.MISSING_IN_LEDGER, BreakType.MISSING_IN_REPORT);
    }

    @Test
    @DisplayName("an unresolved attempt is a timing candidate, left open rather than called wrong")
    void inFlightAttemptIsTiming() {
        SettlementReport report = report(charge("ch_1", "att_1", 2_200, 94));

        SettlementMatcher.Result result =
                match(report, new SettlementMatcher.Candidate("att_1", "CHARGE", "USD", 2_200, "UNKNOWN", "ch_1"));

        assertThat(result.breaks()).singleElement()
                .extracting(SettlementMatcher.Break::type).isEqualTo(BreakType.TIMING);
        // The provider is merely ahead of us, so this must not raise an alert on its own.
        assertThat(result.breaks().getFirst().status()).isEqualTo(BreakStatus.OPEN);
    }

    @Test
    @DisplayName("the provider settling an attempt we believe failed is its own, unexplained break")
    void settledButUnsuccessfulAttempt() {
        SettlementReport report = report(charge("ch_1", "att_1", 2_200, 94));

        SettlementMatcher.Result result =
                match(report, new SettlementMatcher.Candidate("att_1", "CHARGE", "USD", 2_200, "DECLINED", "ch_1"));

        assertThat(result.breaks()).singleElement()
                .extracting(SettlementMatcher.Break::type).isEqualTo(BreakType.LINE_WITHOUT_SUCCESS);
        assertThat(result.breaks().getFirst().status()).isEqualTo(BreakStatus.UNEXPLAINED);
    }

    @Test
    @DisplayName("a refund line matching a charge attempt is a kind mismatch, not an amount one")
    void kindMismatchIsTyped() {
        SettlementReport report = report(
                new SettlementReport.Line("ch_1", "att_1", "REFUND", "USD", -2_200, 0));

        SettlementMatcher.Result result = match(report, succeeded("att_1", 2_200, "ch_1"));

        assertThat(result.breaks()).extracting(SettlementMatcher.Break::type).contains(BreakType.KIND_MISMATCH);
    }

    @Test
    @DisplayName("a currency disagreement is typed as one rather than as an amount mismatch")
    void currencyMismatchIsTyped() {
        SettlementReport report = report(new SettlementReport.Line("ch_1", "att_1", "CHARGE", "EUR", 2_200, 94));

        SettlementMatcher.Result result = match(report, succeeded("att_1", 2_200, "ch_1"));

        assertThat(result.breaks()).extracting(SettlementMatcher.Break::type).contains(BreakType.CURRENCY_MISMATCH);
    }

    @Test
    @DisplayName("a refund settles correctly: the report's negative gross is our positive magnitude")
    void refundLineMatchesRefundAttempt() {
        SettlementReport report = report(new SettlementReport.Line("re_1", "att_2", "REFUND", "USD", -500, 0));

        SettlementMatcher.Result result =
                match(report, new SettlementMatcher.Candidate("att_2", "REFUND", "USD", 500, "SUCCEEDED", "re_1"));

        assertThat(result.breaks()).isEmpty();
        assertThat(result.linesMatched()).isEqualTo(1);
    }

    @Test
    @DisplayName("a report that contradicts its own arithmetic is a report-level break and blocks settlement")
    void badArithmeticBlocksSettlement() {
        // Totals stated by hand rather than derived: net + fee != gross.
        SettlementReport report = new SettlementReport(FAKECARD, DAY, "rpt_2026_09_15",
                List.of(charge("ch_1", "att_1", 2_200, 94)),
                List.of(new SettlementReport.Totals("USD", 2_200, 94, 999)));

        SettlementMatcher.Result result = match(report, succeeded("att_1", 2_200, "ch_1"));

        assertThat(result.breaks()).extracting(SettlementMatcher.Break::type).contains(BreakType.REPORT_ARITHMETIC);
        // Booking a report that cannot add up would invent money, whatever the line-level comparison said.
        assertThat(result.blocksSettlement()).isTrue();
    }

    @Test
    @DisplayName("an empty closed day is a clean report, not a break")
    void emptyDayIsClean() {
        SettlementMatcher.Result result = SettlementMatcher.match(report(), List.of(), Set.of());

        assertThat(result.breaks()).isEmpty();
        assertThat(result.linesMatched()).isZero();
    }

    private static SettlementMatcher.Result match(SettlementReport report, SettlementMatcher.Candidate... candidates) {
        return SettlementMatcher.match(report, List.of(candidates), Set.of());
    }

    private static SettlementReport.Line charge(String providerRef, String attemptId, long gross, long fee) {
        return new SettlementReport.Line(providerRef, attemptId, "CHARGE", "USD", gross, fee);
    }

    private static SettlementMatcher.Candidate succeeded(String attemptId, long amountMinor, String providerRef) {
        return new SettlementMatcher.Candidate(attemptId, "CHARGE", "USD", amountMinor, "SUCCEEDED", providerRef);
    }

    /** A report whose totals are derived from its own lines, which is how the provider actually serves one. */
    private static SettlementReport report(SettlementReport.Line... lines) {
        Map<String, long[]> byCurrency = new LinkedHashMap<>();
        for (SettlementReport.Line line : lines) {
            long[] sums = byCurrency.computeIfAbsent(line.currency(), currency -> new long[2]);
            sums[0] += line.grossMinor();
            sums[1] += line.feeMinor();
        }
        List<SettlementReport.Totals> totals = new ArrayList<>();
        byCurrency.forEach((currency, sums) ->
                totals.add(new SettlementReport.Totals(currency, sums[0], sums[1], sums[0] - sums[1])));
        return new SettlementReport(FAKECARD, DAY, "rpt_2026_09_15", List.of(lines), totals);
    }
}
