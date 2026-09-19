// decision: D06-1 — docs/step_06_reconciliation_verifier.md#decisions-and-outputs
package dev.zerosum.fakeproviders.card;

import dev.zerosum.fakeproviders.card.FakeCardApi.SettlementLineResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.SettlementReportResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.SettlementTotalsResponse;
import dev.zerosum.fakeproviders.faults.Decision;
import dev.zerosum.fakeproviders.faults.FaultProfiles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * FakeCard's settlement report for one closed simulated day (D06-1, master §5.9).
 *
 * <p>Built from the provider's <strong>own</strong> records and nothing else. That is the whole point: the report is
 * the counterparty's account of what happened, so a reconciler comparing it against our attempts is comparing two
 * independent stories. Deriving it from instrument-service data would make every reconciliation pass by construction.
 *
 * <p><strong>Generated once, then frozen.</strong> The first request for a closed day generates, corrupts and persists
 * it; every later request replays the stored bytes. So changing a knob after a day is persisted does not change that
 * day's report, and a restart does not regenerate it — both are properties a reconciliation run has to be able to rely
 * on, since a report that moved between two runs would make every break unreproducible.
 *
 * <p><strong>Discrepancy knobs (§0.3 C23).</strong> Injection happens once, here, at generation, drawing from the
 * seeded streams that {@link FaultProfiles} owns, and every injection is written to the D05-2 fault log on the way out
 * (§0.3 E2). At most one discrepancy per line, in the fixed precedence missing → off-by-one → duplicate, and a knob is
 * only drawn when the earlier ones did not fire — otherwise a dropped line would still log an off-by-one that never
 * reached anybody's report, and the log would over-report what was injected.
 *
 * <p>Totals are recomputed from the lines that are actually served, so the served report is arithmetically consistent
 * with itself. A discrepancy is therefore visible only by matching lines against our own attempts, which is what stops
 * a reconciler from "passing" by merely re-adding the provider's own column.
 */
@Service
public class SettlementReports {

    static final String PROVIDER = "fakecard";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<SettlementLineResponse>> LINES = new TypeReference<>() {
    };

    private final JdbcClient db;
    private final FaultProfiles profiles;
    private final Clock clock;
    private final TransactionTemplate transactions;

