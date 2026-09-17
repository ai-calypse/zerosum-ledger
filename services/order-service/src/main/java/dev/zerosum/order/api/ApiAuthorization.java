package dev.zerosum.order.api;

import dev.zerosum.auth.Principal;
import dev.zerosum.auth.Role;
import dev.zerosum.auth.TokenAuthFilter;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Authorisation for order-service endpoints (D03-4).
 *
 * <p>The filter authenticates; each endpoint states the role it needs by calling this. Shared rather than copied,
 * because a second controller with its own copy of the check is how one endpoint quietly ends up enforcing a weaker
 * rule than the rest.
 */
final class ApiAuthorization {

    private ApiAuthorization() {
    }

    static Principal require(HttpServletRequest request, Role required) {
        Principal principal = TokenAuthFilter.principal(request).orElseThrow(ApiException::unauthorized);
        if (!principal.role().satisfies(required)) {
            throw ApiException.forbidden("this endpoint requires the " + required.name().toLowerCase() + " role");
        }
        return principal;
    }
}
