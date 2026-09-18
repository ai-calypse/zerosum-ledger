// decision: D05-7, D05-11 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.payouts;

import dev.zerosum.instrument.core.InstrumentExceptions.UnknownProviderException;
import dev.zerosum.instrument.core.ProviderRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One payout run: who is owed money, is the pipeline fresh enough to believe it, and one payout each (M10(a),
 * M10(c), D05-7).
 *
 * <p><strong>The order of checks is fixed</strong> (D05-7): payouts kill switch, then freshness, then eligibility.
 * Each refusal is recorded as a {@code REFUSED} run and creates no attempts, so "why did nobody get paid at 09:00"
 * is answered by a row rather than by a log line that has since rotated away.
 *
 * <p><strong>At most one in-flight payout per (driver, currency) is the database's promise, not this class's.</strong>
 * The insert is {@code ON CONFLICT DO NOTHING} against the {@code one_inflight_payout} partial unique index. There is
 * deliberately no "does this driver already have a payout?" query: the check and the insert would be two statements,
 * and two runs racing would both pass the check and both pay the driver. The loser here simply gets no row back and
 * reports the driver as skipped, which is why a second concurrent run is a normal outcome rather than an error.
 *
 * <p><strong>Phasing, as S05-T09 established it.</strong> Freshness and every balance are read <em>before</em> the
 * transaction opens, the transaction does nothing but insert, and the submissions happen <em>after</em> it commits.
 * A provider or ledger call inside the transaction would hold it open for the length of an HTTP timeout, and a
 * rollback after a submission would erase the record of money already sent.
 *
 * <p>The transaction is driven by an explicit {@link TransactionTemplate} rather than {@code @Transactional}:
 * {@link #run} calls the commit phase directly, and a self-invocation never passes through Spring's proxy, so the
 * annotation would be silently inert — a bug this repository has already made twice.
 *
 * <p><strong>Known limitation (H.5, master §5.5).</strong> The amount is the balance as the ledger reports it at that
 * instant (ADR-0004). A downward adjustment still in the pipeline can therefore leave the driver negative after the
 * payout, which is recorded as driver debt and measured later as R1. The freshness check bounds how much can be in
 * flight; it does not eliminate the window.
 */
@Service
public class PayoutRunService {

    private static final Logger log = LoggerFactory.getLogger(PayoutRunService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** decision: D05-7 — the canonical request this key is bound to. Bumped if the canonical form ever changes. */
    private static final short REQUEST_HASH_VERSION = 1;

    /** Refusal codes, which are also the problem codes the endpoint answers with (D05-13). */
    public static final String LEDGER_STALE = "ledger_stale";
    public static final String PAYOUTS_DISABLED = "payouts_disabled";

    /** Per-driver outcomes (D05-7 step 7). Recorded on the run, because a skip leaves no attempt to infer it from. */
    static final String PAID = "PAID";
    static final String SKIPPED_IN_FLIGHT = "SKIPPED_IN_FLIGHT";
    static final String SKIPPED_BELOW_MINIMUM = "SKIPPED_BELOW_MINIMUM";
    static final String SKIPPED_NO_BALANCE = "SKIPPED_NO_BALANCE";
    static final String SKIPPED_NO_TOKEN = "SKIPPED_NO_TOKEN";

    private final JdbcClient db;
    private final PayoutClient reads;
    private final PayoutSubmitter submitter;
    private final ProviderRegistry providers;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final long minimumPayoutMinor;
    private final Duration freshnessThreshold;
    private final boolean payoutsEnabled;

    PayoutRunService(JdbcClient db, PayoutClient reads, PayoutSubmitter submitter, ProviderRegistry providers,
            Clock clock, PlatformTransactionManager transactionManager,
            @Value("${zs.payouts.minimum-payout-minor}") long minimumPayoutMinor,
            // decision: D05-7 — must equal the M10(c) threshold; PayoutFreshnessTest pins it to 5 s.
            @Value("${zs.payouts.freshness-threshold}") Duration freshnessThreshold,
            @Value("${zs.kill-switches.payouts-enabled}") boolean payoutsEnabled) {
        this.db = db;
        this.reads = reads;
        this.submitter = submitter;
        this.providers = providers;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.minimumPayoutMinor = minimumPayoutMinor;
        this.freshnessThreshold = freshnessThreshold;
        this.payoutsEnabled = payoutsEnabled;
    }

    /** What the caller asked for. The currency is part of the canonical request, so it is part of the hash. */
    public record Command(String currency, String idempotencyKey) {
    }

    /**
     * @param amountMinor what the driver was owed, on the account's normal side; null when nothing was read
     * @param asOfSeq     the ledger sequence the balance was read at, kept for audit (D05-7 edge cases)
     */
    public record DriverOutcome(String entityId, String outcome, Long amountMinor, Long asOfSeq, String attemptId) {
    }

    public record RunSummary(UUID runId, String currency, String status, String refusalCode, int attemptsCreated,
            List<DriverOutcome> results, boolean replayed) {
    }

    /** decision: D03-3 — the same key with a different request is a caller bug, never a replay. */
    public static class IdempotencyKeyReusedException extends RuntimeException {
        IdempotencyKeyReusedException(String key) {
            super("idempotency key " + key + " was already used with a different request");
        }
    }

    public RunSummary run(Command command) {
        byte[] requestHash = sha256(canonical(command));

        // The replay check comes first, so a retried run neither re-reads balances nor pays anybody a second time.
        Optional<RunSummary> replay = replayOf(command, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        // decision: D05-11, D05-7 step 2 — kill switch, then freshness, then eligibility.
        if (!payoutsEnabled) {
            log.warn("payouts are disabled; run with key {} is refused and creates no attempts",
                    command.idempotencyKey());
            return refuse(command, requestHash, PAYOUTS_DISABLED);
        }

        Optional<PipelineFreshness> freshness = reads.pipelineFreshness();
        if (freshness.isEmpty()) {
            // Fail closed: a component we could not measure is not a component we may assume is healthy.
            return refuse(command, requestHash, LEDGER_STALE);
        }
        if (freshness.get().exceeds(freshnessThreshold)) {
            log.warn("payout run refused: pipeline freshness {} exceeds {} (M10(c))", freshness.get(),
                    freshnessThreshold);
            return refuse(command, requestHash, LEDGER_STALE);
        }

        List<Planned> planned = new ArrayList<>();
        List<DriverOutcome> skipped = new ArrayList<>();
        try {
            plan(command.currency(), planned, skipped);
        } catch (PayoutClient.LedgerUnavailable unavailable) {
            // Same code as a stale pipeline, and for the same reason: a balance we could not read is one we must not
            // pay against. Nothing has been written at this point, so the refusal is total.
            log.warn("payout run refused: {}", unavailable.getMessage());
            return refuse(command, requestHash, LEDGER_STALE);
        }

        Committed committed = transactions.execute(status -> commit(command, requestHash, planned, skipped));

        // After the commit, never inside it (D05-6 phasing): a bank call in the transaction could be rolled back
        // after the money had gone.
        submitter.submitLater(committed.attemptIds());
        return committed.summary();
    }

    /** A driver this run intends to pay, and what the ledger said when it decided that. */
    private record Planned(String entityId, String provider, String token, long amountMinor, long asOfSeq) {
    }

    private record Committed(RunSummary summary, List<UUID> attemptIds) {
    }

    /**
     * Decides who is eligible, reading each balance from the ledger at that moment (D02-7, ADR-0004).
     *
     * <p>Outside any transaction: every line here is an HTTP call.
     */
    private void plan(String currency, List<Planned> planned, List<DriverOutcome> skipped) {
        for (Map.Entry<String, List<TokenRow>> driver : driverTokens().entrySet()) {
            String entityId = driver.getKey();
            Optional<TokenRow> payable = driver.getValue().stream().filter(this::canDisburse).findFirst();
            if (payable.isEmpty()) {
                // Registered, but at a provider that cannot send money out. Counted as its own outcome rather than
                // as ineligible: the fix is a registration, not a bigger balance.
                skipped.add(new DriverOutcome(entityId, SKIPPED_NO_TOKEN, null, null, null));
                continue;
            }

            Optional<PayoutClient.Payable> balance = reads.payable(entityId, currency);
            if (balance.isEmpty()) {
                // The ledger has no entries for this driver at all.
                skipped.add(new DriverOutcome(entityId, SKIPPED_NO_BALANCE, null, null, null));
                continue;
            }
            long owed = balance.get().presentedMinor();
            if (owed < minimumPayoutMinor) {
                // Also the guard against a zero or negative payable: an attempt's amount must be a positive
                // magnitude, and a driver in debt is owed nothing to send.
                skipped.add(new DriverOutcome(entityId, SKIPPED_BELOW_MINIMUM, owed, balance.get().asOfSeq(), null));
                continue;
            }
            planned.add(new Planned(entityId, payable.get().provider(), payable.get().token(), owed,
                    balance.get().asOfSeq()));
        }
    }

    /**
     * Writes the run and its attempts, or nothing at all.
     *
     * <p>Every insert is {@code ON CONFLICT DO NOTHING}, which is where both guarantees live: the run's key makes a
     * retried call replay instead of paying twice, and {@code one_inflight_payout} makes a second concurrent run
     * lose at the index rather than at a check.
     */
    private Committed commit(Command command, byte[] requestHash, List<Planned> planned,
            List<DriverOutcome> skipped) {
        Optional<UUID> runId = insertRun(command, requestHash, "RUNNING", null);
        if (runId.isEmpty()) {
            // Another caller with the same key committed while we were reading balances. Theirs is the run.
            return new Committed(replayOf(command, requestHash).orElseThrow(() -> new IllegalStateException(
                    "the run for key " + command.idempotencyKey() + " was neither inserted nor found")), List.of());
        }

        List<DriverOutcome> results = new ArrayList<>(skipped);
        List<UUID> created = new ArrayList<>();
        for (Planned driver : planned) {
            Optional<UUID> attemptId = insertAttempt(runId.get(), command.currency(), driver);
            if (attemptId.isPresent()) {
                created.add(attemptId.get());
                results.add(new DriverOutcome(driver.entityId(), PAID, driver.amountMinor(), driver.asOfSeq(),
                        attemptId.get().toString()));
            } else {
                // The index refused it: this driver already has a payout in flight. A skip, never a failed run.
                log.info("driver {} already has an in-flight payout in {}; run {} skips them", driver.entityId(),
                        command.currency(), runId.get());
                results.add(new DriverOutcome(driver.entityId(), SKIPPED_IN_FLIGHT, driver.amountMinor(),
                        driver.asOfSeq(), null));
            }
        }
        results.sort(Comparator.comparing(DriverOutcome::entityId));

        db.sql("""
                UPDATE payout_runs
                   SET status = 'COMPLETED', attempts_created = :created, results = :results::jsonb,
                       completed_at = :now
                 WHERE run_id = :id
                """)
                .param("created", created.size())
                .param("results", toJson(results))
                .param("now", Timestamp.from(clock.instant()))
                .param("id", runId.get())
                .update();

        return new Committed(new RunSummary(runId.get(), command.currency(), "COMPLETED", null, created.size(),
                List.copyOf(results), false), List.copyOf(created));
    }

    /**
     * Inserts one payout attempt in {@code CREATED}.
     *
     * <p>The {@code order_group_id} is derived from the run and the driver, so every payment event this attempt ever
     * emits — accepted, settled, returned — shares one partition key and therefore stays in order behind the others
     * (§0.3 C6, ADR-0007). A generated group would be unique but would put a payout's own events on different
     * partitions.
     */
    private Optional<UUID> insertAttempt(UUID runId, String currency, Planned driver) {
        return db.sql("""
                INSERT INTO payment_attempts (kind, order_group_id, payout_run_id, entity_id, provider,
                                              instrument_token, currency, amount_minor, status)
                VALUES ('PAYOUT', :group, :run, :entity, :provider, :token, :currency, :amount, 'CREATED')
                ON CONFLICT DO NOTHING
                RETURNING attempt_id
                """)
                .param("group", "payout:" + runId + ":" + driver.entityId())
                .param("run", runId)
                .param("entity", driver.entityId())
                .param("provider", driver.provider())
                .param("token", driver.token())
                .param("currency", currency)
                .param("amount", driver.amountMinor())
                .query((rs, rowNum) -> rs.getObject(1, UUID.class))
                .optional();
    }

    /** A refused run, recorded so the refusal is evidence rather than a log line, and creating no attempts. */
    private RunSummary refuse(Command command, byte[] requestHash, String code) {
        return transactions.execute(status -> {
            Optional<UUID> runId = insertRun(command, requestHash, "REFUSED", code);
            if (runId.isEmpty()) {
                return replayOf(command, requestHash).orElseThrow(() -> new IllegalStateException(
                        "the refused run for key " + command.idempotencyKey() + " was neither inserted nor found"));
            }
            return new RunSummary(runId.get(), command.currency(), "REFUSED", code, 0, List.of(), false);
        });
    }

    private Optional<UUID> insertRun(Command command, byte[] requestHash, String status, String refusalCode) {
        return db.sql("""
                INSERT INTO payout_runs (idempotency_key, request_hash, request_hash_version, currency, status,
                                         refusal_code, completed_at)
                VALUES (:key, :hash, :hashVersion, :currency, :status, :refusal, :completed)
                ON CONFLICT DO NOTHING
                RETURNING run_id
                """)
                .param("key", command.idempotencyKey())
                .param("hash", requestHash)
                .param("hashVersion", REQUEST_HASH_VERSION)
                .param("currency", command.currency())
                .param("status", status)
                .param("refusal", refusalCode)
                .param("completed", refusalCode == null ? null : Timestamp.from(clock.instant()))
                .query((rs, rowNum) -> rs.getObject(1, UUID.class))
                .optional();
    }

    /**
     * The stored run for this key, if there is one.
     *
     * <p>A key stored against a different request is the caller's bug, and replaying the first run's result would
     * hide it behind a success — so it throws rather than returning something plausible (D03-3).
     */
    private Optional<RunSummary> replayOf(Command command, byte[] requestHash) {
        Optional<Stored> stored = db.sql("""
                SELECT run_id, request_hash, currency, status, refusal_code, attempts_created, results::text
                  FROM payout_runs WHERE idempotency_key = :key
                """)
                .param("key", command.idempotencyKey())
                .query((rs, rowNum) -> new Stored(rs.getObject(1, UUID.class), rs.getBytes(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7)))
                .optional();

        if (stored.isPresent() && !Arrays.equals(stored.get().requestHash(), requestHash)) {
            throw new IdempotencyKeyReusedException(command.idempotencyKey());
        }
        return stored.map(Stored::asReplay);
    }

    /**
     * A stored run as the S09 dashboard lists it: outcome counts and the total of the PAID amounts, folded from the
     * stored per-driver results rather than returning every driver.
     */
    public record RunListing(UUID runId, String currency, String status, String refusalCode, int attemptsCreated,
            java.time.Instant createdAt, java.time.Instant completedAt, Map<String, Long> outcomes, long paidMinor) {
    }

    /** The newest runs first, refused ones included: a refusal is a recorded run, and the evidence of why. */
    public List<RunListing> runs(int limit) {
        return db.sql("""
                SELECT run_id, currency, status, refusal_code, attempts_created, created_at, completed_at, results::text
                  FROM payout_runs
                 ORDER BY created_at DESC, run_id DESC
                 LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, rowNum) -> {
                    var outcomes = new java.util.TreeMap<String, Long>();
                    long paid = 0;
                    for (DriverOutcome result : parseResults(rs.getString(8))) {
                        outcomes.merge(result.outcome(), 1L, Long::sum);
                        if (PAID.equals(result.outcome()) && result.amountMinor() != null) {
                            paid = Math.addExact(paid, result.amountMinor());
                        }
                    }
                    Timestamp completed = rs.getTimestamp(7);
                    return new RunListing(rs.getObject(1, UUID.class), rs.getString(2).strip(), rs.getString(3),
                            rs.getString(4), rs.getInt(5), rs.getTimestamp(6).toInstant(),
                            completed == null ? null : completed.toInstant(), outcomes, paid);
                })
                .list();
    }

    private record Stored(UUID runId, byte[] requestHash, String currency, String status, String refusalCode,
            int attemptsCreated, String results) {

        RunSummary asReplay() {
            return new RunSummary(runId, currency, status, refusalCode, attemptsCreated, parseResults(results), true);
        }
    }

    /** Drivers with a registered instrument, newest registration first, keyed by entity. */
    private Map<String, List<TokenRow>> driverTokens() {
        var byEntity = new LinkedHashMap<String, List<TokenRow>>();
        db.sql("""
                SELECT entity_id, provider, token FROM instrument_tokens
                 WHERE entity_id LIKE 'driver:%'
                 ORDER BY entity_id, registered_at DESC, provider
                """)
                .query((rs, rowNum) -> new TokenRow(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list()
                .forEach(row -> byEntity.computeIfAbsent(row.entityId(), key -> new ArrayList<>()).add(row));
        return byEntity;
    }

    private record TokenRow(String entityId, String provider, String token) {
    }

    /** Capabilities decide, never the provider's name (D05-1), so a third bank needs no change here. */
    private boolean canDisburse(TokenRow token) {
        try {
            return providers.get(token.provider()).capabilities().disburse();
        } catch (UnknownProviderException | IllegalArgumentException unknown) {
            return false;
        }
    }

    /**
     * The stored results, in snake_case.
     *
     * <p>Assembled key by key rather than serialized from the record, like {@code PaymentEvents}: what is written to
     * the database is then visible here and independent of any mapper setting elsewhere in the service.
     */
    private static String toJson(List<DriverOutcome> results) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DriverOutcome result : results) {
            var row = new LinkedHashMap<String, Object>();
            row.put("entity_id", result.entityId());
            row.put("outcome", result.outcome());
            row.put("amount_minor", result.amountMinor());
            row.put("as_of_seq", result.asOfSeq());
            row.put("attempt_id", result.attemptId());
            rows.add(row);
        }
        return JSON.writeValueAsString(rows);
    }

    private static List<DriverOutcome> parseResults(String json) {
        List<DriverOutcome> results = new ArrayList<>();
        for (JsonNode row : JSON.readTree(json)) {
            results.add(new DriverOutcome(row.path("entity_id").asString(null), row.path("outcome").asString(null),
                    row.path("amount_minor").isNumber() ? row.path("amount_minor").asLong() : null,
                    row.path("as_of_seq").isNumber() ? row.path("as_of_seq").asLong() : null,
                    row.path("attempt_id").asString(null)));
        }
        return List.copyOf(results);
    }

    /** decision: D05-7 — the canonical request a key is bound to. The currency is all a run takes. */
    private static String canonical(Command command) {
        return "v" + REQUEST_HASH_VERSION + "|currency=" + command.currency();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
