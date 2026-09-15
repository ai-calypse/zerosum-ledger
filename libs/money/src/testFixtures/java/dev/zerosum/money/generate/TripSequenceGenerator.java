package dev.zerosum.money.generate;

import dev.zerosum.money.FareSplitter;
import dev.zerosum.money.Money;
import dev.zerosum.money.OrderCandidate;
import dev.zerosum.money.OrderCandidate.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Seeded trips (D01-10): COMMERCE orders built with {@link FareSplitter#split} and adjustments built with
 * {@link FareSplitter#adjustment}, as signed entries per ADR-0003. Commission and adjustment probability are caller
 * parameters (owned by S08 for workloads). Integer percentages only: no floating point in money code (Rule A).
 */
public final class TripSequenceGenerator {

    /** One order; {@code adjustsIndex} is the index of the adjusted order in the same list, or −1. */
    public record TripOrder(OrderCandidate order, int adjustsIndex) {
    }

    private TripSequenceGenerator() {
    }

    public static List<TripOrder> trips(RandomGenerator rng, int tripCount, long commissionBps, int adjustmentPercent,
            String currency) {
        if (adjustmentPercent < 0 || adjustmentPercent > 100) {
            throw new IllegalArgumentException("adjustmentPercent must be 0..100: " + adjustmentPercent);
        }
        List<TripOrder> out = new ArrayList<>();
        for (int t = 0; t < tripCount; t++) {
            String rider = "rider:R" + t;
            String driver = "driver:D" + rng.nextInt(Math.max(1, tripCount / 4 + 1));
            long fare = 1 + rng.nextLong(100_000);
            FareSplitter.Split split = FareSplitter.split(Money.of(fare, currency), commissionBps);
            int original = out.size();
            out.add(new TripOrder(order("trip.completed", rider, driver, currency,
                    fare, split.driverShare().amountMinor(), split.platformCommission().amountMinor()), -1));

            if (rng.nextInt(100) < adjustmentPercent) {
                long newFare = 1 + rng.nextLong(100_000);
                if (newFare != fare) {
                    FareSplitter.Split adj = FareSplitter.adjustment(Money.of(fare, currency), Money.of(newFare, currency), commissionBps);
                    out.add(new TripOrder(order("fare.adjusted", rider, driver, currency, newFare - fare,
                            adj.driverShare().amountMinor(), adj.platformCommission().amountMinor()), original));
                }
            }
        }
        return out;
    }

    /** Rider debit of the fare delta; driver and platform credits of their shares; zero lines omitted. */
    private static OrderCandidate order(String reason, String rider, String driver, String currency,
            long riderDelta, long driverShare, long commission) {
        List<Entry> entries = new ArrayList<>(3);
        entries.add(Entry.of(rider, "receivable", currency, riderDelta));
        if (driverShare != 0) {
            entries.add(Entry.of(driver, "payable", currency, Math.negateExact(driverShare)));
        }
        if (commission != 0) {
            entries.add(Entry.of("platform:main", "revenue", currency, Math.negateExact(commission)));
        }
        return new OrderCandidate("COMMERCE", reason, List.copyOf(entries));
    }
}
