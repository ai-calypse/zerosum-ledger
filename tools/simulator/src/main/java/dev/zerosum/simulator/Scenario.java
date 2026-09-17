package dev.zerosum.simulator;

import dev.zerosum.evidence.Json;
import dev.zerosum.money.OrderCandidate;
import dev.zerosum.money.OrderCandidate.Entry;
import dev.zerosum.money.Violation;
import dev.zerosum.money.ZeroSumValidator;
import dev.zerosum.money.generate.Seed;
import dev.zerosum.money.generate.TripSequenceGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * A named simulator scenario and the seeded orders it produces (M13 (a)).
 *
 * <p>decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness.
 *
 * <p><strong>The randomness is not new.</strong> Orders come from {@link TripSequenceGenerator} (D01-10, ADR-0009),
 * which already builds the W1 shape out of {@code FareSplitter}: the rider owes the fare, the driver is owed the fare
 * less commission, and the platform books the commission, summing to zero per currency. S08-T01 is explicit that the
 * simulator reuses these "unchanged" and that two generators are never maintained, so this class only names the
 * parameters, namespaces the entities and derives the keys.
 */
public record Scenario(String name, String description, int tripsPerRun, long commissionBps, String currency) {

    /**
     * W1 — trip completed ([master §1.4](../../../../../../../docs/zerosum_ledger_mvp_plan.md#workflows)): a rider
     * receivable, a driver payable and platform revenue, summing to zero.
     */
    public static final Scenario W1_TRIP_COMPLETED = new Scenario("w1-trip-completed",
            "W1 (master §1.4): each run completes trips through the real order API. Every order is a COMMERCE "
                    + "`trip.completed` whose entries are a rider receivable (debit), a driver payable (credit) and "
                    + "platform revenue (credit), summing to zero in the run's currency.",
            3, 2_000, "USD");

    public static final List<Scenario> CATALOG = List.of(W1_TRIP_COMPLETED);

    /**
     * A fixed instant the {@code effective_at} of each order is derived from.
     *
     * <p><strong>Not the clock, on purpose.</strong> Idempotency is "same key and same body replays; same key and a
     * different body is rejected" (M3 (a)/(b)). A body carrying {@code Instant.now()} would differ on every
     * invocation, so re-running a seed would collide with its own earlier orders and be rejected 422 rather than
     * replayed. Deriving the timestamp from the seed is what makes a seeded run genuinely repeatable.
     */
    private static final long EFFECTIVE_AT_EPOCH_SECOND = 1_800_000_000L;   // 2027-01-15T08:00:00Z

    public Scenario {
        if (tripsPerRun < 1) {
            throw new IllegalArgumentException("tripsPerRun must be at least 1: " + tripsPerRun);
        }
    }

    public static Optional<Scenario> byName(String name) {
        return CATALOG.stream().filter(scenario -> scenario.name().equals(name)).findFirst();
    }

    public static String catalogNames() {
        return CATALOG.stream().map(Scenario::name).reduce((a, b) -> a + ", " + b).orElse("");
    }

    /**
     * The seed of one run, derived from the base seed so that the whole campaign reproduces from a single number
     * while each run still records a seed of its own. Follows the SP1 study's derivation (D02-10).
     */
    public static long runSeed(long baseSeed, int run) {
        return baseSeed + 31L * run;
    }

