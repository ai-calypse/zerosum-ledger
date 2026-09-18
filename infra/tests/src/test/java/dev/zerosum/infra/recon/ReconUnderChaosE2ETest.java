package dev.zerosum.infra.recon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.evidence.Provenance;
import dev.zerosum.infra.Stack;
import dev.zerosum.verifier.VerifierMain;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * M11 (c): A0 chaos runs end with 0 unexplained breaks after 2 settlement cycles.
 *
 * <p><strong>One run is two settlement cycles.</strong> Each cycle is one simulated day:
 * <ol>
 *   <li>FakeCard gets the day's chaos profile, which is F7 ({@code timeout_after_commit_rate} 0.2,
 *       {@code http_500_rate} 0.05), F8's webhook knobs (duplicate 0.3, reorder 0.3, drop 0.1) and F10 (report
 *       missing line, off by one, duplicate line, 0.05 each), under the day's own seed.</li>
 *   <li>Seeded COMMERCE trips go through the real order API from 8 concurrent callers that retry with the same key and
 *       the same body on any transport error, 5xx or 409 — the F1 caller contract. Mid-load, instrument-service is
 *       {@code docker kill -s KILL}ed and restarted (F3), and later order-service (F1).</li>
 *   <li>Quiesce, per master §8.3: callers stopped; both outboxes empty; consumer lag 0 on every group; no attempt in
 *       {@code CREATED}, {@code SUBMITTING}, {@code PENDING} or {@code UNKNOWN}; no undelivered webhook; every one of
 *       the day's orders has its attempt. Held on two consecutive polls. Not reached within the maximum wait is "not
 *       quiesced", which fails the run and is never a pass.</li>
 *   <li>The day is closed by backdating both sides — this cycle's attempts and their FakeCard charges — into a past
 *       UTC day that nothing else occupies, the method of {@code docs/results/s06/raw/recon_live.py}. The shift is
 *       one interval for every row, chosen so the whole cycle lands inside that day in its original order.</li>
 *   <li>{@code POST /v1/reconciliation-runs} for that day, retried with the same key while the chaos profile is still
 *       active: the report fetch itself draws FakeCard's 500s and lost responses.</li>
 * </ol>
 * After both cycles and a final quiesce, the real verifier runs in-process over all four databases with every check,
 * I12 included, and must exit 0.
 *
 * <p><strong>What counts as explained</strong> is I12's definition, applied here per cycle as well: a break whose
 * type, provider reference and report match an injection recorded in FakeCard's fault log. Every other break is
 * unexplained, whatever its status.
 */
@Tag("chaos")
class ReconUnderChaosE2ETest {

    private static final int TRIPS = Integer.getInteger("zs.recon.trips", 300);
    private static final int RIDERS = 50;
    private static final int CALLERS = 8;
    private static final int MAX_IN_FLIGHT = 120;
    private static final Duration MAX_QUIESCE = Duration.ofMinutes(Long.getLong("zs.recon.quiesceMinutes", 15));
    private static final String INSTRUMENT_SERVICE = "zerosum-ledger-instrument-service-1";
    private static final List<String> TERMINAL = List.of("SUCCEEDED", "DECLINED", "FAILED");
    private static final Map<String, String> INJECTED_BREAK = Map.of("report_missing_line", "MISSING_IN_REPORT",
            "report_off_by_one", "AMOUNT_MISMATCH", "report_duplicate_line", "DUPLICATE_LINE");

