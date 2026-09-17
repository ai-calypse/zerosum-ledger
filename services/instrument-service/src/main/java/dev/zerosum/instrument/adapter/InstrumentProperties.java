package dev.zerosum.instrument.adapter;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider endpoints and timeouts (D05-1).
 *
 * <p>The read timeout is the important one. It is what turns a slow provider into an {@code Unknown} outcome the
 * resolver can work with, instead of a thread parked until something else gives up.
 *
 * @param quietPeriod how long a provider without idempotency keys must be left alone before a resubmission; must stay
 *                    above FakeBank's maximum processing delay, or a resubmission could still duplicate a payout
 */
@ConfigurationProperties(prefix = "zs.instruments")
public record InstrumentProperties(String fakecardBaseUrl, String fakebankBaseUrl, Duration connectTimeout,
        Duration readTimeout, Duration quietPeriod) {

    public InstrumentProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        quietPeriod = quietPeriod == null ? Duration.ofSeconds(60) : quietPeriod;
    }
}
