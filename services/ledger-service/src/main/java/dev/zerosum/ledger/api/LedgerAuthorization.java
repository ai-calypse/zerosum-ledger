package dev.zerosum.ledger.api;

import dev.zerosum.auth.Principal;
import dev.zerosum.auth.Role;
import dev.zerosum.auth.TokenAuthFilter;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Role checks for the ledger read endpoints (D03-4, §0.3 C9).
 *
 * <p>The filter authenticates and attaches a principal; authorisation lives here so each endpoint states the role it
 * needs. Every ledger endpoint is reader-level, which writer and admin tokens also satisfy.
 */
final class LedgerAuthorization {

    private LedgerAuthorization() {
    }

    static Principal requireReader(HttpServletRequest request) {
        Principal principal = TokenAuthFilter.principal(request).orElseThrow(LedgerApiException::unauthorized);
        if (!principal.role().satisfies(Role.READER)) {
            throw LedgerApiException.forbidden("this endpoint requires the reader role");
        }
        return principal;
    }
}
