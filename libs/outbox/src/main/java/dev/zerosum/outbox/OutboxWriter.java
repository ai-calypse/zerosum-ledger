package dev.zerosum.outbox;

import io.opentelemetry.api.trace.Span;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends outbox rows inside the caller's transaction (D03-5).
 *
 * <p>The whole point of an outbox is that the row and the state change commit together, so this class has
 * <strong>no Kafka dependency at all</strong> and refuses to run outside a transaction: with
 * {@link Propagation#MANDATORY} a caller without one gets an exception rather than a row that might commit alone.
 *
 * <p>The current W3C trace context is captured into the row at append time. The relay sends it later from a different
 * thread, where the originating trace is long gone, so a trace that spans HTTP through Kafka to the ledger depends on
 * the context being written here rather than read at send time.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;

    public OutboxWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param payload the message body, already serialized: callers serialize once, and the relay never re-serializes
     * @return the id of the appended row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long append(String topic, String messageKey, String payload, Map<String, String> headers) {
        Map<String, String> withTrace = new LinkedHashMap<>(headers == null ? Map.of() : headers);
        traceparent().ifPresent(value -> withTrace.putIfAbsent("traceparent", value));

        return jdbc.sql("""
                INSERT INTO outbox (topic, message_key, payload, headers)
                VALUES (:topic, :messageKey, cast(:payload as jsonb), cast(:headers as jsonb))
                RETURNING id""")
                .param("topic", topic)
                .param("messageKey", messageKey)
                .param("payload", payload)
                .param("headers", asJson(withTrace))
                .query(Long.class)
                .single();
    }

    /** The active span as a W3C {@code traceparent}, or empty when nothing is recording (§0.3 C11). */
    private static java.util.Optional<String> traceparent() {
        var context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("00-" + context.getTraceId() + "-" + context.getSpanId() + "-"
                + context.getTraceFlags().asHex());
    }

    private static String asJson(Map<String, String> headers) {
        var json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(header.getKey())).append(':').append(quote(header.getValue()));
        }
        return json.append('}').toString();
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
