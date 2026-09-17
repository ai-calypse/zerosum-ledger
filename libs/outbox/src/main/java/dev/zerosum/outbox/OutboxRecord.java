package dev.zerosum.outbox;

import java.time.Instant;
import java.util.Map;

/**
 * One row of the outbox (D03-5).
 *
 * @param id        assigned by the database; the relay publishes in this order
 * @param topic     destination topic
 * @param messageKey partition key — the order group, so a group's records stay ordered (ADR-0007)
 * @param payload   the serialized message body, already JSON
 * @param headers   message headers, including the W3C trace context captured when the row was appended (§0.3 C11)
 * @param createdAt when the row was appended; publish lag is measured from this
 */
public record OutboxRecord(long id, String topic, String messageKey, String payload, Map<String, String> headers,
        Instant createdAt) {
}
