package dev.zerosum.instrument.adapter;

import dev.zerosum.instrument.core.PaymentInstrument;
import dev.zerosum.instrument.core.ProviderRegistry;
import java.util.List;
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
    FakeCardInstrument fakeCardInstrument(InstrumentProperties properties) {
        return new FakeCardInstrument(new ProviderHttp(properties.fakecardBaseUrl(), properties.connectTimeout(),
                properties.readTimeout()));
    }

    @Bean
    FakeBankInstrument fakeBankInstrument(InstrumentProperties properties) {
        return new FakeBankInstrument(new ProviderHttp(properties.fakebankBaseUrl(), properties.connectTimeout(),
                properties.readTimeout()), properties.quietPeriod());
    }

    @Bean
    ProviderRegistry providerRegistry(List<PaymentInstrument> instruments) {
        return new ProviderRegistry(instruments);
    }
}
