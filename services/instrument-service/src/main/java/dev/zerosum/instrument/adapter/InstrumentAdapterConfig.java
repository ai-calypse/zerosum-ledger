package dev.zerosum.instrument.adapter;

import dev.zerosum.instrument.core.PaymentInstrument;
import dev.zerosum.instrument.core.ProviderRegistry;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the adapters (D05-1).
 *
 * <p>The registry is handed the list of {@link PaymentInstrument} beans rather than constructing them, so adding a
 * provider means adding a bean here and changing nothing else — no core class learns a new name.
 */
@Configuration
@EnableConfigurationProperties(InstrumentProperties.class)
class InstrumentAdapterConfig {

    @Bean
    FakeCardInstrument fakeCardInstrument(InstrumentProperties properties,
            @Value("${zs.webhooks.secrets:}") String webhookSecrets, Clock clock) {
        return new FakeCardInstrument(new ProviderHttp(properties.fakecardBaseUrl(), properties.connectTimeout(),
                properties.readTimeout()), secrets(webhookSecrets), clock);
    }

    @Bean
    FakeBankInstrument fakeBankInstrument(InstrumentProperties properties,
            @Value("${zs.webhooks.secrets:}") String webhookSecrets, Clock clock) {
        return new FakeBankInstrument(new ProviderHttp(properties.fakebankBaseUrl(), properties.connectTimeout(),
                properties.readTimeout()), properties.quietPeriod(), secrets(webhookSecrets), clock);
    }

    /**
     * decision: D05-3, D00-8 — {@code ZS_WEBHOOK_SECRETS=current,previous}. The sender signs with the first; a
     * verifier accepts any of them, which is what lets the two sides be restarted independently during a rotation.
     *
     * <p>No default: a service started without the secret verifies nothing, so every webhook is refused rather than
     * every webhook accepted.
     */
    private static List<String> secrets(String configured) {
        return configured == null || configured.isBlank() ? List.of()
                : Arrays.stream(configured.split(",")).map(String::strip).filter(secret -> !secret.isEmpty()).toList();
    }

    @Bean
    ProviderRegistry providerRegistry(List<PaymentInstrument> instruments) {
        return new ProviderRegistry(instruments);
    }
}
