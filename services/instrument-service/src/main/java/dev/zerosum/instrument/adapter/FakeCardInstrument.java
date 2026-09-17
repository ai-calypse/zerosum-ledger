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
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FakeCard behind the common contract (S05-T05).
 *
 * <p>The attempt id is sent as both the idempotency key and the client reference, so a lookup after an uncertain
 * outcome asks about exactly what was submitted, with no mapping table in between.
 */
public class FakeCardInstrument implements PaymentInstrument {

    private static final Logger log = LoggerFactory.getLogger(FakeCardInstrument.class);
    private static final ProviderId PROVIDER = new ProviderId("fakecard");

    private final ProviderHttp http;
    private final List<String> webhookSecrets;
    private final Clock clock;

    /**
     * @param webhookSecrets current first, then any previous one still being rotated out (D05-3). Empty means no
     *                       webhook can ever verify, which fails closed.
     */
    public FakeCardInstrument(ProviderHttp http, List<String> webhookSecrets, Clock clock) {
        this.http = http;
        this.webhookSecrets = List.copyOf(webhookSecrets);
        this.clock = clock;
    }

    @Override
    public ProviderId provider() {
        return PROVIDER;
    }

    @Override
    public Capabilities capabilities() {
        // settlementReports is false: the report generator is S06's (D06-1). Declaring a capability we would then
        // throw on is worse than declaring none, because the contract suite would certify the claim.
        return new Capabilities(true, false, true, true, false, null);
    }

    @Override
    public SubmitResult charge(ChargeCommand command) {
        var request = new ProviderWire.ChargeRequest(command.attemptId().toString(), command.instrumentToken(),
                command.amount().amountMinor(), command.amount().currency());
        return ProviderHttp.submitting(() -> classifyCharge(
                http.post("/fakecard/v1/charges", request, command.attemptId().toString())));
    }

    private SubmitResult classifyCharge(ProviderHttp.Response response) {
        SubmitResult transportLevel = Classification.of(response, PROVIDER);
        if (transportLevel != null) {
            return transportLevel;
        }
        var charge = response.as(ProviderWire.ChargeResponse.class);
        return "SUCCEEDED".equals(charge.status())
                ? new SubmitResult.Succeeded(charge.charge_id())
                : new SubmitResult.Declined(charge.decline_code());
    }

    @Override
    public SubmitResult refund(RefundCommand command) {
        var request = new ProviderWire.RefundRequest(command.originalProviderRef(), command.attemptId().toString(),
                command.amount().amountMinor());
        return ProviderHttp.submitting(() -> {
            ProviderHttp.Response response = http.post("/fakecard/v1/refunds", request,
                    command.attemptId().toString());
            SubmitResult transportLevel = Classification.of(response, PROVIDER);
            if (transportLevel != null) {
                return transportLevel;
            }
            var refund = response.as(ProviderWire.RefundResponse.class);
            // A refund that the provider refuses is final, not retryable: the money was never returned, and
            // resubmitting would not change the reason.
            return "SUCCEEDED".equals(refund.status())
                    ? new SubmitResult.Succeeded(refund.refund_id())
                    : new SubmitResult.Declined(refund.failure_code());
        });
    }

    @Override
    public SubmitResult disburse(DisburseCommand command) {
        throw new UnsupportedCapabilityException(PROVIDER, "disburse");
    }

    @Override
    public LookupResult lookup(LookupQuery query) {
        try {
            ProviderHttp.Response response = http.get(
                    "/fakecard/v1/charges?client_reference=" + query.attemptId());
            if (response.status() >= 500) {
                return new LookupResult.Unavailable("provider returned " + response.status());
            }
            var charges = response.as(ProviderWire.ChargeResponse[].class);
            if (charges.length == 0) {
                return new LookupResult.NotFound();
            }
            if (charges.length > 1) {
                // Idempotency keys should make this impossible. Saying so out loud beats silently taking the first.
                log.warn("fakecard returned {} charges for attempt {}; investigating the duplicate is I7's job",
                        charges.length, query.attemptId());
            }
            var charge = charges[0];
            return new LookupResult.Found("SUCCEEDED".equals(charge.status())
                    ? ProviderStatus.SUCCEEDED : ProviderStatus.DECLINED, charge.charge_id());
        } catch (ProviderHttp.ProviderUnreachable unreachable) {
            // Not NotFound: failing to ask is not the same as being told there is nothing.
            return new LookupResult.Unavailable(unreachable.reason());
        }
    }

    @Override
    public ProviderEvent parseWebhook(WebhookRequest request) {
        return ProviderWebhooks.parse(request, PROVIDER, webhookSecrets, clock.instant());
    }

    @Override
    public SettlementReport settlementReport(LocalDate reportDate) {
        throw new UnsupportedCapabilityException(PROVIDER, "settlementReport");
    }
}
