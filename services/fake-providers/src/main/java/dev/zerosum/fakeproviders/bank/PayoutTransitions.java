package dev.zerosum.fakeproviders.bank;

import dev.zerosum.fakeproviders.bank.FakeBankService.Due;
import dev.zerosum.fakeproviders.faults.Decision;
import dev.zerosum.fakeproviders.faults.FaultProfiles;
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
    private final FaultProfiles faults;
    private final Duration bankingDay;

    PayoutTransitions(JdbcClient db, ProviderEventLog events, FaultProfiles faults,
            @Value("${zs.fakebank.banking-day:30s}") Duration bankingDay) {
        this.db = db;
        this.events = events;
        this.faults = faults;
        this.bankingDay = bankingDay;
    }

    @Transactional
    public void advance(Due payout, Instant now) {
        if ("PENDING".equals(payout.status())) {
            if (MagicTokens.BANK_FAIL_ACCOUNT_CLOSED.equals(payout.destinationToken())) {
                fail(payout, now);
            } else {
                settle(payout, now, returnCodeFor(payout));
            }
        } else if ("SETTLED".equals(payout.status()) && payout.returnCode() != null) {
            // The return was decided when the payout settled, so a restart or a second tick reaches the same answer.
            returnPayout(payout, now, payout.returnCode());
        }
    }

    private void settle(Due payout, Instant now, String returnCode) {
        // A payout destined to be returned still settles first. Jumping straight to returned would skip the
        // "paid, then taken back" sequence, which is the one the ledger has to survive.
        Instant next = now.plus(returnCode != null ? faults.knobs(FakeBankService.PROVIDER).bankingDay(bankingDay)
                : NEVER);
        // The return code is written now, while it is only a decision: the payout is SETTLED, so nothing reads it as
        // money already taken back, and the settled → returned step no longer has to re-decide.
        int updated = db.sql("UPDATE bank_payouts SET status = 'SETTLED', settled_at = ?, process_at = ?, "
                        + "return_code = ? WHERE payout_id = ? AND status = 'PENDING'")
                .params(Timestamp.from(now), Timestamp.from(next), returnCode, payout.payoutId())
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

    /**
     * The magic token decides first, then the seeded return-rate knob (D05-2).
     *
     * <p>That order matters: a test that asked for a return by token must get one whatever profile is active, or
     * every fixture would have to know the current knobs to know what it was testing.
     */
    private String returnCodeFor(Due payout) {
        if (MagicTokens.BANK_RETURN_R01.equals(payout.destinationToken())) {
            return "R01";
        }
        return faults.fires(FakeBankService.PROVIDER, Decision.RETURN, payout.payoutId()) ? "R01" : null;
    }
}
