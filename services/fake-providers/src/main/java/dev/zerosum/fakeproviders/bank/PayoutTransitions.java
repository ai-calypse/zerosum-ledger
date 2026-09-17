package dev.zerosum.fakeproviders.bank;

import dev.zerosum.fakeproviders.bank.FakeBankService.Due;
import dev.zerosum.fakeproviders.shared.MagicTokens;
import dev.zerosum.fakeproviders.shared.ProviderEventLog;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One simulated banking-day transition, applied atomically (S05-T02).
 *
 * <p>A separate bean from the scheduler on purpose: {@code @Transactional} is applied by a proxy, and a scheduler
 * calling its own annotated method would bypass it, committing the status change and its event separately. The
 * provider-event log would then be able to disagree with the payout it describes.
 *
 * <p>Every update names the status it expects to find. Two overlapping scheduler runs therefore cannot both apply the
 * same transition: the second updates no rows and emits no event.
 */
@Component
class PayoutTransitions {

    /** Parked far enough ahead that a terminal payout is never selected as due again. */
    private static final Duration NEVER = Duration.ofDays(3650);

    private final JdbcClient db;
    private final ProviderEventLog events;
    private final Duration bankingDay;

    PayoutTransitions(JdbcClient db, ProviderEventLog events, @Value("${zs.fakebank.banking-day:30s}") Duration bankingDay) {
        this.db = db;
        this.events = events;
        this.bankingDay = bankingDay;
    }

    @Transactional
    public void advance(Due payout, Instant now) {
        String returnCode = returnCodeFor(payout.destinationToken());
        if ("PENDING".equals(payout.status())) {
            if (MagicTokens.BANK_FAIL_ACCOUNT_CLOSED.equals(payout.destinationToken())) {
                fail(payout, now);
            } else {
                settle(payout, now, returnCode != null);
            }
        } else if ("SETTLED".equals(payout.status()) && returnCode != null) {
            returnPayout(payout, now, returnCode);
        }
    }

    private void settle(Due payout, Instant now, boolean willBeReturned) {
        // A payout destined to be returned still settles first. Jumping straight to returned would skip the
        // "paid, then taken back" sequence, which is the one the ledger has to survive.
        Instant next = now.plus(willBeReturned ? bankingDay : NEVER);
        int updated = db.sql("UPDATE bank_payouts SET status = 'SETTLED', settled_at = ?, process_at = ? "
                        + "WHERE payout_id = ? AND status = 'PENDING'")
                .params(Timestamp.from(now), Timestamp.from(next), payout.payoutId())
                .update();
        emit(updated, payout, "payout.settled", null);
    }

    private void fail(Due payout, Instant now) {
        int updated = db.sql("UPDATE bank_payouts SET status = 'FAILED', return_code = ?, process_at = ? "
                        + "WHERE payout_id = ? AND status = 'PENDING'")
                .params("account_closed", Timestamp.from(now.plus(NEVER)), payout.payoutId())
                .update();
        emit(updated, payout, "payout.failed", "account_closed");
    }

    private void returnPayout(Due payout, Instant now, String returnCode) {
        int updated = db.sql("UPDATE bank_payouts SET status = 'RETURNED', return_code = ?, returned_at = ?, "
                        + "process_at = ? WHERE payout_id = ? AND status = 'SETTLED'")
                .params(returnCode, Timestamp.from(now), Timestamp.from(now.plus(NEVER)), payout.payoutId())
                .update();
        emit(updated, payout, "payout.returned", returnCode);
    }

    private void emit(int rowsUpdated, Due payout, String eventType, String failureCode) {
        if (rowsUpdated == 1) {
            events.record(FakeBankService.PROVIDER, eventType, payout.payoutId(), payout.clientReference(),
                    payout.amountMinor(), payout.currency(), failureCode);
        }
    }

    private static String returnCodeFor(String destinationToken) {
        return MagicTokens.BANK_RETURN_R01.equals(destinationToken) ? "R01" : null;
    }
}
