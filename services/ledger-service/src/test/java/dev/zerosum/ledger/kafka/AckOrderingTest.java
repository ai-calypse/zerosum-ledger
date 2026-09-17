package dev.zerosum.ledger.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.apply.ApplyBatchResult;
import dev.zerosum.ledger.apply.ApplyOutcome;
import dev.zerosum.ledger.apply.ApplyRecord;
import dev.zerosum.ledger.apply.LedgerApplyEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/**
 * The ordering the whole design rests on: the offset moves only after the apply transaction has committed.
 *
 * <p>A unit test rather than an integration one, because the claim is about call order, and a container would let a
 * passing run depend on timing. If the offset moved first, a crash would skip orders the ledger never applied — money
 * gone with no trace — whereas acknowledging late merely redelivers, which the engine's dedupe absorbs.
 */
@Tag("unit")
class AckOrderingTest {

    /** Records whether the engine had returned by the time acknowledge() was called. */
    private static final class RecordingAck implements Acknowledgment {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean engineReturned;

        private RecordingAck(AtomicBoolean engineReturned) {
            this.engineReturned = engineReturned;
        }

        @Override
        public void acknowledge() {
            assertTrue(engineReturned.get(), "acknowledge() must not run before the engine returns");
            calls.incrementAndGet();
        }
    }

    /**
     * The listener under test. A null {@code JdbcTemplate} is deliberate: these tests assert call order, and the
     * order-to-apply lookup is required to swallow its own failures, so a null here also proves a metrics fault
     * cannot break an apply that has already committed.
     */
    private static MoneyOrderListener listener(LedgerApplyEngine engine, DlqPublisher dlq) {
        var meters = new SimpleMeterRegistry();
        return new MoneyOrderListener(engine, dlq, new ApplyMetrics(meters), null, meters);
    }

    private static ConsumerRecord<String, String> record(long offset) {
        return new ConsumerRecord<>("payments.money-orders.v1", 0, offset, "trip_1", "{}");
    }

    @Test
    void aFailedBatchIsNeverAcknowledged() {
        var engineReturned = new AtomicBoolean();
        var ack = new RecordingAck(engineReturned);
        var listener = listener(new ThrowingEngine(), new RecordingDlq());

        assertThrows(IllegalStateException.class, () -> listener.onBatch(List.of(record(0), record(1)), ack));

        assertEquals(0, ack.calls.get(),
                "an unacknowledged batch is redelivered; acknowledging it would skip orders that never applied");
        assertFalse(engineReturned.get());
    }

    @Test
    void aSuccessfulBatchIsAcknowledgedExactlyOnceAfterTheEngineReturns() {
        var engineReturned = new AtomicBoolean();
        var ack = new RecordingAck(engineReturned);
        var listener = listener(new SucceedingEngine(engineReturned), new RecordingDlq());

        listener.onBatch(List.of(record(0), record(1), record(2)), ack);

        assertEquals(1, ack.calls.get(), "the whole poll batch is acknowledged once, not once per record");
    }

    @Test
    void anEmptyPollNeitherCallsTheEngineNorAcknowledges() {
        var engine = new SucceedingEngine(new AtomicBoolean());
        var ack = new RecordingAck(new AtomicBoolean(true));
        var listener = listener(engine, new RecordingDlq());

        listener.onBatch(List.of(), ack);

        assertEquals(0, engine.calls.get(), "an empty poll must not open a transaction");
        assertEquals(0, ack.calls.get());
    }

    @Test
    void aQuarantinedRecordReachesTheDlqBeforeTheOffsetMoves() {
        // Order matters: the engine has already written the quarantine row inside the batch transaction, so
        // acknowledging before the DLQ publish would leave a poison record recorded only in this service's database.
        // A crash the other way round merely duplicates a DLQ record.
        var engineReturned = new AtomicBoolean();
        var dlq = new RecordingDlq();
        var ack = new RecordingAck(engineReturned);
        var listener = listener(new QuarantiningEngine(engineReturned), dlq);

        listener.onBatch(List.of(record(0)), ack);

        assertEquals(1, dlq.published.get(), "the quarantined record must be dead-lettered");
        assertEquals(1, ack.calls.get());
        assertTrue(dlq.publishedBeforeAck.get(), "the DLQ publish must precede the acknowledgement");
    }

    /** Records whether it ran, and whether it ran before the acknowledgement. */
    private static final class RecordingDlq extends DlqPublisher {

        private final AtomicInteger published = new AtomicInteger();
        private final AtomicBoolean publishedBeforeAck = new AtomicBoolean();

        private RecordingDlq() {
            // Never sends: every test here either produces no quarantined outcome or overrides publish().
            super(null);
        }

        @Override
        void publish(ConsumerRecord<String, String> original, String errorCode, String detail) {
            published.incrementAndGet();
            publishedBeforeAck.set(true);
        }
    }

    /** Returns a quarantined outcome the way the engine does — by returning, never by throwing. */
    private static final class QuarantiningEngine extends LedgerApplyEngine {

        private final AtomicBoolean returned;

        private QuarantiningEngine(AtomicBoolean returned) {
            super(null, null, null, null, null, null);
            this.returned = returned;
        }

        @Override
        public ApplyBatchResult apply(List<ApplyRecord> records) {
            var outcomes = List.of(new ApplyOutcome(0, null, ApplyOutcome.Status.QUARANTINED,
                    dev.zerosum.ledger.apply.QuarantineCode.UNDECODABLE_PAYLOAD, "not json"));
            returned.set(true);
            return new ApplyBatchResult(outcomes, Duration.ZERO, Duration.ZERO, 1, 0, 0, 0);
        }
    }

    /** Fails the way a database fault does: the batch's transaction rolled back and nothing was applied. */
    private static final class ThrowingEngine extends LedgerApplyEngine {

        private ThrowingEngine() {
            super(null, null, null, null, null, null);
        }

        @Override
        public ApplyBatchResult apply(List<ApplyRecord> records) {
            throw new IllegalStateException("apply failed after exhausting retries");
        }
    }

    private static final class SucceedingEngine extends LedgerApplyEngine {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean returned;

        private SucceedingEngine(AtomicBoolean returned) {
            super(null, null, null, null, null, null);
            this.returned = returned;
        }

        @Override
        public ApplyBatchResult apply(List<ApplyRecord> records) {
            calls.incrementAndGet();
            var outcomes = java.util.stream.IntStream.range(0, records.size())
                    .mapToObj(i -> new ApplyOutcome(i, java.util.UUID.randomUUID(), ApplyOutcome.Status.APPLIED,
                            null, null))
                    .toList();
            returned.set(true);
            return new ApplyBatchResult(outcomes, Duration.ZERO, Duration.ZERO, 1, 0, 0, 0);
        }
    }
}
