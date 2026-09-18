package dev.zerosum.ledger.perf;

import dev.zerosum.money.OrderCandidate;
import dev.zerosum.money.OrderCandidate.Entry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * Money-order payloads for the S07 studies.
 *
 * <p>This renders the same D01-8 wire form as {@code MoneyOrderPayloads}, which the rest of the ledger tests use, but
 * takes {@code createdAt} as an argument. The shared helper hardcodes a fixed timestamp, and measurement 3 needs the
 * real creation instant on the wire, so the field cannot be a constant here.
 *
 * <p>The pool pre-renders payload <em>shapes</em> before a window starts and stamps a fresh order id into one at
 * submission time, which is two string concatenations. That keeps the client off the critical path exactly as SP1
 * requires, while still making every submission a genuinely new order: cycling the pool re-uses a shape, never an
 * order id, so a window never silently turns into a duplicate-detection benchmark.
 */
final class PerfPayloads {

    /** Substituted for the order id when a shape is pre-rendered; never appears in a submitted payload. */
    private static final String ORDER_ID_PLACEHOLDER = "00000000-0000-4000-8000-000000000000";

    private static final Instant POOL_CREATED_AT = Instant.parse("2026-09-15T10:00:00.001Z");

    private PerfPayloads() {
    }

    /** The stored money-order JSON (D01-8). Mirrors {@code MoneyOrderPayloads.render}, with a caller-set creation time. */
    static String render(UUID orderId, String groupId, OrderCandidate order, Instant createdAt) {
        String entries = order.entries().stream()
                .map(e -> "{\"entity_id\":\"" + e.entityId() + "\",\"account\":\"" + e.account() + "\",\"currency\":\""
                        + e.currency() + "\",\"amount_minor\":" + e.amountMinor() + "}")
                .collect(Collectors.joining(","));
        return "{\"schema\":\"zerosum.money_order.v1\",\"order_id\":\"" + orderId + "\",\"order_group_id\":\"" + groupId
                + "\",\"type\":\"" + order.type() + "\",\"reason\":\"" + order.reason() + "\",\"adjusts_order_id\":null,"
                + "\"source\":{\"system\":\"trip-simulator\",\"idempotency_key\":\"" + orderId + "\"},"
                + "\"entries\":[" + entries + "],\"metadata\":{},"
                + "\"effective_at\":\"" + createdAt + "\",\"created_at\":\"" + createdAt + "\"}";
    }

    /**
     * A balanced COMMERCE trip: a rider owes the fare, the driver is owed the fare less commission, and a platform
     * entity earns the commission. Identical in shape to SP1's generated order, so the only thing that differs between
     * that study and this one is the variable under test.
     *
     * @param platformEntityId the platform entity this order's commission lands on; SP1's workload is the single
     *                         {@code platform:main}, and measurement 2 spreads it over N of these
     */
    static OrderCandidate trip(RandomGenerator rng, int riderPool, int driverPool, String platformEntityId) {
        long fare = 100 + rng.nextLong(9_900);
        long commission = fare / 5;
        return new OrderCandidate("COMMERCE", "trip.completed", List.of(
                Entry.of("rider:R" + rng.nextInt(riderPool), "receivable", "USD", fare),
                Entry.of("driver:D" + rng.nextInt(driverPool), "payable", "USD", -(fare - commission)),
                Entry.of(platformEntityId, "revenue", "USD", -commission)));
    }

    /**
     * The platform entity for slot {@code i} when the workload is spread over {@code entityCount} of them.
     *
     * <p>{@code entityCount == 1} is literally {@code platform:main}, the hot entity D01-6 names and the one SP1
     * measured, so the N=1 row of measurement 2 is SP1's scenario rather than an approximation of it. Wider counts are
     * the workload-level emulation of option (b) sharding that S07-T04 instruction 2 asks for: the ledger
     * auto-provisions these entities (D02-5), so no schema or aggregation change is needed to measure the lock effect.
     */
    static String platformEntityId(int slot, int entityCount) {
        return entityCount == 1 ? "platform:main" : String.format("platform:p%04d", slot % entityCount);
    }

    /** Pre-rendered payload shapes; {@link #payload} stamps a fresh order id into one. */
    static final class Pool {

        private final String[] heads;
        private final String[] middles;
        private final String[] tails;

        Pool(RandomGenerator rng, int size, int riderPool, int driverPool, int entityCount) {
            heads = new String[size];
            middles = new String[size];
            tails = new String[size];
            for (int i = 0; i < size; i++) {
                OrderCandidate order = trip(rng, riderPool, driverPool, platformEntityId(i, entityCount));
                String shape = render(UUID.fromString(ORDER_ID_PLACEHOLDER), "perf_" + (i % 1000), order,
                        POOL_CREATED_AT);
                String[] parts = shape.split(ORDER_ID_PLACEHOLDER, -1);
                if (parts.length != 3) {
                    throw new IllegalStateException("the order id must appear exactly twice in a payload, as the id "
                            + "and the idempotency key; found " + (parts.length - 1) + " occurrences");
                }
                heads[i] = parts[0];
                middles[i] = parts[1];
                tails[i] = parts[2];
            }
        }

        int size() {
            return heads.length;
        }

        String payload(int slot, UUID orderId) {
            int i = slot % heads.length;
            return heads[i] + orderId + middles[i] + orderId + tails[i];
        }
    }
}
