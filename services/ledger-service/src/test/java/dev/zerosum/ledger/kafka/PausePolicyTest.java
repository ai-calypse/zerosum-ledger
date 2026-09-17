package dev.zerosum.ledger.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.zerosum.ledger.apply.ApplyRecord;
import dev.zerosum.ledger.apply.RetriesExhaustedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * The failure policy (D04-4): an exhausted or unclassified failure pauses the listener and never dead-letters, and a
 * paused listener resumes on its own once the database answers.
 *
 * <p>Deliberately a unit test. A container version would need a second Spring context, which joins the same
 * {@code ledger-apply} group on the same topic as the other pipeline tests — the partitions would then be split
 * between two consumers and the assertion would race for whichever one held the record. The real-database-fault
 * version is recorded as deferred rather than written as a test that passes for the wrong reason.
 */
@Tag("unit")
class PausePolicyTest {

    private static final TopicPartition PARTITION = new TopicPartition("payments.money-orders.v1", 0);

    @Test
    void anExhaustedTransientFailurePausesSeeksBackAndDeadLettersNothing() {
        var meters = new SimpleMeterRegistry();
        var handler = new PauseOnFailureErrorHandler(meters);
        var container = mock(MessageListenerContainer.class);
        Consumer<?, ?> consumer = mock(Consumer.class);
        when(container.isPauseRequested()).thenReturn(false);

        var failure = new RetriesExhaustedException(List.of(ApplyRecord.of("{}")), 10, new RuntimeException("db down"));
        handler.handleBatch(failure, records(5L, 6L), consumer, container, () -> { });

        verify(container).pause();
        // Seeks back to the first record of the failed batch, so resuming reprocesses it rather than skipping it.
        verify(consumer).seek(eq(PARTITION), eq(5L));
        assertEquals(1.0, meters.get("ledger_listener_pauses_total").counter().count());
        assertEquals(1.0, meters.get("ledger_listener_paused").gauge().value(),
                "the paused gauge is the alert signal: a stopped consumer is otherwise invisible");
        assertTrue(handler.isPaused());
    }

    @Test
    void anUnclassifiedFailureAlsoPausesRatherThanDeadLettering() {
        // Dead-lettering an unclassified failure would throw away a valid order because something unexpected broke.
        var meters = new SimpleMeterRegistry();
        var handler = new PauseOnFailureErrorHandler(meters);
        var container = mock(MessageListenerContainer.class);

        handler.handleOtherException(new IllegalStateException("something unexpected"), mock(Consumer.class),
                container, true);

        verify(container).pause();
        assertEquals(1.0, meters.get("ledger_listener_paused").gauge().value());
    }

    @Test
    void anAlreadyPausedContainerIsNotPausedTwice() {
        var meters = new SimpleMeterRegistry();
        var handler = new PauseOnFailureErrorHandler(meters);
        var container = mock(MessageListenerContainer.class);
        when(container.isPauseRequested()).thenReturn(true);

        handler.handleOtherException(new IllegalStateException("again"), mock(Consumer.class), container, true);

        verify(container, never()).pause();
        assertEquals(0.0, meters.get("ledger_listener_pauses_total").counter().count(),
                "repeated failures while paused must not inflate the pause count");
    }

    @Test
    void theProbeResumesOnlyOnceTheDatabaseAnswers() {
        var meters = new SimpleMeterRegistry();
        var handler = new PauseOnFailureErrorHandler(meters);
        var container = mock(MessageListenerContainer.class);
        // Running when the failure arrives, paused afterwards. Stubbing it as already paused — which is what the
        // first version of this test did — meant the handler took its do-not-pause-twice branch and never paused at
        // all, so the test was asserting against a state it had invented rather than one the code produced.
        when(container.isPauseRequested()).thenReturn(false, true);
        var registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getListenerContainers()).thenAnswer(invocation -> List.of(container));
        var template = mock(JdbcTemplate.class);

        handler.handleOtherException(new IllegalStateException("db down"), mock(Consumer.class), container, true);
        verify(container).pause();
        var probe = new ListenerResumeProbe(registry, template, handler);

        // Still failing: stay paused, or the same batch fails again immediately and re-pauses.
        when(template.queryForObject(any(String.class), eq(Integer.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("still down"));
        probe.resumeIfHealthy();
        verify(container, never()).resume();
        assertTrue(handler.isPaused());

        // Answering again: resume without anyone intervening, because S08's faults expect unattended recovery.
        when(template.queryForObject(any(String.class), eq(Integer.class))).thenReturn(1);
        probe.resumeIfHealthy();
        verify(container, times(1)).resume();
        assertFalse(handler.isPaused());
        assertEquals(0.0, meters.get("ledger_listener_paused").gauge().value());
    }

    @Test
    void aRunningContainerIsLeftAlone() {
        var handler = new PauseOnFailureErrorHandler(new SimpleMeterRegistry());
        var container = mock(MessageListenerContainer.class);
        when(container.isPauseRequested()).thenReturn(false);
        var registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getListenerContainers()).thenAnswer(invocation -> List.of(container));
        var template = mock(JdbcTemplate.class);

        new ListenerResumeProbe(registry, template, handler).resumeIfHealthy();

        verify(container, never()).resume();
        // The probe must not query the database for a container that was never paused.
        verify(template, never()).queryForObject(any(String.class), eq(Integer.class));
    }

    private static ConsumerRecords<?, ?> records(long... offsets) {
        var list = new java.util.ArrayList<ConsumerRecord<String, String>>();
        for (long offset : offsets) {
            list.add(new ConsumerRecord<>(PARTITION.topic(), PARTITION.partition(), offset, "trip_1", "{}"));
        }
        return new ConsumerRecords<>(java.util.Map.of(PARTITION, list), java.util.Map.of());
    }
}
