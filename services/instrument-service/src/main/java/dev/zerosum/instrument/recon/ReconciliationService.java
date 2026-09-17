// decision: D06-2, D06-3 — docs/step_06_reconciliation_verifier.md#decisions-and-outputs
package dev.zerosum.instrument.recon;

import dev.zerosum.contracts.kafka.TopicDefinitions;
import dev.zerosum.instrument.core.InstrumentExceptions.UnsupportedCapabilityException;
import dev.zerosum.instrument.core.PaymentInstrument;
import dev.zerosum.instrument.core.ProviderRegistry;
import dev.zerosum.instrument.core.SettlementReport;
import dev.zerosum.money.CurrencyRules;
import dev.zerosum.outbox.OutboxWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reconciles one provider's closed day and books what it settled (D06-2, D06-3, W6).
 *
 * <p><strong>Fetch first, then open the transaction.</strong> The provider call happens outside any transaction (the
 * Orpheus phase rule): a network call inside one holds a database transaction open for the length of an HTTP timeout,
 * and a rollback after it would erase the record of money that has already moved.
 *
 * <p><strong>One transaction writes everything or nothing:</strong> the run, its stored lines, every break and the
 * outbox row. A crash before commit leaves no run, no breaks and no event, so the next trigger with the same identity
 * starts over cleanly rather than booking a settlement whose breaks were lost.
 *
 * <p><strong>Breaks never change what is booked.</strong> The event carries the report's totals as reported, so a
 * disagreement leaves a residual on the provider's clearing account equal to the signed sum of the open breaks — which
 * is exactly what I9 checks (§0.3 E1). Quietly adjusting the booked amount to match our own records would hide the
 * discrepancy in the one account whose non-zero balance is supposed to reveal it.
 */
