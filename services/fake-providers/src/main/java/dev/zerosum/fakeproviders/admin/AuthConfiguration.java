package dev.zerosum.fakeproviders.admin;

import dev.zerosum.auth.TokenAuthFilter;
import dev.zerosum.auth.TokenRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared token filter over the admin endpoints only (D03-4, TB4).
 *
 * <p>The provider APIs stay unauthenticated: they simulate an external system on an internal network and hold no
 * value worth protecting, while the fault and truth endpoints control and expose that system's reality.
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
        registration.addUrlPatterns("/admin/*");
        return registration;
    }
}
