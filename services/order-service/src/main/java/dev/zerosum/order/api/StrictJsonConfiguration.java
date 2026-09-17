package dev.zerosum.order.api;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Strict request parsing at the trust boundary (D03-2, TB1).
 *
 * <p>Money endpoints must reject rather than absorb malformed input:
 * <ul>
 *   <li>unknown fields, so a body carrying a server-assigned {@code order_id}, {@code source} or {@code created_at}
 *       fails instead of appearing to have set it;</li>
 *   <li>duplicate keys, which would make the canonical hashed request ambiguous (D03-3);</li>
 *   <li>a float or string where an integer amount belongs — {@code 2500.5} is never quietly truncated to 2500.</li>
 * </ul>
 *
 * <p>Configured through {@link JsonMapperBuilderCustomizer} rather than {@code spring.jackson.*} properties: Boot 4.1
 * builds its {@code JsonMapper} from these customizers and that mapper is what the HTTP message converter uses, while
 * the equivalent property keys have moved between Boot versions ({@code spring.jackson.parser.*} no longer exists) and
 * a key that binds to nothing fails silently — the worst failure mode for input validation.
 *
 * <p>This class contributes the customizer only. The check that it actually took effect lives in
 * {@link StrictJsonSelfCheck}, because a class that both defines a customizer and injects the mapper built from it
 * depends on its own output, which Spring rejects as a circular reference.
 */
@Configuration
class StrictJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictRequestParsing() {
        return builder -> builder
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                // A fractional amount is a different value, not a formatting variant of an integer one.
                .withCoercionConfig(LogicalType.Integer, config -> {
                    config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                    config.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                });
    }
}