    @Test
    @Timeout(value = 120, unit = TimeUnit.MINUTES)
    @DisplayName("M11(c): two settlement cycles under A0 chaos end with 0 unexplained breaks")
    void twoSettlementCyclesUnderChaos() {
        long seed = Long.getLong("zs.recon.seed", 11_000_001L);
        String run = "rc" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String label = System.getProperty("zs.recon.label", "m11c-" + run);

        for (int r = 0; r < RIDERS; r++) {
            HttpResponse<String> registered = Stack.post(Stack.INSTRUMENTS + "/v1/instrument-tokens",
                    Stack.writerToken(), null,
                    "{\"entity_id\":\"rider:%sr%d\",\"provider\":\"fakecard\",\"token\":\"tok_card_ok\"}".formatted(run, r));
            assertEquals(200, registered.statusCode(), registered.body());
        }

        LocalDate[] days = twoFreeDays();
        var cycles = new ArrayList<Map<String, Object>>();
        try {
            for (int d = 1; d <= 2; d++) {
                cycles.add(cycle(run, d, days[d - 1], seed * 10 + d));
            }
        } finally {
            // Restored whatever happened: a stack left injecting faults, or with a service down, poisons every later run.
            HttpResponse<String> restored = Stack.put(Stack.PROVIDERS + "/admin/faults/fakecard", Stack.adminToken(),
                    "{\"seed\":0}");
            for (String container : List.of(Stack.ORDER_SERVICE, INSTRUMENT_SERVICE)) {
                if (!"true".equals(Stack.docker("inspect", "-f", "{{.State.Running}}", container))) {
                    Stack.docker("start", container);
                }
            }
            assertEquals(200, restored.statusCode(), "fault profile restored: " + restored.body());
        }

        // The two SETTLEMENT orders travel outbox → Kafka → order-service → ledger like everything else.
        Map<String, Object> finalQuiesce = awaitQuiesce(null, 0);

        Path evidence = Path.of(System.getProperty("zs.evidenceDir",
                Stack.ROOT.resolve("infra/tests/build/evidence").toString()));
        var verifierOut = new ByteArrayOutputStream();
        String db = "jdbc:postgresql://127.0.0.1:5432/";
        int verifierExit = VerifierMain.run(new String[] {"--jdbc-url", db + "ledger", "--orders-jdbc-url", db + "orders",
                "--instruments-jdbc-url", db + "instruments", "--providers-jdbc-url", db + "fakeproviders",
                "--checks", "I1,I2,I3,I4,I6,I6b,I7,I10,I12", "--env-file", Stack.ROOT.resolve(".env").toString(),
                "--out", evidence.toString(), "--label", label + "-verifier",
                "--description", "After M11(c) run " + run + " (" + label + "), both settlement cycles quiesced."},
                new PrintStream(verifierOut, true, StandardCharsets.UTF_8));
        String verifier = verifierOut.toString(StandardCharsets.UTF_8);
        System.out.println(verifier);

        var summary = new LinkedHashMap<String, Object>();
        summary.put("label", label);
        summary.put("run", run);
        summary.put("seed", seed);
        summary.put("trips_per_cycle", TRIPS);
        summary.put("cycles", cycles);
        summary.put("final_quiesce", finalQuiesce);
        summary.put("verifier_exit_code", verifierExit);
        summary.put("verifier_lines", verifier.lines().filter(l -> l.matches("ZS-VERIFY I\\d.*|ZS-VERIFY result.*"))
                .toList());
        var provenance = Provenance.capture(Stack.ROOT, List.of(seed * 10 + 1, seed * 10 + 2), images());
        String json = "{\"provenance\": " + provenance.toJson() + ",\n\"summary\": "
                + Stack.JSON.writeValueAsString(summary) + "}\n";
        System.out.println(json);
        Stack.writeEvidence(label + ".json", json);

        // --- the claims -----------------------------------------------------------------------------------------------
        for (Map<String, Object> cycle : cycles) {
            String day = "cycle " + cycle.get("cycle") + " (" + cycle.get("report_date") + ")";
            assertEquals(true, ((Map<?, ?>) cycle.get("quiesce")).get("reached"), day + " quiesced");
            for (Object kill : (List<?>) cycle.get("kills")) {
                assertEquals("137", ((Map<?, ?>) kill).get("exit_code"), day + " SIGKILL, not a stop: " + kill);
            }
            assertEquals(2, ((List<?>) cycle.get("kills")).size(), day + ": F3 and F1 both fired");
            Map<?, ?> faults = (Map<?, ?>) cycle.get("faults_fired");
            for (String knob : List.of("timeout_after_commit", "http_500", "webhook_duplicate", "webhook_reorder",
                    "webhook_drop")) {
                assertTrue(faults.containsKey(knob), day + ": " + knob + " fired; zero would mean a dead knob: " + faults);
            }
            assertEquals(0L, cycle.get("fault_rows_for_seed_before"), day + ": the seed was fresh");
            assertEquals(0L, cycle.get("rows_outside_the_day"), day + ": the whole cycle landed on its day");
            assertEquals("COMPLETED", cycle.get("recon_status"), day + " reconciled");
            assertTrue((Long) cycle.get("injected_discrepancies") > 0, day + ": F10 injected something");
            assertEquals(0L, cycle.get("unexplained_breaks"), day + ": M11(c) 0 unexplained breaks " + cycle);
            assertEquals(0L, cycle.get("undetected_injections"), day + ": every injection has its typed break");
            assertEquals(0L, cycle.get("duplicate_charges"), day + ": I7, no duplicate charge");
        }
        assertEquals(true, finalQuiesce.get("reached"), "final quiesce");
        assertEquals(0, verifierExit, "verifier, every check including I6, I7 and I12:\n" + verifier);
    }

