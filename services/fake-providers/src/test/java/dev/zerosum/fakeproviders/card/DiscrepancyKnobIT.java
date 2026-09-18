package dev.zerosum.fakeproviders.card;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.SettlementReportResponse;
import dev.zerosum.fakeproviders.shared.MagicTokens;
import dev.zerosum.fakeproviders.support.FakeProvidersIT;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S06-T01 evidence: each discrepancy knob corrupts the report in its own way and records what it did (D06-1, §0.3 E2).
 *
 * <p>Two assertions per knob, and both matter. The served report must actually differ from the clean one — otherwise
 * the knob is decorative — and the injection must appear in the fault log, so a knob that quietly stopped firing
 * cannot turn a reconciliation suite green by injecting nothing.
 *
 * <p>Each case uses its own closed day and its own charge, for two reasons: a report is generated once and then
 * frozen, so reusing a day would test the stored bytes rather than the knob; and the fault log is shared, so every
 * assertion below is scoped to its own report id rather than to the whole table, which would otherwise make these
 * tests pass or fail depending on the order JUnit happened to run them in.
 */
class DiscrepancyKnobIT extends FakeProvidersIT {

    private static final long SEED = 4_242L;

    /** Days already closed against the real clock; an open day is refused and would test nothing. */
    private static final LocalDate MISSING_DAY = LocalDate.of(2026, 2, 1);
    private static final LocalDate OFF_BY_ONE_DAY = LocalDate.of(2026, 2, 2);
    private static final LocalDate DUPLICATE_DAY = LocalDate.of(2026, 2, 3);
    private static final LocalDate CLEAN_DAY = LocalDate.of(2026, 2, 4);
    private static final LocalDate PRECEDENCE_DAY = LocalDate.of(2026, 2, 5);
    private static final LocalDate RACE_DAY = LocalDate.of(2026, 2, 6);

    @Autowired
    SettlementReports settlements;

    @AfterEach
    void clearProfile() {
        // The database outlives the application context, so a profile left active would corrupt whichever test runs
        // next.
        activate(Map.of());
    }

    @Test
    @DisplayName("the missing-line knob drops the line and records exactly one injection")
    void missingLineKnob() {
        String chargeId = capture(MISSING_DAY, 6_000);
        activate(Map.of("report_missing_line_rate", 1, "seed", SEED));

        SettlementReportResponse report = report(MISSING_DAY);

        assertThat(report.lines()).as("the only line was dropped").isEmpty();
        // The day still closes, with nothing in it: a valid report that disagrees with our attempts.
        assertThat(report.totals()).isEmpty();
        assertThat(faults(MISSING_DAY, "report_missing_line")).containsExactly(target(MISSING_DAY, chargeId));
        assertThat(faults(MISSING_DAY, "report_off_by_one")).isEmpty();
        assertThat(faults(MISSING_DAY, "report_duplicate_line")).isEmpty();
    }

    @Test
    @DisplayName("the off-by-one knob moves the gross by one minor unit and leaves the totals consistent")
    void offByOneKnob() {
        String chargeId = capture(OFF_BY_ONE_DAY, 6_000);
        activate(Map.of("report_off_by_one_rate", 1, "seed", SEED));

        SettlementReportResponse report = report(OFF_BY_ONE_DAY);

        assertThat(report.lines()).hasSize(1);
        assertThat(report.lines().getFirst().gross_minor()).isEqualTo(6_001);
        // Recomputed from the served line, so the report is arithmetically consistent with itself and the
        // discrepancy is visible only against our own attempt.
        assertThat(report.totals().getFirst().gross_minor()).isEqualTo(6_001);
        assertThat(report.totals().getFirst().net_minor())
                .isEqualTo(6_001 - report.totals().getFirst().fee_minor());
        assertThat(faults(OFF_BY_ONE_DAY, "report_off_by_one")).containsExactly(target(OFF_BY_ONE_DAY, chargeId));
        assertThat(faults(OFF_BY_ONE_DAY, "report_missing_line")).isEmpty();
    }

    @Test
    @DisplayName("the duplicate-line knob reports the same capture twice")
    void duplicateLineKnob() {
        String chargeId = capture(DUPLICATE_DAY, 6_000);
        activate(Map.of("report_duplicate_line_rate", 1, "seed", SEED));

        SettlementReportResponse report = report(DUPLICATE_DAY);

        assertThat(report.lines()).hasSize(2);
        assertThat(report.lines()).allSatisfy(line -> assertThat(line.provider_ref()).isEqualTo(chargeId));
        // The provider now claims to have settled twice what it actually took.
        assertThat(report.totals().getFirst().gross_minor()).isEqualTo(12_000);
        assertThat(faults(DUPLICATE_DAY, "report_duplicate_line")).containsExactly(target(DUPLICATE_DAY, chargeId));
        assertThat(faults(DUPLICATE_DAY, "report_missing_line")).isEmpty();
    }

