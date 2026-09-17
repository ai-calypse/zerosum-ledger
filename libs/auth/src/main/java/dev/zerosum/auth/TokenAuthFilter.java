package dev.zerosum.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates a bearer token and attaches the {@link Principal} to the request (D03-4, TB1).
 *
 * <p>A plain servlet filter rather than a security framework: none is pinned in D00-1, and static per-role tokens need
 * nothing more. The filter only <em>authenticates</em>; each endpoint checks the role it requires, so an endpoint's
 * authorisation is visible in the endpoint rather than in a configuration file far from it.
 *
 * <p>An absent or unrecognised token leaves no principal and is not rejected here. The endpoint decides, which keeps
 * unauthenticated paths (health, for example) working without listing exceptions in the filter.
 */
public class TokenAuthFilter implements Filter {

    /** Request attribute holding the authenticated {@link Principal}, absent when the request carried no valid token. */
    public static final String PRINCIPAL_ATTRIBUTE = "dev.zerosum.auth.principal";

    private static final String BEARER = "Bearer ";

    private final TokenRegistry tokens;

    public TokenAuthFilter(TokenRegistry tokens) {
        this.tokens = tokens;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http) {
            bearerToken(http).flatMap(tokens::authenticate)
                    .ifPresent(principal -> request.setAttribute(PRINCIPAL_ATTRIBUTE, principal));
        }
        chain.doFilter(request, response);
    }

    /** The principal on this request, or empty when it was not authenticated. */
    public static Optional<Principal> principal(HttpServletRequest request) {
        return Optional.ofNullable((Principal) request.getAttribute(PRINCIPAL_ATTRIBUTE));
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        String token = header.substring(BEARER.length()).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
