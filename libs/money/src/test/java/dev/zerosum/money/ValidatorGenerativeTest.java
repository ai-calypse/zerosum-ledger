package dev.zerosum.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import dev.zerosum.contracts.ContractSchemas;
import dev.zerosum.money.OrderCandidate.Entry;
import dev.zerosum.money.generate.OrderGenerator;
import dev.zerosum.money.generate.OrderGenerator.Labeled;
import dev.zerosum.money.generate.Seed;
import dev.zerosum.money.generate.SeededExtension;
import dev.zerosum.money.generate.TripSequenceGenerator;
import dev.zerosum.money.generate.TripSequenceGenerator.TripOrder;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SeededExtension.class)
class ValidatorGenerativeTest {

    /** M1(a): docs/zerosum_ledger_mvp_plan.md#must-have — seeded random orders. */
    private static final int ORDERS = 10_000;

    /** Shape constraints the money-order schema also expresses; the other families are cross-field rules. */
    private static final Set<Violation.Code> SCHEMA_EXPRESSIBLE = Set.of(
            Violation.Code.TYPE_INVALID, Violation.Code.REASON_INVALID, Violation.Code.ENTRY_COUNT_OUT_OF_RANGE,
            Violation.Code.AMOUNT_ZERO, Violation.Code.AMOUNT_OUT_OF_RANGE, Violation.Code.ENTITY_ID_INVALID,
            Violation.Code.CURRENCY_NOT_ALLOWED);

    private final ZeroSumValidator validator = ZeroSumValidator.defaults();

    @Test
    void acceptsEveryBalancedOrderAndRejectsEveryLabeledOrder(Seed seed) {
        OrderGenerator generator = new OrderGenerator(seed.random(), seed.value());
        Map<Violation.Code, Integer> families = new EnumMap<>(Violation.Code.class);
        int valid = 0;
        for (int i = 0; i < ORDERS; i++) {
            if (i % 2 == 0) {
                OrderCandidate order = generator.valid();
                List<Violation> violations = validator.validate(order);
                if (!violations.isEmpty()) {
                    fail("generated valid order #" + i + " rejected with " + violations + ": " + order);
                }
                valid++;
            } else {
                Labeled labeled = generator.invalid();
                List<Violation> violations = validator.validate(labeled.order());
                if (violations.stream().noneMatch(v -> v.code() == labeled.expected())) {
                    fail("order #" + i + " labeled " + labeled.expected() + " got " + violations + ": " + labeled.order());
                }
                families.merge(labeled.expected(), 1, Integer::sum);
            }
        }
        assertEquals(ORDERS / 2, valid);
        assertEquals(Set.copyOf(OrderGenerator.FAMILIES), families.keySet(), "every mutation family was exercised");
        System.out.println("ZS-GENERATIVE orders=" + ORDERS + " valid=" + valid + " invalid=" + (ORDERS - valid)
                + " families=" + families);
    }

    @Test
    void schemaAndValidatorAgreeOnSharedShapeConstraints(Seed seed) {
        OrderGenerator generator = new OrderGenerator(seed.random(), seed.value());
        for (int i = 0; i < 500; i++) {
            OrderCandidate order = generator.valid();
            assertEquals(List.of(), ContractSchemas.validate(ContractSchemas.MONEY_ORDER_V1, render(order)), "valid #" + i);
            Labeled labeled = generator.invalid();
            if (SCHEMA_EXPRESSIBLE.contains(labeled.expected())) {
                assertFalse(ContractSchemas.validate(ContractSchemas.MONEY_ORDER_V1, render(labeled.order())).isEmpty(),
                        "schema must reject " + labeled.expected() + ": " + labeled.order());
            }
        }
    }

    @Test
    void generatedTripSequencesValidateAndAccumulateToADirectSplit(Seed seed) {
        long bps = 2000;
        List<TripOrder> trips = TripSequenceGenerator.trips(seed.random(), 300, bps, 40, "USD");
        Map<String, Long> fares = new TreeMap<>();
        Map<String, Long> driverCredits = new TreeMap<>();
        for (TripOrder trip : trips) {
            assertEquals(List.of(), validator.validate(trip.order()), trip.toString());
            Entry rider = trip.order().entries().get(0);
            fares.merge(rider.entityId(), rider.amountMinor(), Math::addExact);
            long driver = trip.order().entries().stream().filter(e -> e.account().equals("payable"))
                    .mapToLong(Entry::amountMinor).sum();
            driverCredits.merge(rider.entityId(), -driver, Math::addExact);
        }
        // Per trip (one rider each), cumulative driver credit equals the driver share of a direct split of the final fare.
        fares.forEach((rider, finalFare) -> assertEquals(
                FareSplitter.split(Money.of(finalFare, "USD"), bps).driverShare().amountMinor(), driverCredits.get(rider), rider));
        assertFalse(trips.stream().filter(t -> t.adjustsIndex() >= 0).collect(Collectors.toList()).isEmpty(), "some adjustments");
    }

    /** Minimal stored-order JSON around generated fields; generated strings never need escaping. */
    static String render(OrderCandidate o) {
        String entries = o.entries().stream().map(e -> "{\"entity_id\":" + str(e.entityId()) + ",\"account\":"
                + str(e.account()) + ",\"currency\":" + str(e.currency()) + ",\"amount_minor\":" + e.amountMinor() + "}")
                .collect(Collectors.joining(","));
        return "{\"schema\":\"zerosum.money_order.v1\",\"order_id\":\"01996a3e-8f10-7c2a-9a1e-3d5c7b2e4f99\","
                + "\"order_group_id\":\"gen_group\",\"type\":" + str(o.type()) + ",\"reason\":" + str(o.reason())
                + ",\"adjusts_order_id\":null,\"source\":{\"system\":\"generator\",\"idempotency_key\":\"k\"},"
                + "\"entries\":[" + entries + "],\"metadata\":{},\"effective_at\":\"2026-09-15T10:00:00.000Z\","
                + "\"created_at\":\"2026-09-15T10:00:00.001Z\"}";
    }

    private static String str(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }
}