    SettlementReports(JdbcClient db, FaultProfiles profiles, Clock clock, PlatformTransactionManager transactionManager) {
        this.db = db;
        this.profiles = profiles;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * The report for {@code date}, generating and persisting it on first request.
     *
     * @throws ResponseStatusException 409 for a day that has not closed. A typed refusal rather than an empty report:
     *                                 an empty report for an open day is indistinguishable from a day on which nothing
     *                                 happened, and a reconciler would book a settlement of zero against real captures.
     */
    public SettlementReportResponse forDate(LocalDate date) {
        if (!date.isBefore(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "report_not_ready: " + date + " has not closed yet; a settlement cycle is one closed day");
        }
        return stored(date).orElseGet(() -> generate(date));
    }

    /**
     * Generates, corrupts, persists and returns the report.
     *
     * <p>The insert is {@code ON CONFLICT DO NOTHING} and the row is then read back unconditionally, so two concurrent
     * first requests produce one report: the loser discards its own (differently corrupted) draft and serves the
     * winner's bytes.
     *
     * <p><strong>The loser's fault-log rows are discarded with its draft.</strong> Drawing the knobs records each
     * injection in the fault log (§0.3 E2), and I12 holds every recorded injection to a break. A draft that was never
     * served must therefore leave no record, or I12 reports injections that reached nobody as undetected. So the draw,
     * the log rows and the insert share one transaction, rolled back when the insert finds the day already stored.
     * An explicit template, not {@code @Transactional}: {@link #forDate} calls this on {@code this}, which never
     * passes through Spring's proxy, so the annotation this method used to carry was silently inert.
     */
    SettlementReportResponse generate(LocalDate date) {
        String reportId = reportId(date);
        transactions.executeWithoutResult(status -> {
            List<SettlementLineResponse> clean = lines(date);
            List<SettlementLineResponse> served = inject(reportId, clean);
            String servedJson = JSON.writeValueAsString(served);

            int inserted = db.sql("""
                    INSERT INTO settlement_reports (provider, report_date, report_id, clean_lines, served_lines,
                                                    content_hash)
                    VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)
                    ON CONFLICT (provider, report_date) DO NOTHING
                    """)
                    .params(PROVIDER, Timestamp.valueOf(date.atStartOfDay()), reportId,
                            JSON.writeValueAsString(clean), servedJson, sha256(servedJson))
                    .update();
            if (inserted == 0) {
                status.setRollbackOnly();
            }
        });

        return stored(date).orElseThrow(() -> new IllegalStateException(
                "the settlement report for " + date + " was neither inserted nor found"));
    }

    private Optional<SettlementReportResponse> stored(LocalDate date) {
        return db.sql("""
                SELECT report_id, served_lines, content_hash FROM settlement_reports
                 WHERE provider = ? AND report_date = ?
                """)
                .params(PROVIDER, Timestamp.valueOf(date.atStartOfDay()))
                .query((rs, rowNum) -> {
                    List<SettlementLineResponse> served = JSON.readValue(rs.getString(2), LINES);
                    return new SettlementReportResponse(rs.getString(1), PROVIDER, date.toString(), served,
                            totals(served), rs.getString(3));
                })
                .optional();
    }

    /**
     * One line per successful capture and one per successful refund, from the provider's own tables.
     *
     * <p>A refund reduces the day's gross and carries no fee, because FakeCard never returns a capture fee (master
     * §5.9). It appears in the day it was committed, not the day of the capture it reverses.
     */
    private List<SettlementLineResponse> lines(LocalDate date) {
        Timestamp from = Timestamp.valueOf(date.atStartOfDay());
        Timestamp to = Timestamp.valueOf(date.plusDays(1).atStartOfDay());

        List<SettlementLineResponse> lines = new ArrayList<>(db.sql("""
                SELECT charge_id, client_reference, currency, amount_minor, fee_minor FROM card_charges
                 WHERE status = 'SUCCEEDED' AND created_at >= ? AND created_at < ?
                 ORDER BY created_at, charge_id
                """)
                .params(from, to)
                .query((rs, rowNum) -> new SettlementLineResponse(rs.getString(1), rs.getString(2), "CHARGE",
                        rs.getString(3), rs.getLong(4), rs.getLong(5)))
                .list());

        // The currency comes from the capture: a refund row does not carry one, and inventing a default here would
        // let a EUR refund settle as USD.
        lines.addAll(db.sql("""
                SELECT r.refund_id, r.client_reference, c.currency, r.amount_minor
                  FROM card_refunds r JOIN card_charges c ON c.charge_id = r.charge_id
                 WHERE r.status = 'SUCCEEDED' AND r.created_at >= ? AND r.created_at < ?
                 ORDER BY r.created_at, r.refund_id
                """)
                .params(from, to)
                .query((rs, rowNum) -> new SettlementLineResponse(rs.getString(1), rs.getString(2), "REFUND",
                        rs.getString(3), Math.negateExact(rs.getLong(4)), 0))
                .list());

        return lines;
    }

    /** Applies the seeded discrepancy knobs, recording each injection in the fault log (§0.3 E2). */
    private List<SettlementLineResponse> inject(String reportId, List<SettlementLineResponse> clean) {
        List<SettlementLineResponse> served = new ArrayList<>();
        for (SettlementLineResponse line : clean) {
            // The fault-log target names the line, so I12 can pair an injected discrepancy with the break it caused.
            String target = reportId + ":" + line.provider_ref();

            if (profiles.fires(PROVIDER, Decision.REPORT_MISSING_LINE, target)) {
                continue;
            }
            if (profiles.fires(PROVIDER, Decision.REPORT_OFF_BY_ONE, target)) {
                // One minor unit, which is the smallest disagreement that is still real money. Totals are recomputed
                // from this, so only a line-level comparison against our attempts can see it.
                served.add(new SettlementLineResponse(line.provider_ref(), line.client_reference(), line.kind(),
                        line.currency(), Math.addExact(line.gross_minor(), 1), line.fee_minor()));
                continue;
            }
            served.add(line);
            if (profiles.fires(PROVIDER, Decision.REPORT_DUPLICATE_LINE, target)) {
                // The same capture reported twice: the provider claims to have settled money once more than it did.
                served.add(line);
            }
        }
        return served;
    }

    /**
     * Per-currency totals, summed exactly.
     *
     * <p>{@code addExact} rather than {@code +}: a wrapped total would be served as a plausible number and booked as
     * real money, which is worse than a report that fails to generate at all.
     */
    private static List<SettlementTotalsResponse> totals(List<SettlementLineResponse> lines) {
        Map<String, long[]> byCurrency = new LinkedHashMap<>();
        for (SettlementLineResponse line : lines) {
            long[] sums = byCurrency.computeIfAbsent(line.currency(), currency -> new long[2]);
            sums[0] = Math.addExact(sums[0], line.gross_minor());
            sums[1] = Math.addExact(sums[1], line.fee_minor());
        }
        return byCurrency.entrySet().stream()
                .map(entry -> new SettlementTotalsResponse(entry.getKey(), entry.getValue()[0], entry.getValue()[1],
                        Math.subtractExact(entry.getValue()[0], entry.getValue()[1])))
                .toList();
    }

    /** {@code rpt_YYYY_MM_DD}, as the golden O6 payload spells it (D01-9). */
    private static String reportId(LocalDate date) {
        return "rpt_" + date.toString().replace('-', '_');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
