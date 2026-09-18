package dev.zerosum.infra.volume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.evidence.Provenance;
import dev.zerosum.infra.Stack;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * M8 (b): FakeCard at {@code timeout_after_commit_rate=0.2}, charges driven through the real money path, and provider
 * ground truth showing exactly one successful charge per attempt.
 *
 * <p><strong>The path.</strong> Each charge is a COMMERCE order for a rider with a registered card: order-service →
 * outbox → Kafka → instrument-service's collection policy → attempt → FakeCard. Nothing calls FakeCard directly.
 *
 * <p><strong>Per-row attribution, not two counts.</strong> A total of injected faults and a total of attempts prove
 * nothing about each other. Every attempt is classified by how its outcome was learned, from its own transition
 * history:
 * <ul>
 *   <li>{@code response} — the POST answered ("provider accepted the submission");</li>
 *   <li>{@code webhook} — the answer never came, and the provider's webhook settled the attempt while it was still
 *       {@code SUBMITTING};</li>
 *   <li>{@code resolver} — the answer never came, the attempt went {@code UNKNOWN}, and S05-T12's resolver settled
 *       it.</li>
 * </ul>
 * Every attempt whose answer was lost is then matched one-to-one to a {@code timeout_after_commit} row of the fault
 * log that lies between its {@code SUBMITTING} transition and the creation of its provider charge (instruments and
 * fakeproviders share one PostgreSQL server, hence one clock). Fault rows left unmatched are reported, not dropped:
 * they are faults on something other than an original submission (an idempotent retry or a lookup).
 *
 * <p>The fault log stores types lowercase ({@code Decision.faultType()}); the query uses the stored spelling, and the
 * test refuses a run in which the log shows no fault at all, because "zero before and zero after" is what a wrong
 * filter looks like.
 */
@Tag("e2e")
class CardTimeoutVolumeE2ETest {

    private static final long FARE = 1_000;
    private static final long FEE = 200;
    private static final int MAX_IN_FLIGHT = Integer.getInteger("zs.volume.inFlight", 120);
    private static final List<String> TERMINAL = List.of("SUCCEEDED", "DECLINED", "FAILED");

    /** One attempt, joined across the instruments and fakeproviders databases. */
    record AttemptRow(String attemptId, String orderId, String rider, String status, String path, String settledBy,
            int charges, int succeeded, Instant submittingAt, Instant chargeCreatedAt, Instant unknownAt,
            Instant settledAt) {
    }

