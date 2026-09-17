package dev.zerosum.fakeproviders.bank;

import dev.zerosum.fakeproviders.bank.FakeBankService.Due;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the simulated banking day (S05-T02).
 *
 * <p>The schedule lives in the database, not in an in-memory timer: a payout whose transition was only a pending task
 * would be stranded by a restart and would look to the resolver like a bank that never answers.
 */
@Component
class PayoutLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PayoutLifecycle.class);

    private final FakeBankService bank;
    private final PayoutTransitions transitions;
    private final Clock clock;

    PayoutLifecycle(FakeBankService bank, PayoutTransitions transitions, Clock clock) {
        this.bank = bank;
        this.transitions = transitions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${zs.fakebank.lifecycle-interval:1s}")
    void advance() {
        Instant now = clock.instant();
        for (Due payout : bank.due(now)) {
            try {
                transitions.advance(payout, now);
            } catch (RuntimeException failure) {
                // One stuck payout must not hold up the others: they are unrelated money.
                log.warn("could not advance payout {}", payout.payoutId(), failure);
            }
        }
    }
}
