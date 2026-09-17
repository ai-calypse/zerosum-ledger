package dev.zerosum.outbox.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;

/**
 * M4(b): the outbox relay is the only publish path (ADR-0008).
 *
 * <p>Shipped as a test fixture so every producing service applies the same rule instead of copying it. S05's
 * instrument-service reuses it unchanged.
 *
 * <p>The guarantee this protects is ordering and durability: an order published directly from a request thread has
 * not necessarily committed, and it bypasses the per-key sequencing the relay provides. A rule is the only way to
 * keep that true as code is added, because the mistake looks perfectly reasonable at the call site.
 */
public final class OutboxArchRules {

    private OutboxArchRules() {
    }

    /**
     * Kafka producer types an application class must not touch directly, as one regex over the fully-qualified name.
     *
     * <p>ArchUnit 1.5 offers {@code haveNameMatching(String)} for this; there is no
     * {@code haveFullyQualifiedNameMatching}. The pattern is anchored so a class merely <em>named</em> like one of
     * these elsewhere cannot match by accident.
     */
    private static final String PRODUCER_TYPE_PATTERN =
            "^(org\\.apache\\.kafka\\.clients\\.producer\\.(KafkaProducer|Producer)"
                    + "|org\\.springframework\\.kafka\\.core\\.(KafkaTemplate|ProducerFactory))$";

    /**
     * No class outside {@code dev.zerosum.outbox} may call a Kafka producer.
     *
     * @param applicationPackage the service package to check, for example {@code dev.zerosum.order}
     */
    public static ArchRule onlyTheOutboxPublishes(String applicationPackage) {
        return noClasses()
                .that().resideInAPackage(applicationPackage + "..")
                .and().resideOutsideOfPackage("dev.zerosum.outbox..")
                .should().dependOnClassesThat().haveNameMatching(PRODUCER_TYPE_PATTERN)
                .because("only libs/outbox may publish to Kafka (M4(b), ADR-0008): a direct send can publish an order "
                        + "that has not committed, and bypasses the per-key ordering the relay guarantees");
    }

    /** Runs the rule against already-imported classes. */
    public static void assertOnlyTheOutboxPublishes(JavaClasses classes, String applicationPackage) {
        onlyTheOutboxPublishes(applicationPackage).check(classes);
    }
}
