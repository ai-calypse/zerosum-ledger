package dev.zerosum.instrument;

import dev.zerosum.auth.ChaosGuard;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * decision: D08-3 — the chaos switches this service owns (master §8.5): A3 (a timeout resubmits the same attempt with a
 * fresh provider idempotency key, §0.3 E7), A5 (webhook {@code provider_events} dedupe) and the F3 crash hook (§0.3
 * E9). All off unless {@link ChaosGuard} accepts them; otherwise the guard throws and the service does not start.
 */
@Configuration
class ChaosConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChaosConfiguration.class);

    @Bean
    ChaosGuard.Active chaosSwitches(Environment environment) {
        ChaosGuard.Active active = ChaosGuard.check("instrument-service", List.of("A3", "A5", "F3"),
                environment::getProperty, environment.getActiveProfiles());
        log.info(active.logLine());
        return active;
    }
}
