package dev.zerosum.order.api;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fails startup if the configured mapper does not actually refuse malformed money-order input (D03-2).
 *
 * <p>Configuration that silently stops applying is the real risk: two earlier attempts set {@code spring.jackson.*}
 * keys that bound to nothing, and the service accepted floats, unknown fields and duplicate keys while looking
 * correctly configured. A Boot upgrade that moves the customizer hook again should break the service loudly at boot
 * rather than quietly at the trust boundary.
 *
 * <p>Separate from {@link StrictJsonConfiguration} on purpose: injecting the {@code JsonMapper} into the same class
 * that contributes the builder customizer makes that class depend on its own output.
 */
@Component
class StrictJsonSelfCheck {

    private final JsonMapper mapper;

    StrictJsonSelfCheck(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void assertTheMapperIsStrict() {
        refuses("{\"order_group_id\":\"g\",\"unknown_field\":1}", "unknown fields");
        refuses("{\"order_group_id\":\"g\",\"order_group_id\":\"h\"}", "duplicate keys");
        refuses("{\"entries\":[{\"entity_id\":\"rider:R1\",\"account\":\"receivable\",\"currency\":\"USD\","
                + "\"amount_minor\":2500.5}]}", "a float where an integer amount belongs");
    }

    private void refuses(String json, String what) {
        try {
            mapper.readValue(json, MoneyOrderRequest.class);
        } catch (RuntimeException expected) {
            return;
        }
        throw new IllegalStateException("request parsing is not strict: the mapper accepted " + what
                + ". Money endpoints would absorb malformed input instead of rejecting it (D03-2).");
    }
}