    // --- one settlement cycle -------------------------------------------------------------------------------------------

    private static Map<String, Object> cycle(String run, int d, LocalDate day, long daySeed) {
        String prefix = "trip_%s_d%d_".formatted(run, d);
        String profile = """
                {"timeout_after_commit_rate":0.2,"http_500_rate":0.05,"webhook_duplicate_rate":0.3,\
                "webhook_reorder_rate":0.3,"webhook_drop_rate":0.1,"report_missing_line_rate":0.05,\
                "report_off_by_one_rate":0.05,"report_duplicate_line_rate":0.05,"seed":%d}""".formatted(daySeed);
        long faultsBefore = Stack.count("fakeproviders",
                "SELECT count(*) FROM fault_log WHERE provider = 'fakecard' AND seed = ?", daySeed);
        HttpResponse<String> activated = Stack.put(Stack.PROVIDERS + "/admin/faults/fakecard", Stack.adminToken(),
                profile);
        assertEquals(200, activated.statusCode(), activated.body());
        Instant activatedAt = dbNow().minusSeconds(1);

        // --- load, with F3 and F1 landing mid-way ---------------------------------------------------------------------
        var random = new SplittableRandom(daySeed);
        long[] fares = new long[TRIPS];
        for (int i = 0; i < TRIPS; i++) {
            fares[i] = 500 + random.nextInt(3_000);
        }
        int killInstrumentsAt = (int) (TRIPS * (0.25 + 0.15 * random.nextDouble()));
        int killOrdersAt = (int) (TRIPS * (0.60 + 0.15 * random.nextDouble()));
        long downMillis = 1_000 + random.nextInt(2_000);

        var posted = new AtomicInteger();
        var next = new AtomicInteger();
        var terminal = new AtomicLong();
        var retries = new AtomicLong();
        var replays = new AtomicLong();
        var kills = new ArrayList<Map<String, Object>>();
        Instant loadStart = dbNow().minusSeconds(1);
        ExecutorService callers = Executors.newFixedThreadPool(CALLERS);
        var futures = new ArrayList<Future<?>>();
        for (int c = 0; c < CALLERS; c++) {
            futures.add(callers.submit(() -> {
                for (int i = next.getAndIncrement(); i < TRIPS; i = next.getAndIncrement()) {
                    // Paced so the policy's bounded queue (200) never overflows; overflow is a legitimate path, but it
                    // parks attempts in CREATED for the 30 s sweeper and is not what this run measures.
                    while (i - terminal.get() >= MAX_IN_FLIGHT) {
                        Stack.sleep(Duration.ofMillis(200));
                    }
                    String body = Stack.tripOrder(prefix + i, "rider:%sr%d".formatted(run, i % RIDERS),
                            "driver:" + run, fares[i], fares[i] / 5);
                    postUntilAccepted("%s-d%d-%d".formatted(run, d, i), body, retries, replays);
                    posted.incrementAndGet();
                }
                return null;
            }));
        }
        callers.shutdown();
        boolean instrumentsKilled = false;
        boolean ordersKilled = false;
        while (!callers.isTerminated()) {
            terminal.set(countTerminal(prefix));
            if (!instrumentsKilled && posted.get() >= killInstrumentsAt) {
                kills.add(kill(INSTRUMENT_SERVICE, posted.get(), prefix, downMillis));
                instrumentsKilled = true;
            } else if (instrumentsKilled && !ordersKilled && posted.get() >= killOrdersAt) {
                kills.add(kill(Stack.ORDER_SERVICE, posted.get(), prefix, downMillis));
                ordersKilled = true;
            }
            Stack.sleep(Duration.ofMillis(500));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                throw new IllegalStateException("a caller failed", e);
            }
        }
        Instant loadEnd = dbNow();

