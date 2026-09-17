package dev.zerosum.fakeproviders;

import java.time.Clock;
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
}
