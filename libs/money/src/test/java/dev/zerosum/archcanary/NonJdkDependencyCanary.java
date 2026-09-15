package dev.zerosum.archcanary;

import org.junit.jupiter.api.Assertions;

/** Deliberately violates Rule D (a non-JDK dependency); excluded from the real scan. */
public class NonJdkDependencyCanary {

    public void requirePositive(long amount) {
        Assertions.assertTrue(amount > 0);
    }
}
