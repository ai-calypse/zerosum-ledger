package dev.zerosum.instrument.recovery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FakeCard and FakeBank in the test JVM, keeping the one thing these tests have to be judged against: <strong>the
 * provider's own record of what it did</strong>.
 *
 * <p>{@code ProviderStub} answers with a fixed body and cannot say how many charges exist, which is precisely the
 * question S05-T12 has to answer — "the attempt says SUCCEEDED" and "the payer was charged once" are different
 * claims, and only the second one is what a resolver can get wrong. So this keeps ground truth per client reference,
 * honours FakeCard's idempotency keys, and can inject the two faults that produce an uncertain outcome.
 *
 * <p><strong>This is not the real {@code services/fake-providers}.</strong> S05-T12's verification asks for the
 * fault knobs of that service, but instrument-service's tests do not depend on that module and standing it up would
 * mean a second Spring context and a second database container per test class. The wire contract reproduced here is
 * the one the adapters actually parse ({@code ProviderWire}), and the two knobs behave as that service's do:
 *
 * <ul>
 *   <li><strong>timeout after commit</strong> — record the payment, then go quiet past the caller's read timeout.
 *       The money moved and the caller cannot know it. This is the case an idempotent retry must not double.</li>
 *   <li><strong>reset before commit</strong> — drop the connection without recording anything. The money did not
 *       move and the caller cannot know that either. This is the case a resubmission must eventually recover.</li>
 * </ul>
 *
 * <p>The two are deliberately indistinguishable to the caller, which is the whole point: from the adapter's side both
 * are an {@code Unknown} outcome, and only a lookup can tell them apart.
 */
final class FakeProviderTruth implements AutoCloseable {

    private static final Pattern CLIENT_REFERENCE = Pattern.compile("\"client_reference\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpServer server;

    /** One knob set per provider, because a test freezes one side while the other keeps working. */
    final Knobs fakecard = new Knobs();
    final Knobs fakebank = new Knobs();

    /** Ground truth: client reference -> the payments this provider actually holds for it. */
    private final Map<String, List<String>> charges = new ConcurrentHashMap<>();
    private final Map<String, List<String>> payouts = new ConcurrentHashMap<>();

    /** FakeCard's idempotency keys: key -> the charge id it first produced. FakeBank deliberately has none. */
    private final Map<String, String> byIdempotencyKey = new ConcurrentHashMap<>();

    private final AtomicInteger ids = new AtomicInteger();

    /** How a provider misbehaves. Counts are "the next N requests", which keeps a test's intent explicit. */
    static final class Knobs {

        /** Record the payment, then stall past the caller's read timeout. */
        final AtomicInteger timeoutAfterCommit = new AtomicInteger();

        /** Drop the connection before recording anything. */
        final AtomicInteger resetBeforeCommit = new AtomicInteger();

        /** Applied to every submission once the counts above are exhausted; seeded, so a run is reproducible. */
        volatile double timeoutAfterCommitRate;

        /** Non-zero forces this status on lookups — 500 is what the adapters map to {@code Unavailable}. */
        volatile int lookupStatus;

        /** How long "gone quiet" lasts. Must exceed the read timeout the test configures. */
        volatile long stallMillis = 1_500;

        private volatile Random random = new Random(0);

        void seed(long seed) {
            random = new Random(seed);
        }

        void reset() {
            timeoutAfterCommit.set(0);
            resetBeforeCommit.set(0);
            timeoutAfterCommitRate = 0;
            lookupStatus = 0;
            stallMillis = 1_500;
            random = new Random(0);
        }

        private boolean drawTimeout() {
            if (timeoutAfterCommit.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                return true;
            }
            double rate = timeoutAfterCommitRate;
            return rate > 0 && random.nextDouble() < rate;
        }

        private boolean drawReset() {
            return resetBeforeCommit.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
        }
    }

    private FakeProviderTruth(HttpServer server) {
        this.server = server;
    }

    static FakeProviderTruth start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var provider = new FakeProviderTruth(server);
            server.createContext("/", provider::handle);
            // A pool, not the default single thread: a stalled request must not hold up every other call, or a
            // timeout knob would freeze the whole suite rather than one attempt.
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
                var thread = new Thread(runnable, "fake-provider");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            return provider;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // --- ground truth, which is what the assertions are made against ----------------------------------------------

    /** How many charges this provider really holds for the attempt. The number that must never be two. */
    int chargesFor(String clientReference) {
        return charges.getOrDefault(clientReference, List.of()).size();
    }

    int payoutsFor(String clientReference) {
        return payouts.getOrDefault(clientReference, List.of()).size();
    }

    /** Marks a payout as settled, so a pending-payout poll has something new to find. */
    void settlePayouts(String clientReference) {
        settled.add(clientReference);
    }

    private final java.util.Set<String> settled = ConcurrentHashMap.newKeySet();

    void reset() {
        fakecard.reset();
        fakebank.reset();
        charges.clear();
        payouts.clear();
        byIdempotencyKey.clear();
        settled.clear();
    }

    // --- the wire -------------------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        boolean bank = path.startsWith("/fakebank");
        Knobs knobs = bank ? fakebank : fakecard;

        if ("GET".equals(exchange.getRequestMethod())) {
            lookup(exchange, bank, knobs);
            return;
        }
        submit(exchange, bank, knobs);
    }

