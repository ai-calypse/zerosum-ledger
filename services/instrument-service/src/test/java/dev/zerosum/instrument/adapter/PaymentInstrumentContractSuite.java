package dev.zerosum.instrument.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.zerosum.instrument.core.Capabilities;
import dev.zerosum.instrument.core.Commands.ChargeCommand;
import dev.zerosum.instrument.core.Commands.DisburseCommand;
import dev.zerosum.instrument.core.Commands.LookupQuery;
import dev.zerosum.instrument.core.Commands.RefundCommand;
import dev.zerosum.instrument.core.InstrumentExceptions.InvalidSignatureException;
import dev.zerosum.instrument.core.InstrumentExceptions.UnsupportedCapabilityException;
import dev.zerosum.instrument.core.LookupResult;
import dev.zerosum.instrument.core.PaymentInstrument;
import dev.zerosum.instrument.core.ProviderEvent;
import dev.zerosum.instrument.core.SubmitResult;
import dev.zerosum.instrument.core.WebhookRequest;
import dev.zerosum.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The contract every adapter must satisfy (S05-T06, master §5.9).
 *
 * <p>One suite, run against each provider, so "a third adapter needs no core change" is a claim with a test behind it
 * rather than an intention. Cases that need a capability the adapter does not have are **skipped with an assumption
 * naming that capability**, so the report distinguishes "not applicable here" from "passed".
 */
abstract class PaymentInstrumentContractSuite {

    private static final Money TEN = Money.of(1_000, "USD");
    private static final Duration READ_TIMEOUT = Duration.ofMillis(400);

    /** decision: D05-3 — the secrets every adapter under this suite verifies with; never a deployed value. */
    protected static final String WEBHOOK_SECRET = "contract-current-secret";
    protected static final String PREVIOUS_WEBHOOK_SECRET = "contract-previous-secret";

    protected ProviderStub stub;

    /** Builds the adapter under test against the stub. */
    protected abstract PaymentInstrument instrument(ProviderHttp http);

