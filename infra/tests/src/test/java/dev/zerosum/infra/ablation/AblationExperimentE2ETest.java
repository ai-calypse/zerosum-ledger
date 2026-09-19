package dev.zerosum.infra.ablation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.evidence.Provenance;
import dev.zerosum.infra.Stack;
import dev.zerosum.infra.ablation.TripLoad.Workload;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * M13 (b): the ablation experiment of master §8.5, against the real Compose stack under docker-compose.chaos.yml.
 *
 * <p>decision: D08-4, D08-6 — docs/results/s08/ablation-results.md holds the run plan (committed before the first
 * evidence run) and the results. One run is: reset the stack from empty volumes with the cell's switches, assert each
 * service's {@code ZS-CHAOS} startup line, drive the seeded {@link TripLoad} while the cell's faults fire on seeded
 * 20–40 s schedules, stop injecting, restore FakeCard, wait for quiesce (bounded; a run that does not quiesce is
 * {@code NOT_QUIESCED}, never a pass), run {@code tools/verifier}, write the run JSON, tear down.
 *
 * <p><strong>Paired design.</strong> A run's seed depends on the fault cell and the run index, not the variant, so
 * run r of A0/F2 and of A1/F2 send the same trips under the same fault schedule; the switch is the only difference.
 *
 * <p>Select cells with {@code -Dzs.ablation.cells=A0/F2,A1/F2} and runs per cell with {@code -Dzs.ablation.runs=3}.
 * Runs are interleaved (run 1 of every cell, then run 2, ...) so a slow drift in the machine is spread across cells.
 */
@Tag("chaos")
class AblationExperimentE2ETest {

    private static final long BASE_SEED = Long.getLong("zs.ablation.seed", 20260918L);
    private static final Duration MAX_QUIESCE = Duration.ofSeconds(Long.getLong("zs.ablation.maxQuiesceSeconds", 300));
    private static final Path OUT = Path.of(System.getProperty("zs.evidenceDir",
            Stack.ROOT.resolve("infra/tests/build/evidence").toString())).resolve("ablation");
    private static final String NO_FAULTS = "{\"seed\":0}";

    /** Cells named crash-timing-dependent in the run plan before the first evidence run (master §8.5, §0.3 E5). */
    static final Set<String> TIMING_DEPENDENT = Set.of("A1/F4", "A2/F1", "A2/F4", "A1/F4h");

    /** F4h outage: longer than the producer's delivery.timeout.ms (Kafka default 120 s; order-service does not override it). */
    static final Duration F4H_OUTAGE = Duration.ofSeconds(150);

    private static final Workload PLAIN_60 = new Workload(6_000, 100, 200, 50, false, 0, 0, 0);
    private static final Workload PLAIN_90 = new Workload(9_000, 100, 200, 50, false, 0, 0, 0);
    private static final Workload F11_BUGS = new Workload(6_000, 100, 200, 50, false, 0.01, 0.20, 0.02);
    private static final Workload CARDS = new Workload(200, 4, 50, 20, true, 0, 0, 0);
    private static final Workload CARDS_BUGS = new Workload(300, 5, 50, 20, true, 0.01, 0, 0);

    /** A variant under one fault set: the switches it turns on, and the workload its faults run against. */
    record Cell(String id, String variant, String faults, List<String> switches, Workload workload) {
    }

