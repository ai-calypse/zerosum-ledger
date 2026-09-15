package dev.zerosum.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.money.FareSplitter.Split;
import org.junit.jupiter.api.Test;

class FareSplitterTest {

    private static final long[] BPS = {0, 1, 250, 333, 2000, 5000, 6667, 9999, 10_000};

    @Test
    void reproducesWorkedExampleO1AndO3() {
        // docs/zerosum_ledger_mvp_plan.md#worked-example: fare 2500 at 20% commission, later adjusted to 2200.
        Split o1 = FareSplitter.split(Money.of(2500, "USD"), 2000);
        assertEquals(Money.of(2000, "USD"), o1.driverShare());
        assertEquals(Money.of(500, "USD"), o1.platformCommission());

        Split o3 = FareSplitter.adjustment(Money.of(2500, "USD"), Money.of(2200, "USD"), 2000);
        assertEquals(Money.of(-240, "USD"), o3.driverShare());
        assertEquals(Money.of(-60, "USD"), o3.platformCommission());
        assertEquals(Money.of(-300, "USD"), o3.total());
    }

    @Test
    void partsSumExactlyToTheFareAndSplitIsSignSymmetric() {
        for (String currency : new String[] {"USD", "JPY", "KWD"}) {
            for (long fare = -10_007; fare <= 10_007; fare += 7) {
                for (long bps : BPS) {
                    Money money = Money.of(fare, currency);
                    Split split = FareSplitter.split(money, bps);
                    assertEquals(money, split.total(), () -> "sum " + money + " @" + bps);
                    Split negated = FareSplitter.split(money.negate(), bps);
                    assertEquals(split.driverShare().negate(), negated.driverShare(), () -> "symmetry " + money + " @" + bps);
                    assertEquals(split.platformCommission().negate(), negated.platformCommission());
                }
            }
        }
    }

    @Test
    void cumulativeAdjustmentsEqualASplitOfTheFinalFare() {
        long[] fares = {2500, 2200, 2201, 2203, 1999, 5, 0, -3, 7777, 7778, 13, 2500, 1};
        for (long bps : BPS) {
            Split first = FareSplitter.split(Money.of(fares[0], "USD"), bps);
            Money driver = first.driverShare();
            Money platform = first.platformCommission();
            for (int i = 1; i < fares.length; i++) {
                Split adj = FareSplitter.adjustment(Money.of(fares[i - 1], "USD"), Money.of(fares[i], "USD"), bps);
                driver = driver.plus(adj.driverShare());
                platform = platform.plus(adj.platformCommission());
            }
            Split direct = FareSplitter.split(Money.of(fares[fares.length - 1], "USD"), bps);
            assertEquals(direct.driverShare(), driver, "driver drift at " + bps + " bps");
            assertEquals(direct.platformCommission(), platform, "platform drift at " + bps + " bps");
        }
    }

    @Test
    void edgeCases() {
        Split zero = FareSplitter.split(Money.of(0, "USD"), 2000);
        assertTrue(zero.driverShare().isZero() && zero.platformCommission().isZero());
        assertEquals(Money.of(0, "USD"), FareSplitter.split(Money.of(2500, "USD"), 10_000).driverShare());
        assertEquals(Money.of(2500, "USD"), FareSplitter.split(Money.of(2500, "USD"), 0).driverShare());

        var low = assertThrows(IllegalArgumentException.class, () -> FareSplitter.split(Money.of(1, "USD"), -1));
        assertTrue(low.getMessage().contains("-1"), low.getMessage());
        var high = assertThrows(IllegalArgumentException.class, () -> FareSplitter.split(Money.of(1, "USD"), 10_001));
        assertTrue(high.getMessage().contains("10001"), high.getMessage());
        assertThrows(CurrencyMismatchException.class,
                () -> FareSplitter.adjustment(Money.of(1, "USD"), Money.of(1, "EUR"), 2000));
    }
}