    private void lookup(HttpExchange exchange, boolean bank, Knobs knobs) throws IOException {
        if (knobs.lookupStatus != 0) {
            // The adapters turn 5xx into Unavailable — "I could not ask" — which must never permit a resubmission.
            respond(exchange, knobs.lookupStatus, "{\"error\":\"forced\"}");
            return;
        }
        String reference = queryParameter(exchange.getRequestURI().getQuery());
        List<String> held = (bank ? payouts : charges).getOrDefault(reference, List.of());

        var body = new StringBuilder("[");
        for (int i = 0; i < held.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(bank ? payoutJson(held.get(i), reference) : chargeJson(held.get(i), reference));
        }
        respond(exchange, 200, body.append(']').toString());
    }

    private void submit(HttpExchange exchange, boolean bank, Knobs knobs) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String reference = clientReference(body);
        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");

        if (knobs.drawReset()) {
            // Nothing recorded, connection dropped: the request never landed, and the caller cannot tell.
            exchange.close();
            return;
        }

        // FakeCard replays a stored result for a repeated key; FakeBank sends none, which is the whole reason the
        // quiet period exists. The replay deliberately does NOT add to ground truth.
        String paymentId;
        if (idempotencyKey != null && byIdempotencyKey.containsKey(idempotencyKey)) {
            paymentId = byIdempotencyKey.get(idempotencyKey);
        } else {
            paymentId = (bank ? "po_" : "ch_") + ids.incrementAndGet();
            (bank ? payouts : charges).computeIfAbsent(reference, key -> new ArrayList<>()).add(paymentId);
            if (idempotencyKey != null) {
                byIdempotencyKey.put(idempotencyKey, paymentId);
            }
        }

        if (knobs.drawTimeout()) {
            // Committed, then silent. The payment exists and the caller will never hear about it.
            sleep(knobs.stallMillis);
        }
        respond(exchange, bank ? 202 : 200,
                bank ? payoutJson(paymentId, reference) : chargeJson(paymentId, reference));
    }

    private String payoutJson(String payoutId, String reference) {
        String status = settled.contains(reference) ? "SETTLED" : "PENDING";
        return "{\"payout_id\":\"" + payoutId + "\",\"client_reference\":\"" + reference + "\",\"status\":\""
                + status + "\",\"return_code\":null,\"amount_minor\":1000,\"currency\":\"USD\"}";
    }

    private static String chargeJson(String chargeId, String reference) {
        return "{\"charge_id\":\"" + chargeId + "\",\"client_reference\":\"" + reference + "\","
                + "\"status\":\"SUCCEEDED\",\"decline_code\":null,\"amount_minor\":1000,\"currency\":\"USD\","
                + "\"fee_minor\":59,\"refunded_minor\":0}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String clientReference(String body) {
        Matcher matcher = CLIENT_REFERENCE.matcher(body);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String queryParameter(String query) {
        if (query == null) {
            return "";
        }
        int equals = query.indexOf('=');
        return equals < 0 ? "" : java.net.URLDecoder.decode(query.substring(equals + 1), StandardCharsets.UTF_8);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
