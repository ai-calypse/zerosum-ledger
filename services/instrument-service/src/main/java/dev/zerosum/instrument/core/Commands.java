package dev.zerosum.instrument.core;

import dev.zerosum.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The commands adapters accept (D05-1).
 *
 * <p>Every command carries the attempt id, which serves as both the idempotency key and the client reference. One
 * identifier means a lookup after an uncertain outcome asks about exactly the thing that was submitted, with no
 * mapping table in between.
 *
 * <p>The deadline is the caller's, not the adapter's. An adapter that blocks past it turns a slow provider into a
 * stuck consumer thread.
 */
public final class Commands {

    private Commands() {
    }

    public record ChargeCommand(UUID attemptId, String instrumentToken, Money amount, Instant deadline) {
        public ChargeCommand {
            requireCommon(attemptId, amount, deadline);
            if (instrumentToken == null || instrumentToken.isBlank()) {
                throw new IllegalArgumentException("a charge needs an instrument token");
            }
        }
    }

    public record DisburseCommand(UUID attemptId, String instrumentToken, Money amount, Instant deadline) {
        public DisburseCommand {
            requireCommon(attemptId, amount, deadline);
            if (instrumentToken == null || instrumentToken.isBlank()) {
                throw new IllegalArgumentException("a disbursement needs a destination token");
            }
        }
    }

    /**
     * @param originalProviderRef the provider's reference for the charge being refunded; a refund without it is an
     *                            unattached credit, which no provider will accept and the ledger could not explain
     */
    public record RefundCommand(UUID attemptId, String originalProviderRef, Money amount, Instant deadline) {
        public RefundCommand {
            requireCommon(attemptId, amount, deadline);
            if (originalProviderRef == null || originalProviderRef.isBlank()) {
                throw new IllegalArgumentException("a refund must name the charge it reverses");
            }
        }
    }

    /** Resolves an uncertain outcome. Keyed by attempt id, which is what was sent as the client reference. */
    public record LookupQuery(UUID attemptId, Instant deadline) {
        public LookupQuery {
            if (attemptId == null) {
                throw new IllegalArgumentException("lookup needs an attempt id");
            }
        }
    }

    private static void requireCommon(UUID attemptId, Money amount, Instant deadline) {
        if (attemptId == null) {
            throw new IllegalArgumentException("attempt id is the idempotency key and must be set");
        }
        if (amount == null || amount.amountMinor() <= 0) {
            throw new IllegalArgumentException("amount must be a positive magnitude: " + amount);
        }
        if (deadline == null) {
            throw new IllegalArgumentException("a deadline is required so a slow provider cannot block a caller");
        }
    }
}