    @BeforeEach
    void startStub() {
        stub = ProviderStub.start();
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    private PaymentInstrument adapter() {
        return instrument(new ProviderHttp(stub.baseUrl(), Duration.ofSeconds(1), READ_TIMEOUT));
    }

    private static UUID attempt() {
        return UUID.randomUUID();
    }

    private static Instant deadline() {
        return Instant.now().plusSeconds(30);
    }

    /** Submits through whichever direction this provider supports, so the case is genuinely shared. */
    private SubmitResult submit(PaymentInstrument instrument) {
        Capabilities capabilities = instrument.capabilities();
        return capabilities.charge()
                ? instrument.charge(new ChargeCommand(attempt(), "tok_card_ok", TEN, deadline()))
                : instrument.disburse(new DisburseCommand(attempt(), "tok_bank_ok", TEN, deadline()));
    }

    @Test
    @DisplayName("an operation the provider does not support throws rather than pretending")
    void unsupportedOperationsThrow() {
        PaymentInstrument instrument = adapter();
        Capabilities capabilities = instrument.capabilities();

        if (!capabilities.charge()) {
            assertThatThrownBy(() -> instrument.charge(new ChargeCommand(attempt(), "tok", TEN, deadline())))
                    .isInstanceOf(UnsupportedCapabilityException.class);
        }
        if (!capabilities.disburse()) {
            assertThatThrownBy(() -> instrument.disburse(new DisburseCommand(attempt(), "tok", TEN, deadline())))
                    .isInstanceOf(UnsupportedCapabilityException.class);
        }
        if (!capabilities.refund()) {
            assertThatThrownBy(() -> instrument.refund(new RefundCommand(attempt(), "ch_1", TEN, deadline())))
                    .isInstanceOf(UnsupportedCapabilityException.class);
        }
    }

    @Test
    @DisplayName("an accepted submission is succeeded or pending, never unknown")
    void submissionIsAccepted() {
        SubmitResult result = submit(adapter());

        // Which of the two depends on whether the provider answers synchronously; both mean "the provider has it".
        assertThat(result).isInstanceOfAny(SubmitResult.Succeeded.class, SubmitResult.Pending.class);
    }

    @Test
    @DisplayName("a provider error is unknown, because it may have applied the request before failing to answer")
    void serverErrorIsUnknown() {
        stub.forcedStatus = 500;

        assertThat(submit(adapter())).isInstanceOf(SubmitResult.Unknown.class);
    }

    @Test
    @DisplayName("a read timeout is unknown, not a failure: the request was sent")
    void readTimeoutIsUnknown() {
        stub.stallMillis = READ_TIMEOUT.toMillis() * 4;

        SubmitResult result = submit(adapter());

        assertThat(result).isInstanceOf(SubmitResult.Unknown.class);
        assertThat(((SubmitResult.Unknown) result).reason()).contains("timeout");
    }

    @Test
    @DisplayName("a lookup that finds nothing is NotFound")
    void lookupNotFound() {
        stub.lookupBody = "[]";

        assertThat(adapter().lookup(new LookupQuery(attempt(), deadline()))).isInstanceOf(LookupResult.NotFound.class);
    }

    @Test
    @DisplayName("a lookup that could not be answered is Unavailable, which is not the same as NotFound")
    void lookupUnavailableOnServerError() {
        stub.forcedStatus = 503;

        // Only NotFound permits a resubmission. Collapsing these two would turn an outage into a double payment.
        assertThat(adapter().lookup(new LookupQuery(attempt(), deadline())))
                .isInstanceOf(LookupResult.Unavailable.class);
    }

    @Test
    @DisplayName("a lookup against an unreachable provider is Unavailable, never NotFound")
    void lookupUnavailableWhenUnreachable() {
        PaymentInstrument instrument = adapter();
        stub.close();

        assertThat(instrument.lookup(new LookupQuery(attempt(), deadline())))
                .isInstanceOf(LookupResult.Unavailable.class);
    }

    /**
     * M7(a): every adapter verifies before it parses, and refuses what does not verify (S05-T11, TB2).
     *
     * <p>Shared rather than written per provider, because "a third adapter needs no core change" includes the
     * webhook path: a new provider that accepted an unsigned payload would pass every other test in this suite.
     */
    @Test
    @DisplayName("a signed webhook parses, and a forged, tampered or stale one does not")
    void parsesWebhooks() {
        PaymentInstrument instrument = adapter();
        Instant now = Instant.now();
        byte[] body = webhookBody(instrument);

        ProviderEvent event = instrument.parseWebhook(signed(body, now, WEBHOOK_SECRET));

        assertThat(event.providerEventId()).isEqualTo("evt_contract");
        assertThat(event.provider()).isEqualTo(instrument.provider());
        assertThat(event.clientReference()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(event.amount()).isEqualTo(Money.of(4_200, "USD"));
        assertThat(event.status()).isNotNull();

        // Rotation: the previous secret still verifies, which is what lets the two sides restart independently.
        assertThat(instrument.parseWebhook(signed(body, now, PREVIOUS_WEBHOOK_SECRET)).providerEventId())
                .isEqualTo("evt_contract");

        // The signature is genuine and covers the ORIGINAL bytes; the amount was changed afterwards. Signing the
        // tampered bytes instead would prove nothing — it is what an honest sender does.
        byte[] tampered = new String(body, java.nio.charset.StandardCharsets.UTF_8)
                .replace("4200", "990000").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> instrument.parseWebhook(new WebhookRequest(tampered,
                java.util.Map.of("ZS-Signature", TestWebhookSigner.header(now, body, WEBHOOK_SECRET)))))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> instrument.parseWebhook(signed(body, now, "someone-elses-secret")))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> instrument.parseWebhook(
                signed(body, now.minus(Duration.ofSeconds(301)), WEBHOOK_SECRET)))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> instrument.parseWebhook(new WebhookRequest(body, java.util.Map.of())))
                .as("an unsigned delivery is refused, not parsed").isInstanceOf(InvalidSignatureException.class);
    }

    /** One event of whatever kind this provider actually emits, so the case is genuinely shared. */
    private static byte[] webhookBody(PaymentInstrument instrument) {
        String eventType = instrument.capabilities().charge() ? "charge.succeeded" : "payout.settled";
        return ("{\"event_id\":\"evt_contract\",\"provider\":\"" + instrument.provider()
                + "\",\"event_type\":\"" + eventType + "\",\"provider_ref\":\"ref_1\","
                + "\"client_reference\":\"11111111-2222-3333-4444-555555555555\",\"amount_minor\":4200,"
                + "\"currency\":\"USD\",\"failure_code\":null,\"occurred_at\":\"2026-01-01T00:00:00Z\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static WebhookRequest signed(byte[] body, Instant at, String secret) {
        return new WebhookRequest(body,
                java.util.Map.of("ZS-Signature", TestWebhookSigner.header(at, body, secret)));
    }

    @Test
    @DisplayName("settlement reports")
    void settlementReport() {
        PaymentInstrument instrument = adapter();
        assumeTrue(instrument.capabilities().settlementReports(),
                "capability 'settlementReports' is not implemented: the report generator is S06-T01 (D06-1)");

        assertThat(instrument.settlementReport(LocalDate.of(2026, 1, 1))).isNotNull();
    }
}
