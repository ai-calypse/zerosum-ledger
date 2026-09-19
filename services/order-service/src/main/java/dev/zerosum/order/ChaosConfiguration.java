package dev.zerosum.order;

import dev.zerosum.auth.ChaosGuard;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * decision: D08-3 — the chaos switches this service owns (master §8.5): A2 (dual write instead of the outbox, the seam
 * inside {@code libs/outbox}, §0.3 C10) and A4 (the zero-sum rule in the API and the deferred trigger, §0.3 E8). All
 * off unless {@link ChaosGuard} accepts them; otherwise the guard throws and the service does not start.
 */
@Configuration
class ChaosConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChaosConfiguration.class);

    @Bean
    ChaosGuard.Active chaosSwitches(Environment environment) {
        ChaosGuard.Active active = ChaosGuard.check("order-service", List.of("A2", "A4"),
                environment::getProperty, environment.getActiveProfiles());
        log.info(active.logLine());
        return active;
    }
}