        // --- quiesce ------------------------------------------------------------------------------------------------
        Map<String, Object> quiesce = awaitQuiesce(prefix, TRIPS);

        var result = new LinkedHashMap<String, Object>();
        result.put("cycle", d);
        result.put("report_date", day.toString());
        result.put("seed", daySeed);
        result.put("profile", profile);
        result.put("trips", TRIPS);
        result.put("fare_total", java.util.Arrays.stream(fares).sum());
        result.put("orders_accepted", posted.get());
        result.put("post_retries", retries.get());
        result.put("post_replays_after_retry", replays.get());
        result.put("load_started_at", loadStart.toString());
        result.put("load_ended_at", loadEnd.toString());
        result.put("kills", kills);
        result.put("quiesce", quiesce);
        result.put("attempt_statuses", grouped("instruments",
                "SELECT status AS k, count(*) AS n FROM payment_attempts WHERE order_group_id LIKE ? GROUP BY status",
                prefix + "%"));
        result.put("attempts_that_went_unknown", Stack.count("instruments", """
                SELECT count(DISTINCT t.attempt_id) FROM attempt_transitions t JOIN payment_attempts a USING (attempt_id)
                WHERE a.order_group_id LIKE ? AND t.to_status = 'UNKNOWN'""", prefix + "%"));
        String[] attemptIds = Stack.query("instruments",
                "SELECT attempt_id::text AS id FROM payment_attempts WHERE order_group_id LIKE ?", prefix + "%")
                .stream().map(row -> (String) row.get("id")).toArray(String[]::new);
        result.put("duplicate_charges", Stack.count("fakeproviders", """
                SELECT count(*) - count(DISTINCT client_reference) FROM card_charges
                WHERE status = 'SUCCEEDED' AND client_reference = ANY (?)""", (Object) attemptIds));

        // --- close the day: both sides, one interval, the whole cycle inside the target day -------------------------
        Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant target = dayStart.plus(Duration.ofHours(8));
        double shiftSeconds = Duration.between(target, loadStart).toNanos() / 1e9;
        int attemptsMoved = Stack.query("instruments", """
                UPDATE payment_attempts SET created_at = created_at - make_interval(secs => ?)
                WHERE order_group_id LIKE ? RETURNING attempt_id""", shiftSeconds, prefix + "%").size();
        int chargesMoved = Stack.query("fakeproviders", """
                UPDATE card_charges SET created_at = created_at - make_interval(secs => ?)
                WHERE client_reference = ANY (?) RETURNING charge_id""", shiftSeconds, (Object) attemptIds).size();
        Timestamp from = Timestamp.from(dayStart);
        Timestamp to = Timestamp.from(dayStart.plus(Duration.ofDays(1)));
        long outside = Stack.count("instruments", "SELECT count(*) FROM payment_attempts WHERE order_group_id LIKE ? "
                + "AND (created_at < ? OR created_at >= ?)", prefix + "%", from, to)
                + Stack.count("fakeproviders", "SELECT count(*) FROM card_charges WHERE client_reference = ANY (?) "
                + "AND (created_at < ? OR created_at >= ?)", (Object) attemptIds, from, to);
        result.put("backdate_shift_seconds", shiftSeconds);
        result.put("attempts_backdated", attemptsMoved);
        result.put("charges_backdated", chargesMoved);
        result.put("rows_outside_the_day", outside);

