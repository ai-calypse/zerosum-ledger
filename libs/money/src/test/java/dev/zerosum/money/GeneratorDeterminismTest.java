package dev.zerosum.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.zerosum.money.generate.OrderGenerator;
import dev.zerosum.money.generate.Seed;
import dev.zerosum.money.generate.SeededExtension;
import dev.zerosum.money.generate.StreamPerturbation;
import dev.zerosum.money.generate.TripSequenceGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SeededExtension.class)
class GeneratorDeterminismTest {

    @Test
    void sameSeedGivesTheSameDigestAndADifferentSeedDoesNot(Seed seed) throws Exception {
        String first = digest(seed.value());
        String second = digest(seed.value());
        assertEquals(first, second);
        assertNotEquals(first, digest(seed.value() + 1));
        System.out.println("ZS-DIGEST seed=" + seed.value() + " sha256=" + first);
    }

    @Test
    void perturbationDisplacesElementsAtMostTheBound(Seed seed) {
        RandomGenerator rng = seed.random();
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            items.add(i);
        }
        List<Integer> noDuplicates = StreamPerturbation.perturb(items, rng, 0, 5);
        assertEquals(items.size(), noDuplicates.size());
        for (int position = 0; position < noDuplicates.size(); position++) {
            int original = noDuplicates.get(position);
            assertEquals(true, Math.abs(position - original) <= 5, "moved " + original + " to " + position);
        }
        List<Integer> duplicated = StreamPerturbation.perturb(items, rng, 30, 3);
        assertEquals(true, duplicated.size() > items.size() && duplicated.containsAll(items));
    }

    /** SHA-256 over a canonical rendering of every generator family for one seed. */
    private static String digest(long seed) throws Exception {
        RandomGenerator rng = Seed.random(seed);
        OrderGenerator orders = new OrderGenerator(rng, seed);
        StringBuilder canonical = new StringBuilder();
        List<Object> sequence = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            canonical.append(orders.valid()).append('\n').append(orders.invalid()).append('\n');
        }
        TripSequenceGenerator.trips(rng, 100, 2000, 30, "JPY").forEach(t -> canonical.append(t).append('\n'));
        for (int i = 0; i < 200; i++) {
            sequence.add(i);
        }
        canonical.append(StreamPerturbation.perturb(sequence, rng, 20, 4));
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(sha.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
