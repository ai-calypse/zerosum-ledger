package dev.zerosum.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * CR-S04-01: the relay must make the row's stored trace context <em>current</em> for the send, not merely copy the
 * header onto the record.
 *
 * <p>S04-T06 found the difference the hard way on the live stack. The header was being copied, yet the publish span
 * still began its own orphan trace, because tracing instrumentation reads the sending thread's context — empty on the
 * relay thread — and then overwrote the copied header. The request's trace stopped dead at the outbox.
 *
 * <p>No SDK or agent is needed here: {@code W3CTraceContextPropagator} builds a valid {@code SpanContext} from the
 * API alone, so the test observes exactly what instrumentation would observe at the moment of the send.
 */
@Tag("unit")
class RelayTraceContextTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";

    /** Captures the trace context that is current at the instant the record is handed to Kafka. */
    private static final class ContextCapturingTemplate extends KafkaTemplate<String, String> {

        private final List<String> traceIdsAtSend = new ArrayList<>();
        private final List<String> traceparentHeaders = new ArrayList<>();

        private ContextCapturingTemplate() {
            super(new DefaultKafkaProducerFactory<>(Map.of()));
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
            traceIdsAtSend.add(Span.fromContext(Context.current()).getSpanContext().getTraceId());
            var header = record.headers().lastHeader("traceparent");
            traceparentHeaders.add(header == null ? null
                    : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static OutboxRecord row(long id, Map<String, String> headers) {
        return new OutboxRecord(id, "payments.money-orders.v1", "trip_1", "{}", headers, Instant.now());
    }

    private static OutboxRelay relayFor(ContextCapturingTemplate template) {
        // The store is never reached: these tests drive send() through publishBatch's collaborators only.
        return new OutboxRelay(null, template, OutboxTestSupport.properties(100, Duration.ofSeconds(1)),
                new OutboxMetrics(new SimpleMeterRegistry(), () -> 0), null);
    }

    @Test
    void theStoredTraceContextIsCurrentWhenTheRecordIsSent() throws Exception {
        var template = new ContextCapturingTemplate();
        var relay = relayFor(template);

        invokeSend(relay, List.of(row(1, Map.of("traceparent", TRACEPARENT))));

        assertEquals(List.of(TRACE_ID), template.traceIdsAtSend,
                "the publish must happen inside the trace that wrote the row, or it starts an orphan trace");
        assertEquals(TRACEPARENT, template.traceparentHeaders.get(0),
                "the stored header still travels with the record as a fallback when nothing is instrumented");
    }

    @Test
    void eachRecordInABatchGetsItsOwnTraceContext() throws Exception {
        // A batch mixes rows written by different requests. Restoring one context for the whole batch would file
        // every publish under whichever request happened to be first.
        String otherTrace = "0af7651916cd43dd8448eb211c80319c";
        String otherParent = "00-" + otherTrace + "-b7ad6b7169203331-01";
        var template = new ContextCapturingTemplate();
        var relay = relayFor(template);

        invokeSend(relay, List.of(
                row(1, Map.of("traceparent", TRACEPARENT)),
                row(2, Map.of("traceparent", otherParent))));

        assertEquals(List.of(TRACE_ID, otherTrace), template.traceIdsAtSend);
    }

    @Test
    void aRowWithNoStoredContextStillSends() throws Exception {
        // Rows written before the context was captured, or with no active span. An untraced send is still a send;
        // refusing to publish would turn a tracing gap into lost money.
        var template = new ContextCapturingTemplate();
        var relay = relayFor(template);

        invokeSend(relay, List.of(row(1, Map.of("schema", "zerosum.money_order.v1"))));

        assertEquals(1, template.traceIdsAtSend.size());
        assertFalse(Span.fromContext(Context.root()).getSpanContext().isValid());
        assertEquals("00000000000000000000000000000000", template.traceIdsAtSend.get(0),
                "no stored context means no parent, not a fabricated one");
    }

    @Test
    void theContextDoesNotLeakPastTheSend() throws Exception {
        var template = new ContextCapturingTemplate();
        var relay = relayFor(template);

        invokeSend(relay, List.of(row(1, Map.of("traceparent", TRACEPARENT))));

        assertTrue(!Span.fromContext(Context.current()).getSpanContext().isValid(),
                "the scope must close, or the relay thread would keep publishing under a stale trace forever");
    }

    /** send() is private; these tests exercise it directly because that is where the context restoration lives. */
    private static void invokeSend(OutboxRelay relay, List<OutboxRecord> batch) throws Exception {
        var method = OutboxRelay.class.getDeclaredMethod("send", List.class);
        method.setAccessible(true);
        method.invoke(relay, batch);
    }
}
