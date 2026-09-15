package dev.zerosum.money.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Set;

/**
 * ArchUnit rules that keep floating point and unrounded decimal arithmetic out of money code (M1(c), D01-11).
 *
 * <p>Each rule is built from a scope predicate, so services can apply the same rules to their own money-handling
 * packages. The {@code ..._IN_LIBS_MONEY} constants are the scopes {@code libs/money} itself enforces.
 *
 * <p>Known blind spot: bytecode analysis doesn't see primitive locals or arithmetic opcodes, so a {@code double} local
 * computed without calling a floating-point member escapes Rule A. Code review is the mitigation.
 */
public final class MoneyArchRules {

    /** decision: D01-11 — money packages: libs/money main classes and its generator fixtures (the rule class itself is tooling). */
    public static final DescribedPredicate<JavaClass> LIBS_MONEY_SCOPE =
            JavaClass.Predicates.resideInAnyPackage("dev.zerosum.money", "dev.zerosum.money.generate..")
                    .as("libs/money classes");

    /** decision: D01-11 — the JDK-only rule applies to libs/money main classes (package dev.zerosum.money, no subpackages). */
    public static final DescribedPredicate<JavaClass> LIBS_MONEY_MAIN_SCOPE =
            JavaClass.Predicates.resideInAPackage("dev.zerosum.money").as("libs/money main classes");

    /** decision: D01-11 — the only class allowed to use BigDecimal. */
    public static final String BIG_DECIMAL_ALLOW_LIST = "dev.zerosum.money.FeeCalculator";

    /** decision: D01-11 — BigDecimal overloads that round without an explicit RoundingMode (or with a legacy int mode). */
    public static final List<List<Class<?>>> UNROUNDED_BIG_DECIMAL_CALLS = List.of(
            List.of(BigDecimal.class),            // divide(BigDecimal)
            List.of(BigDecimal.class, int.class), // divide(BigDecimal, int legacyRoundingMode)
            List.of(BigDecimal.class, int.class, int.class), // divide(BigDecimal, int scale, int legacyRoundingMode)
            List.of(int.class),                   // setScale(int)
            List.of(int.class, int.class));       // setScale(int, int legacyRoundingMode)

    private static final Set<String> FLOATING_POINT =
            Set.of("float", "double", Float.class.getName(), Double.class.getName());

    private MoneyArchRules() {
    }

