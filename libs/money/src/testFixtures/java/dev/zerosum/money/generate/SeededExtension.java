package dev.zerosum.money.generate;

import java.security.SecureRandom;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

/**
 * Seed reporting and replay for generative tests (D01-10, ADR-0009).
 *
 * <p>Before each test it takes the seed from {@value #SEED_ENV} if set, otherwise draws a fresh one, and prints one
 * line {@code ZS-SEED test=<class>#<method> seed=<long>}. A {@link Seed} parameter receives it. On failure the seed
 * and the exact replay command are added to the failure message.
 */
public final class SeededExtension implements BeforeEachCallback, ParameterResolver, TestExecutionExceptionHandler {

    /** decision: D01-10 — pinned by name; RandomGenerator.getDefault() may change between JDK releases. */
    public static final String ALGORITHM = "L64X128MixRandom";

    /** decision: D01-10, D00-8 — seed override for replay; CI must never set it. */
    public static final String SEED_ENV = "ZS_TEST_SEED";

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(SeededExtension.class);
    private static final String KEY = "seed";

    @Override
    public void beforeEach(ExtensionContext context) {
        long seed = seedFromEnvironment();
        context.getStore(NAMESPACE).put(KEY, seed);
        System.out.println(seedLine(context, seed));
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == Seed.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        return new Seed(seed(context));
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        long seed = seed(context);
        String task = System.getProperty("zs.gradleTestTask", "test");
        String replay = SEED_ENV + "=" + seed + " ./gradlew " + task + " --tests '"
                + context.getRequiredTestClass().getName() + "." + context.getRequiredTestMethod().getName() + "' --rerun";
        throw new AssertionError(failure.getMessage() + System.lineSeparator() + seedLine(context, seed)
                + System.lineSeparator() + "replay: " + replay, failure);
    }

    /** The greppable line format recorded in ADR-0009. */
    public static String seedLine(ExtensionContext context, long seed) {
        return "ZS-SEED test=" + context.getRequiredTestClass().getName() + "#"
                + context.getRequiredTestMethod().getName() + " seed=" + seed;
    }

    private static long seed(ExtensionContext context) {
        return context.getStore(NAMESPACE).get(KEY, Long.class);
    }

    private static long seedFromEnvironment() {
        String value = System.getenv(SEED_ENV);
        if (value == null || value.isBlank()) {
            return new SecureRandom().nextLong();
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(SEED_ENV + " must be a long, was: " + value, e);
        }
    }
}