    /** Which fault row explains what: an attempt's lost original response, or a lost retry of an UNKNOWN attempt. */
    record Attribution(Map<String, String> originalFault, Map<String, Integer> retryFaults, long ambiguous,
            long unexplained) {
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    @DisplayName("M8(b): timeout_after_commit_rate=0.2 — provider truth shows exactly one successful charge per attempt")
    void oneSuccessfulChargePerAttempt() {
        int n = Integer.getInteger("zs.volume.charges", 200);
        long seed = Long.getLong("zs.volume.seed", 20260918L);
        double webhookDrop = Double.parseDouble(System.getProperty("zs.volume.webhookDropRate", "0"));
        String run = "v" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String label = System.getProperty("zs.volume.label", "m8b-" + run);

        int riders = Math.min(50, n);
        for (int r = 0; r < riders; r++) {
            HttpResponse<String> registered = Stack.post(Stack.INSTRUMENTS + "/v1/instrument-tokens",
                    Stack.writerToken(), null,
                    "{\"entity_id\":\"rider:%sr%d\",\"provider\":\"fakecard\",\"token\":\"tok_card_ok\"}".formatted(run, r));
            assertEquals(200, registered.statusCode(), registered.body());
        }

        String previousProfile = (String) Stack.query("fakeproviders",
                "SELECT knobs::text AS k FROM fault_profiles WHERE provider = 'fakecard'").stream()
                .findFirst().map(row -> row.get("k")).orElse("{\"seed\":0}");
        long faultsForSeedBefore = Stack.count("fakeproviders",
                "SELECT count(*) FROM fault_log WHERE provider = 'fakecard' AND seed = ?", seed);

        String profile = "{\"timeout_after_commit_rate\":0.2,\"webhook_drop_rate\":%s,\"seed\":%d}"
                .formatted(webhookDrop, seed);
        Instant activatedAt;
        Instant lastOrderAt;
        var orderIds = new ArrayList<String>();
        HttpResponse<String> activated = Stack.put(Stack.PROVIDERS + "/admin/faults/fakecard", Stack.adminToken(),
                profile);
        assertEquals(200, activated.statusCode(), activated.body());
        try {
            activatedAt = ((Timestamp) Stack.query("fakeproviders",
                    "SELECT activated_at FROM fault_profiles WHERE provider = 'fakecard'").getFirst()
                    .get("activated_at")).toInstant();

            // Paced so the policy's bounded queue (200) never overflows: overflow is a legitimate path (the sweeper
            // picks up CREATED attempts after 30 s), but it is not the path M8 (b) is about.
            for (int i = 0; i < n; i++) {
                while (i - terminal(run) >= MAX_IN_FLIGHT) {
                    Stack.sleep(Duration.ofMillis(500));
                }
                String rider = "rider:%sr%d".formatted(run, i % riders);
                HttpResponse<String> created = Stack.post(Stack.ORDERS + "/v1/money-orders", Stack.writerToken(),
                        run + "-" + i, Stack.tripOrder("trip_%s_%d".formatted(run, i), rider,
                                "driver:%s".formatted(run), FARE, FEE));
                assertEquals(201, created.statusCode(), created.body());
                orderIds.add(Stack.json(created.body()).get("order_id").asString());
            }
            lastOrderAt = Instant.now();
            awaitQuiesce(run, n, Duration.ofMinutes(10));
        } finally {
            // Restored whatever happened: a stack left injecting faults would poison every later test.
            HttpResponse<String> restored = Stack.put(Stack.PROVIDERS + "/admin/faults/fakecard", Stack.adminToken(),
                    stripNulls(previousProfile));
            assertEquals(200, restored.statusCode(), "fault profile restored: " + restored.body());
        }
        Instant restoredAt = Instant.now();

        // --- evidence ---------------------------------------------------------------------------------------------
        List<AttemptRow> rows = attempts(run);
        List<Map<String, Object>> faults = Stack.query("fakeproviders",
                "SELECT fault_id, fault_type, target, occurred_at FROM fault_log WHERE provider = 'fakecard' "
                        + "AND seed = ? AND occurred_at >= ? ORDER BY occurred_at", seed, Timestamp.from(activatedAt));
        Map<String, Long> faultTypes = faults.stream().collect(Collectors.groupingBy(
                f -> f.get("fault_type") + " " + f.get("target").toString().replaceAll("^evt_.*", "evt_*"),
                TreeMap::new, Collectors.counting()));
        List<Map<String, Object>> timeouts = faults.stream()
                .filter(f -> "timeout_after_commit".equals(f.get("fault_type"))).toList();
        Attribution attribution = attribute(rows, timeouts);
        Map<String, Long> settledBy = rows.stream().collect(Collectors.groupingBy(
                r -> r.path() + " | " + r.settledBy(), TreeMap::new, Collectors.counting()));
        long retryFaults = attribution.retryFaults().values().stream().mapToLong(Integer::longValue).sum();

        long stray = Stack.count("fakeproviders", "SELECT count(*) FROM card_charges WHERE created_at >= ? "
                + "AND created_at <= ? AND NOT (client_reference = ANY (?))", Timestamp.from(activatedAt),
                Timestamp.from(restoredAt), (Object) rows.stream().map(AttemptRow::attemptId).toArray(String[]::new));
        Map<String, Long> paths = rows.stream().collect(Collectors.groupingBy(AttemptRow::path, TreeMap::new,
                Collectors.counting()));
        Map<String, Long> statuses = rows.stream().collect(Collectors.groupingBy(AttemptRow::status, TreeMap::new,
                Collectors.counting()));
        long lostResponses = rows.stream().filter(r -> !r.path().equals("response")).count();
        long matched = attribution.originalFault().size();
        long exactlyOne = rows.stream().filter(r -> r.charges() == 1 && r.succeeded() == 1).count();
        Map<Integer, Long> chargesPerAttempt = rows.stream().collect(Collectors.groupingBy(AttemptRow::charges,
                TreeMap::new, Collectors.counting()));
        Map<String, Long> receivables = riderReceivables(run, riders);
        long nonZeroReceivables = receivables.values().stream().filter(v -> v != 0).count();
        long distinctOrders = rows.stream().map(AttemptRow::orderId).distinct().count();

        var summary = new LinkedHashMap<String, Object>();
        summary.put("label", label);
        summary.put("run", run);
        summary.put("seed", seed);
        summary.put("profile", profile);
        summary.put("charges_requested", n);
        summary.put("orders_created", orderIds.size());
        summary.put("attempts", rows.size());
        summary.put("attempts_distinct_orders", distinctOrders);
        summary.put("attempt_statuses", statuses);
        summary.put("outcome_paths", paths);
        summary.put("settled_by", settledBy);
        summary.put("provider_charges_per_attempt", chargesPerAttempt);
        summary.put("attempts_with_exactly_one_successful_charge", exactlyOne);
        summary.put("stray_charges_in_window", stray);
        summary.put("fault_rows_for_seed_before", faultsForSeedBefore);
        summary.put("fault_rows_in_window_by_type", faultTypes);
        summary.put("timeout_after_commit_rows", timeouts.size());
        summary.put("lost_responses", lostResponses);
        summary.put("lost_responses_matched_to_a_fault_row", matched);
        summary.put("timeout_fault_rows_attributed_to_retries_of_unknown_attempts", retryFaults);
        summary.put("unknown_attempts_with_a_lost_retry", attribution.retryFaults().size());
        summary.put("timeout_fault_rows_ambiguous", attribution.ambiguous());
        summary.put("timeout_fault_rows_unexplained", attribution.unexplained());
        summary.put("riders", riders);
        summary.put("riders_with_nonzero_receivable", nonZeroReceivables);
        summary.put("fault_profile_activated_at", activatedAt.toString());
        summary.put("last_order_posted_at", lastOrderAt.toString());
        summary.put("fault_profile_restored_at", restoredAt.toString());
        summary.put("wall_seconds_activation_to_restore", Duration.between(activatedAt, restoredAt).toSeconds());

        var provenance = Provenance.capture(Stack.ROOT, List.of(seed), Map.of(
                "instrument-service image", Stack.docker("inspect", "-f", "{{.Image}}",
                        "zerosum-ledger-instrument-service-1"),
                "fake-providers image", Stack.docker("inspect", "-f", "{{.Image}}", "zerosum-ledger-fake-providers-1")));
        String json = "{\"provenance\": " + provenance.toJson() + ",\n\"summary\": " + Stack.JSON.writeValueAsString(summary)
                + "}\n";
        System.out.println(json);
        Stack.writeEvidence(label + ".json", json);
        Stack.writeEvidence(label + "-attempts.csv", csv(rows, attribution));

        // --- the claims -------------------------------------------------------------------------------------------
        assertTrue(!timeouts.isEmpty(), "the fault log shows injected timeouts; zero would mean a broken filter");
        assertEquals(0, faultsForSeedBefore, "the seed was fresh, so every counted fault row is this run's");
        assertEquals(n, rows.size(), "one attempt per order");
        assertEquals(n, distinctOrders, "and no order produced two attempts");
        assertEquals(n, exactlyOne, "M8(b): provider ground truth — exactly one successful charge per attempt");
        assertEquals(n, statuses.getOrDefault("SUCCEEDED", 0L), "every attempt ended SUCCEEDED");
        assertEquals(lostResponses, matched, "every lost response is attributed to an injected timeout");
        assertEquals(0, attribution.unexplained(), "every timeout row is an original submission or a retry");
        assertEquals(0, stray, "no charge in the window belongs to anything but these attempts");
        assertEquals(0, nonZeroReceivables, "each rider receivable was collected exactly once in the ledger");
    }

    // --- queries --------------------------------------------------------------------------------------------------

    private static long terminal(String run) {
        return Stack.count("instruments", "SELECT count(*) FROM payment_attempts WHERE kind = 'CHARGE' "
                + "AND entity_id LIKE ? AND status = ANY (?)", "rider:" + run + "r%", (Object) TERMINAL.toArray(String[]::new));
    }

    private static void awaitQuiesce(String run, int n, Duration patience) {
        Instant deadline = Instant.now().plus(patience);
        while (Instant.now().isBefore(deadline)) {
            if (terminal(run) == n) {
                return;
            }
            Stack.sleep(Duration.ofSeconds(1));
        }
        // Not failed here: the evidence below still gets written, and the assertions name what did not settle.
    }

    private static List<AttemptRow> attempts(String run) {
        var transitions = new HashMap<String, List<Map<String, Object>>>();
        Stack.query("instruments", """
                SELECT t.attempt_id::text AS id, t.seq, t.from_status, t.to_status, t.cause, t.at
                FROM attempt_transitions t JOIN payment_attempts a USING (attempt_id)
                WHERE a.kind = 'CHARGE' AND a.entity_id LIKE ? ORDER BY t.attempt_id, t.seq""", "rider:" + run + "r%")
                .forEach(t -> transitions.computeIfAbsent((String) t.get("id"), k -> new ArrayList<>()).add(t));
        var attempts = Stack.query("instruments", """
                SELECT attempt_id::text AS id, source_order_id::text AS order_id, entity_id, status
                FROM payment_attempts WHERE kind = 'CHARGE' AND entity_id LIKE ? ORDER BY created_at""",
                "rider:" + run + "r%");
        String[] ids = attempts.stream().map(a -> (String) a.get("id")).toArray(String[]::new);
        var charges = new HashMap<String, Map<String, Object>>();
        Stack.query("fakeproviders", """
                SELECT client_reference, count(*) AS total, count(*) FILTER (WHERE status = 'SUCCEEDED') AS ok,
                       min(created_at) AS first_created
                FROM card_charges WHERE client_reference = ANY (?) GROUP BY client_reference""", (Object) ids)
                .forEach(c -> charges.put((String) c.get("client_reference"), c));

        var rows = new ArrayList<AttemptRow>();
        for (var a : attempts) {
            String id = (String) a.get("id");
            List<Map<String, Object>> history = transitions.getOrDefault(id, List.of());
            Map<String, Object> charge = charges.get(id);
            Map<String, Object> last = history.isEmpty() ? null : history.getLast();
            rows.add(new AttemptRow(id, (String) a.get("order_id"), (String) a.get("entity_id"),
                    (String) a.get("status"), path(history),
                    // Provider and event ids stripped, so the causes group into kinds.
                    last == null ? null : ((String) last.get("cause")).replaceAll("(ch|evt)_[0-9a-f-]+", "<id>"),
                    charge == null ? 0 : ((Number) charge.get("total")).intValue(),
                    charge == null ? 0 : ((Number) charge.get("ok")).intValue(), firstAt(history, "SUBMITTING"),
                    charge == null ? null : ((Timestamp) charge.get("first_created")).toInstant(),
                    firstAt(history, "UNKNOWN"), last == null ? null : ((Timestamp) last.get("at")).toInstant()));
        }
        return rows;
    }

    private static Instant firstAt(List<Map<String, Object>> history, String toStatus) {
        return history.stream().filter(t -> toStatus.equals(t.get("to_status")))
                .map(t -> ((Timestamp) t.get("at")).toInstant()).findFirst().orElse(null);
    }

    /** How the attempt learned its outcome, read from its own transitions. */
    private static String path(List<Map<String, Object>> history) {
        boolean wentUnknown = history.stream().anyMatch(t -> "UNKNOWN".equals(t.get("to_status")));
        if (wentUnknown) {
            return "resolver";
        }
        return history.stream().filter(t -> "SUBMITTING".equals(t.get("from_status"))).findFirst()
                .map(t -> {
                    String cause = (String) t.get("cause");
                    if (cause.startsWith("provider accepted")) {
                        return "response";
                    }
                    return cause.startsWith("webhook") ? "webhook" : "other: " + cause;
                })
                .orElse("unsettled");
    }

    /**
     * Two passes over the {@code timeout_after_commit} rows.
     *
     * <ol>
     *   <li><strong>Original submissions, one-to-one.</strong> Each attempt whose response was lost claims the
     *       earliest unclaimed row between its {@code SUBMITTING} transition and its charge's creation — the fault is
     *       drawn before the handler commits the charge, and both databases share one clock. Attempts are taken in
     *       charge order so an early attempt cannot take a later one's row.</li>
     *   <li><strong>Everything left</strong> must fall inside some resolver attempt's {@code UNKNOWN} interval: an
     *       idempotent retry of that attempt that lost its response too. A row inside exactly one such interval is
     *       attributed to it; inside several is counted ambiguous; inside none is unexplained.</li>
     * </ol>
     */
    private static Attribution attribute(List<AttemptRow> rows, List<Map<String, Object>> timeouts) {
        var claimed = new java.util.HashSet<String>();
        var original = new LinkedHashMap<String, String>();
        rows.stream().filter(r -> !r.path().equals("response") && r.submittingAt() != null
                        && r.chargeCreatedAt() != null)
                .sorted(java.util.Comparator.comparing(AttemptRow::chargeCreatedAt))
                .forEach(r -> timeouts.stream()
                        .filter(f -> !claimed.contains((String) f.get("fault_id")))
                        .filter(f -> within(at(f), r.submittingAt(), r.chargeCreatedAt()))
                        .findFirst()
                        .ifPresent(f -> {
                            claimed.add((String) f.get("fault_id"));
                            original.put(r.attemptId(), (String) f.get("fault_id"));
                        }));

        var retries = new LinkedHashMap<String, Integer>();
        long ambiguous = 0;
        long unexplained = 0;
        List<AttemptRow> resolved = rows.stream().filter(r -> r.unknownAt() != null).toList();
        for (var f : timeouts) {
            if (claimed.contains((String) f.get("fault_id"))) {
                continue;
            }
            List<AttemptRow> candidates = resolved.stream()
                    .filter(r -> within(at(f), r.unknownAt(), r.settledAt())).toList();
            if (candidates.size() == 1) {
                retries.merge(candidates.getFirst().attemptId(), 1, Integer::sum);
            } else if (candidates.isEmpty()) {
                unexplained++;
            } else {
                ambiguous++;
            }
        }
        return new Attribution(original, retries, ambiguous, unexplained);
    }

    private static Instant at(Map<String, Object> fault) {
        return ((Timestamp) fault.get("occurred_at")).toInstant();
    }

    private static boolean within(Instant at, Instant from, Instant to) {
        return from != null && to != null && !at.isBefore(from) && !at.isAfter(to);
    }

    private static Map<String, Long> riderReceivables(String run, int riders) {
        var balances = new LinkedHashMap<String, Long>();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        for (int r = 0; r < riders; r++) {
            String rider = "rider:%sr%d".formatted(run, r);
            Long balance = Stack.balance(rider, "receivable");
            // The collection orders trail the charge by one more trip through the outbox and the broker.
            while ((balance == null || balance != 0) && Instant.now().isBefore(deadline)) {
                Stack.sleep(Duration.ofMillis(500));
                balance = Stack.balance(rider, "receivable");
            }
            balances.put(rider, balance == null ? Long.MIN_VALUE : balance);
        }
        return balances;
    }

    private static String stripNulls(String knobsJson) {
        // The stored profile carries simulated_banking_day_seconds: null; the endpoint takes an absent knob instead.
        return knobsJson.replaceAll(",?\\s*\"[a-z_]+\"\\s*:\\s*null", "").replace("{,", "{");
    }

    private static String csv(List<AttemptRow> rows, Attribution attribution) {
        return rows.stream().map(r -> String.join(",", r.attemptId(), r.orderId(), r.rider(), r.status(), r.path(),
                        String.valueOf(r.charges()), String.valueOf(r.succeeded()), String.valueOf(r.submittingAt()),
                        String.valueOf(r.chargeCreatedAt()), String.valueOf(r.unknownAt()),
                        String.valueOf(r.settledAt()), String.valueOf(attribution.originalFault().get(r.attemptId())),
                        String.valueOf(attribution.retryFaults().getOrDefault(r.attemptId(), 0)),
                        '"' + String.valueOf(r.settledBy()) + '"'))
                .collect(Collectors.joining("\n",
                        "attempt_id,order_id,rider,status,path,provider_charges,provider_succeeded,submitting_at,"
                                + "charge_created_at,unknown_at,settled_at,original_submit_fault_id,"
                                + "lost_retry_faults,settled_by\n", "\n"));
    }
}
