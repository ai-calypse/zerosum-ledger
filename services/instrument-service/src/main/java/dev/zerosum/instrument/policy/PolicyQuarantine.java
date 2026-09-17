// decision: D05-6, D04-4 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Records a money order this service could not act on (§0.3 C8, C25; D04-4 poison policy).
 *
 * <p>Quarantined in instrument-service's own database, not a shared one, so the team that owns the consumer owns the
 * evidence. Shape and conflict key are order-service's (D03-6), changing only the table name.
 *
 * <p>The conflict key is the Kafka position, so a record redelivered after a crash lands on the existing row rather
 * than adding one per delivery (§0.3 C25).
 *
 * <p><strong>No dead-letter publish.</strong> Instrument-service produces payment events, so it sits inside the
 * package M4(b) guards: naming a Kafka producer here is exactly what that rule forbids, and the carve-out would be
 * indistinguishable from a real publish path later. Order-service deferred its DLQ for the same reason. The payload
 * is on the row, so nothing is lost by it.
 */
@Component
class PolicyQuarantine {

    private final JdbcClient db;

    PolicyQuarantine(JdbcClient db) {
        this.db = db;
    }

    void quarantine(String payload, String errorCode, String detail, String topic, Integer partition, Long offset) {
        db.sql("""
                INSERT INTO quarantined_records (payload, error_code, error_detail,
                                                 kafka_topic, kafka_partition, kafka_offset)
                VALUES (:payload, :code, :detail, :topic, :partition, :offset)
                ON CONFLICT (kafka_topic, kafka_partition, kafka_offset) DO NOTHING
                """)
                .param("payload", payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8))
                .param("code", errorCode)
                // Truncated: the row points at the evidence, and the payload above is the evidence.
                .param("detail", detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000)))
                .param("topic", topic)
                .param("partition", partition)
                .param("offset", offset)
                .update();
    }
}
