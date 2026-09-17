package dev.zerosum.ledger.api;

import dev.zerosum.auth.TokenAuthFilter;
import dev.zerosum.auth.TokenRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared token filter into ledger-service (D03-4, §0.3 C9), which is what makes the 401 and 403 responses
 * already declared in {@code openapi/ledger-service.yaml} real rather than documentary.
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
        registration.addUrlPatterns("/v1/*");
        return registration;
    }
}