    static final Map<String, Cell> CELLS = Stream.of(
            new Cell("A0/F1", "A0", "F1", List.of(), PLAIN_90),
            new Cell("A2/F1", "A2", "F1", List.of("A2"), PLAIN_90),
            new Cell("A0/F2", "A0", "F2", List.of("F2"), PLAIN_60),
            new Cell("A1/F2", "A1", "F2", List.of("A1", "F2"), PLAIN_60),
            new Cell("A0/F4", "A0", "F4", List.of(), PLAIN_60),
            new Cell("A1/F4", "A1", "F4", List.of("A1"), PLAIN_60),
            new Cell("A2/F4", "A2", "F4", List.of("A2"), PLAIN_60),
            new Cell("A0/F4h", "A0", "F4h", List.of(), PLAIN_60),
            new Cell("A1/F4h", "A1", "F4h", List.of("A1"), PLAIN_60),
            new Cell("A2/F4h", "A2", "F4h", List.of("A2"), PLAIN_60),
            new Cell("A0/F7", "A0", "F7", List.of(), CARDS),
            new Cell("A3/F7", "A3", "F7", List.of("A3"), CARDS),
            new Cell("A0/F11v", "A0", "F11v", List.of(), F11_BUGS),
            new Cell("A4/F11v", "A4", "F11v", List.of("A4"), F11_BUGS),
            new Cell("A0/F12b", "A0", "F12b", List.of("F3"), CARDS_BUGS),
            new Cell("B0/F12b", "B0", "F12b", List.of("A1", "A2", "A3", "A4", "F3"), CARDS_BUGS),
            new Cell("A0/F8c", "A0", "F8c", List.of(), CARDS),
            new Cell("A5/F8c", "A5", "F8c", List.of("A5"), CARDS))
            .collect(Collectors.toMap(Cell::id, c -> c, (a, b) -> a, LinkedHashMap::new));

    @Test
    void ablationCells() throws Exception {
        List<Cell> cells = Stream.of(System.getProperty("zs.ablation.cells", "").split(","))
                .map(String::strip).filter(id -> !id.isEmpty())
                .map(id -> java.util.Objects.requireNonNull(CELLS.get(id), "unknown cell " + id)).toList();
        assertTrue(!cells.isEmpty(), "select cells with -Dzs.ablation.cells=..., one of " + CELLS.keySet());
        int runs = Integer.getInteger("zs.ablation.runs", 3);
        int firstRun = Integer.getInteger("zs.ablation.firstRun", 1);
        boolean pilot = Boolean.getBoolean("zs.ablation.pilot");
        Files.createDirectories(OUT.resolve("runs"));

        System.out.println("ZS-ABLATION building images: " + ChaosStack.build().lines().count() + " lines of output");
        var harnessErrors = new ArrayList<String>();
        try {
            for (int r = firstRun; r < firstRun + runs; r++) {
                for (Cell cell : cells) {
                    ObjectNode result = run(cell, r, pilot);
                    if ("HARNESS_ERROR".equals(result.get("classification").asString())) {
                        harnessErrors.add(result.get("label").asString() + ": " + result.path("error").asString());
                    }
                }
            }
        } finally {
            // Observability amendment: the data services and their volumes go, otel-lgtm stays, so every run's metrics,
            // traces and annotations can be read in Grafana afterwards. `make down` removes it.
            ChaosStack.teardown();
            System.out.println("ZS-ABLATION data services torn down; otel-lgtm left running at " + RunTelemetry.GRAFANA);
        }
        aggregate();
        assertEquals(List.of(), harnessErrors, "harness errors are rerun, never counted");
    }

    // --- one run ----------------------------------------------------------------------------------------------------

