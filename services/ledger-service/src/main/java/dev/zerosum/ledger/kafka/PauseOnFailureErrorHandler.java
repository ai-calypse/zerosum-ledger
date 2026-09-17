package dev.zerosum.ledger.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Pauses the listener when a batch fails, and never dead-letters (D04-4).
 *
 * <p><strong>No retry loop lives here.</strong> The apply engine already retries transient SQLSTATEs with backoff and
 * throws {@code RetriesExhaustedException} when it gives up, so a second loop in the container would multiply the
 * retry budget and could outlive the poll interval. This handler reacts to exhaustion rather than repeating it.
 *
 * <p><strong>Nothing reaching this handler is ever dead-lettered.</strong> Poison never gets here — the engine
 * quarantines a bad record and returns, so the listener publishes it to the DLQ itself. What arrives here is either an
 * exhausted transient failure or an unclassified one, and dead-lettering either would mean skipping a valid order:
 * money thrown away because the database was briefly unavailable.
 */
class PauseOnFailureErrorHandler implements CommonErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(PauseOnFailureErrorHandler.class);

    private final Counter pauses;
    private final AtomicInteger paused = new AtomicInteger();

    PauseOnFailureErrorHandler(MeterRegistry meters) {
        this.pauses = meters.counter("ledger_listener_pauses_total");
        // The alert signal (§0.3 O9). A gauge rather than a log line, because "stopped consuming" is invisible
        // otherwise: lag grows while everything looks healthy. S07-T03 turns this into the alert; D07-1 owns the name.
        meters.gauge("ledger_listener_paused", paused, AtomicInteger::doubleValue);
    }

    boolean isPaused() {
        return paused.get() == 1;
    }

    @Override
    public void handleBatch(Exception thrownException, ConsumerRecords<?, ?> data, Consumer<?, ?> consumer,
            MessageListenerContainer container, Runnable invokeListener) {
        // Seek back to the start of the failed batch so nothing is skipped when the listener resumes.
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
        // Outside the guard on purpose: the gauge means "stopped because of a failure", not "we were the ones who
        // called pause()". A container already paused for another reason would otherwise fail silently with no alert
        // signal at all — the failure mode this gauge exists to make visible.
        paused.set(1);
        // One line, no payload: a failing batch must not print money into the log.
        log.warn("ledger listener paused after {}: {}", failure.getClass().getSimpleName(), failure.getMessage());
    }

    /** Called by the resume probe once the database answers again. */
    void markResumed() {
        paused.set(0);
    }
}
