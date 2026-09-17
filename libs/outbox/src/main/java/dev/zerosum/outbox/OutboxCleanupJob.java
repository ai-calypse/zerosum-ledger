package dev.zerosum.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes published outbox rows past their retention (D03-5).
 *
 * <p>Runs in bounded batches so a large backlog never holds one long transaction, and never touches unpublished rows.
 *
 * <p>Known consequence: deleted rows are ones a full ledger rebuild from the outbox would have needed. That is
 * recorded as a limitation rather than solved here; the replay tool is conditional and unassigned.
 */
public class OutboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final OutboxRelay relay;

    public OutboxCleanupJob(OutboxRelay relay) {
        this.relay = relay;
    }

    // The property placeholder, not a SpEL bean reference: @EnableConfigurationProperties registers the bean as
    // "zs.outbox-dev.zerosum.outbox.OutboxProperties", so "#{@outboxProperties...}" resolved to nothing and took the
    // whole application context down with it.
    @Scheduled(fixedDelayString = "${zs.outbox.cleanup-interval}")
    public void cleanUp() {
        int deleted = relay.cleanUp();
        if (deleted > 0) {
            log.info("deleted {} published outbox rows past retention", deleted);
        }
    }
}
