package dev.zerosum.money.generate;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/** The seed of one generative test run (ADR-0009). Injected by {@link SeededExtension}. */
public record Seed(long value) {

    /** A fresh generator of the pinned algorithm, seeded with {@link #value()}; equal seeds give equal streams. */
    public RandomGenerator random() {
        return random(value);
    }

    public static RandomGenerator random(long seed) {
        return RandomGeneratorFactory.of(SeededExtension.ALGORITHM).create(seed);
    }
}
