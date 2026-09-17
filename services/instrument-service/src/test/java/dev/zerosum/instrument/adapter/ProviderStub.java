package dev.zerosum.instrument.adapter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for a provider, built on the JDK's own HTTP server.
 *
 * <p>No stubbing library: what these tests need is a socket that can answer 500, or accept a request and then say
 * nothing at all until the read timeout fires. The JDK does both, and it is one fewer dependency to pin.
 */
final class ProviderStub implements AutoCloseable {

    private final HttpServer server;

    /** When set, every response uses this status instead of the routed one. */
    volatile int forcedStatus;

    /** When positive, the handler stalls this long before answering — the read-timeout case. */
    volatile long stallMillis;

    /** Routed GET responses, so a lookup can be made to return nothing or several rows. */
    volatile String lookupBody = "[]";

    private ProviderStub(HttpServer server) {
        this.server = server;
    }

    static ProviderStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var stub = new ProviderStub(server);
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

    private void handle(HttpExchange exchange) throws IOException {
        if (stallMillis > 0) {
            try {
                Thread.sleep(stallMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        String path = exchange.getRequestURI().getPath();
        boolean get = "GET".equals(exchange.getRequestMethod());
        String body = get ? lookupBody : submitBody(path);
        int status = forcedStatus != 0 ? forcedStatus : (get ? 200 : submitStatus(path));

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static int submitStatus(String path) {
        return path.startsWith("/fakebank") ? 202 : 200;
    }

    private static String submitBody(String path) {
        if (path.startsWith("/fakebank")) {
            return """
                    {"payout_id":"po_1","client_reference":"ref","status":"PENDING","return_code":null,
                     "amount_minor":1000,"currency":"USD"}
                    """;
        }
        if (path.contains("refunds")) {
            return """
                    {"refund_id":"re_1","charge_id":"ch_1","client_reference":"ref","status":"SUCCEEDED",
                     "failure_code":null,"amount_minor":1000}
                    """;
        }
        return """
                {"charge_id":"ch_1","client_reference":"ref","status":"SUCCEEDED","decline_code":null,
                 "amount_minor":1000,"currency":"USD","fee_minor":59,"refunded_minor":0}
                """;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