    /**
     * The orders of one run. Deterministic: the same {@code runSeed} yields byte-identical bodies and identical
     * idempotency keys, which is what lets a repeated run be a replay rather than a duplicate.
     */
    public List<GeneratedOrder> orders(long runSeed) {
        RandomGenerator rng = Seed.random(runSeed);
        // Adjustment probability 0: an adjustment must carry adjusts_order_id, which means posting it only after its
        // original has an id. That wiring is not built, and a scenario that needs adjustments must add it rather than
        // quietly post unlinked orders. W1 is trip.completed only, so nothing here is lost.
        List<TripSequenceGenerator.TripOrder> trips =
                TripSequenceGenerator.trips(rng, tripsPerRun, commissionBps, 0, currency);

        String namespace = Long.toHexString(runSeed);
        var out = new ArrayList<GeneratedOrder>(trips.size());
        for (int index = 0; index < trips.size(); index++) {
            OrderCandidate order = namespaced(trips.get(index).order(), namespace);

            // A generated body that the shared validator rejects is a simulator bug, not a service rejection, and it
            // fails here with the seed rather than being recorded as evidence about the service (S08-T01).
            List<Violation> violations = ZeroSumValidator.defaults().validate(order);
            if (!violations.isEmpty()) {
                throw new IllegalStateException("simulator generated an invalid order at seed " + runSeed
                        + " index " + index + ": " + violations);
            }

            out.add(new GeneratedOrder(
                    "sim-" + name + "-" + namespace + "-" + index,
                    "trip-" + namespace + "-" + index,
                    Instant.ofEpochSecond(EFFECTIVE_AT_EPOCH_SECOND + Math.floorMod(runSeed, 86_400) + index),
                    order));
        }
        return List.copyOf(out);
    }

    /**
     * Gives the run's riders and drivers a suffix derived from its seed, so repeated runs and concurrent campaigns
     * never share an entity. {@code platform:main} is deliberately left alone: it is the hot entity every trip books
     * revenue to, and namespacing it would remove the contention that makes the W1 shape realistic.
     */
    private static OrderCandidate namespaced(OrderCandidate order, String namespace) {
        List<Entry> entries = order.entries().stream()
                .map(entry -> entry.entityId().startsWith("rider:") || entry.entityId().startsWith("driver:")
                        ? Entry.of(entry.entityId() + "s" + namespace, entry.account(), entry.currency(),
                                entry.amountMinor())
                        : entry)
                .toList();
        return new OrderCandidate(order.type(), order.reason(), entries);
    }

    /** One order the simulator will post, with the key and group it will post it under. */
    public record GeneratedOrder(String idempotencyKey, String orderGroupId, Instant effectiveAt, OrderCandidate order) {

        /** The single entry booked to a rider; every W1 order has exactly one. */
        public Entry riderEntry() {
            return order.entries().stream()
                    .filter(entry -> entry.entityId().startsWith("rider:"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no rider entry in " + order));
        }

        /**
         * The request body, built by hand rather than serialized from a map.
         *
         * <p>Byte-identical output for the same inputs is a requirement here, not a preference: order-service
         * compares the replayed body with the stored one, so a serializer that reordered keys would turn every
         * replay into a 422.
         */
        public String toRequestBody(String scenarioName, long runSeed) {
            var body = new StringBuilder(256);
            body.append("{\"order_group_id\":").append(Json.quote(orderGroupId))
                    .append(",\"type\":").append(Json.quote(order.type()))
                    .append(",\"reason\":").append(Json.quote(order.reason()))
                    .append(",\"adjusts_order_id\":null")
                    .append(",\"entries\":[");
            for (int i = 0; i < order.entries().size(); i++) {
                Entry entry = order.entries().get(i);
                if (i > 0) {
                    body.append(',');
                }
                body.append("{\"entity_id\":").append(Json.quote(entry.entityId()))
                        .append(",\"account\":").append(Json.quote(entry.account()))
                        .append(",\"currency\":").append(Json.quote(entry.currency()))
                        .append(",\"amount_minor\":").append(entry.amountMinor())
                        .append('}');
            }
            body.append("],\"metadata\":{\"scenario\":").append(Json.quote(scenarioName))
                    .append(",\"run_seed\":").append(Json.quote(Long.toString(runSeed)))
                    .append("},\"effective_at\":").append(Json.quote(effectiveAt.toString()))
                    .append('}');
            return body.toString();
        }
    }
}
