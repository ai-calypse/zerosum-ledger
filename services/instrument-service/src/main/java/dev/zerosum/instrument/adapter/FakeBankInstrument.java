package dev.zerosum.instrument.adapter;

import dev.zerosum.instrument.core.Capabilities;
import dev.zerosum.instrument.core.Commands.ChargeCommand;
import dev.zerosum.instrument.core.Commands.DisburseCommand;
import dev.zerosum.instrument.core.Commands.LookupQuery;
import dev.zerosum.instrument.core.Commands.RefundCommand;
import dev.zerosum.instrument.core.InstrumentExceptions.UnsupportedCapabilityException;
import dev.zerosum.instrument.core.LookupResult;
import dev.zerosum.instrument.core.PaymentInstrument;
import dev.zerosum.instrument.core.ProviderEvent;
import dev.zerosum.instrument.core.ProviderId;
import dev.zerosum.instrument.core.ProviderStatus;
import dev.zerosum.instrument.core.SettlementReport;
import dev.zerosum.instrument.core.SubmitResult;
import dev.zerosum.instrument.core.WebhookRequest;
import java.time.Duration;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FakeBank behind the common contract (S05-T05).
 *
 * <p>No idempotency key is sent, because the provider has none. That is not an omission to fix later: it is the
 * property that makes the quiet period necessary, and hiding it behind a key the provider ignores would make an
 * unsafe retry look safe.
 */
public class FakeBankInstrument implements PaymentInstrument {

    private static final Logger log = LoggerFactory.getLogger(FakeBankInstrument.class);
    private static final ProviderId PROVIDER = new ProviderId("fakebank");

    private final ProviderHttp http;
    private final Duration quietPeriod;

    public FakeBankInstrument(ProviderHttp http, Duration quietPeriod) {
        this.http = http;
        this.quietPeriod = quietPeriod;
    }

    @Override
    public ProviderId provider() {
        return PROVIDER;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, true, false, false, false, quietPeriod);
    }

    @Override
    public SubmitResult disburse(DisburseCommand command) {
        var request = new ProviderWire.PayoutRequest(command.attemptId().toString(), command.instrumentToken(),
                command.amount().amountMinor(), command.amount().currency());
        // Deliberately no idempotency key: FakeBank would ignore it, and passing one would imply a guarantee.
        return ProviderHttp.submitting(() -> {
            ProviderHttp.Response response = http.post("/fakebank/v1/payouts", request, null);
            SubmitResult transportLevel = Classification.of(response, PROVIDER);
            if (transportLevel != null) {
                return transportLevel;
            }
            var payout = response.as(ProviderWire.PayoutResponse.class);
            // Accepted is not settled. The outcome arrives later, so this stays Pending until a lookup resolves it.
            return new SubmitResult.Pending(payout.payout_id());
        });
    }

    @Override
    public LookupResult lookup(LookupQuery query) {
        try {
            ProviderHttp.Response response = http.get("/fakebank/v1/payouts?client_reference=" + query.attemptId());
            if (response.status() >= 500) {
                return new LookupResult.Unavailable("provider returned " + response.status());
            }
            var payouts = response.as(ProviderWire.PayoutResponse[].class);
            if (payouts.length == 0) {
                // The submission never landed. This is the only answer that permits a resubmission, and only after
                // the quiet period has also elapsed (ADR-0010).
                return new LookupResult.NotFound();
            }
            if (payouts.length > 1) {
                // A duplicate already happened. Never resubmit on top of this; D05-5 requires it be logged and
                // counted rather than silently reduced to one.
                log.warn("fakebank returned {} payouts for attempt {}: a duplicate payout already exists, and I7 "
                        + "should be investigated before anything is resubmitted", payouts.length, query.attemptId());
            }
            var payout = payouts[0];
            return new LookupResult.Found(status(payout.status()), payout.payout_id());
        } catch (ProviderHttp.ProviderUnreachable unreachable) {
            return new LookupResult.Unavailable(unreachable.reason());
        }
    }

    private static ProviderStatus status(String providerStatus) {
        return switch (providerStatus) {
            case "PENDING" -> ProviderStatus.PENDING;
            case "SETTLED" -> ProviderStatus.SETTLED;
            case "FAILED" -> ProviderStatus.FAILED;
            case "RETURNED" -> ProviderStatus.RETURNED;
            default -> throw new IllegalStateException("unmapped fakebank status: " + providerStatus);
        };
    }

    @Override
    public SubmitResult charge(ChargeCommand command) {
        throw new UnsupportedCapabilityException(PROVIDER, "charge");
    }

    @Override
    public SubmitResult refund(RefundCommand command) {
        throw new UnsupportedCapabilityException(PROVIDER, "refund");
    }

    @Override
    public ProviderEvent parseWebhook(WebhookRequest request) {
        throw new UnsupportedOperationException(
                "webhook receipt is deferred with S05-T03: no sender exists and no HMAC secret is wired");
    }

    @Override
    public SettlementReport settlementReport(LocalDate reportDate) {
        throw new UnsupportedCapabilityException(PROVIDER, "settlementReport");
    }
}
