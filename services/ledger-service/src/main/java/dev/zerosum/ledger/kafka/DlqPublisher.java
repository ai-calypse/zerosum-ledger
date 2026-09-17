package dev.zerosum.ledger.kafka;

import dev.zerosum.contracts.kafka.TopicDefinitions;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes a quarantined record to its dead-letter topic (D04-4).
 *
 * <p>Written by hand rather than using Spring's {@code DeadLetterPublishingRecoverer}, because that recoverer fires
 * from the container's error handler — and poison never reaches the error handler here. The apply engine isolates a
 * bad record, writes its quarantine row and <em>returns</em> a {@code QUARANTINED} outcome instead of throwing, so a
 * recoverer would sit there and never publish anything. The DLQ step therefore belongs where the outcomes are read.
 *
 * <p><strong>No explicit partition.</strong> The DLQ has fewer partitions than its source (3 against 12), so copying
 * the source partition number — which the stock recoverer does by default — would address a partition that does not
 * exist. Publishing by key alone lets the partitioner place it, and keeps a re-published record with the rest of its
 * order group.
 */
class DlqPublisher {

    private final KafkaTemplate<String, String> kafka;

    DlqPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    /**
     * Publishes and waits for the broker to confirm, so the caller can acknowledge afterwards knowing the record is
     * durable somewhere. A failure propagates: the batch is not acknowledged and the records are redelivered.
     */
    void publish(ConsumerRecord<String, String> original, String errorCode, String detail) {
        String dlq = TopicDefinitions.dlqFor(TopicDefinitions.MONEY_ORDERS).name();
        var record = new ProducerRecord<>(dlq, null, original.key(), original.value());

        // Keep the original headers — including the W3C trace context — so a dead-lettered record can still be tied
        // to the request that produced it.
        original.headers().forEach(header -> record.headers().add(header.key(), header.value()));
        header(record, "zs-dlq-original-topic", original.topic());
        header(record, "zs-dlq-original-partition", Integer.toString(original.partition()));
        header(record, "zs-dlq-original-offset", Long.toString(original.offset()));
        header(record, "zs-dlq-classification", "POISON");
        header(record, "zs-dlq-error-code", errorCode);
        if (detail != null) {
            header(record, "zs-dlq-error-detail", detail.substring(0, Math.min(detail.length(), 500)));
        }

        try {
            kafka.send(record).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing to " + dlq, interrupted);
        } catch (Exception failure) {
            // Treated as transient by the caller: nothing is acknowledged, so the record comes back. The quarantine
            // row is already committed and its unique Kafka coordinates stop a second row being written.
            throw new IllegalStateException("could not publish to " + dlq, failure);
        }
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
