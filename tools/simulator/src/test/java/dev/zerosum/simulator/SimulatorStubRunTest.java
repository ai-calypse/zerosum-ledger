package dev.zerosum.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The simulator CLI end to end against a stub that speaks the two service APIs.
 *
 * <p><strong>What this proves and what it does not.</strong> It proves the command runs a named scenario for N seeded
 * runs, that a repeated seed produces byte-identical bodies (the stub answers them as replays, exactly as
 * order-service would), that a JSON and a Markdown file are written with the M13 (c) provenance block, and — the
 * case that matters most — that the command <em>fails</em> when orders are never applied. It does not prove anything
 * about the money path: nothing here crosses an outbox, a broker or the apply engine. A run against the real stack
 * is separate evidence, and is recorded as such.
 */
class SimulatorStubRunTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path output;

    @Test
    void aSeededScenarioRunPostsAppliesAndWritesBothFiles() throws Exception {
        try (StubStack stub = StubStack.applying()) {
            Captured result = run(stub, "--scenario", "w1-trip-completed", "--runs", "2", "--seed", "4242");

            assertEquals(SimulatorMain.PASSED, result.exitCode(), result.output());
            assertTrue(result.output().contains("ZS-SIM result PASS"), result.output());
            assertEquals(6, stub.created.get(), "2 runs × 3 trips were created: " + result.output());

            // M13 (a): a single command wrote both a JSON and a Markdown file.
            String json = Files.readString(output.resolve("w1-trip-completed.json"));
            String markdown = Files.readString(output.resolve("w1-trip-completed.md"));
            JsonNode parsed = JSON.readTree(json);

            assertEquals(2, parsed.get("run_results").size());
            assertTrue(parsed.get("passed").asBoolean(), json);

            // Every run records its own seed, and the seeds are the documented derivation of the base seed.
            assertEquals(Scenario.runSeed(4242, 1), parsed.get("run_results").get(0).get("seed").asLong());
            assertEquals(Scenario.runSeed(4242, 2), parsed.get("run_results").get(1).get("seed").asLong());

            // M13 (c): hardware, versions, git SHA and seeds.
            JsonNode provenance = parsed.get("provenance");
            assertTrue(provenance.has("git_sha") && provenance.has("hardware") && provenance.has("working_tree"), json);
            assertEquals(2, provenance.get("seeds").size(), "one recorded seed per run");
            assertTrue(markdown.contains("## 3. Provenance (master §3.1 M13 c)"), markdown);
            assertTrue(markdown.contains(String.valueOf(Scenario.runSeed(4242, 1))),
                    "the report carries the seed that reproduces it");
            assertTrue(markdown.contains("**Overall: PASS**"), markdown);

            // Kept outside the temp directory, and printed, so the run's real artifacts and real output can be
            // inspected and quoted as evidence. build/ is git-ignored, so CI leaves no diff behind.
            Path sample = Path.of("build", "m13-sample");
            Files.createDirectories(sample);
            Files.copy(output.resolve("w1-trip-completed.json"), sample.resolve("w1-trip-completed.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(output.resolve("w1-trip-completed.md"), sample.resolve("w1-trip-completed.md"),
                    StandardCopyOption.REPLACE_EXISTING);
            System.out.println("ZS-SIM-EVIDENCE stub run:\n" + result.output());
        }
    }

    @Test
    void theSameSeedRerunsAsAReplayRatherThanADuplicate() throws Exception {
        try (StubStack stub = StubStack.applying()) {
            assertEquals(SimulatorMain.PASSED,
                    run(stub, "--scenario", "w1-trip-completed", "--runs", "1", "--seed", "77").exitCode());
            Captured second = run(stub, "--scenario", "w1-trip-completed", "--runs", "1", "--seed", "77",
                    "--label", "second");

            assertEquals(SimulatorMain.PASSED, second.exitCode(), second.output());
            assertEquals(3, stub.created.get(), "the second invocation created nothing new");
            assertEquals(3, stub.replayed.get(), "it replayed all three, so the bodies were byte-identical");
            assertEquals(0, stub.bodyMismatches.get(),
                    "a body that differed would have been a 422 here, exactly as order-service would answer");
            assertTrue(second.output().contains("replayed=3"), second.output());
        }
    }

    @Test
    void aDifferentSeedProducesDifferentOrders() throws Exception {
        try (StubStack stub = StubStack.applying()) {
            run(stub, "--scenario", "w1-trip-completed", "--runs", "1", "--seed", "77");
            run(stub, "--scenario", "w1-trip-completed", "--runs", "1", "--seed", "78", "--label", "other");

            assertEquals(6, stub.created.get(), "a different seed shares no idempotency key with the first");
            assertEquals(0, stub.replayed.get());
        }
    }

    @Test
    void anOrderThatIsNeverAppliedFailsTheRun() throws Exception {
        try (StubStack stub = StubStack.acceptingButNeverApplying()) {
            Captured result = run(stub, "--scenario", "w1-trip-completed", "--runs", "1", "--seed", "5",
                    "--apply-timeout-seconds", "1", "--poll-millis", "100");

            // The case that makes the other assertions worth anything: accepted is not applied.
            assertEquals(SimulatorMain.SCENARIO_FAILED, result.exitCode(), result.output());
            assertTrue(result.output().contains("NOT APPLIED"), result.output());
            assertTrue(result.output().contains("ZS-SIM result FAIL"), result.output());
            assertFalse(JSON.readTree(Files.readString(output.resolve("w1-trip-completed.json")))
                    .get("passed").asBoolean(), "the written evidence records the failure too");
        }
    }

    @Test
    void aRefusedTokenIsAHarnessFailureAndNeverAResult() throws Exception {
        try (StubStack stub = StubStack.refusingAuth()) {
            Captured result = run(stub, "--scenario", "w1-trip-completed", "--runs", "1", "--seed", "5");

            // Distinct from a scenario failure: an unauthenticated run says nothing about the money path.
            assertEquals(SimulatorMain.HARNESS_ERROR, result.exitCode(), result.output());
            assertTrue(result.output().contains("harness failure"), result.output());
            assertFalse(Files.exists(output.resolve("w1-trip-completed.json")),
                    "nothing is written as evidence when the run never happened");
        }
    }

    @Test
    void anUnknownScenarioIsRejectedWithTheCatalog() {
        var captured = new java.io.ByteArrayOutputStream();
        int exitCode = SimulatorMain.run(new String[] {"--scenario", "w9-nope", "--runs", "1", "--seed", "1"},
                new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));

        assertEquals(SimulatorMain.HARNESS_ERROR, exitCode);
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("w1-trip-completed"),
                "the usage names the scenarios that do exist");
    }

    private Captured run(StubStack stub, String... args) throws IOException {
        Path envFile = output.resolve("throwaway.env");
        Files.writeString(envFile, "ZS_WRITER_TOKENS=trip-simulator:writer-token\nZS_READER_TOKEN=reader-token\n");

        var full = new java.util.ArrayList<>(List.of(args));
        full.addAll(List.of("--orders-url", stub.baseUrl(), "--ledger-url", stub.baseUrl(),
                "--env-file", envFile.toString(), "--out", output.toString()));

        var captured = new java.io.ByteArrayOutputStream();
        var out = new java.io.PrintStream(captured, true, StandardCharsets.UTF_8);
        int exitCode = SimulatorMain.run(full.toArray(String[]::new), out);
        out.flush();
        return new Captured(exitCode, captured.toString(StandardCharsets.UTF_8));
    }

    private record Captured(int exitCode, String output) {
    }

    /**
     * A stub of order-service and ledger-service: enough of both APIs for the simulator to drive, with a tiny
     * in-memory ledger so a posted order shows up as a balance.
     *
     * <p>The first balance read of any entity answers 404, so the simulator's polling path is exercised rather than
     * short-circuited by an answer that was ready before the question.
     */
    private static final class StubStack implements AutoCloseable {

        private final HttpServer server;
        private final Map<String, String> bodyByKey = new ConcurrentHashMap<>();
        private final Map<String, String> orderIdByKey = new ConcurrentHashMap<>();
        private final Map<String, Long> balances = new ConcurrentHashMap<>();
        private final java.util.Set<String> alreadyPolled = ConcurrentHashMap.newKeySet();
        private final boolean applies;
        private final boolean authorizes;

        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger replayed = new AtomicInteger();
        final AtomicInteger bodyMismatches = new AtomicInteger();

        static StubStack applying() throws IOException {
            return new StubStack(true, true);
        }

        static StubStack acceptingButNeverApplying() throws IOException {
            return new StubStack(false, true);
        }

        static StubStack refusingAuth() throws IOException {
            return new StubStack(true, false);
        }

        private StubStack(boolean applies, boolean authorizes) throws IOException {
            this.applies = applies;
            this.authorizes = authorizes;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                if (!authorizes) {
                    respond(exchange, 401, "{\"code\":\"unauthorized\"}");
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                if ("POST".equals(exchange.getRequestMethod()) && path.equals("/v1/money-orders")) {
                    createOrder(exchange);
                } else if (path.equals("/v1/invariants")) {
                    respond(exchange, 200, "{\"consistent\":true,\"i2_non_zero_currencies\":[],\"i3_violations\":[],"
                            + "\"i4_violations\":[],\"i5\":{\"evaluated\":false,\"reason\":\"stub\"},"
                            + "\"unresolved_quarantined_count\":0,\"non_zero_clearing_balances\":[]}");
                } else if (path.startsWith("/v1/entities/") && path.endsWith("/balances")) {
                    readBalances(exchange, path.substring("/v1/entities/".length(), path.length() - "/balances".length()));
                } else {
                    respond(exchange, 404, "{\"code\":\"not_found\"}");
                }
            } catch (RuntimeException e) {
                respond(exchange, 500, "{\"code\":\"internal_error\"}");
            }
        }

        private void createOrder(HttpExchange exchange) throws IOException {
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            String seen = bodyByKey.putIfAbsent(key, body);
            if (seen != null) {
                if (!seen.equals(body)) {
                    // Exactly what order-service does with a reused key (M3 (b)), so a non-deterministic simulator
                    // would be caught here rather than quietly creating a second order.
                    bodyMismatches.incrementAndGet();
                    respond(exchange, 422, "{\"code\":\"idempotency_key_reused\"}");
                    return;
                }
                replayed.incrementAndGet();
                exchange.getResponseHeaders().add("Idempotent-Replayed", "true");
                respond(exchange, 200, "{\"order_id\":\"" + orderIdByKey.get(key) + "\"}");
                return;
            }

            String orderId = UUID.randomUUID().toString();
            orderIdByKey.put(key, orderId);
            created.incrementAndGet();
            if (applies) {
                for (JsonNode entry : JSON.readTree(body).get("entries")) {
                    balances.merge(entry.get("entity_id").asString() + "|" + entry.get("account").asString(),
                            entry.get("amount_minor").asLong(), Long::sum);
                }
            }
            respond(exchange, 201, "{\"order_id\":\"" + orderId + "\"}");
        }

        private void readBalances(HttpExchange exchange, String entityId) throws IOException {
            if (alreadyPolled.add(entityId)) {
                respond(exchange, 404, "{\"code\":\"entity_not_found\"}");   // not applied yet, as in a real run
                return;
            }
            var accounts = new StringBuilder();
            balances.forEach((key, signed) -> {
                if (key.startsWith(entityId + "|")) {
                    if (accounts.length() > 0) {
                        accounts.append(',');
                    }
                    accounts.append("{\"account\":\"").append(key.substring(key.indexOf('|') + 1))
                            .append("\",\"currency\":\"USD\",\"normal_side\":\"DEBIT\",\"presented_minor\":")
                            .append(Math.abs(signed)).append(",\"signed_minor\":").append(signed).append('}');
                }
            });
            if (accounts.length() == 0) {
                respond(exchange, 404, "{\"code\":\"entity_not_found\"}");
                return;
            }
            respond(exchange, 200, "{\"entity_id\":\"" + entityId + "\",\"kind\":\"rider\",\"as_of_seq\":1,"
                    + "\"accounts\":[" + accounts + "]}");
        }

        private void respond(HttpExchange exchange, int status, String body) {
            try {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
