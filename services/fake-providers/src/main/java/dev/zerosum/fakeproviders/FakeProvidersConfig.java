package dev.zerosum.fakeproviders;

import dev.zerosum.fakeproviders.faults.FaultInjectionFilter;
import dev.zerosum.fakeproviders.faults.FaultProfiles;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wiring for the simulator (D05-2).
 *
 * <p>Time is injected rather than read from {@code Instant.now()}, so a test can compress a banking day instead of
 * waiting for one. A simulator whose clock cannot be moved is a simulator that makes tests sleep.
 */
@Configuration
@EnableScheduling
class FakeProvidersConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Fault injection covers the two provider APIs and nothing else: the admin endpoints must keep answering while
     * the providers they control are failing, or a test could not turn a fault back off.
     */
    @Bean
    FilterRegistrationBean<FaultInjectionFilter> faultInjectionFilter(FaultProfiles profiles,
            // decision: D05-2, D05-14 — the withhold must exceed the adapter read timeout (5 s per
            // services/instrument-service/src/main/resources/application.yml), or "timeout after commit" would
            // return in time and inject nothing.
            @Value("${zs.faults.timeout-after-commit-withhold:7s}") Duration withhold) {
        var registration = new FilterRegistrationBean<>(new FaultInjectionFilter(profiles, withhold));
        registration.addUrlPatterns("/fakecard/*", "/fakebank/*");
        return registration;
    }
}
