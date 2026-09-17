// decision: D05-1 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.core;

import dev.zerosum.instrument.core.Commands.ChargeCommand;
import dev.zerosum.instrument.core.Commands.DisburseCommand;
import dev.zerosum.instrument.core.Commands.LookupQuery;
import dev.zerosum.instrument.core.Commands.RefundCommand;
import dev.zerosum.instrument.core.InstrumentExceptions.InvalidSignatureException;
import java.time.LocalDate;

/**
 * The contract every payment provider is reached through (D05-1, ADR pending in H.1).
 *
 * <p>This is the abstraction the whole step exists to demonstrate: core code moves money without naming a provider,
 * and a third adapter can be added without changing any state machine (master H4).
 *
 * <p>Implementations must be free of core business rules. They translate: our command into the provider's request,
 * and the provider's answer into {@link SubmitResult}, {@link LookupResult} or {@link ProviderEvent}. Deciding what
 * a declined charge *means* is the state machine's job, not the adapter's.
 */
public interface PaymentInstrument {

    ProviderId provider();

    Capabilities capabilities();

    /** Collect from a payer. */
    SubmitResult charge(ChargeCommand command);

    /** Pay out to a payee. */
    SubmitResult disburse(DisburseCommand command);

    /** Return collected funds. */
    SubmitResult refund(RefundCommand command);

    /**
     * Resolve an outcome we are unsure of. The one operation every provider must support, because without it an
     * {@link SubmitResult.Unknown} could never be settled except by guessing.
     */
    LookupResult lookup(LookupQuery query);

    /** Verify and normalize an inbound webhook. Throws rather than returning a value for an unverified request. */
    ProviderEvent parseWebhook(WebhookRequest request) throws InvalidSignatureException;

    /** Declared for S06 (D06-1); providers without {@code settlementReports} throw unsupported. */
    SettlementReport settlementReport(LocalDate reportDate);
}