    private ObjectNode run(Cell cell, int index, boolean pilot) {
        String label = (pilot ? "pilot-" : "") + cell.variant() + "-" + cell.faults() + "-r" + index;
        long seed = seed(cell.faults(), index);
        String runId = label.toLowerCase().replaceAll("[^a-z0-9]", "");
        ObjectNode json = Stack.JSON.createObjectNode();
        json.put("evidence", "S08-M13b");
        json.put("label", label);
        json.put("cell", cell.id());
        json.put("variant", cell.variant());
        json.put("faults", cell.faults());
        json.put("run", index);
        json.put("seed", seed);
        json.put("run_id", runId);
        json.put("timing_dependent_cell", TIMING_DEPENDENT.contains(cell.id()));
        json.set("switches_requested", Stack.JSON.valueToTree(cell.switches()));
        System.out.printf("ZS-ABLATION %s seed=%d switches=%s%n", label, seed, cell.switches());

        var actions = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        Instant startedAt = Instant.now();
        try {
            ChaosStack.reset(cell.switches());
            json.put("reset_seconds", secondsSince(startedAt));
            json.set("switches_observed", observedSwitches(cell));
            List<String> tags = List.of(label, cell.variant(), cell.faults());
            RunTelemetry.annotate(Instant.now(), null, label + " start: switches " + cell.switches(), tags);

            if (cell.workload().cards()) {
                TripLoad.registerCards(runId, cell.workload().riders());
            }
            String profile = fakeCardProfile(cell.faults(), seed);
            json.put("fakecard_profile", profile);
            putFakeCard(profile);

            var stop = new AtomicBoolean();
            List<Thread> schedules = schedules(cell.faults(), seed, stop, actions, tags);
            Map<String, Object> load;
            try {
                load = new TripLoad().run(runId, seed, cell.workload());
            } finally {
                stop.set(true);
                for (Thread schedule : schedules) {
                    schedule.join();
                }
                putFakeCard(NO_FAULTS);
            }
            Instant generationEnd = Instant.now();
            RunTelemetry.annotate(generationEnd, null, label + " load finished: " + load.get("counts"), tags);
            json.set("load", Stack.JSON.valueToTree(load));
            json.set("fault_actions", Stack.JSON.valueToTree(new ArrayList<>(actions)));
            json.put("fault_injections", actions.size());
            json.put("fault_action_errors", actions.stream().filter(a -> a.containsKey("error")).count());

            restoreAll();
            Instant lastFault = actions.stream().map(a -> Instant.parse((String) a.get("ended_at")))
                    .max(Instant::compareTo).orElse(generationEnd);
            Instant recoveryFrom = lastFault.isAfter(generationEnd) ? lastFault : generationEnd;
            json.set("quiesce", quiesce(recoveryFrom));

            Path verifierDir = OUT.resolve("verifier");
            int exit = ChaosStack.verify(label, verifierDir, Files.createDirectories(verifierDir).resolve(label + ".log"));
            json.put("verifier_exit", exit);
            JsonNode verdict = exit == 2 ? null : Stack.JSON.readTree(verifierDir.resolve(label + ".json").toFile());
            if (verdict != null) {
                json.set("verifier_checks", verdict.get("checks"));
            }
            classify(json, cell, exit, verdict);
            json.set("provenance", Stack.JSON.readTree(Provenance.capture(Stack.ROOT, List.of(seed), images())
                    .toJson()));
            if (!pilot && !json.get("provenance").get("working_tree").asString().startsWith("clean")) {
                json.put("classification", "HARNESS_ERROR");
                json.put("error", "evidence runs require a clean working tree");
            }
        } catch (Exception failure) {
            json.put("classification", "HARNESS_ERROR");
            json.put("error", failure.toString());
            json.set("fault_actions", Stack.JSON.valueToTree(new ArrayList<>(actions)));
            failure.printStackTrace();
        } finally {
            try {
                putFakeCard(NO_FAULTS);
            } catch (RuntimeException ignored) {
                // the stack may already be down; teardown below discards the profile with the volume anyway
            }
            json.put("wall_seconds", secondsSince(startedAt));
            Instant endedAt = Instant.now();
            ObjectNode window = json.putObject("window_utc");
            window.put("start", startedAt.toString());
            window.put("end", endedAt.toString());
            json.put("grafana_url", RunTelemetry.grafanaUrl(startedAt, endedAt));
            json.put("logs", "logs/" + label + "/");
            RunTelemetry.saveLogs(OUT.resolve("logs"), label);
            RunTelemetry.annotate(startedAt, endedAt, label + " -> " + json.path("classification").asString()
                    + " violated=" + json.path("violated"), List.of(label, cell.variant(), cell.faults()));
            write(OUT.resolve("runs").resolve(label + ".json"), json.toPrettyString());
            ChaosStack.teardown();
        }
        System.out.printf("ZS-ABLATION %s -> %s violated=%s predicted_observed=%s (%.0f s)%n", label,
                json.path("classification").asString(), json.path("violated"),
                json.path("predicted_class_observed"), json.path("wall_seconds").asDouble());
        return json;
    }

