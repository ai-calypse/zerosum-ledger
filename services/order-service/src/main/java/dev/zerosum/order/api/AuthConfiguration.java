package dev.zerosum.order.api;

import dev.zerosum.auth.TokenAuthFilter;
import dev.zerosum.auth.TokenRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared token filter (D03-4). Tokens come from the environment per D00-8 and have no defaults: a service
 * started without them authenticates nobody, which fails closed rather than open.
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
