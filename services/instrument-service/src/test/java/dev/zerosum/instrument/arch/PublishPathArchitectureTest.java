package dev.zerosum.instrument.arch;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.zerosum.outbox.arch.OutboxArchRules;
import org.junit.jupiter.api.Test;

/**
 * M4(b): only {@code libs/outbox} publishes to Kafka (ADR-0008).
 *
 * <p>Applied to instrument-service from S05-T09, when this service gained both a producer and a consumer. It matters
 * more here than anywhere: payment events are the facts the entire ledger is derived from, so one sent outside the
 * outbox is one that can describe money that never committed.
 *
 * <p>The rule ships as a {@code libs/outbox} test fixture rather than being copied, and the canary below proves it
 * actually fires — a rule that passes because it matches nothing is worse than no rule, because it reads like a
 * guarantee.
 */
class PublishPathArchitectureTest {

    private static final String APPLICATION_PACKAGE = "dev.zerosum.instrument";

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(APPLICATION_PACKAGE);
    }

    @Test
    void noApplicationClassPublishesToKafkaDirectly() {
        OutboxArchRules.assertOnlyTheOutboxPublishes(productionClasses(), APPLICATION_PACKAGE);
    }

    @Test
    void theRuleFiresOnAViolation() {
        JavaClasses withViolation = new ClassFileImporter().importPackages(getClass().getPackageName());

        AssertionError fired = assertThrows(AssertionError.class,
                () -> OutboxArchRules.assertOnlyTheOutboxPublishes(withViolation, APPLICATION_PACKAGE));
        assertTrue(fired.getMessage().contains("DeliberateViolation"),
                "the failure must name the offending class: " + fired.getMessage());
    }

    /**
     * Exists only to be caught by the rule above. Never instantiated, never wired, and in a test source set, so it
     * cannot reach production.
     */
    @SuppressWarnings("unused")
    private static final class DeliberateViolation {

        private final org.springframework.kafka.core.KafkaTemplate<String, String> forbidden;

        private DeliberateViolation(org.springframework.kafka.core.KafkaTemplate<String, String> forbidden) {
            this.forbidden = forbidden;
        }

        void publishDirectly(String topic, String key, String payload) {
            forbidden.send(topic, key, payload);
        }
    }
}
