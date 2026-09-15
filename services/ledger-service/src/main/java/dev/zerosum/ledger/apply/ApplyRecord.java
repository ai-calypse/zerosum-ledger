package dev.zerosum.ledger.apply;

/**
 * One record handed to the apply engine (D02-3): the raw money-order payload and, when the caller has one, its source
 * position. The signature carries no Kafka types; S04 fills the position from its listener (D04-3).
 */
public record ApplyRecord(byte[] payload, SourcePosition position) {

    public static ApplyRecord of(String payload) {
        return new ApplyRecord(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
    }

    public static ApplyRecord of(String payload, SourcePosition position) {
        return new ApplyRecord(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), position);
    }

    /** A source position: all three fields are set together, or the position is absent (D02-1 CHECK). */
    public record SourcePosition(String topic, int partition, long offset) {

        public SourcePosition {
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException("topic must be set when a source position is given");
            }
        }
    }
}