    /** Seed per (fault cell, run), shared across variants: the paired design. */
    static long seed(String faults, int run) {
        return new SplittableRandom(BASE_SEED ^ ((long) faults.hashCode() << 16) ^ run).nextLong() & Long.MAX_VALUE;
    }

    /** The orchestrator's per-service assertion of the active set (S08-T04), read from each service's own log. */
    private static ObjectNode observedSwitches(Cell cell) {
        ObjectNode observed = Stack.JSON.createObjectNode();
        var owned = Map.of("order-service", List.of("A2", "A4"), "ledger-service", List.of("A1", "A4", "F2"),
                "instrument-service", List.of("A3", "A5", "F3"));
        owned.forEach((service, ids) -> {
            String line = ChaosStack.chaosLine(service, "ZS-CHAOS service=" + service + " active=");
            String expected = "ZS-CHAOS service=" + service + " active="
                    + ids.stream().filter(cell.switches()::contains).sorted().toList();
            if (!expected.equals(line)) {
                throw new IllegalStateException(service + " started with '" + line + "', expected '" + expected + "'");
            }
            observed.put(service, line);
        });
        String triggers = ChaosStack.chaosLine("order-service", "zero-sum-triggers-enabled=");
        boolean wanted = !cell.switches().contains("A4");
        if (triggers == null || !triggers.contains("zero-sum-triggers-enabled=" + wanted)) {
            throw new IllegalStateException("zero-sum trigger state: " + triggers);
        }
        observed.put("order-service zero-sum triggers", triggers);
        return observed;
    }

    // --- faults -----------------------------------------------------------------------------------------------------

    /** F7, F8 (FakeCard's webhook knobs: F8 as specified is FakeBank's, and no payout runs here) or both for F12. */
    private static String fakeCardProfile(String faults, long seed) {
        String f7 = "\"timeout_after_commit_rate\":0.2,\"http_500_rate\":0.05";
        String f8 = "\"webhook_duplicate_rate\":0.3,\"webhook_reorder_rate\":0.3,\"webhook_drop_rate\":0.1";
        String knobs = switch (faults) {
            case "F7" -> f7;
            case "F8c" -> f8;
            case "F12b" -> f7 + "," + f8;
            default -> null;
        };
        // The knob parser reads numbers as doubles, so the provider gets a seed that survives that exactly.
        return knobs == null ? NO_FAULTS : "{" + knobs + ",\"seed\":" + (seed & 0x7fffffff) + "}";
    }

