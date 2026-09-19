package dev.zerosum.ledger.kafka;

import dev.zerosum.auth.ChaosGuard;
import dev.zerosum.ledger.apply.ApplyBatchResult;
import dev.zerosum.ledger.apply.ApplyOutcome;
import dev.zerosum.ledger.apply.ApplyRecord;
import dev.zerosum.ledger.apply.LedgerApplyEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Applies money orders from Kafka (D04-3, master apply algorithm).
 *
 * <p><strong>Acknowledge only after the apply transaction commits.</strong> That ordering is the whole design: if the
 * offset moved first, a crash would skip orders the ledger never applied, and money would silently vanish. Committing
 * first and crashing before the acknowledgement merely redelivers, and the engine's {@code applied_orders} dedupe
 * makes the repeat a no-op (M5(a)).
 *
 * <p>The poll batch is handed to the engine in one call, preserving offset order within each partition. Cross-partition
 * order does not matter: the key is the order group (ADR-0007), so a group is confined to one partition, and balance
 * arithmetic is commutative across groups.
 */
@Component
class MoneyOrderListener {

    private static final Logger log = LoggerFactory.getLogger(MoneyOrderListener.class);

    private final LedgerApplyEngine engine;
    private final DlqPublisher dlq;
    private final ApplyMetrics applyMetrics;
    private final org.springframework.jdbc.core.JdbcTemplate template;
    private final Counter received;
    private final Counter applied;
    private final Counter duplicates;
    private final Counter quarantined;
    /** decision: D08-3 — the F2 crash hook (master §8.4 F2). Off outside the guarded chaos profile. */
    private final boolean crashHook;

    MoneyOrderListener(LedgerApplyEngine engine, DlqPublisher dlq, ApplyMetrics applyMetrics,
            org.springframework.jdbc.core.JdbcTemplate template, MeterRegistry meters) {
        this(engine, dlq, applyMetrics, template, meters, new ChaosGuard.Active("ledger-service", List.of()));
    }

    @Autowired
    MoneyOrderListener(LedgerApplyEngine engine, DlqPublisher dlq, ApplyMetrics applyMetrics,
            org.springframework.jdbc.core.JdbcTemplate template, MeterRegistry meters, ChaosGuard.Active chaos) {
        this.crashHook = chaos.on("F2");
        this.engine = engine;
        this.dlq = dlq;
        this.applyMetrics = applyMetrics;
        this.template = template;
        // Names follow the master's instrumentation style; S07 (D07-1) owns the permanent catalogue and may rename
        // them through the change procedure.
        this.received = meters.counter("ledger_records_received_total");
        this.applied = meters.counter("ledger_orders_applied_total");
        this.duplicates = meters.counter("ledger_duplicates_skipped_total");
        this.quarantined = meters.counter("ledger_quarantined_total");
    }

