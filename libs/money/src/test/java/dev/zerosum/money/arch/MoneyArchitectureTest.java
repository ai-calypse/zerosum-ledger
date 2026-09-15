package dev.zerosum.money.arch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import dev.zerosum.archcanary.BigDecimalCanary;
import dev.zerosum.archcanary.FloatingPointCanary;
import dev.zerosum.archcanary.JdkCurrencyCanary;
import dev.zerosum.archcanary.NonJdkDependencyCanary;
import dev.zerosum.archcanary.RoundingModeCanary;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** M1(c): rules A–E pass on the real money classes, and each rule fires on its canary. */
class MoneyArchitectureTest {

    /** libs/money main classes and test fixtures; test classes (including the canary package) are excluded. */
    private static final JavaClasses MONEY_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.zerosum.money");

    private static final DescribedPredicate<JavaClass> CANARIES =
            JavaClass.Predicates.resideInAPackage("dev.zerosum.archcanary").as("canary classes");

    @Test
    void realMoneyClassesSatisfyEveryRule() {
        assertTrue(MONEY_CLASSES.contain(dev.zerosum.money.Money.class), "import found Money");
        assertTrue(MONEY_CLASSES.contain(dev.zerosum.money.generate.OrderGenerator.class), "import found the fixtures");
        for (ArchRule rule : MoneyArchRules.forLibsMoney()) {
            rule.check(MONEY_CLASSES);
        }
    }

    @Test
    void ruleAFiresOnTheFloatingPointCanary() {
        assertFires(MoneyArchRules::noFloatingPoint, FloatingPointCanary.class);
    }

    @Test
    void ruleBFiresOnTheBigDecimalCanary() {
        assertFires(MoneyArchRules::bigDecimalOnlyInFeeCalculator, BigDecimalCanary.class);
    }

    @Test
    void ruleCFiresOnTheRoundingModeCanary() {
        assertFires(MoneyArchRules::explicitHalfEven, RoundingModeCanary.class);
    }

    @Test
    void ruleDFiresOnTheNonJdkDependencyCanary() {
        assertFires(scope -> MoneyArchRules.jdkOnly(scope, "dev.zerosum.archcanary"), NonJdkDependencyCanary.class);
    }

    @Test
    void ruleEFiresOnTheJdkCurrencyCanary() {
        assertFires(MoneyArchRules::noJdkCurrencyData, JdkCurrencyCanary.class);
    }

    private static void assertFires(Function<DescribedPredicate<JavaClass>, ArchRule> rule, Class<?> canary) {
        JavaClasses imported = new ClassFileImporter().importClasses(canary);
        EvaluationResult result = rule.apply(CANARIES).evaluate(imported);
        assertTrue(result.hasViolation(), canary.getSimpleName() + " should violate " + rule.apply(CANARIES).getDescription());
        String report = String.join("\n", result.getFailureReport().getDetails());
        assertTrue(report.contains(canary.getName()), report);
        System.out.println("ZS-ARCH-CANARY " + canary.getSimpleName() + " -> " + report.lines().findFirst().orElse(""));
    }
}
