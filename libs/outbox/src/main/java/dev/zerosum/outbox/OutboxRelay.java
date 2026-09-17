package dev.zerosum.outbox;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to Kafka (D03-5, ADR-0008).
 *
 * <p>One transaction per batch: select unpublished rows in id order, send them, wait for every acknowledgement, then
 * mark them published and commit. Any failure rolls the whole batch back, so rows are re-sent rather than lost. A
 * crash between send and commit therefore produces duplicates, never loss — which is the trade the outbox pattern
 * makes, and why consumers must be idempotent.
 *
 * <p><strong>Rows are marked only after the broker acknowledges.</strong> Marking first would turn a broker failure
 * into silent loss.
 *
 * <p><strong>Single instance.</strong> A transaction-scoped advisory lock serialises batches. Row locks with
 * {@code SKIP LOCKED} alone would not preserve per-key order across two relays: a second relay could publish a later
 * batch while the first still held earlier rows. Compose runs one replica; this guard is the backstop for a
 * misconfiguration, and it counts every skip so the misconfiguration is visible rather than silent.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * Namespace for the advisory lock, so it cannot collide with another feature's lock in the same database.
     *
     * <p>Package-private rather than private so the guard test can take this exact lock from another connection and
     * observe a deterministic skip, instead of racing two relays and hoping they collide.
     */
    static final long ADVISORY_LOCK_KEY = 0x7A53_0B01L;

    private final JdbcTemplate template;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;
    private final OutboxMetrics metrics;
    private final TransactionTemplate transactions;

    public OutboxRelay(JdbcTemplate template, KafkaTemplate<String, String> kafka, OutboxProperties properties,
            OutboxMetrics metrics, PlatformTransactionManager transactionManager) {
        this.template = template;
        this.kafka = kafka;
        this.properties = properties;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** What one batch did, so the loop knows whether to sleep and tests can assert without timing. */
    public record BatchResult(int published, boolean full, boolean skippedByGuard) {

        static BatchResult skipped() {
            return new BatchResult(0, false, true);
        }
    }

    /**
     * Runs one batch. Returns without publishing when another relay holds the guard, and throws when the send fails,
     * leaving every row unpublished for the next attempt.
     */
    public BatchResult publishBatch() {
        return transactions.execute(status -> {
            if (!acquireGuard()) {
                metrics.guardSkipped();
                return BatchResult.skipped();
            }
            List<OutboxRecord> batch = unpublished();
            if (batch.isEmpty()) {
                return new BatchResult(0, false, false);
            }

            send(batch);   // throws before anything is marked if the broker does not acknowledge

            markPublished(batch);
            batch.forEach(record -> metrics.published(record.createdAt()));
            metrics.batchSize(batch.size());
            return new BatchResult(batch.size(), batch.size() == properties.batchSize(), false);
        });
    }

    /**
     * A transaction-scoped advisory lock: released automatically at commit or rollback, so a crashed relay never
     * leaves the lock held.
     */
    private boolean acquireGuard() {
        return Boolean.TRUE.equals(
                template.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY));
    }

    private List<OutboxRecord> unpublished() {
        // SKIP LOCKED keeps a second relay from blocking behind this one; the advisory lock above is what actually
        // preserves ordering between relays.
        return template.query("""
                SELECT id, topic, message_key, payload, headers, created_at
                FROM outbox WHERE published_at IS NULL
                ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""",
                (ResultSet rs, int rowNumber) -> new OutboxRecord(rs.getLong("id"), rs.getString("topic"),
                        rs.getString("message_key"), rs.getString("payload"), headersOf(rs),
                        rs.getTimestamp("created_at").toInstant()),
                properties.batchSize());
    }

    private void send(List<OutboxRecord> batch) {
        var pending = new ArrayList<java.util.concurrent.CompletableFuture<?>>(batch.size());
        try {
            // The send loop belongs inside the try: KafkaProducer.send throws synchronously when it cannot fetch
            // metadata within max.block.ms, which is exactly what an unreachable broker looks like. With the loop
            // outside, that failure escaped uncounted and outbox_send_failures_total stayed at zero while the relay
            // was failing — the one signal an operator alerts on. A relay test with the broker paused caught it.
            for (OutboxRecord record : batch) {
                var message = new ProducerRecord<>(record.topic(), null, record.messageKey(), record.payload());
                record.headers().forEach((name, value) ->
                        message.headers().add(name, value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                // CR-S04-01. Copying the stored traceparent onto the record is not enough: tracing instrumentation
                // builds the publish span from the *current* context, which on this relay thread is empty, and then
                // overwrites the copied header with its own orphan context. S04-T06 caught exactly that on the live
                // stack — the request trace stopped at the outbox and the publish began a trace of its own. Restoring
                // the stored context here makes the publish span a child of the originating request.
                try (Scope ignored = restoredContext(record).makeCurrent()) {
                    pending.add(kafka.send(message));
                }
            }
            java.util.concurrent.CompletableFuture.allOf(pending.toArray(java.util.concurrent.CompletableFuture[]::new))
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("relay interrupted while awaiting acknowledgements", interrupted);
        } catch (Exception failure) {
            metrics.sendFailed();
            // The batch rolls back, so every row stays unpublished and is re-sent. Money is never skipped.
            throw new IllegalStateException("outbox batch of " + batch.size() + " was not acknowledged", failure);
        }
    }

    /**
     * The trace context captured when the row was appended (§0.3 C11), rebuilt from the row's own headers.
     *
     * <p>Falls back to a root context when the row carries no {@code traceparent} — rows written before this existed,
     * or written with no active span. A send without a parent is still a correct send; it is simply untraced.
     */
    private static Context restoredContext(OutboxRecord record) {
        return W3CTraceContextPropagator.getInstance().extract(Context.root(), record.headers(), HEADER_GETTER);
    }

    /** Reads the W3C fields straight out of the stored header map. */
    private static final TextMapGetter<Map<String, String>> HEADER_GETTER = new TextMapGetter<>() {

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier == null ? List.of() : carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private void markPublished(List<OutboxRecord> batch) {
        // clock_timestamp(), not now(): now() is the transaction start time, which would understate publish lag by the
        // whole duration of the send.
        template.batchUpdate("UPDATE outbox SET published_at = clock_timestamp() WHERE id = ?",
                batch.stream().map(record -> new Object[] {record.id()}).toList());
    }

    /** Deletes published rows older than the retention, in bounded batches, never touching unpublished rows. */
    public int cleanUp() {
        return transactions.execute(status -> template.update("""
                DELETE FROM outbox WHERE id IN (
                  SELECT id FROM outbox
                  WHERE published_at IS NOT NULL AND published_at < now() - CAST(? AS interval)
                  ORDER BY id LIMIT ?)""",
                properties.retention().toSeconds() + " seconds", properties.cleanupBatchSize()));
    }

    /** Oldest unpublished row age in seconds, for the freshness gauge and the stats endpoint (S03-T06). */
    public double oldestUnpublishedAgeSeconds() {
        Double age = template.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) FROM outbox WHERE published_at IS NULL",
                Double.class);
        return age == null ? 0 : age;
    }

    public long unpublishedCount() {
        Long count = template.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }

    private static Map<String, String> headersOf(ResultSet rs) throws SQLException {
        String json = rs.getString("headers");
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return Map.of();
        }
        // Headers are written by this library as a flat string map, so a minimal reader avoids a JSON dependency here.
        var headers = new LinkedHashMap<String, String>();
        var matcher = java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        while (matcher.find()) {
            headers.put(matcher.group(1), matcher.group(2));
        }
        return headers;
    }
}