        // --- reconcile, still under the chaos profile -----------------------------------------------------------------
        String reportId = "rpt_" + day.toString().replace('-', '_');
        int reconAttempts = 0;
        HttpResponse<String> recon;
        do {
            reconAttempts++;
            try {
                recon = Stack.post(Stack.INSTRUMENTS + "/v1/reconciliation-runs", Stack.adminToken(),
                        "recon-%s-d%d".formatted(run, d),
                        "{\"provider\":\"fakecard\",\"report_date\":\"%s\"}".formatted(day));
            } catch (UncheckedIOException e) {
                recon = null;
            }
            if (recon == null || recon.statusCode() >= 500) {
                Stack.sleep(Duration.ofSeconds(2));
            }
        } while ((recon == null || recon.statusCode() >= 500) && reconAttempts < 30);
        assertTrue(recon != null && (recon.statusCode() == 201 || recon.statusCode() == 200),
                "reconciliation run: " + (recon == null ? "no response" : recon.statusCode() + " " + recon.body()));
        var runJson = Stack.json(recon.body());
        String runId = runJson.get("run_id").asString();
        result.put("recon_http_attempts", reconAttempts);
        result.put("recon_http_status", recon.statusCode());
        result.put("recon_run_id", runId);
        result.put("recon_status", runJson.get("status").asString());
        result.put("recon_lines_matched", runJson.get("lines_matched").asLong());
        result.put("recon_breaks_found", runJson.get("breaks_found").asLong());
        result.put("recon_settled", runJson.get("settled").asBoolean());
        var report = Stack.query("fakeproviders", """
                SELECT jsonb_array_length(clean_lines) AS clean, jsonb_array_length(served_lines) AS served
                FROM settlement_reports WHERE provider = 'fakecard' AND report_id = ?""", reportId).getFirst();
        result.put("report_lines_clean", ((Number) report.get("clean")).longValue());
        result.put("report_lines_served", ((Number) report.get("served")).longValue());

