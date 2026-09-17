package dev.zerosum.instrument.payouts;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * order-service and ledger-service, as far as a payout run can tell.
 *
 * <p>Built on the JDK's own HTTP server, like {@code ProviderStub}: what these tests need is a socket that can answer
 * a chosen number, a 503, or the ledger's own {@code status: error} shape, and that is one fewer dependency to pin.
 *
 * <p>Standing up the real services would test their freshness endpoints, which already have their own tests; what is
 * under test here is what a payout run <em>does</em> with the three ages — in particular that it sums them rather
 * than taking the worst, which is only visible when each stage is individually fresh and the total is not.
 *
 * <p>{@link #lastAuthorization} is recorded because the outbound reader token is real wiring that nothing else in
 * this service exercises: a run that forgot it would get 401s and refuse every time, in a way a stub that ignored
 * the header would never reveal.
 */
final class ServiceStub implements AutoCloseable {

    private final HttpServer server;

    /** D03-7: order-service's oldest unpublished outbox row, in seconds. */
    volatile double orderOutboxAgeSeconds;

    /** D04-5: the ledger's oldest unapplied Kafka record, in seconds. */
    volatile double ledgerUnappliedAgeSeconds;

    /** {@code ok} or {@code error}; the error shape omits the numbers exactly as the real endpoint does. */
    volatile String ledgerStatus = "ok";

    /** When non-zero, {@code /v1/outbox/stats} answers with this status instead of 200. */
    volatile int forcedOutboxStatsStatus;

    /** When non-zero, {@code /v1/freshness} answers with this status instead of 200. */
    volatile int forcedFreshnessStatus;

    /** The ledger sequence balances are reported at, so a test can assert it was carried into the run record. */
    volatile long asOfSeq = 42;

    /** The last {@code Authorization} header any endpoint saw. */
    volatile String lastAuthorization;

    /** Payable balances on the normal side, keyed {@code <entity>|<currency>}. Absent entity means a 404. */
    private final Map<String, Long> payables = new ConcurrentHashMap<>();

    private ServiceStub(HttpServer server) {
        this.server = server;
    }

    static ServiceStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var stub = new ServiceStub(server);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A driver the ledger knows, owed this much on {@code payable} presented on its normal side (ADR-0003). */
    void payable(String entityId, String currency, long presentedMinor) {
        payables.put(entityId + "|" + currency, presentedMinor);
    }

    /** Back to a healthy, empty pipeline. Called before each test, so one test's drivers cannot pay another's. */
    void reset() {
        orderOutboxAgeSeconds = 0;
        ledgerUnappliedAgeSeconds = 0;
        ledgerStatus = "ok";
        forcedOutboxStatsStatus = 0;
        forcedFreshnessStatus = 0;
        asOfSeq = 42;
        lastAuthorization = null;
        payables.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        String path = exchange.getRequestURI().getPath();

        int status = 200;
        String body;
        if (path.equals("/v1/outbox/stats")) {
            status = forcedOutboxStatsStatus != 0 ? forcedOutboxStatsStatus : 200;
            body = """
                    {"unpublished_count":0,"oldest_unpublished_age_seconds":%s}"""
                    .formatted(orderOutboxAgeSeconds);
        } else if (path.equals("/v1/freshness")) {
            status = forcedFreshnessStatus != 0 ? forcedFreshnessStatus : 200;
            // The real endpoint omits every number when it could not measure them, rather than reporting zero; a
            // stub that reported a comfortable 0.0 here would hide the whole fail-closed path.
            body = "error".equals(ledgerStatus)
                    ? """
                    {"status":"error","total_lag_records":null,"partition_lag":{},
                     "oldest_unapplied_age_seconds":null,"listener_paused":false,
                     "computed_at":"2026-01-01T00:00:00Z","error":"the consumer could not be reached"}"""
                    : """
                    {"status":"ok","total_lag_records":0,"partition_lag":{},
                     "oldest_unapplied_age_seconds":%s,"listener_paused":false,
                     "computed_at":"2026-01-01T00:00:00Z","error":null}"""
                    .formatted(ledgerUnappliedAgeSeconds);
        } else if (path.startsWith("/v1/entities/") && path.endsWith("/balances")) {
            String entityId = URLDecoder.decode(
                    path.substring("/v1/entities/".length(), path.length() - "/balances".length()),
                    StandardCharsets.UTF_8);
            body = balances(entityId);
            if (body == null) {
                status = 404;
                body = """
                        {"status":404,"code":"entity_not_found","detail":"no such entity"}""";
            }
        } else {
            status = 404;
            body = """
                    {"status":404,"code":"not_found","detail":"the stub serves no such path"}""";
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The D02-7 shape, or null when this entity has no entries at all — which the caller answers 404 for. */
    private String balances(String entityId) {
        var accounts = new StringBuilder();
        for (Map.Entry<String, Long> entry : payables.entrySet()) {
            String[] key = entry.getKey().split("\\|", 2);
            if (!key[0].equals(entityId)) {
                continue;
            }
            if (!accounts.isEmpty()) {
                accounts.append(',');
            }
            // payable is credit-normal, so the presented figure is positive exactly when the driver is owed money
            // and the signed one is its negation (ADR-0003).
            accounts.append("""
                    {"account":"payable","currency":"%s","normal_side":"CREDIT","presented_minor":%d,
                     "signed_minor":%d}""".formatted(key[1], entry.getValue(), -entry.getValue()));
        }
        if (accounts.isEmpty()) {
            return null;
        }
        return """
                {"entity_id":"%s","kind":"DRIVER","as_of_seq":%d,"accounts":[%s]}"""
                .formatted(entityId, asOfSeq, accounts);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
