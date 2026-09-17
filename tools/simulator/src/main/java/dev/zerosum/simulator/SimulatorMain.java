package dev.zerosum.simulator;

import dev.zerosum.evidence.DotEnv;
import dev.zerosum.evidence.Json;
import dev.zerosum.evidence.Provenance;
import dev.zerosum.money.OrderCandidate.Entry;
import dev.zerosum.simulator.Scenario.GeneratedOrder;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The seeded scenario runner (M13 (a)): posts money orders through order-service's real HTTP API for N seeded runs,
 * waits for the ledger to apply them, and writes JSON and Markdown evidence to {@code docs/results/}.
 *
 * <p>decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness.
 *
 * <p><strong>It drives the real money path, not a shortcut into it.</strong> Orders go over HTTP to the public API
 * with an idempotency key and a writer token, exactly as any client would, and "applied" means the balance was read
 * back from ledger-service — so the run genuinely crosses the outbox, the broker and the apply engine. S04 found
 * three defects that a fully green test suite could not see because nothing crossed a real deployment; a harness
 * that called the engine directly would reintroduce that blind spot.
 *
 * <p><strong>Every run records its seed.</strong> Run {@code r} of base seed {@code S} uses
 * {@link Scenario#runSeed(long, int)}, and the same seed produces byte-identical bodies and identical idempotency
 * keys. Re-running a seed is therefore a replay, not a duplicate: order-service answers 200 with
 * {@code Idempotent-Replayed: true}, and the evidence records created and replayed separately.
 *
 * <p>Exit status: {@code 0} every order applied and the ledger's invariants hold, {@code 1} the scenario failed
 * (something was not applied in time, a balance disagreed, or the ledger reported itself inconsistent), {@code 2} a
 * harness failure (bad arguments, missing token, a service that refused or could not be reached). The three are
 * distinct because "the stack was not up" is not a result about the money path.
 */
public final class SimulatorMain {

    static final int PASSED = 0;
    static final int SCENARIO_FAILED = 1;
    static final int HARNESS_ERROR = 2;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String USAGE = """
            usage: simulator --scenario <name> --runs <N> --seed <S> [options]

              --scenario <name>         scenario to run                                (required)
              --runs <N>                number of seeded runs                          (required)
              --seed <S>                base seed; run r uses seed + 31*r              (required)
              --orders-url <url>        order-service                                  (default: http://127.0.0.1:8081)
              --ledger-url <url>        ledger-service                                 (default: http://127.0.0.1:8082)
              --env-file <path>         .env holding the API tokens                    (default: ./.env)
              --out <dir>               where the JSON and Markdown are written        (default: docs/results/m13)
              --label <name>            base name of the written files                 (default: the scenario name)
              --apply-timeout-seconds   how long to wait for an order to be applied    (default: 60)
              --poll-millis             balance polling interval                       (default: 250)

            scenarios: %s

            exit status: 0 applied and consistent · 1 scenario failed · 2 harness failure""";

    private SimulatorMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    /** Package-private so tests drive the real entry point rather than a copy of its logic. */
    static int run(String[] args, PrintStream out) {
        Map<String, String> options;
        Scenario scenario;
        int runs;
        long baseSeed;
        try {
            options = parse(args);
            scenario = Scenario.byName(options.get("scenario"))
                    .orElseThrow(() -> new IllegalArgumentException("unknown scenario: " + options.get("scenario")));
            runs = Integer.parseInt(options.get("runs"));
            baseSeed = Long.parseLong(options.get("seed"));
            if (runs < 1) {
                throw new IllegalArgumentException("--runs must be at least 1: " + runs);
            }
        } catch (IllegalArgumentException e) {
            out.println("simulator: " + e.getMessage());
            out.println();
            out.println(USAGE.formatted(Scenario.catalogNames()));
            return HARNESS_ERROR;
        }

        String ordersUrl = options.getOrDefault("orders-url", "http://127.0.0.1:8081");
        String ledgerUrl = options.getOrDefault("ledger-url", "http://127.0.0.1:8082");
        String label = options.getOrDefault("label", scenario.name());
        Path envFile = Path.of(options.getOrDefault("env-file", ".env"));
        Duration applyTimeout = Duration.ofSeconds(Long.parseLong(options.getOrDefault("apply-timeout-seconds", "60")));
        Duration pollInterval = Duration.ofMillis(Long.parseLong(options.getOrDefault("poll-millis", "250")));

        String writerToken;
        String readerToken;
        try {
            DotEnv env = DotEnv.read(envFile);
            // decision: D03-4 — the writer token carries the source system it authenticates as.
            writerToken = env.requireFirstWriterToken("ZS_WRITER_TOKENS", envFile);
            readerToken = env.require("ZS_READER_TOKEN", envFile);
        } catch (RuntimeException e) {
            out.println("simulator: " + e.getMessage());
            return HARNESS_ERROR;
        }

        Instant startedAt = Instant.now();
        out.printf("ZS-SIM start scenario=%s runs=%d seed=%d orders=%s ledger=%s at=%s%n",
                scenario.name(), runs, baseSeed, ordersUrl, ledgerUrl, startedAt);

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var runOutcomes = new ArrayList<RunOutcome>();
        JsonNode invariants;
        try {
            for (int run = 1; run <= runs; run++) {
                long runSeed = Scenario.runSeed(baseSeed, run);
                out.printf("ZS-SIM run=%d seed=%d%n", run, runSeed);
                runOutcomes.add(runOne(http, scenario, run, runSeed, ordersUrl, ledgerUrl, writerToken, readerToken,
                        applyTimeout, pollInterval, out));
            }
            // The ledger's own I2-I4 report, taken once at the end. tools/verifier is the independent check; this is
            // the service's opinion of itself, and the two disagreeing is itself a finding.
            invariants = getJson(http, ledgerUrl + "/v1/invariants", readerToken);
        } catch (HarnessFailure e) {
            out.println("ZS-SIM harness failure: " + e.getMessage());
            out.println("ZS-SIM seeds so far: " + runOutcomes.stream().map(r -> String.valueOf(r.seed()))
                    .collect(Collectors.joining(", ")));
            return HARNESS_ERROR;
        }

        boolean consistent = invariants.get("consistent").asBoolean();
        long quarantined = invariants.get("unresolved_quarantined_count").asLong();
        boolean allRunsPassed = runOutcomes.stream().allMatch(RunOutcome::passed);
        boolean passed = allRunsPassed && consistent && quarantined == 0;

        runOutcomes.forEach(outcome -> out.println("ZS-SIM " + outcome.summary()));
        out.printf("ZS-SIM ledger invariants consistent=%s unresolved_quarantined=%d%n", consistent, quarantined);
        out.printf("ZS-SIM result %s%n", passed ? "PASS" : "FAIL");

        Provenance provenance = Provenance.capture(Path.of("."),
                runOutcomes.stream().map(RunOutcome::seed).toList(), Map.of());
        try {
            Path directory = Path.of(options.getOrDefault("out", "docs/results/m13"));
            Files.createDirectories(directory);
            Path json = directory.resolve(label + ".json");
            Path markdown = directory.resolve(label + ".md");
            Files.writeString(json, json(scenario, baseSeed, runOutcomes, invariants, passed, startedAt, provenance,
                    ordersUrl, ledgerUrl));
            Files.writeString(markdown, markdown(label, scenario, baseSeed, runOutcomes, consistent, quarantined,
                    passed, startedAt, provenance, ordersUrl, ledgerUrl, args));
            out.println("ZS-SIM wrote " + json + " and " + markdown);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return passed ? PASSED : SCENARIO_FAILED;
    }

    private static RunOutcome runOne(HttpClient http, Scenario scenario, int run, long runSeed, String ordersUrl,
            String ledgerUrl, String writerToken, String readerToken, Duration applyTimeout, Duration pollInterval,
            PrintStream out) {
        List<GeneratedOrder> orders = scenario.orders(runSeed);
        var outcomes = new ArrayList<OrderOutcome>(orders.size());

        for (GeneratedOrder generated : orders) {
            String body = generated.toRequestBody(scenario.name(), runSeed);
            Posted posted = post(http, ordersUrl, generated.idempotencyKey(), body, writerToken);
            Entry rider = generated.riderEntry();

            Instant postedAt = Instant.now();
            boolean applied = awaitBalance(http, ledgerUrl, rider.entityId(), rider.account(), rider.amountMinor(),
                    readerToken, applyTimeout, pollInterval);
            long appliedAfterMillis = Duration.between(postedAt, Instant.now()).toMillis();

            if (!applied) {
                out.printf("ZS-SIM   NOT APPLIED within %s: %s %s expected signed_minor=%d (order %s)%n",
                        applyTimeout, rider.entityId(), rider.account(), rider.amountMinor(), posted.orderId());
            }
            outcomes.add(new OrderOutcome(generated.idempotencyKey(), posted.orderId(), posted.status(),
                    posted.attempts(), rider.entityId(), rider.amountMinor(), applied,
                    applied ? appliedAfterMillis : -1));
        }

        // Drivers are shared by the trips of one run (and only that run: entities are namespaced by seed), so their
        // balance is checked against the sum of this run's deltas rather than per order. platform:main is shared
        // across every run by design, so no exact balance is asserted for it here; the global per-currency sum is
        // what covers it, and that is tools/verifier's I2.
        var expectedDriverBalances = new TreeMap<String, Long>();
        for (GeneratedOrder generated : orders) {
            for (Entry entry : generated.order().entries()) {
                if (entry.entityId().startsWith("driver:")) {
                    expectedDriverBalances.merge(entry.entityId() + "|" + entry.account(), entry.amountMinor(), Long::sum);
                }
            }
        }
        var driverChecks = new ArrayList<BalanceCheck>();
        expectedDriverBalances.forEach((key, expected) -> {
            String entityId = key.substring(0, key.indexOf('|'));
            String account = key.substring(key.indexOf('|') + 1);
            // Polled rather than read once. The driver's entry is applied in the same transaction as the rider's, so
            // it is there by the time the rider's balance is visible — but the read that proves it is a separate
            // request, and a single-shot read turns ordinary lag into a reported balance mismatch. This was a real
            // defect: the first version of this check failed against a stack that was behaving correctly.
            awaitBalance(http, ledgerUrl, entityId, account, expected, readerToken, applyTimeout, pollInterval);
            driverChecks.add(new BalanceCheck(entityId, account, expected,
                    readBalance(http, ledgerUrl, entityId, account, readerToken)));
        });

        return new RunOutcome(run, runSeed, List.copyOf(outcomes), List.copyOf(driverChecks));
    }

    private static Posted post(HttpClient http, String ordersUrl, String idempotencyKey, String body, String token) {
        int attempts = 0;
        RuntimeException last = null;
        // decision: S08-T01 — retries reuse the same key AND the same body; a new key is never minted for a retry,
        // because that is exactly how a retry turns into a second charge.
        while (attempts < 5) {
            attempts++;
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(URI.create(ordersUrl + "/v1/money-orders"))
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", idempotencyKey)
                                .header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(10))
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();
                if (status == 201 || status == 200) {
                    return new Posted(JSON.readTree(response.body()).get("order_id").asString(),
                            status == 201 ? "created" : "replayed", attempts);
                }
                if (status == 409) {
                    // idempotency_key_in_progress (D03-2): another attempt of this same key is mid-flight. Retryable.
                    last = new HarnessFailure("409 in progress for " + idempotencyKey);
                } else if (status >= 500) {
                    last = new HarnessFailure(status + " from order-service: " + response.body());
                } else {
                    // 400/401/403/422 are definitive. A 422 means this tool generated a body the service rejects,
                    // which is a simulator bug; retrying it would only produce the same rejection more slowly.
                    throw new HarnessFailure("order-service refused " + idempotencyKey + " with " + status + ": "
                            + response.body());
                }
            } catch (IOException e) {
                last = new HarnessFailure("transport error posting " + idempotencyKey + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new HarnessFailure("interrupted while posting " + idempotencyKey);
            }
            sleep(Duration.ofMillis(200L * attempts));
        }
        throw new HarnessFailure("gave up posting " + idempotencyKey + " after " + attempts + " attempts: " + last);
    }

    /** Polls until the ledger holds the expected balance, because the path between the two services is asynchronous. */
    private static boolean awaitBalance(HttpClient http, String ledgerUrl, String entityId, String account,
            long expectedSigned, String token, Duration timeout, Duration pollInterval) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            Long actual = readBalance(http, ledgerUrl, entityId, account, token);
            if (actual != null && actual == expectedSigned) {
                return true;
            }
            if (!Instant.now().isBefore(deadline)) {
                return false;
            }
            sleep(pollInterval);
        }
    }

    /** The stored signed balance, or null when the entity or account is not in the ledger yet. */
    private static Long readBalance(HttpClient http, String ledgerUrl, String entityId, String account, String token) {
        HttpResponse<String> response = send(http, ledgerUrl + "/v1/entities/" + entityId + "/balances", token);
        if (response.statusCode() == 404) {
            return null;   // nothing applied for this entity yet; expected while the order is in flight
        }
        if (response.statusCode() != 200) {
            throw new HarnessFailure("ledger-service returned " + response.statusCode() + " for " + entityId + ": "
                    + response.body());
        }
        for (JsonNode entry : JSON.readTree(response.body()).get("accounts")) {
            if (account.equals(entry.get("account").asString())) {
                return entry.get("signed_minor").asLong();
            }
        }
        return null;
    }

    private static JsonNode getJson(HttpClient http, String url, String token) {
        HttpResponse<String> response = send(http, url, token);
        if (response.statusCode() != 200) {
            throw new HarnessFailure("GET " + url + " returned " + response.statusCode() + ": " + response.body());
        }
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> send(HttpClient http, String url, String token) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(10))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new HarnessFailure("is the stack running? GET " + url + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessFailure("interrupted during GET " + url);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessFailure("interrupted while waiting");
        }
    }

    private static String json(Scenario scenario, long baseSeed, List<RunOutcome> runs, JsonNode invariants,
            boolean passed, Instant startedAt, Provenance provenance, String ordersUrl, String ledgerUrl) {
        String runJson = runs.stream().map(RunOutcome::toJson).collect(Collectors.joining(",\n"));
        return "{\n"
                + "  \"tool\": \"tools/simulator\",\n"
                + "  \"scenario\": " + Json.quote(scenario.name()) + ",\n"
                + "  \"scenario_description\": " + Json.quote(scenario.description()) + ",\n"
                + "  \"base_seed\": " + baseSeed + ",\n"
                + "  \"runs\": " + runs.size() + ",\n"
                + "  \"trips_per_run\": " + scenario.tripsPerRun() + ",\n"
                + "  \"commission_bps\": " + scenario.commissionBps() + ",\n"
                + "  \"currency\": " + Json.quote(scenario.currency()) + ",\n"
                + "  \"started_at\": " + Json.quote(startedAt.toString()) + ",\n"
                + "  \"orders_url\": " + Json.quote(ordersUrl) + ",\n"
                + "  \"ledger_url\": " + Json.quote(ledgerUrl) + ",\n"
                + "  \"run_results\": [\n" + runJson + "\n  ],\n"
                + "  \"ledger_invariants\": " + invariants + ",\n"
                + "  \"passed\": " + passed + ",\n"
                + "  \"provenance\": " + provenance.toJson() + "\n"
                + "}\n";
    }

    /** The result document, laid out as docs/results/TEMPLATE.md requires; no section is dropped. */
    private static String markdown(String label, Scenario scenario, long baseSeed, List<RunOutcome> runs,
            boolean consistent, long quarantined, boolean passed, Instant startedAt, Provenance provenance,
            String ordersUrl, String ledgerUrl, String[] args) {
        var md = new StringBuilder(4096);
        // The heading states what ran, not what it ran against: the tool is pointed at whatever URLs it is given and
        // cannot verify that they are the real stack. Section 5 records the actual targets, and claiming "the real
        // money path" in a generated title would be asserting something the tool does not know.
        md.append("# ").append(label).append(" — seeded scenario run\n\n")
                .append("<!--\ndecision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness\n")
                .append("Written by tools/simulator. Do not edit by hand: re-run the tool with the seed below, which "
                        + "reproduces the same orders and recaptures the provenance block.\n-->\n\n");

        md.append("## 1. Evidence ID and type\n\n| Field | Value |\n|---|---|\n")
                .append("| Evidence ID | ").append(label).append(" |\n")
                .append("| Type | verification (seeded scenario run) |\n")
                .append("| Owning step and task | M13 (a), M13 (c) — evidence harness |\n")
                .append("| Date (UTC) | ").append(startedAt.toString(), 0, 10).append(" |\n\n");

        md.append("## 2. Status\n\n- **Measured**\n\n");

        md.append("## 3. Provenance (master §3.1 M13 c)\n\n")
                .append(provenance.markdownTable("../../adr/0002-stack-and-pinned-versions.md")).append('\n');

        md.append("## 4. Host and Docker allocation\n\n| Item | Value |\n|---|---|\n")
                .append("| Docker engine / Compose version | not captured by the tool: it speaks HTTP to two services "
                        + "and does not know how they are hosted |\n")
                .append("| Docker VM CPUs / memory | not captured; see above |\n")
                .append("| Emulated images (non-native architecture) | not captured; see above |\n")
                .append("| Other load on the host during the run | not captured. The apply times below are wall-clock "
                        + "observations, not an isolated measurement, and must not be read as a latency benchmark |\n\n");

        md.append("## 5. Scenario, workload and seeds\n\n").append(scenario.description()).append("\n\n")
                .append("Target: order-service `").append(ordersUrl).append("`, ledger-service `").append(ledgerUrl)
                .append("`.\n\n")
                .append("Parameters: **").append(runs.size()).append(" runs × ").append(scenario.tripsPerRun())
                .append(" trips**, commission ").append(scenario.commissionBps()).append(" bps, currency ")
                .append(scenario.currency()).append(". Orders come from `TripSequenceGenerator` (D01-10, ADR-0009); ")
                .append("riders and drivers are namespaced by the run seed so repeated runs never share an entity, ")
                .append("while `platform:main` is deliberately shared, as it is in production.\n\n")
                .append("Base seed **").append(baseSeed).append("**; run *r* uses `seed + 31*r`. Every seed: ")
                .append(provenance.seedsText()).append(".\n\n");

        md.append("## 6. Exact commands\n\n```sh\n./gradlew :tools:simulator:run --args=\"")
                .append(String.join(" ", args)).append("\"\n```\n\n");

        md.append("## 7. Raw data\n\n[").append(label).append(".json](").append(label)
                .append(".json) — the same run, with every order's idempotency key, order id, response class and ")
                .append("observed apply time.\n\n");

        md.append("## 8. Results\n\n")
                .append("| Run | Seed | Orders | Created | Replayed | Applied | Max observed apply |\n|---|---|---|---|---|---|---|\n");
        for (RunOutcome run : runs) {
            md.append("| ").append(run.run()).append(" | ").append(run.seed()).append(" | ")
                    .append(run.orders().size()).append(" | ").append(run.created()).append(" | ")
                    .append(run.replayed()).append(" | ").append(run.appliedCount()).append('/')
                    .append(run.orders().size()).append(" | ").append(run.maxAppliedMillis()).append(" ms |\n");
        }
        md.append('\n');

        md.append("Driver balances, checked against the sum of each run's own deltas:\n\n")
                .append("| Run | Entity | Account | Expected | In the ledger | Match |\n|---|---|---|---|---|---|\n");
        for (RunOutcome run : runs) {
            for (BalanceCheck check : run.driverChecks()) {
                md.append("| ").append(run.run()).append(" | ").append(check.entityId()).append(" | ")
                        .append(check.account()).append(" | ").append(check.expectedSigned()).append(" | ")
                        .append(check.actualSigned() == null ? "absent" : check.actualSigned()).append(" | ")
                        .append(check.matches() ? "yes" : "**NO**").append(" |\n");
            }
        }
        md.append('\n').append("Ledger's own invariants endpoint after the last run: `consistent = ").append(consistent)
                .append("`, `unresolved_quarantined_count = ").append(quarantined).append("`.\n\n")
                .append("**Overall: ").append(passed ? "PASS" : "FAIL").append("**\n\n");

        md.append("## 9. Gate or threshold compared against\n\n")
                .append("M13 (a) in [master §3.1](../../zerosum_ledger_mvp_plan.md#must-have): a single command runs a ")
                .append("named scenario for N seeded runs and writes JSON + Markdown to `docs/results/`. **")
                .append(passed ? "Pass" : "Miss").append("**\n\n");

        md.append("## 10. Deviations and limitations\n\n")
                .append("- **Apply time is a polling observation, not a latency measurement.** It is the wall-clock gap "
                        + "between the POST returning and the first successful balance poll, so it is quantised to the "
                        + "poll interval and includes this tool's own scheduling. P2 and T1 in S07 are the latency "
                        + "evidence; these figures are not.\n")
                .append("- **`platform:main` is not balance-checked.** It is shared across every run, so it has no "
                        + "expected value here. The global per-currency sum covers it, and that is `tools/verifier`'s "
                        + "I2 — run it after this.\n")
                .append("- **The ledger's invariants line is the service's opinion of itself**, read from its own "
                        + "endpoint. The independent check is `tools/verifier`, which reads the database directly as "
                        + "the read-only role.\n")
                .append("- **One scenario shape.** W1 only: no adjustments, refunds, payouts or provider path, and no "
                        + "injected faults. Those are S08's ablations and fault matrix, which are not built.\n");
        return md.toString();
    }

    private static Map<String, String> parse(String[] args) {
        var options = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + argument);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(argument + " needs a value");
            }
            options.put(argument.substring(2), args[++i]);
        }
        for (String required : List.of("scenario", "runs", "seed")) {
            if (!options.containsKey(required)) {
                throw new IllegalArgumentException("--" + required + " is required");
            }
        }
        return options;
    }

    /** A failure of the harness or its environment, never a finding about the system under test. */
    static final class HarnessFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        HarnessFailure(String message) {
            super(message);
        }
    }

    private record Posted(String orderId, String status, int attempts) {
    }

    private record OrderOutcome(String idempotencyKey, String orderId, String status, int attempts, String entityId,
            long expectedSigned, boolean applied, long appliedAfterMillis) {

        String toJson() {
            return "{\"idempotency_key\": " + Json.quote(idempotencyKey)
                    + ", \"order_id\": " + Json.quote(orderId)
                    + ", \"response\": " + Json.quote(status)
                    + ", \"post_attempts\": " + attempts
                    + ", \"awaited_entity\": " + Json.quote(entityId)
                    + ", \"expected_signed_minor\": " + expectedSigned
                    + ", \"applied\": " + applied
                    + ", \"observed_apply_millis\": " + appliedAfterMillis + "}";
        }
    }

    private record BalanceCheck(String entityId, String account, long expectedSigned, Long actualSigned) {

        boolean matches() {
            return actualSigned != null && actualSigned == expectedSigned;
        }

        String toJson() {
            return "{\"entity_id\": " + Json.quote(entityId) + ", \"account\": " + Json.quote(account)
                    + ", \"expected_signed_minor\": " + expectedSigned
                    + ", \"ledger_signed_minor\": " + actualSigned
                    + ", \"matches\": " + matches() + "}";
        }
    }

    private record RunOutcome(int run, long seed, List<OrderOutcome> orders, List<BalanceCheck> driverChecks) {

        long created() {
            return orders.stream().filter(order -> "created".equals(order.status())).count();
        }

        long replayed() {
            return orders.stream().filter(order -> "replayed".equals(order.status())).count();
        }

        long appliedCount() {
            return orders.stream().filter(OrderOutcome::applied).count();
        }

        long maxAppliedMillis() {
            return orders.stream().filter(OrderOutcome::applied).mapToLong(OrderOutcome::appliedAfterMillis).max().orElse(-1);
        }

        boolean passed() {
            return appliedCount() == orders.size() && driverChecks.stream().allMatch(BalanceCheck::matches);
        }

        String summary() {
            return "run=" + run + " seed=" + seed + " orders=" + orders.size() + " created=" + created()
                    + " replayed=" + replayed() + " applied=" + appliedCount() + " maxApplyMs=" + maxAppliedMillis()
                    + " driverBalancesMatch=" + driverChecks.stream().allMatch(BalanceCheck::matches)
                    + " passed=" + passed();
        }

        String toJson() {
            return "    {\"run\": " + run + ", \"seed\": " + seed
                    + ", \"created\": " + created() + ", \"replayed\": " + replayed()
                    + ", \"applied\": " + appliedCount() + ", \"passed\": " + passed()
                    + ", \"orders\": [" + orders.stream().map(OrderOutcome::toJson).collect(Collectors.joining(", "))
                    + "], \"driver_balance_checks\": ["
                    + driverChecks.stream().map(BalanceCheck::toJson).collect(Collectors.joining(", ")) + "]}";
        }
    }
}
