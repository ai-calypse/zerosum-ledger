package dev.zerosum.ledger;

import dev.zerosum.auth.ChaosGuard;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * decision: D08-3 — the chaos switches this service owns (master §8.5): A1 (skip the {@code applied_orders} dedupe),
 * A4 (skip the ledger's zero-sum re-check, §0.3 E8) and the F2 crash hook. All off unless {@link ChaosGuard} accepts
 * them; otherwise the guard throws and the service does not start.
 */
@Configuration
class ChaosConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChaosConfiguration.class);

    @Bean
    ChaosGuard.Active chaosSwitches(Environment environment) {
        ChaosGuard.Active active = ChaosGuard.check("ledger-service", List.of("A1", "A4", "F2"),
                environment::getProperty, environment.getActiveProfiles());
        log.info(active.logLine());
        return active;
    }
}
