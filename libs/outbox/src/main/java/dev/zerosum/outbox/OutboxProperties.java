package dev.zerosum.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Relay and cleanup settings (D03-5). Every value comes from the consuming service's {@code application.yml} with a
 * trace comment; the library ships no defaults, so a deployment cannot silently inherit one.
 *
 * @param batchSize        rows per relay transaction
 * @param pollInterval     sleep between batches, applied only when the last batch was not full
 * @param sendTimeout      how long one batch waits for every broker acknowledgement
 * @param backoffMin       first backoff after a failed batch
 * @param backoffMax       backoff ceiling; the relay retries indefinitely rather than skipping money
 * @param retention        published rows older than this are deleted by the cleanup job
 * @param cleanupInterval  how often cleanup runs
 * @param cleanupBatchSize rows deleted per cleanup transaction, so a large backlog never holds one long transaction
 */
@ConfigurationProperties("zs.outbox")
public record OutboxProperties(int batchSize, Duration pollInterval, Duration sendTimeout, Duration backoffMin,
        Duration backoffMax, Duration retention, Duration cleanupInterval, int cleanupBatchSize) {

    public OutboxProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("zs.outbox.batch-size must be >= 1: " + batchSize);
        }
        if (cleanupBatchSize < 1) {
            throw new IllegalArgumentException("zs.outbox.cleanup-batch-size must be >= 1: " + cleanupBatchSize);
        }
        if (backoffMin == null || backoffMax == null || backoffMin.compareTo(backoffMax) > 0) {
            throw new IllegalArgumentException("zs.outbox backoff must satisfy min <= max");
        }
    }
}
