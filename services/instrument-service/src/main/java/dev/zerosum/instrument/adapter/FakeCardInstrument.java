package dev.zerosum.instrument.adapter;

import dev.zerosum.instrument.core.Capabilities;
import dev.zerosum.instrument.core.Commands.ChargeCommand;
import dev.zerosum.instrument.core.Commands.DisburseCommand;
import dev.zerosum.instrument.core.Commands.LookupQuery;
import dev.zerosum.instrument.core.Commands.RefundCommand;
import dev.zerosum.instrument.core.InstrumentExceptions.ProviderUnavailableException;
import dev.zerosum.instrument.core.InstrumentExceptions.ReportNotReadyException;
import dev.zerosum.instrument.core.InstrumentExceptions.UnsupportedCapabilityException;
import dev.zerosum.instrument.core.LookupResult;
import dev.zerosum.instrument.core.PaymentInstrument;
import dev.zerosum.instrument.core.ProviderEvent;
import dev.zerosum.instrument.core.ProviderId;
import dev.zerosum.instrument.core.ProviderStatus;
import dev.zerosum.instrument.core.SettlementReport;
import dev.zerosum.instrument.core.SubmitResult;
import dev.zerosum.instrument.core.WebhookRequest;
import java.time.LocalDate;
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

    public FakeCardInstrument(ProviderHttp http) {
        this.http = http;
    }

    @Override
    public ProviderId provider() {
        return PROVIDER;
    }

    @Override
    public Capabilities capabilities() {
        // settlementReports became true in S06-T01, when the generator behind it actually started existing (D06-1).
        // The shared contract suite skips that case for any adapter declaring false, so flipping this is what makes
        // the suite run it rather than report it as not applicable.
        return new Capabilities(true, false, true, true, true, null);
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
        throw new UnsupportedOperationException(
                "webhook receipt is deferred with S05-T03: no sender exists and no HMAC secret is wired, so parsing "
                        + "an unauthenticated payload here would be a security hole standing in for a feature");
    }

    /**
     * Fetches and maps one day's settlement report (D06-1).
     *
     * <p>The three outcomes are kept apart deliberately. A closed day is a report; an open day is
     * {@link ReportNotReadyException}, which the caller retries on the next cycle; anything else is
     * {@link ProviderUnavailableException}, which must never be mistaken for a day on which nothing settled.
     * Collapsing the last two into an empty report is how a reconciler ends up booking a settlement of zero against
     * real captures.
     */
    @Override
    public SettlementReport settlementReport(LocalDate reportDate) {
        ProviderHttp.Response response;
        try {
            response = http.get("/fakecard/v1/settlement-reports/" + reportDate);
        } catch (ProviderHttp.ProviderUnreachable unreachable) {
            throw new ProviderUnavailableException(PROVIDER, unreachable.reason());
        }
        if (response.status() == 409) {
            throw new ReportNotReadyException(PROVIDER, reportDate);
        }
        if (response.status() != 200) {
            throw new ProviderUnavailableException(PROVIDER, "provider returned " + response.status());
        }

        var report = response.as(ProviderWire.SettlementReportResponse.class);
        return new SettlementReport(PROVIDER, reportDate, report.report_id(),
                report.lines().stream()
                        .map(line -> new SettlementReport.Line(line.provider_ref(), line.client_reference(),
                                line.kind(), line.currency(), line.gross_minor(), line.fee_minor()))
                        .toList(),
                report.totals().stream()
                        .map(totals -> new SettlementReport.Totals(totals.currency(), totals.gross_minor(),
                                totals.fee_minor(), totals.net_minor()))
                        .toList());
    }
}
