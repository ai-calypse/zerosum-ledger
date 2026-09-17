// decision: D04-4 — docs/step_04_kafka_pipeline.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Pauses the policy listener when a record fails, and never skips one (D04-4).
 *
 * <p>Copied from ledger-service's handler rather than varied: two consumers with two failure policies is how one of
 * them ends up dropping money quietly.
 *
 * <p><strong>Nothing reaching here is ever dead-lettered or skipped.</strong> Poison never gets here — the listener
 * quarantines a bad record itself and acknowledges — so what arrives is a real failure, a database outage above all.
 * Spring's stock handler would retry and then <em>skip</em> the record, which on this topic means a rider who is
 * never charged because the database was briefly unavailable.
 */
class PauseOnFailureErrorHandler implements CommonErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(PauseOnFailureErrorHandler.class);

    private final Counter pauses;
    private final AtomicInteger paused = new AtomicInteger();

    PauseOnFailureErrorHandler(MeterRegistry meters) {
        this.pauses = meters.counter("instrument_listener_pauses_total");
        // The alert signal (§0.3 O9): "stopped consuming" is invisible otherwise — lag grows while the service looks
        // perfectly healthy. D07-1 owns the permanent name.
        meters.gauge("instrument_listener_paused", paused, AtomicInteger::doubleValue);
    }

    boolean isPaused() {
        return paused.get() == 1;
    }

    /**
     * Declared so the container takes the seek-and-pause path below rather than asking this handler to recover a
     * single record. Recovering would mean deciding to move past it, which is the one thing that must not happen.
     */
    @Override
    public boolean seeksAfterHandling() {
        return true;
    }

    @Override
    public void handleRemaining(Exception thrownException, List<ConsumerRecord<?, ?>> records, Consumer<?, ?> consumer,
            MessageListenerContainer container) {
        // Seek back to the failed record so nothing is skipped when the listener resumes.
        records.stream().findFirst().ifPresent(record ->
                consumer.seek(new TopicPartition(record.topic(), record.partition()), record.offset()));
        pause(container, thrownException);
    }

    @Override
    public void handleBatch(Exception thrownException, org.apache.kafka.clients.consumer.ConsumerRecords<?, ?> data,
            Consumer<?, ?> consumer, MessageListenerContainer container, Runnable invokeListener) {
        for (TopicPartition partition : data.partitions()) {
            data.records(partition).stream().map(ConsumerRecord::offset).min(Long::compareTo)
                    .ifPresent(earliest -> consumer.seek(partition, earliest));
        }
        pause(container, thrownException);
    }

    @Override
    public void handleOtherException(Exception thrownException, Consumer<?, ?> consumer,
            MessageListenerContainer container, boolean batchListener) {
        pause(container, thrownException);
    }

    private void pause(MessageListenerContainer container, Exception failure) {
        if (!container.isPauseRequested()) {
            container.pause();
            pauses.increment();
        }
        // Outside the guard on purpose: the gauge means "stopped because of a failure", not "we called pause()".
        paused.set(1);
        // One line, no payload: a failing record must not print money into the log.
        log.warn("instrument policy listener paused after {}: {}", failure.getClass().getSimpleName(),
                failure.getMessage());
    }

    /** Called by the resume probe once the database answers again. */
    void markResumed() {
        paused.set(0);
    }
}