@Service
public class ReconciliationService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Attempt statuses that may still change, so they stay candidates after their own day has closed. */
    private static final String OPEN_STATUSES = "'CREATED','SUBMITTING','PENDING','UNKNOWN'";

    private final ProviderRegistry providers;
    private final JdbcClient db;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final TransactionTemplate transactions;

    ReconciliationService(ProviderRegistry providers, JdbcClient db, OutboxWriter outbox, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.providers = providers;
        this.db = db;
        this.outbox = outbox;
        this.clock = clock;
        // An explicit template rather than @Transactional on commit(): reconcile() calls it directly, and a
        // self-invocation never passes through Spring's proxy, so the annotation would be silently inert. The
        // symptom is not a missing transaction but a 500 from OutboxWriter, which demands one (MANDATORY) — the
        // outbox row and the run must commit together or the ledger learns about money the run never recorded.
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record Command(String provider, LocalDate reportDate, String idempotencyKey) {
    }

    public record RunSummary(UUID runId, String provider, LocalDate reportDate, String reportId, String status,
            int linesMatched, int breaksFound, boolean settled, boolean replayed) {
    }

    /** A stored break, as the reader endpoint presents it. */
    public record BreakView(long breakId, String breakType, String status, String providerRef,
            String clientReference, String currency, Long reportGrossMinor, Long ledgerGrossMinor, String attemptId,
            String detail) {
    }

    /** Thrown when a key is replayed with a different request (D03-3). */
    public static class IdempotencyKeyReusedException extends RuntimeException {
        IdempotencyKeyReusedException(String key) {
            super("idempotency key " + key + " was already used with a different request");
        }
    }

    /** Thrown when the report names a currency the ledger does not accept (D01-7). */
    public static class UnsupportedCurrencyException extends RuntimeException {
        UnsupportedCurrencyException(String currency) {
            super("the report settles " + currency + ", which is not in the D01-7 allow-list");
        }
    }

    public RunSummary reconcile(Command command) {
        // Capabilities decide, never the provider's name: core code contains no provider names, so a third provider
        // with settlement reports needs no change here (D05-1).
        PaymentInstrument instrument = providers.get(command.provider());
        if (!instrument.capabilities().settlementReports()) {
            throw new UnsupportedCapabilityException(instrument.provider(), "settlementReport");
        }

        byte[] requestHash = sha256(command.provider() + "|" + command.reportDate());
        Optional<RunSummary> replay = replayOf(command, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        // Outside the transaction, and before anything is written.
        SettlementReport report = instrument.settlementReport(command.reportDate());
        requireAllowedCurrencies(report);

        List<SettlementMatcher.Candidate> candidates = candidates(command.provider(), command.reportDate());
        SettlementMatcher.Result result =
                SettlementMatcher.match(report, candidates, settledRefsBefore(command.provider(), command.reportDate()));

        return transactions.execute(status -> commit(command, requestHash, report, result));
    }

    /**
     * Writes the run, its lines, its breaks and one outbox row per currency.
     *
     * <p>The insert is {@code ON CONFLICT DO NOTHING}: if another caller reconciled this day while we were fetching,
     * it returns no id and we replay theirs instead of writing a second set of breaks and a second settlement event.
     * That is the guarantee the idempotency test rests on, and it comes from the constraint rather than from a
     * check-then-insert, which two concurrent runs would both pass.
     *
     * <p>Runs inside {@link #transactions}, never on its own: see the constructor for why the annotation would not
     * have worked here.
     */
    RunSummary commit(Command command, byte[] requestHash, SettlementReport report,
            SettlementMatcher.Result result) {
        boolean blocked = result.blocksSettlement();
        String status = blocked ? "FAILED_VALIDATION" : "COMPLETED";
        // A report that disagrees with itself is never booked, and a day with nothing in it has no currency to key an
        // event by, so neither settles.
        boolean settles = !blocked && !report.totals().isEmpty();

        Optional<UUID> runId = db.sql("""
                INSERT INTO reconciliation_runs (idempotency_key, request_hash, provider, report_date, report_id,
                                                 content_hash, status, report_lines, lines_matched, breaks_found,
                                                 settled)
                VALUES (:key, :hash, :provider, :date, :reportId, :contentHash, :status, :lines::jsonb, :matched,
                        :breaks, :settled)
                ON CONFLICT DO NOTHING
                RETURNING run_id
                """)
                .param("key", command.idempotencyKey())
                .param("hash", requestHash)
                .param("provider", command.provider())
                .param("date", Timestamp.valueOf(command.reportDate().atStartOfDay()))
                .param("reportId", report.reportId())
                .param("contentHash", contentHash(report))
                .param("status", status)
                .param("lines", storedLines(report))
                .param("matched", result.linesMatched())
                .param("breaks", result.breaks().size())
                .param("settled", settles)
                .query((rs, rowNum) -> rs.getObject(1, UUID.class))
                .optional();

        if (runId.isEmpty()) {
            return replayOf(command, requestHash).orElseThrow(() -> new IllegalStateException(
                    "the run for " + command.provider() + " " + command.reportDate()
                            + " was neither inserted nor found"));
        }

        for (SettlementMatcher.Break found : result.breaks()) {
            insertBreak(runId.get(), found);
        }
        if (settles) {
            report.totals().forEach(totals -> emit(command.provider(), report.reportId(), totals));
        }

        return new RunSummary(runId.get(), command.provider(), command.reportDate(), report.reportId(), status,
                result.linesMatched(), result.breaks().size(), settles, false);
    }

    /**
     * Appends one settlement event through {@code libs/outbox} (D03-5, ADR-0008).
     *
     * <p>The message key is the event id, which §0.3 C6 also makes the order group, so every event about one
     * settlement shares a partition and stays ordered behind the orders it settles.
     */
    private void emit(String provider, String reportId, SettlementReport.Totals totals) {
        String payload = SettlementEvents.toJson(provider, reportId, totals, clock.instant());
        String eventId = SettlementEvents.eventId(provider, reportId, totals.currency());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("schema", SettlementEvents.SCHEMA);
        headers.put("event_id", eventId);
        // No traceparent: OutboxWriter captures the active span itself (§0.3 C11).
        outbox.append(TopicDefinitions.PAYMENT_EVENTS.name(), eventId, payload, headers);
    }

    private void insertBreak(UUID runId, SettlementMatcher.Break found) {
        db.sql("""
                INSERT INTO reconciliation_breaks (run_id, break_type, status, provider_ref, client_reference,
                                                   currency, report_gross_minor, ledger_gross_minor, attempt_id,
                                                   detail)
                VALUES (:run, :type, :status, :providerRef, :clientRef, :currency, :reportGross, :ledgerGross,
                        :attempt::uuid, :detail)
                """)
                .param("run", runId)
                .param("type", found.type().name())
                .param("status", found.status().name())
                .param("providerRef", found.providerRef())
                .param("clientRef", found.clientReference())
                .param("currency", found.currency())
                .param("reportGross", found.reportGrossMinor())
                .param("ledgerGross", found.ledgerGrossMinor())
                .param("attempt", found.attemptId())
                .param("detail", found.detail())
                .update();
    }

    /**
     * The stored run for this request, if one exists.
     *
     * <p>Looked up by key first, then by run identity: a day is reconciled once, so a second caller arriving with a
     * different key for the same day receives that run rather than booking the settlement again.
     */
    private Optional<RunSummary> replayOf(Command command, byte[] requestHash) {
        Optional<Stored> byKey = stored("idempotency_key = :key", Map.of("key", command.idempotencyKey()));
        if (byKey.isPresent()) {
            if (!Arrays.equals(byKey.get().requestHash(), requestHash)) {
                // Replaying the first run's result would hide the caller's bug behind a success.
                throw new IdempotencyKeyReusedException(command.idempotencyKey());
            }
            return byKey.map(Stored::asReplay);
        }
        return stored("provider = :provider AND report_date = :date",
                Map.of("provider", command.provider(),
                        "date", Timestamp.valueOf(command.reportDate().atStartOfDay())))
                .map(Stored::asReplay);
    }

    private Optional<Stored> stored(String where, Map<String, ?> params) {
        // The separator is concatenated explicitly rather than left at the end of the text block: Java strips
        // trailing white space from every line of a text block, so "... WHERE " would arrive as "...WHERE" and glue
        // itself to the first predicate.
        var query = db.sql("""
                SELECT run_id, request_hash, provider, report_date, report_id, status, lines_matched, breaks_found,
                       settled
                  FROM reconciliation_runs
                """ + " WHERE " + where);
        params.forEach(query::param);
        return query.query((rs, rowNum) -> new Stored(rs.getObject(1, UUID.class), rs.getBytes(2), rs.getString(3),
                        rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8),
                        rs.getBoolean(9)))
                .optional();
    }

    private record Stored(UUID runId, byte[] requestHash, String provider, LocalDate reportDate, String reportId,
            String status, int linesMatched, int breaksFound, boolean settled) {

        RunSummary asReplay() {
            return new RunSummary(runId, provider, reportDate, reportId, status, linesMatched, breaksFound, settled,
                    true);
        }
    }

    /**
     * Charge and refund attempts this report could be talking about: those created in the report's window, plus any
     * still open from earlier cycles, which is what makes a late line resolve instead of breaking twice.
     */
    private List<SettlementMatcher.Candidate> candidates(String provider, LocalDate reportDate) {
        return db.sql("""
                SELECT attempt_id, kind, currency, amount_minor, status, provider_ref
                  FROM payment_attempts
                 WHERE provider = :provider AND kind IN ('CHARGE','REFUND')
                   AND ((created_at >= :from AND created_at < :to) OR status IN (""" + OPEN_STATUSES + """
                   ))
                 ORDER BY created_at, attempt_id
                """)
                .param("provider", provider)
                .param("from", Timestamp.valueOf(reportDate.atStartOfDay()))
                .param("to", Timestamp.valueOf(reportDate.plusDays(1).atStartOfDay()))
                .query((rs, rowNum) -> new SettlementMatcher.Candidate(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6)))
                .list();
    }

    /** Provider references already settled by an earlier day's run, so the same capture twice is a duplicate. */
    private Set<String> settledRefsBefore(String provider, LocalDate reportDate) {
        return Set.copyOf(db.sql("""
                SELECT DISTINCT jsonb_array_elements(report_lines)->>'provider_ref'
                  FROM reconciliation_runs WHERE provider = :provider AND report_date < :date
                """)
                .param("provider", provider)
                .param("date", Timestamp.valueOf(reportDate.atStartOfDay()))
                .query(String.class)
                .list());
    }

    public List<BreakView> breaks(UUID runId, String type, String status, int limit) {
        return db.sql("""
                SELECT break_id, break_type, status, provider_ref, client_reference, currency, report_gross_minor,
                       ledger_gross_minor, attempt_id, detail
                  FROM reconciliation_breaks
                 WHERE run_id = :run
                   AND (:type::text IS NULL OR break_type = :type)
                   AND (:status::text IS NULL OR status = :status)
                 ORDER BY break_id
                 LIMIT :limit
                """)
                .param("run", runId)
                .param("type", type)
                .param("status", status)
                .param("limit", limit)
                .query((rs, rowNum) -> new BreakView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), (Long) rs.getObject(7), (Long) rs.getObject(8),
                        rs.getString(9), rs.getString(10)))
                .list();
    }

    public boolean runExists(UUID runId) {
        return db.sql("SELECT count(*) FROM reconciliation_runs WHERE run_id = :run")
                .param("run", runId).query(Long.class).single() > 0;
    }

    /** decision: D01-7 — a currency the ledger would refuse is malformed input, rejected before anything is stored. */
    private static void requireAllowedCurrencies(SettlementReport report) {
        CurrencyRules rules = CurrencyRules.defaults();
        for (SettlementReport.Line line : report.lines()) {
            if (!rules.isAllowed(line.currency())) {
                throw new UnsupportedCurrencyException(line.currency());
            }
        }
        for (SettlementReport.Totals totals : report.totals()) {
            if (!rules.isAllowed(totals.currency())) {
                throw new UnsupportedCurrencyException(totals.currency());
            }
        }
    }

    /** The served lines, in snake_case, so the stored form reads like the report and is queryable by provider_ref. */
    private static String storedLines(SettlementReport report) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (SettlementReport.Line line : report.lines()) {
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("provider_ref", line.providerRef());
            stored.put("client_reference", line.clientReference());
            stored.put("kind", line.kind());
            stored.put("currency", line.currency());
            stored.put("gross_minor", line.grossMinor());
            stored.put("fee_minor", line.feeMinor());
            lines.add(stored);
        }
        return JSON.writeValueAsString(lines);
    }

    /** Over the served lines and totals, so a report that changed between two runs is detectable. */
    private static String contentHash(SettlementReport report) {
        return HexFormat.of().formatHex(sha256(storedLines(report) + "|" + report.totals()));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
