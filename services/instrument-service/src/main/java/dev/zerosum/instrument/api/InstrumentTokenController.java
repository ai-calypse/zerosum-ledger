// decision: D05-6, D05-13 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.api;

import dev.zerosum.auth.Role;
import dev.zerosum.instrument.core.InstrumentExceptions.UnknownProviderException;
import dev.zerosum.instrument.core.ProviderRegistry;
import dev.zerosum.instrument.policy.InstrumentTokens;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registers the instrument an entity's money moves through (D05-6, master §5.6).
 *
 * <p>Writer role: registering an instrument decides where a rider's money will be taken from, which is a money
 * operation rather than a read.
 *
 * <p><strong>Re-registration replaces the token for future attempts only.</strong> An attempt copies the token when
 * it is created, so a charge already on its way keeps the instrument it was submitted with — and a refund goes back
 * to the card that paid rather than to whichever card was registered last.
 *
 * <p>The token is never returned, here or anywhere else: it is the one field that identifies a payment instrument,
 * and echoing it would put it in every client log (D00-8).
 */
@RestController
class InstrumentTokenController {

    /** D01-5 rule 3, so an entity that could never appear in a money order cannot be registered here either. */
    private static final Pattern ENTITY_ID = Pattern.compile("^(rider|driver|platform|provider):[A-Za-z0-9_-]{1,64}$");

    private final InstrumentTokens tokens;
    private final ProviderRegistry providers;

    InstrumentTokenController(InstrumentTokens tokens, ProviderRegistry providers) {
        this.tokens = tokens;
        this.providers = providers;
    }

    /** The request. Field names are snake_case through this service's global mapper setting. */
    record Registration(String entityId, String provider, String token) {
    }

    /** The response: what was registered, never the token itself. */
    record Registered(String entityId, String provider) {
    }

    @PostMapping("/v1/instrument-tokens")
    Registered register(HttpServletRequest request, @RequestBody Registration registration) {
        InstrumentAuthorization.require(request, Role.WRITER);

        if (registration == null || registration.entityId() == null
                || !ENTITY_ID.matcher(registration.entityId()).matches()) {
            throw InstrumentApiException.invalidRegistration(
                    "entity_id must look like rider:R1 (D01-5 rule 3)");
        }
        if (registration.token() == null || registration.token().isBlank()) {
            throw InstrumentApiException.invalidRegistration("token must not be blank");
        }
        try {
            // Checked against the registry, not a list of names: a token registered for a provider no adapter serves
            // would create attempts that could never be submitted, discovered only when a rider was not charged.
            providers.get(registration.provider() == null ? "" : registration.provider());
        } catch (UnknownProviderException | IllegalArgumentException unknown) {
            throw InstrumentApiException.unknownProvider(String.valueOf(registration.provider()));
        }

        tokens.register(registration.entityId(), registration.provider(), registration.token());
        return new Registered(registration.entityId(), registration.provider());
    }
}