    @Test
    @DisplayName("with every knob off the served report is the clean one and nothing is recorded")
    void noKnobsInjectNothing() {
        capture(CLEAN_DAY, 6_000);
        activate(Map.of("seed", SEED));

        SettlementReportResponse report = report(CLEAN_DAY);

        assertThat(report.lines()).singleElement()
                .satisfies(line -> assertThat(line.gross_minor()).isEqualTo(6_000));
        assertThat(served(CLEAN_DAY)).isEqualTo(clean(CLEAN_DAY));
        assertThat(faults(CLEAN_DAY, "report_missing_line")).isEmpty();
        assertThat(faults(CLEAN_DAY, "report_off_by_one")).isEmpty();
        assertThat(faults(CLEAN_DAY, "report_duplicate_line")).isEmpty();
    }

    @Test
    @DisplayName("at most one discrepancy per line: missing wins, and the later knobs are not drawn")
    void precedenceIsDeterministic() {
        String chargeId = capture(PRECEDENCE_DAY, 6_000);
        activate(Map.of("report_missing_line_rate", 1, "report_off_by_one_rate", 1,
                "report_duplicate_line_rate", 1, "seed", SEED));

        assertThat(report(PRECEDENCE_DAY).lines()).isEmpty();

        // The line is gone, so the later knobs must not have logged injections that never reached anybody's report.
        assertThat(faults(PRECEDENCE_DAY, "report_missing_line")).containsExactly(target(PRECEDENCE_DAY, chargeId));
        assertThat(faults(PRECEDENCE_DAY, "report_off_by_one")).isEmpty();
        assertThat(faults(PRECEDENCE_DAY, "report_duplicate_line")).isEmpty();
    }

    @Test
    @DisplayName("a request that loses the generation race leaves no injection in the fault log")
    void theLosingDraftLeavesNoFaultLogRows() {
        String chargeId = capture(RACE_DAY, 6_000);
        activate(Map.of("report_off_by_one_rate", 1, "seed", SEED));
        SettlementReportResponse winner = report(RACE_DAY);

        // The loser of two concurrent first requests is exactly a generation that finds the day already stored: it
        // draws the knobs, logs its injections, and its insert does nothing. Driven directly, so the race is certain
        // rather than hoped for. Before the fix those log rows committed although the draft was never served, and
        // I12 counted an injection that reached nobody as undetected.
        SettlementReportResponse loser = settlements.generate(RACE_DAY);

        assertThat(loser).as("the loser serves the winner's bytes").isEqualTo(winner);
        assertThat(faults(RACE_DAY, "report_off_by_one")).containsExactly(target(RACE_DAY, chargeId));
    }

    private SettlementReportResponse report(LocalDate day) {
        return get("/fakecard/v1/settlement-reports/" + day).as(SettlementReportResponse.class);
    }

    /** A successful capture, backdated into a closed day. */
    private String capture(LocalDate day, long amountMinor) {
        ChargeResponse charge = post("/fakecard/v1/charges",
                new ChargeRequest(UUID.randomUUID().toString(), MagicTokens.CARD_OK, amountMinor, "USD"), null)
                .as(ChargeResponse.class);
        db.sql("UPDATE card_charges SET created_at = ? WHERE charge_id = ?")
                .params(Timestamp.valueOf(day.atTime(9, 30)), charge.charge_id())
                .update();
        return charge.charge_id();
    }

    private void activate(Map<String, Object> knobs) {
        assertThat(put("/admin/faults/fakecard", knobs, ADMIN_TOKEN).status()).isEqualTo(200);
    }

    /**
     * The fault log's targets for one injection type on one report (§0.3 E2).
     *
     * <p>Scoped to the report rather than read from the whole table: the log is shared by every test in this class,
     * so an unscoped query would make each assertion depend on the order the others ran in.
     */
    private List<String> faults(LocalDate day, String faultType) {
        return db.sql("SELECT target FROM fault_log WHERE fault_type = ? AND target LIKE ? "
                        + "ORDER BY occurred_at, fault_id")
                .params(faultType, reportId(day) + ":%")
                .query(String.class)
                .list();
    }

    private static String target(LocalDate day, String chargeId) {
        return reportId(day) + ":" + chargeId;
    }

    private static String reportId(LocalDate day) {
        return "rpt_" + day.toString().replace('-', '_');
    }

    private String served(LocalDate day) {
        return stored(day, "served_lines");
    }

    private String clean(LocalDate day) {
        return stored(day, "clean_lines");
    }

    private String stored(LocalDate day, String column) {
        return db.sql("SELECT " + column + "::text FROM settlement_reports WHERE provider = 'fakecard' "
                        + "AND report_date = ?")
                .param(Timestamp.valueOf(day.atStartOfDay()))
                .query(String.class)
                .single();
    }
}