        // --- I12 for this cycle ---------------------------------------------------------------------------------------
        var counts = new TreeMap<String, long[]>();
        var breaksByType = new TreeMap<String, Long>();
        for (var row : Stack.query("instruments", """
                SELECT b.break_type AS type, b.status, coalesce(b.provider_ref, a.provider_ref, 'null') AS ref
                FROM reconciliation_breaks b LEFT JOIN payment_attempts a ON a.attempt_id = b.attempt_id
                WHERE b.run_id = ?::uuid""", runId)) {
            counts.computeIfAbsent(row.get("type") + " " + row.get("ref"), k -> new long[2])[0]++;
            breaksByType.merge(row.get("type") + "/" + row.get("status"), 1L, Long::sum);
        }
        var injectedByType = new TreeMap<String, Long>();
        for (var row : Stack.query("fakeproviders", """
                SELECT fault_type AS type, split_part(target, ':', 2) AS ref FROM fault_log
                WHERE fault_type IN ('report_missing_line','report_off_by_one','report_duplicate_line')
                  AND split_part(target, ':', 1) = ?""", reportId)) {
            counts.computeIfAbsent(INJECTED_BREAK.get(row.get("type")) + " " + row.get("ref"), k -> new long[2])[1]++;
            injectedByType.merge((String) row.get("type"), 1L, Long::sum);
        }
        long explained = 0;
        long unexplained = 0;
        long undetected = 0;
        var unexplainedSample = new ArrayList<String>();
        for (var entry : counts.entrySet()) {
            long b = entry.getValue()[0];
            long i = entry.getValue()[1];
            explained += Math.min(b, i);
            unexplained += Math.max(0, b - i);
            undetected += Math.max(0, i - b);
            if (b != i && unexplainedSample.size() < 20) {
                unexplainedSample.add(entry.getKey() + " breaks=" + b + " injected=" + i);
            }
        }
        result.put("breaks_by_type_and_status", breaksByType);
        result.put("injected_by_type", injectedByType);
        result.put("injected_discrepancies", injectedByType.values().stream().mapToLong(Long::longValue).sum());
        result.put("explained_breaks", explained);
        result.put("unexplained_breaks", unexplained);
        result.put("undetected_injections", undetected);
        result.put("mismatches", unexplainedSample);
        result.put("fault_rows_for_seed_before", faultsBefore);
        result.put("faults_fired", grouped("fakeproviders", """
                SELECT fault_type AS k, count(*) AS n FROM fault_log
                WHERE provider = 'fakecard' AND seed = ? AND occurred_at >= ? GROUP BY fault_type""",
                daySeed, Timestamp.from(activatedAt)));
        System.out.println("ZS-M11C cycle " + d + " " + Stack.JSON.writeValueAsString(result));
        return result;
    }

    // --- callers and chaos ----------------------------------------------------------------------------------------------

    /** The F1 caller contract: same key, same body, until the order is created or replayed. */
    private static void postUntilAccepted(String key, String body, AtomicLong retries, AtomicLong replays) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
        boolean retried = false;
        while (true) {
            try {
                HttpResponse<String> response = Stack.post(Stack.ORDERS + "/v1/money-orders", Stack.writerToken(),
                        key, body);
                int status = response.statusCode();
                if (status == 201 || status == 200) {
                    if (status == 200 && retried) {
                        replays.incrementAndGet();
                    }
                    return;
                }
                if (status != 409 && status < 500) {
                    // 400/422 is a harness bug (a changed body under the same key), never something to retry.
                    throw new IllegalStateException(key + ": " + status + " " + response.body());
                }
            } catch (UncheckedIOException connectionLost) {
                // order-service is down or the request died with it: the outcome is unknown, so retry the same key.
            }
            retried = true;
            retries.incrementAndGet();
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException(key + " was never accepted within 5 minutes");
            }
            Stack.sleep(Duration.ofMillis(500));
        }
    }

    private static Map<String, Object> kill(String container, int postedAtKill, String prefix, long downMillis) {
        var kill = new LinkedHashMap<String, Object>();
        kill.put("container", container);
        kill.put("orders_posted_at_kill", postedAtKill);
        kill.put("cycle_attempts_submitting_at_kill", Stack.count("instruments",
                "SELECT count(*) FROM payment_attempts WHERE order_group_id LIKE ? AND status = 'SUBMITTING'",
                prefix + "%"));
        kill.put("killed_at", Instant.now().toString());
        Stack.docker("kill", "-s", "KILL", container);
        for (int i = 0; i < 20 && "true".equals(Stack.docker("inspect", "-f", "{{.State.Running}}", container)); i++) {
            Stack.sleep(Duration.ofMillis(250));
        }
        kill.put("exit_code", Stack.docker("inspect", "-f", "{{.State.ExitCode}}", container));
        Stack.sleep(Duration.ofMillis(downMillis));
        Stack.docker("start", container);
        Instant started = Instant.now();
        kill.put("down_ms", downMillis);
        Instant deadline = started.plus(Duration.ofMinutes(3));
        while (!"healthy".equals(Stack.docker("inspect", "-f", "{{.State.Health.Status}}", container))) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException(container + " did not return to healthy within 3 minutes");
            }
            Stack.sleep(Duration.ofMillis(500));
        }
        kill.put("healthy_after_start_s", Duration.between(started, Instant.now()).toMillis() / 1000.0);
        return kill;
    }

    // --- quiesce --------------------------------------------------------------------------------------------------------

    /**
     * The master §8.3 conditions, all at zero on two consecutive polls. {@code prefix} additionally requires every one
     * of this cycle's orders to have its attempt; null for the final quiesce.
     */
    private static Map<String, Object> awaitQuiesce(String prefix, int expected) {
        Instant start = Instant.now();
        Instant deadline = start.plus(MAX_QUIESCE);
        Map<String, Long> last = Map.of();
        int consecutive = 0;
        while (Instant.now().isBefore(deadline)) {
            last = conditions(prefix, expected);
            if (last.values().stream().allMatch(v -> v == 0)) {
                var withLag = new LinkedHashMap<>(last);
                withLag.put("consumer_lag", consumerLag());
                last = withLag;
                consecutive = withLag.get("consumer_lag") == 0 ? consecutive + 1 : 0;
                if (consecutive == 2) {
                    break;
                }
            } else {
                consecutive = 0;
            }
            Stack.sleep(Duration.ofSeconds(2));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("reached", consecutive == 2);
        result.put("waited_s", Duration.between(start, Instant.now()).toMillis() / 1000.0);
        result.put("max_wait_s", MAX_QUIESCE.toSeconds());
        result.put("at", Instant.now().toString());
        result.put("conditions", last);
        return result;
    }

    private static Map<String, Long> conditions(String prefix, int expected) {
        var conditions = new LinkedHashMap<String, Long>();
        conditions.put("orders_outbox_unpublished",
                Stack.count("orders", "SELECT count(*) FROM outbox WHERE published_at IS NULL"));
        conditions.put("instruments_outbox_unpublished",
                Stack.count("instruments", "SELECT count(*) FROM outbox WHERE published_at IS NULL"));
        conditions.put("attempts_not_terminal", Stack.count("instruments", "SELECT count(*) FROM payment_attempts "
                + "WHERE status IN ('CREATED','SUBMITTING','PENDING','UNKNOWN')"));
        conditions.put("webhooks_undelivered",
                Stack.count("fakeproviders", "SELECT count(*) FROM provider_events WHERE delivered_at IS NULL"));
        if (prefix != null) {
            conditions.put("cycle_orders_without_attempt", expected - Stack.count("instruments",
                    "SELECT count(*) FROM payment_attempts WHERE order_group_id LIKE ?", prefix + "%"));
        }
        return conditions;
    }

    /** Σ lag over every partition of every consumer group, from the broker itself. */
    private static long consumerLag() {
        String out = Stack.docker("exec", "-e", "KAFKA_HEAP_OPTS=-Xmx128m", Stack.KAFKA,
                "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "kafka:29092", "--describe",
                "--all-groups");
        long lag = 0;
        for (String line : out.lines().toList()) {
            String[] c = line.trim().split("\\s+");
            // GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG ...
            if (c.length >= 6 && c[2].matches("\\d+") && c[4].matches("\\d+")) {
                lag += c[3].matches("\\d+") ? Long.parseLong(c[4]) - Long.parseLong(c[3]) : Long.parseLong(c[4]);
            }
        }
        return lag;
    }

    // --- helpers --------------------------------------------------------------------------------------------------------

    /**
     * The latest two consecutive closed UTC days that nothing on the stack occupies: no report, no reconciliation run,
     * no charge and no attempt created on either. A run never shares a settlement day with anything else.
     */
    private static LocalDate[] twoFreeDays() {
        LocalDate today = ((java.sql.Date) Stack.query("instruments",
                "SELECT (now() AT TIME ZONE 'UTC')::date AS d").getFirst().get("d")).toLocalDate();
        LocalDate later = today.minusDays(1);
        for (int i = 0; i < 3650; i++, later = later.minusDays(1)) {
            if (free(later) && free(later.minusDays(1))) {
                return new LocalDate[] {later.minusDays(1), later};
            }
        }
        throw new IllegalStateException("no two free days in ten years");
    }

    private static boolean free(LocalDate day) {
        Timestamp from = Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp to = Timestamp.from(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        return Stack.count("fakeproviders", "SELECT count(*) FROM settlement_reports WHERE report_date = ?",
                java.sql.Date.valueOf(day)) == 0
                && Stack.count("instruments", "SELECT count(*) FROM reconciliation_runs WHERE report_date = ?",
                java.sql.Date.valueOf(day)) == 0
                && Stack.count("fakeproviders", "SELECT count(*) FROM card_charges WHERE created_at >= ? AND created_at < ?",
                from, to) == 0
                && Stack.count("instruments", "SELECT count(*) FROM payment_attempts WHERE created_at >= ? "
                + "AND created_at < ?", from, to) == 0;
    }

    private static long countTerminal(String prefix) {
        return Stack.count("instruments", "SELECT count(*) FROM payment_attempts WHERE order_group_id LIKE ? "
                + "AND status = ANY (?)", prefix + "%", (Object) TERMINAL.toArray(String[]::new));
    }

    /** The database clock, which both the backdating and the fault log use; the host's may differ from the VM's. */
    private static Instant dbNow() {
        return ((Timestamp) Stack.query("instruments", "SELECT now() AS n").getFirst().get("n")).toInstant();
    }

    private static Map<String, Long> grouped(String database, String sql, Object... params) {
        var grouped = new TreeMap<String, Long>();
        Stack.query(database, sql, params).forEach(row ->
                grouped.put((String) row.get("k"), ((Number) row.get("n")).longValue()));
        return grouped;
    }

    private static Map<String, String> images() {
        var images = new LinkedHashMap<String, String>();
        for (String service : List.of("order-service", "ledger-service", "instrument-service", "fake-providers")) {
            images.put(service + " image", Stack.docker("inspect", "-f", "{{.Image}}",
                    "zerosum-ledger-" + service + "-1"));
        }
        return images;
    }
}
