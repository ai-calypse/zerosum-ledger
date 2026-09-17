package dev.zerosum.instrument.core;

import dev.zerosum.instrument.core.InstrumentExceptions.UnknownProviderException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a provider id to its adapter (D05-1).
 *
 * <p>Collects {@link PaymentInstrument} beans rather than constructing them, so adding a provider means adding a
 * bean and nothing else — no factory to edit, which is what "a third adapter without core changes" requires.
 *
 * <p><strong>Duplicate ids fail construction.</strong> Two adapters claiming the same id is a wiring mistake whose
 * runtime symptom would be money routed to whichever bean happened to win — a silent, non-deterministic error, so it
 * is turned into a startup failure instead.
 */
public class ProviderRegistry {

    private final Map<ProviderId, PaymentInstrument> byProvider;

    public ProviderRegistry(List<PaymentInstrument> instruments) {
        var collected = new LinkedHashMap<ProviderId, PaymentInstrument>();
        for (PaymentInstrument instrument : instruments) {
            PaymentInstrument existing = collected.putIfAbsent(instrument.provider(), instrument);
            if (existing != null) {
                throw new IllegalStateException("two payment instruments claim provider '" + instrument.provider()
                        + "': " + existing.getClass().getName() + " and " + instrument.getClass().getName());
            }
        }
        // Collections.unmodifiableMap, not Map.copyOf: the latter's iteration order is unspecified, which would make
        // registered() shuffle between JVM runs and any listing built on it non-deterministic.
        this.byProvider = Collections.unmodifiableMap(collected);
    }

    public PaymentInstrument get(ProviderId provider) {
        PaymentInstrument instrument = byProvider.get(provider);
        if (instrument == null) {
            throw new UnknownProviderException(String.valueOf(provider));
        }
        return instrument;
    }

    public PaymentInstrument get(String providerId) {
        return get(new ProviderId(providerId));
    }

    public List<ProviderId> registered() {
        return List.copyOf(byProvider.keySet());
    }
}
