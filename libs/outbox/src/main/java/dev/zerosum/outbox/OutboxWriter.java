package dev.zerosum.outbox;

import io.opentelemetry.api.trace.Span;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private final JdbcClient jdbc;
    /** decision: D08-3 — non-null only for the A2 ablation ({@link OutboxFactory#dualWriter}); null means the outbox. */
    private final KafkaTemplate<String, String> dualWrite;

    public OutboxWriter(JdbcClient jdbc) {
        this(jdbc, null);
    }

    OutboxWriter(JdbcClient jdbc, KafkaTemplate<String, String> dualWrite) {
        this.jdbc = jdbc;
        this.dualWrite = dualWrite;
    }

    /**
     * @param payload the message body, already serialized: callers serialize once, and the relay never re-serializes
     * @return the id of the appended row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long append(String topic, String messageKey, String payload, Map<String, String> headers) {
        Map<String, String> withTrace = new LinkedHashMap<>(headers == null ? Map.of() : headers);
        traceparent().ifPresent(value -> withTrace.putIfAbsent("traceparent", value));

        if (dualWrite != null) {
            return sendAfterCommit(topic, messageKey, payload, withTrace);
        }
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

    /**
     * decision: D08-3 — the A2 ablation (master §8.5; §0.3 C10 exempts this seam, inside {@code libs/outbox}, from the
     * relay-only rule). The naive dual write: no row, and once the caller's transaction has committed, one direct send.
     * A send that fails or never leaves the process is logged and dropped; nothing persists or retries it, because
     * persisting and retrying it is exactly what the outbox adds.
     */
    private long sendAfterCommit(String topic, String messageKey, String payload, Map<String, String> headers) {
        var message = new ProducerRecord<>(topic, null, messageKey, payload);
        headers.forEach((name, value) ->
                message.headers().add(name, value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dualWrite.send(message).whenComplete((sent, failure) -> {
                    if (failure != null) {
                        log.error("ZS-CHAOS A2 dual write lost a committed message for key {}", messageKey, failure);
                    }
                });
            }
        });
        return -1;
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
