package dev.zerosum.instrument.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M7(b): core code never names a provider adapter.
 *
 * <p>This is the rule that makes "a third provider needs no core change" enforceable instead of aspirational. Without
 * it, the first time someone needs a FakeBank-specific detail in a state machine, the abstraction quietly stops being
 * one and nothing fails.
 */
class InstrumentBoundaryTest {

    /**
     * M7(b) as the master words it: no class <em>outside</em> the provider package names one. Restricting this to the
     * core package would leave every future API and scheduler class free to reach for a provider directly, which is
     * the exact drift the rule exists to stop.
     */
    private static final ArchRule CORE_DOES_NOT_KNOW_ADAPTERS = noClasses()
            .that().resideOutsideOfPackage("..instrument.adapter..")
            .should().dependOnClassesThat().resideInAPackage("..instrument.adapter..")
            .because("code outside the adapters must reach providers only through PaymentInstrument (ADR-0010)");

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.zerosum.instrument");

    @Test
    @DisplayName("the real core classes satisfy the boundary rule")
    void coreIsIndependentOfAdapters() {
        assertTrue(MAIN_CLASSES.contain(dev.zerosum.instrument.core.PaymentInstrument.class),
                "the import actually found the core classes");
        assertTrue(MAIN_CLASSES.contain(dev.zerosum.instrument.adapter.FakeCardInstrument.class),
                "and the adapters, so the rule has something to catch");
        CORE_DOES_NOT_KNOW_ADAPTERS.check(MAIN_CLASSES);
    }

    @Test
    @DisplayName("the rule fires on a canary that breaks it")
    void ruleFiresOnTheCanary() {
        // Test classes included this time, which is the only way the canary is in scope.
        JavaClasses withCanary = new ClassFileImporter().importPackages("dev.zerosum.instrument");

        EvaluationResult result = CORE_DOES_NOT_KNOW_ADAPTERS.evaluate(withCanary);

        assertTrue(result.hasViolation(), "the canary must break the rule");
        assertFalse(result.getFailureReport().getDetails().stream()
                .noneMatch(detail -> detail.contains("CoreImportingAdapterCanary")),
                "and it must be the canary that broke it, not something else");
    }
}
