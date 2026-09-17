package dev.zerosum.order.consumer;

import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Records a payment event that could not become a valid order (D03-6, §0.3 C8, C25).
 *
 * <p>Order-service quarantines in its own database rather than a shared one, so a poison record is investigated by
 * the team that owns the consumer.
 *
 * <p>The insert does nothing on conflict, keyed by the Kafka coordinates, so a redelivered poison record is
 * quarantined once rather than once per delivery.
 */
class QuarantineStore {

    private final JdbcClient jdbc;

    QuarantineStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void quarantine(String eventId, String payload, String errorCode, String detail,
            String topic, Integer partition, Long offset) {
        jdbc.sql("""
                INSERT INTO quarantined_events (event_id, payload, error_code, error_detail,
                                                kafka_topic, kafka_partition, kafka_offset)
                VALUES (:eventId, :payload, :errorCode, :detail, :topic, :partition, :offset)
                ON CONFLICT (kafka_topic, kafka_partition, kafka_offset) DO NOTHING""")
                .param("eventId", eventId)
                .param("payload", payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8))
                .param("errorCode", errorCode)
                // Truncated: a quarantine row is a pointer to the evidence, and the payload above is the evidence.
                .param("detail", detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000)))
                .param("topic", topic)
                .param("partition", partition)
                .param("offset", offset)
                .update();
    }
}
