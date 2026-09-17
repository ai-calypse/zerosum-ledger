// decision: D03-4 — docs/step_03_order_service_outbox.md#decisions-and-outputs
package dev.zerosum.instrument.api;

import dev.zerosum.auth.TokenAuthFilter;
import dev.zerosum.auth.TokenRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared token filter into instrument-service (D03-4, §0.3 C9). Copied from order-service and
 * ledger-service rather than varied: three services authenticating three ways is how one of them ends up weaker.
 *
 * <p>Tokens come from the environment per D00-8 and have no defaults. A service started without them authenticates
 * nobody, which fails closed — and is why the {@code ZS_*_TOKEN} variables had to be added to this service's
 * {@code docker-compose.yml} block in the same change as these endpoints.
 */
@Configuration
class AuthConfiguration {

    @Bean
    TokenRegistry tokenRegistry(
            @Value("${zs.auth.writer-tokens:}") String writerTokens,
            @Value("${zs.auth.reader-token:}") String readerToken,
            @Value("${zs.auth.admin-token:}") String adminToken) {
        return TokenRegistry.of(writerTokens, readerToken, adminToken);
    }

    @Bean
    FilterRegistrationBean<TokenAuthFilter> tokenAuthFilter(TokenRegistry tokens) {
        var registration = new FilterRegistrationBean<>(new TokenAuthFilter(tokens));
        registration.addUrlPatterns("/v1/*");   // actuator health stays reachable without a token
        return registration;
    }
}