    /** Rule A: no float/double fields, parameters, return types, or calls to members whose signature involves them. */
    public static ArchRule noFloatingPoint(DescribedPredicate<JavaClass> scope) {
        return classes().that(scope).should(new ArchCondition<JavaClass>("not use float, double, Float or Double") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaField field : javaClass.getFields()) {
                    report(events, javaClass, isFloating(field.getRawType()), "field " + field.getFullName());
                }
                for (JavaCodeUnit unit : javaClass.getCodeUnits()) {
                    report(events, javaClass, isFloating(unit.getRawReturnType())
                            || unit.getRawParameterTypes().stream().anyMatch(MoneyArchRules::isFloating),
                            "signature of " + unit.getFullName());
                }
                javaClass.getCodeUnitCallsFromSelf().forEach(call -> report(events, javaClass,
                        isFloating(call.getTarget().getRawReturnType())
                                || call.getTarget().getRawParameterTypes().stream().anyMatch(MoneyArchRules::isFloating),
                        "call " + call.getDescription()));
                javaClass.getCodeUnitReferencesFromSelf().forEach(ref -> report(events, javaClass,
                        isFloating(ref.getTarget().getRawReturnType())
                                || ref.getTarget().getRawParameterTypes().stream().anyMatch(MoneyArchRules::isFloating),
                        "reference " + ref.getDescription()));
                javaClass.getFieldAccessesFromSelf().forEach(access -> report(events, javaClass,
                        isFloating(access.getTarget().getRawType()), "field access " + access.getDescription()));
            }
        }).as("Rule A: money code uses no floating point");
    }

    /** Rule B: only {@link #BIG_DECIMAL_ALLOW_LIST} depends on BigDecimal. */
    public static ArchRule bigDecimalOnlyInFeeCalculator(DescribedPredicate<JavaClass> scope) {
        return noClasses().that(scope).and().doNotHaveFullyQualifiedName(BIG_DECIMAL_ALLOW_LIST)
                .should().dependOnClassesThat().areAssignableTo(BigDecimal.class)
                .as("Rule B: BigDecimal only in FeeCalculator");
    }

    /** Rule C: HALF_EVEN only, no MathContext, and no BigDecimal overload that rounds implicitly. */
    public static ArchRule explicitHalfEven(DescribedPredicate<JavaClass> scope) {
        DescribedPredicate<JavaFieldAccess> otherRoundingMode = new DescribedPredicate<>("access a RoundingMode other than HALF_EVEN") {
            @Override
            public boolean test(JavaFieldAccess access) {
                return access.getTargetOwner().isEquivalentTo(RoundingMode.class)
                        && !access.getTarget().getName().equals(RoundingMode.HALF_EVEN.name());
            }
        };
        DescribedPredicate<JavaMethodCall> unroundedDecimalCall = new DescribedPredicate<>("call a BigDecimal overload without an explicit RoundingMode") {
            @Override
            public boolean test(JavaMethodCall call) {
                if (!call.getTargetOwner().isEquivalentTo(BigDecimal.class)) {
                    return false;
                }
                String name = call.getTarget().getName();
                List<String> params = call.getTarget().getRawParameterTypes().stream().map(JavaClass::getName).toList();
                return (name.equals("divide") || name.equals("setScale")) && UNROUNDED_BIG_DECIMAL_CALLS.stream()
                        .anyMatch(signature -> signature.stream().map(Class::getName).toList().equals(params)
                                && (name.equals("divide") ? !signature.get(0).equals(int.class) : signature.get(0).equals(int.class)));
            }
        };
        return noClasses().that(scope)
                .should().accessFieldWhere(otherRoundingMode)
                .orShould().dependOnClassesThat().areAssignableTo(MathContext.class)
                .orShould().callMethodWhere(unroundedDecimalCall)
                .as("Rule C: explicit HALF_EVEN rounding");
    }

    /** Rule D: main classes depend only on the JDK (and on themselves). */
    public static ArchRule jdkOnly(DescribedPredicate<JavaClass> scope, String... ownPackages) {
        String[] allowed = new String[ownPackages.length + 1];
        allowed[0] = "java..";
        System.arraycopy(ownPackages, 0, allowed, 1, ownPackages.length);
        return classes().that(scope).should().onlyDependOnClassesThat().resideInAnyPackage(allowed)
                .as("Rule D: depend only on the JDK");
    }

    /** Rule E: no JDK currency data; digits come from the checked-in ISO table. */
    public static ArchRule noJdkCurrencyData(DescribedPredicate<JavaClass> scope) {
        return noClasses().that(scope).should().dependOnClassesThat().areAssignableTo(Currency.class)
                .as("Rule E: no java.util.Currency");
    }

    /** All five rules for {@code libs/money}. */
    public static List<ArchRule> forLibsMoney() {
        return List.of(
                noFloatingPoint(LIBS_MONEY_SCOPE),
                bigDecimalOnlyInFeeCalculator(LIBS_MONEY_SCOPE),
                explicitHalfEven(LIBS_MONEY_SCOPE),
                jdkOnly(LIBS_MONEY_MAIN_SCOPE, "dev.zerosum.money"),
                noJdkCurrencyData(LIBS_MONEY_SCOPE));
    }

    static boolean isFloating(JavaClass type) {
        return FLOATING_POINT.contains(type.getName());
    }

    private static void report(ConditionEvents events, JavaClass owner, boolean violated, String what) {
        if (violated) {
            events.add(SimpleConditionEvent.violated(owner, owner.getName() + " uses floating point: " + what));
        }
    }
}
