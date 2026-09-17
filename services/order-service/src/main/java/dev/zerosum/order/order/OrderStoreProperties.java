package dev.zerosum.order.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Order-store settings. Values live in {@code application.yml} with trace comments; this record only declares them.
 *
 * @param lockTimeout transaction-local bound on waiting for a concurrent same-key insert. Exceeding it is reported as
 *                    IN_PROGRESS rather than holding the caller's request open indefinitely (D03-3)
 */
@ConfigurationProperties("orders.store")
public record OrderStoreProperties(Duration lockTimeout) {

    public OrderStoreProperties {
        if (lockTimeout == null || lockTimeout.isNegative() || lockTimeout.isZero()) {
            throw new IllegalArgumentException("orders.store.lock-timeout must be positive: " + lockTimeout);
        }
    }
}