    private static void putFakeCard(String profile) {
        HttpResponse<String> response = Stack.put(Stack.PROVIDERS + "/admin/faults/fakecard", Stack.adminToken(),
                profile);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("fakecard profile " + profile + ": " + response.statusCode() + " "
                    + response.body());
        }
    }

    /** One thread per container fault, each on its own seeded 20–40 s schedule (master §8.4 F1). */
    private static List<Thread> schedules(String faults, long seed, AtomicBoolean stop,
            List<Map<String, Object>> actions, List<String> tags) {
        var threads = new ArrayList<Thread>();
        switch (faults) {
            case "F1" -> threads.add(every("F1", seed, stop, actions, tags, () -> crash("order-service")));
            case "F2" -> threads.add(every("F2", seed, stop, actions, tags, () -> hook("ledger-service", "F2")));
            case "F4" -> threads.add(every("F4", seed, stop, actions, tags, () -> {
                Instant began = Instant.now();
                ChaosStack.restartKafka();
                return Map.of("action", "docker restart kafka", "restart_seconds", secondsSince(began));
            }));
            // Run plan 3.8: a hard broker kill held down past the producer's delivery timeout. F4's graceful restart
            // neither lost a buffered send nor dropped an offset commit, so it exercised neither A1 nor A2.
            case "F4h" -> threads.add(every("F4h", seed, stop, actions, tags, () -> {
                ChaosStack.kill("kafka");
                int exit = ChaosStack.exitCode("kafka");
                Stack.sleep(F4H_OUTAGE);
                ChaosStack.start("kafka");
                return Map.of("action", "docker kill -s KILL kafka; down " + F4H_OUTAGE.toSeconds() + " s; docker start",
                        "exit_code", exit);
            }));
            case "F12b" -> {
                threads.add(every("F1", seed, stop, actions, tags, () -> crash("order-service")));
                threads.add(every("F3", seed, stop, actions, tags, () -> hook("instrument-service", "F3")));
            }
            default -> {
                // F7, F8c and F11v are provider profiles and workload properties, not container actions
            }
        }
        return threads;
    }

    private static Thread every(String fault, long seed, AtomicBoolean stop, List<Map<String, Object>> actions,
            List<String> tags, Supplier<Map<String, Object>> action) {
        var random = new SplittableRandom(seed ^ fault.hashCode());
        return Thread.ofPlatform().name("fault-" + fault).start(() -> {
            while (true) {
                Instant due = Instant.now().plusMillis(20_000 + random.nextInt(20_001));
                while (Instant.now().isBefore(due)) {
                    if (stop.get()) {
                        return;
                    }
                    Stack.sleep(Duration.ofMillis(200));
                }
                var record = new LinkedHashMap<String, Object>();
                record.put("fault", fault);
                record.put("started_at", Instant.now().toString());
                try {
                    record.putAll(action.get());
                } catch (RuntimeException failure) {
                    record.put("error", failure.toString());
                }
                record.put("ended_at", Instant.now().toString());
                actions.add(record);
                RunTelemetry.annotate(Instant.parse((String) record.get("started_at")),
                        Instant.parse((String) record.get("ended_at")),
                        tags.getFirst() + " " + fault + ": " + record.get("action") + " exit=" + record.get("exit_code"),
                        concatTags(tags, "zs-fault"));
            }
        });
    }

    /** F1: kill -9 and restart at once, as a supervisor would; callers retry with the same keys meanwhile. */
    private static Map<String, Object> crash(String service) {
        ChaosStack.kill(service);
        int exit = ChaosStack.exitCode(service);
        ChaosStack.start(service);
        return Map.of("action", "docker kill -s KILL " + service + "; docker start", "exit_code", exit);
    }

    /**
     * F2/F3: arm the service's crash hook, wait for it to halt the process at its crash point, restart it. If the hook
     * has not fired within the patience it is disarmed and the process killed instead, and the record says so.
     */
    private static Map<String, Object> hook(String service, String hook) {
        ChaosStack.arm(service, hook);
        boolean fired = ChaosStack.awaitExit(service, Duration.ofSeconds(60));
        if (!fired) {
            ChaosStack.docker(Duration.ofSeconds(30), "exec", ChaosStack.container(service), "rm", "-f",
                    "/tmp/zs-chaos-" + hook.toLowerCase());
            ChaosStack.kill(service);
        }
        int exit = ChaosStack.exitCode(service);
        ChaosStack.start(service);
        return Map.of("action", "arm " + hook + " hook; docker start", "hook_fired", fired, "exit_code", exit);
    }

    /** End of generation: every container back up and healthy before quiesce is measured. */
    private static void restoreAll() {
        for (String service : ChaosStack.SERVICES) {
            if (!ChaosStack.running(service)) {
                ChaosStack.start(service);
            }
        }
        ChaosStack.awaitHealthy("kafka", Duration.ofMinutes(3));
        for (String service : ChaosStack.SERVICES) {
            ChaosStack.awaitHealthy(service, Duration.ofMinutes(3));
        }
    }

    // --- quiesce and verdict ----------------------------------------------------------------------------------------

    /** Master §8.3 quiesce, bounded by the run plan's maximum wait; two consecutive quiet probes with no movement. */
    private static ObjectNode quiesce(Instant recoveryFrom) {
        Instant began = Instant.now();
        Instant deadline = began.plus(MAX_QUIESCE);
        Map<String, Long> previous = null;
        Double recoverySeconds = null;
        Map<String, Long> probe = ChaosStack.quiesceProbe();
        boolean quiesced = false;
        while (true) {
            if (recoverySeconds == null && probe.get("consumer_lag") == 0 && probe.get("orders_outbox_unpublished") == 0
                    && probe.get("instruments_outbox_unpublished") == 0) {
                recoverySeconds = Duration.between(recoveryFrom, Instant.now()).toMillis() / 1000.0;
            }
            if (previous != null && ChaosStack.quiet(previous) && ChaosStack.quiet(probe)
                    && previous.get("orders").equals(probe.get("orders"))
                    && previous.get("applied_orders").equals(probe.get("applied_orders"))) {
                quiesced = true;
                break;
            }
            if (Instant.now().isAfter(deadline)) {
                break;
            }
            previous = probe;
            Stack.sleep(Duration.ofSeconds(3));
            probe = ChaosStack.quiesceProbe();
        }
        ObjectNode out = Stack.JSON.createObjectNode();
        out.put("quiesced", quiesced);
        out.put("max_wait_seconds", MAX_QUIESCE.toSeconds());
        out.put("waited_seconds", secondsSince(began));
        if (recoverySeconds != null) {
            out.put("recovery_to_zero_lag_seconds", recoverySeconds);
        }
        out.set("last_probe", Stack.JSON.valueToTree(probe));
        out.put("stuck_attempts", probe.get("attempts_not_terminal"));
        return out;
    }

    /**
     * PASS / VIOLATION from the verifier's exit status; NOT_QUIESCED overrides both (never a pass); HARNESS_ERROR when
     * the verifier could not judge. The predicted class is judged only on quiesced, judged runs.
     */
    private static void classify(ObjectNode json, Cell cell, int exit, JsonNode verdict) {
        boolean quiesced = json.get("quiesce").get("quiesced").asBoolean();
        String classification = exit == 2 ? "HARNESS_ERROR" : !quiesced ? "NOT_QUIESCED"
                : exit == 1 ? "VIOLATION" : "PASS";
        json.put("classification", classification);
        if (verdict == null) {
            return;
        }
        Map<String, JsonNode> checks = new TreeMap<>();
        verdict.get("checks").forEach(check -> checks.put(check.get("id").asString(), check));
        json.set("violated", Stack.JSON.valueToTree(checks.values().stream()
                .filter(c -> "FAIL".equals(c.get("status").asString())).map(c -> c.get("id").asString()).toList()));
        json.put("predicted_class_observed", quiesced && predicted(cell.variant(), checks));
    }

    static boolean predicted(String variant, Map<String, JsonNode> checks) {
        return switch (variant) {
            case "A0", "A5" -> checks.values().stream().noneMatch(c -> "FAIL".equals(c.get("status").asString()));
            case "A1" -> failed(checks, "I6b");
            case "A2" -> failed(checks, "I6") && metric(checks, "I6", "missing_orders") > 0;
            case "A3" -> failed(checks, "I7") && metric(checks, "I7", "duplicate_charges") > 0;
            case "A4" -> failed(checks, "I1") || failed(checks, "I2");
            // Classes, not ids: zero-sum (I1/I2), missing orders (I6), duplicate charges (I7), and drift (I6b) only
            // when I6 passed, because missing orders also move I6b and must not count twice.
            case "B0" -> (failed(checks, "I1") || failed(checks, "I2") ? 1 : 0) + (failed(checks, "I6") ? 1 : 0)
                    + (failed(checks, "I7") ? 1 : 0) + (failed(checks, "I6b") && !failed(checks, "I6") ? 1 : 0) >= 2;
            default -> throw new IllegalArgumentException(variant);
        };
    }

    private static boolean failed(Map<String, JsonNode> checks, String id) {
        return checks.containsKey(id) && "FAIL".equals(checks.get(id).get("status").asString());
    }

    private static long metric(Map<String, JsonNode> checks, String id, String name) {
        return checks.containsKey(id) ? checks.get(id).path("metrics").path(name).asLong(0) : 0;
    }

    // --- aggregation ------------------------------------------------------------------------------------------------

    /**
     * Rebuilds summary.json from every non-pilot run JSON on disk, so the results document regenerates from raw data
     * whichever batches produced it. Validity per cell: the predicted class in at least half its judged runs, or at
     * least once for a cell named crash-timing-dependent in advance; A0 cells must show no violation at all.
     */
    static void aggregate() throws IOException {
        var byCell = new TreeMap<String, List<JsonNode>>();
        try (var files = Files.list(OUT.resolve("runs"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonNode run = Stack.JSON.readTree(file.toFile());
                if (!run.get("label").asString().startsWith("pilot-")) {
                    byCell.computeIfAbsent(run.get("cell").asString(), k -> new ArrayList<>()).add(run);
                }
            }
        }
        ObjectNode summary = Stack.JSON.createObjectNode();
        byCell.forEach((cellId, runs) -> {
            ObjectNode cell = summary.putObject(cellId);
            var classes = new TreeMap<String, Integer>();
            runs.forEach(r -> classes.merge(r.get("classification").asString(), 1, Integer::sum));
            long judged = runs.stream().filter(r -> Set.of("PASS", "VIOLATION")
                    .contains(r.get("classification").asString())).count();
            long observed = runs.stream().filter(r -> r.path("predicted_class_observed").asBoolean(false)).count();
            boolean control = cellId.startsWith("A0/");
            boolean timing = TIMING_DEPENDENT.contains(cellId);
            cell.put("runs", runs.size());
            cell.set("classifications", Stack.JSON.valueToTree(classes));
            cell.put("judged_runs", judged);
            cell.put("predicted_class_observed", observed);
            if (cellId.startsWith("A5/")) {
                // Master §8.5: A5 predicts no violation and is reported either way; the validity rule does not apply.
                cell.put("threshold", "reported only (predicted: no violation)");
            } else {
                cell.put("threshold", control ? "all runs PASS" : timing ? ">= 1 run" : ">= 50% of runs");
                cell.put("valid", control ? observed == runs.size()
                        : timing ? observed >= 1 : observed * 2 >= runs.size());
            }
            var violations = new TreeMap<String, Integer>();
            runs.forEach(r -> r.path("violated").forEach(v -> violations.merge(v.asString(), 1, Integer::sum)));
            cell.set("runs_violating_by_invariant", Stack.JSON.valueToTree(violations));
        });
        write(OUT.resolve("summary.json"), summary.toPrettyString());
        System.out.println("ZS-ABLATION summary\n" + summary.toPrettyString());
    }

    // --- small helpers ----------------------------------------------------------------------------------------------

    private static Map<String, String> images() {
        var images = new TreeMap<String, String>();
        for (String service : ChaosStack.SERVICES) {
            images.put(service + " image", ChaosStack.image(service));
        }
        return images;
    }

    private static List<String> concatTags(List<String> tags, String extra) {
        var all = new ArrayList<>(tags);
        all.add(extra);
        return all;
    }

    private static double secondsSince(Instant from) {
        return Duration.between(from, Instant.now()).toMillis() / 1000.0;
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
