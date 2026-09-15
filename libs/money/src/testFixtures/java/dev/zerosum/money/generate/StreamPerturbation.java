package dev.zerosum.money.generate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Seeded duplication and bounded reordering of any list (D01-10), for duplicate/reorder tests (S02) and workloads (S08).
 * Rates are integer percentages supplied by the caller.
 */
public final class StreamPerturbation {

    private StreamPerturbation() {
    }

    /**
     * Each item is followed by a copy with probability {@code duplicatePercent}; then every element of the resulting
     * list moves at most {@code maxDisplacement} positions from where it was.
     */
    public static <T> List<T> perturb(List<T> items, RandomGenerator rng, int duplicatePercent, int maxDisplacement) {
        if (duplicatePercent < 0 || duplicatePercent > 100 || maxDisplacement < 0) {
            throw new IllegalArgumentException("duplicatePercent 0..100 and maxDisplacement >= 0 required: "
                    + duplicatePercent + ", " + maxDisplacement);
        }
        List<T> duplicated = new ArrayList<>(items.size());
        for (T item : items) {
            duplicated.add(item);
            if (rng.nextInt(100) < duplicatePercent) {
                duplicated.add(item);
            }
        }
        record Keyed<T>(int key, int index, T value) {
        }
        List<Keyed<T>> keyed = new ArrayList<>(duplicated.size());
        for (int i = 0; i < duplicated.size(); i++) {
            keyed.add(new Keyed<>(i + rng.nextInt(maxDisplacement + 1), i, duplicated.get(i)));
        }
        keyed.sort(Comparator.comparingInt((Keyed<T> k) -> k.key()).thenComparingInt(Keyed::index));
        return keyed.stream().map(Keyed::value).toList();
    }
}
