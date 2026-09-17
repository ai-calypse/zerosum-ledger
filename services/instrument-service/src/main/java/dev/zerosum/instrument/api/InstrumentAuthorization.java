// decision: D03-4 — docs/step_03_order_service_outbox.md#decisions-and-outputs
package dev.zerosum.instrument.api;

import dev.zerosum.auth.Principal;
import dev.zerosum.auth.Role;
import dev.zerosum.auth.TokenAuthFilter;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Authorisation for instrument-service endpoints (D03-4, §0.3 C9).
 *
 * <p>The filter authenticates; each endpoint states the role it needs by calling this. Shared rather than copied,
 * because a second controller with its own copy of the check — and S05-T09 to T11 add three — is how one endpoint
 * quietly ends up enforcing a weaker rule than the rest.
 */
final class InstrumentAuthorization {

    private InstrumentAuthorization() {
    }

    static Principal require(HttpServletRequest request, Role required) {
        Principal principal = TokenAuthFilter.principal(request).orElseThrow(InstrumentApiException::unauthorized);
        if (!principal.role().satisfies(required)) {
            throw InstrumentApiException.forbidden(
                    "this endpoint requires the " + required.name().toLowerCase() + " role");
        }
        return principal;
    }
}