    @KafkaListener(
            topics = "#{T(dev.zerosum.contracts.kafka.TopicDefinitions).MONEY_ORDERS.name()}",
            groupId = "#{T(dev.zerosum.contracts.kafka.TopicDefinitions).LEDGER_APPLY_GROUP}",
            autoStartup = "${ledger.consumer.enabled:true}")
    void onBatch(List<ConsumerRecord<String, String>> records, Acknowledgment acknowledgment) {
        if (records.isEmpty()) {
            return;   // an empty poll must not reach the engine, and has nothing to acknowledge
        }
        received.increment(records.size());

        // The engine takes the payload plus its source position; it writes that position on quarantine rows, which is
        // what makes a redelivered poison record land on the existing row instead of a second one.
        List<ApplyRecord> batch = records.stream()
                .map(record -> ApplyRecord.of(record.value(),
                        new ApplyRecord.SourcePosition(record.topic(), record.partition(), record.offset())))
                .toList();

        ApplyBatchResult result = engine.apply(batch);

        // decision: D08-3 — F2 (master §8.4, "kill during apply"), chaos only, armed once per injection by the harness.
        // Committed and not yet acknowledged: the one point where a crash makes Kafka redeliver orders the ledger has
        // already applied. A random kill -9 almost never lands there, because each poll commits in one transaction.
        if (crashHook && ChaosGuard.consumeArm("F2")) {
            log.warn("ZS-CHAOS F2 halting after committing a batch of {} records, before acknowledging it",
                    records.size());
            ChaosGuard.halt();
        }

        // Latency and retry signals, taken from what the engine measured rather than re-timed here (D07-1).
        applyMetrics.batchApplied(result);
        recordOrderToApply(result);

        applied.increment(result.countOf(ApplyOutcome.Status.APPLIED));
        duplicates.increment(result.countOf(ApplyOutcome.Status.DUPLICATE));
        quarantined.increment(result.countOf(ApplyOutcome.Status.QUARANTINED));

        // Quarantine row first (the engine wrote it inside the batch transaction), then the DLQ copy, then the
        // acknowledgement. A crash between the publish and the ack duplicates a DLQ record, which is tolerable; the
        // reverse order would acknowledge a record that never reached the DLQ, leaving only a database row.
        publishQuarantinedToDlq(records, result);

        // Reached only when apply() returned, which means its transaction committed. An exception propagates instead,
        // leaving the offsets where they were so the batch is redelivered.
        acknowledgment.acknowledge();

        if (result.totalRetries() > 0) {
            log.info("applied batch of {} with {} retries ({} deadlock, {} lock timeout, {} connection)",
                    records.size(), result.totalRetries(), result.deadlockRetries(), result.lockTimeoutRetries(),
                    result.connectionRetries());
        }
    }

    /**
     * One order-to-apply observation per order actually applied (P2).
     *
     * <p>The creation time is read back from {@code applied_orders}, which the engine has just written inside the
     * committed transaction. {@link ApplyOutcome} carries no timestamp, and widening the engine's return type to
     * carry one would change a contract S02 owns for the sake of a metric.
     */
    private void recordOrderToApply(ApplyBatchResult result) {
        List<java.util.UUID> appliedIds = result.outcomes().stream()
                .filter(o -> o.status() == ApplyOutcome.Status.APPLIED)
                .map(ApplyOutcome::orderId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (appliedIds.isEmpty()) {
            return;
        }
        try {
            String placeholders = String.join(",", java.util.Collections.nCopies(appliedIds.size(), "?"));
            template.query("SELECT order_id, order_created_at FROM applied_orders WHERE order_id IN (" + placeholders + ")",
                    rs -> {
                        var createdAt = rs.getTimestamp("order_created_at");
                        var id = (java.util.UUID) rs.getObject("order_id");
                        result.outcomes().stream()
                                .filter(o -> id.equals(o.orderId()))
                                .findFirst()
                                .ifPresent(o -> applyMetrics.orderApplied(o,
                                        createdAt == null ? null : createdAt.toInstant()));
                    }, appliedIds.toArray());
        } catch (RuntimeException failure) {
            // A metric must never fail an apply that has already committed.
            log.debug("could not record order-to-apply latency", failure);
        }
    }

    /**
     * Dead-letters every record the engine quarantined. Driven from the outcomes rather than from a
     * {@code DeadLetterPublishingRecoverer}, because the engine quarantines and <em>returns</em> instead of throwing,
     * so no error handler ever sees a poison record.
     */
    private void publishQuarantinedToDlq(List<ConsumerRecord<String, String>> records, ApplyBatchResult result) {
        for (ApplyOutcome outcome : result.outcomes()) {
            if (outcome.status() != ApplyOutcome.Status.QUARANTINED) {
                continue;
            }
            ConsumerRecord<String, String> original = records.get(outcome.index());
            dlq.publish(original, String.valueOf(outcome.errorCode()), outcome.detail());
            log.warn("dead-lettered {}-{}@{} as {}", original.topic(), original.partition(), original.offset(),
                    outcome.errorCode());
        }
    }
}
