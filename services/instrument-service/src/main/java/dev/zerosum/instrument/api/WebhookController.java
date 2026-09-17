// decision: D05-3, D05-13 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.api;

import dev.zerosum.instrument.core.InstrumentExceptions.InvalidSignatureException;
import dev.zerosum.instrument.core.InstrumentExceptions.UnknownProviderException;
import dev.zerosum.instrument.core.PaymentInstrument;
import dev.zerosum.instrument.core.ProviderEvent;
import dev.zerosum.instrument.core.ProviderRegistry;
import dev.zerosum.instrument.core.WebhookRequest;
import dev.zerosum.instrument.webhooks.AheadOfStateResolver;
import dev.zerosum.instrument.webhooks.WebhookReceiver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The provider webhook endpoint (S05-T11, TB2).
 *
 * <p><strong>This endpoint authenticates by signature, not by bearer token.</strong> A provider has no token of
 * ours; what it has is the shared secret, and the HMAC over the raw body plus a timestamp inside the signed string
 * is the whole of TB2. The token filter runs over {@code /v1/*} and simply attaches no principal here, which is
 * right: an unsigned or stale request is refused below, before anything is parsed.
 *
 * <p><strong>The body is taken as bytes.</strong> Binding to a record first and re-serializing would change
 * whitespace and key order, and a legitimate delivery would then fail to verify — the one bug that would make this
 * endpoint reject exactly the requests it exists to accept.
 *
 * <p>2xx means recorded and, where the table said so, applied. Anything else leaves the event with the provider,
 * whose redelivery schedule is then driven by a real failure rather than by our own bookkeeping.
 */
@RestController
class WebhookController {

    /** Master §5.11: bodies are capped at 64 KB. Checked before the HMAC, because it is the cheaper refusal. */
    private static final int BODY_CAP_BYTES = 64 * 1024;

    private static final String SIGNATURE_HEADER = "ZS-Signature";

    private final ProviderRegistry providers;
    private final WebhookReceiver receiver;
    private final AheadOfStateResolver resolver;

    WebhookController(ProviderRegistry providers, WebhookReceiver receiver, AheadOfStateResolver resolver) {
        this.providers = providers;
        this.receiver = receiver;
        this.resolver = resolver;
    }

    /**
     * What was done with the delivery. Returned rather than an empty 200 because "recorded, and here is why nothing
     * moved" is the answer an operator needs when a webhook appears to have had no effect.
     */
    record WebhookAck(String eventId, String disposition) {
    }

    @PostMapping("/v1/webhooks/{provider}")
    WebhookAck receive(HttpServletRequest request, @PathVariable String provider,
            @RequestBody(required = false) byte[] body) {
        byte[] payload = body == null ? new byte[0] : body;
        if (payload.length > BODY_CAP_BYTES) {
            throw InstrumentApiException.webhookTooLarge(BODY_CAP_BYTES);
        }

        PaymentInstrument instrument;
        try {
            instrument = providers.get(provider);
        } catch (UnknownProviderException | IllegalArgumentException unknown) {
            // IllegalArgumentException too: a path segment that is not even a valid provider id is not a 500.
            throw InstrumentApiException.unknownWebhookProvider(provider);
        }

        ProviderEvent event;
        try {
            event = instrument.parseWebhook(new WebhookRequest(payload, signatureHeader(request)));
        } catch (InvalidSignatureException unverified) {
            // Nothing is written and nothing is logged from the payload: an unverified request is a stranger's.
            throw InstrumentApiException.invalidSignature();
        } catch (IllegalArgumentException malformed) {
            // Verified, but not a payload this provider sends. Recorded nowhere, refused with the reason.
            throw InstrumentApiException.invalidWebhook(malformed.getMessage());
        }

        WebhookReceiver.Received received = receiver.receive(event, payload);
        if (received.disposition() == WebhookReceiver.Disposition.AHEAD_OF_STATE) {
            // After the commit above, never inside it: the lookup is a provider call and the handler has a budget.
            resolver.resolveLater(received.attemptId(), received.failureCode());
        }
        return new WebhookAck(event.providerEventId(), received.disposition().name().toLowerCase(Locale.ROOT));
    }

    /** Only the signature header is passed on; the rest of the request has no business inside an adapter. */
    private static Map<String, String> signatureHeader(HttpServletRequest request) {
        var headers = new HashMap<String, String>();
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (signature != null) {
            headers.put(SIGNATURE_HEADER, signature);
        }
        return headers;
    }
}
