package dev.zerosum.outbox;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Drives {@link OutboxRelay#publishBatch()} continuously (D03-5, ADR-0008).
 *
 * <p>The loop sleeps only when the last batch was not full, so a backlog drains at full speed while an idle service
 * polls gently. A failed batch backs off with a cap and is retried indefinitely: the relay never gives up on a row,
 * because skipping one would lose money that is already committed.
 *
 * <p>Shutdown stops the loop and lets the in-flight batch finish its transaction, so rows are never marked without an
 * acknowledgement.
 */
public class OutboxRelayLoop implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayLoop.class);

    private final OutboxRelay relay;
    private final OutboxProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;

    public OutboxRelayLoop(OutboxRelay relay, OutboxProperties properties) {
        this.relay = relay;
        this.properties = properties;
    }

    /** Starts once the context is ready, so the relay never publishes from a half-built application. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::run, "outbox-relay");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        long backoffMillis = properties.backoffMin().toMillis();
        while (running.get()) {
            try {
                OutboxRelay.BatchResult result = relay.publishBatch();
                backoffMillis = properties.backoffMin().toMillis();
                if (!result.full()) {
                    sleep(properties.pollInterval().toMillis());
                }
            } catch (RuntimeException failure) {
                if (!running.get()) {
                    return;   // shutting down: the batch rolled back, nothing was marked
                }
                // Kafka being unavailable is expected, not exceptional: the API keeps accepting orders and the
                // oldest-unpublished gauge grows until the broker returns (master §6.6).
                log.warn("outbox batch failed; retrying after {} ms", backoffMillis, failure);
                sleep(ThreadLocalRandom.current().nextLong(backoffMillis / 2 + 1, backoffMillis + 1));
                backoffMillis = Math.min(properties.backoffMax().toMillis(), backoffMillis * 2);
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        running.set(false);
        Thread current = worker;
        if (current != null) {
            current.interrupt();
            // Bounded by the graceful shutdown budget: an in-flight batch either commits or rolls back.
            current.join(properties.sendTimeout().toMillis() + properties.pollInterval().toMillis());
        }
    }
}
