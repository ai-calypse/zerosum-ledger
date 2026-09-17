package dev.zerosum.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.money.OrderCandidate.Entry;
import dev.zerosum.money.ZeroSumValidator;
import dev.zerosum.money.generate.Seed;
import dev.zerosum.money.generate.SeededExtension;
import dev.zerosum.simulator.Scenario.GeneratedOrder;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The claim M13 (a) rests on: a seed reproduces its orders exactly.
 *
 * <p>Reproducibility here means <em>byte-identical request bodies and identical idempotency keys</em>, not merely
 * "similar orders". Anything weaker would break the idempotency contract it is supposed to exploit: order-service
 * compares a replayed body with the stored one, so a body that differs by a single timestamp is rejected 422 rather
 * than replayed, and a seeded rerun would stop being a rerun.
 */
@ExtendWith(SeededExtension.class)
class ScenarioDeterminismTest {

    private static final Scenario W1 = Scenario.W1_TRIP_COMPLETED;

    @Test
    void theSameSeedProducesIdenticalOrdersAndADifferentSeedDoesNot(Seed seed) {
        String first = render(W1.orders(seed.value()), seed.value());
        String second = render(W1.orders(seed.value()), seed.value());

        assertEquals(first, second, "the same seed must produce byte-identical orders");
        assertNotEquals(first, render(W1.orders(seed.value() + 1), seed.value() + 1),
                "a different seed must produce different orders, or the seed is not doing anything");
    }

    @Test
    void everyRunOfACampaignHasItsOwnRecordedSeedAndTheyDiffer(Seed seed) {
        long runOne = Scenario.runSeed(seed.value(), 1);
        long runTwo = Scenario.runSeed(seed.value(), 2);

        assertNotEquals(runOne, runTwo, "two runs of one campaign must not share a seed");
        assertEquals(runOne, Scenario.runSeed(seed.value(), 1), "a run's seed is a function of the base seed and index");
        assertNotEquals(render(W1.orders(runOne), runOne), render(W1.orders(runTwo), runTwo));
    }

    @Test
    void everyGeneratedOrderIsValidAndSumsToZeroPerCurrency(Seed seed) {
        for (GeneratedOrder generated : W1.orders(seed.value())) {
            assertTrue(ZeroSumValidator.defaults().validate(generated.order()).isEmpty(),
                    "the shared validator accepts it: " + generated.order());

            var perCurrency = new TreeMap<String, Long>();
            generated.order().entries().forEach(e -> perCurrency.merge(e.currency(), e.amountMinor(), Math::addExact));
            perCurrency.forEach((currency, sum) -> assertEquals(0L, sum.longValue(), currency + " sums to zero"));
        }
    }

    /**
     * The W1 shape itself (master §1.4): a rider receivable debit, a driver payable credit and platform revenue.
     *
     * <p>A fixed seed, not the injected one, because the assertion is about exact structure. {@code FareSplitter}
     * rounds the commission HALF_EVEN, so a fare of one or two minor units legitimately produces no platform line at
     * all; asserting three entries under a random seed would be a test that fails a few times in a hundred thousand
     * runs for a reason that is not a defect.
     */
    @Test
    void theW1ShapeIsARiderDebitADriverCreditAndPlatformRevenue() {
        List<GeneratedOrder> orders = W1.orders(4_242L);
        assertEquals(W1.tripsPerRun(), orders.size(), "one order per trip, with no adjustments in W1");

        for (GeneratedOrder generated : orders) {
            List<Entry> entries = generated.order().entries();
            assertEquals("COMMERCE", generated.order().type());
            assertEquals("trip.completed", generated.order().reason());
            assertEquals(3, entries.size(), "rider, driver and platform: " + entries);

            Entry rider = entries.get(0);
            Entry driver = entries.get(1);
            Entry platform = entries.get(2);

            assertTrue(rider.entityId().startsWith("rider:"), rider.toString());
            assertEquals("receivable", rider.account());
            assertTrue(rider.amountMinor() > 0, "the rider owes the fare, so the entry is a debit: " + rider);

            assertTrue(driver.entityId().startsWith("driver:"), driver.toString());
            assertEquals("payable", driver.account());
            assertTrue(driver.amountMinor() < 0, "the driver is owed, so the entry is a credit: " + driver);

            assertEquals("platform:main", platform.entityId(), "the hot entity is never namespaced away");
            assertEquals("revenue", platform.account());
            assertTrue(platform.amountMinor() < 0, "commission is revenue, so the entry is a credit: " + platform);

            assertEquals(0, rider.amountMinor() + driver.amountMinor() + platform.amountMinor(),
                    "the three entries sum to zero");
        }
    }

    @Test
    void ridersAndDriversAreNamespacedBySeedSoRepeatedRunsNeverShareAnEntity() {
        List<GeneratedOrder> first = W1.orders(1L);
        List<GeneratedOrder> second = W1.orders(2L);

        var firstEntities = first.stream().flatMap(o -> o.order().entries().stream()).map(Entry::entityId).toList();
        var secondEntities = second.stream().flatMap(o -> o.order().entries().stream()).map(Entry::entityId).toList();

        assertTrue(firstEntities.stream().anyMatch(id -> id.equals("platform:main")), "platform:main stays shared");
        assertTrue(firstEntities.stream().filter(id -> !id.equals("platform:main"))
                        .noneMatch(secondEntities::contains),
                "no rider or driver appears in both runs: " + firstEntities + " vs " + secondEntities);
    }

    @Test
    void idempotencyKeysAreDerivedFromTheSeedAndAreUniqueWithinARun(Seed seed) {
        List<GeneratedOrder> orders = W1.orders(seed.value());
        List<String> keys = orders.stream().map(GeneratedOrder::idempotencyKey).toList();

        assertEquals(keys.size(), Set.copyOf(keys).size(), "one key per order within a run: " + keys);
        assertEquals(keys, W1.orders(seed.value()).stream().map(GeneratedOrder::idempotencyKey).toList(),
                "the same seed mints the same keys, which is what makes a rerun a replay");
        keys.forEach(key -> assertTrue(key.length() <= 255, "the API caps keys at 255 characters: " + key));
    }

    /** Exactly what would go on the wire: the bodies plus the keys they would be posted under. */
    private static String render(List<GeneratedOrder> orders, long runSeed) {
        return orders.stream()
                .map(order -> order.idempotencyKey() + " " + order.toRequestBody(W1.name(), runSeed))
                .reduce("", (a, b) -> a + b + "\n");
    }
}
