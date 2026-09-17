// decision: D05-8 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.recovery;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the D05-8 configuration block.
 *
 * <p>{@code @EnableConfigurationProperties} rather than {@code @Component} on the record: {@link SweeperProperties}
 * is constructor-bound, and constructor binding only happens for types registered this way.
 *
 * <p>Scheduling itself is enabled by the policy's configuration, which already carries {@code @EnableScheduling} for
 * the listener resume probe. It is not enabled a second time here — two declarations would work, but the one that
 * matters would no longer be obvious from either.
 */
@Configuration
@EnableConfigurationProperties(SweeperProperties.class)
class RecoveryConfiguration {
}
