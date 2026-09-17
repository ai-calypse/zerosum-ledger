package dev.zerosum.order.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.money.OrderCandidate;
import dev.zerosum.money.Violation;
import dev.zerosum.money.ZeroSumValidator;
import dev.zerosum.order.mapping.PaymentEventMapper.MappingResult;
import dev.zerosum.order.mapping.PaymentEventMapper.NoOrder;
import dev.zerosum.order.mapping.PaymentEventMapper.OrderDraft;
import dev.zerosum.order.mapping.PaymentEventMapper.Poison;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every row of the master mapping table (§5.5), checked against the D01-9 goldens rather than against my own reading
 * of the table — the goldens are the contract the ledger's expected balances were computed from.
 */
@Tag("unit")
class PaymentEventMapperTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZeroSumValidator VALIDATOR = ZeroSumValidator.defaults();

    /** Each golden event and the golden order it must produce. */
    static Stream<Arguments> goldenPairs() {
        return Stream.of(
                Arguments.of("EV-O2", "O2"),
                Arguments.of("EV-O4", "O4"),
                Arguments.of("EV-O5", "O5"),
                Arguments.of("EV-O6", "O6"),
                Arguments.of("EV-O7", "O7"),
                Arguments.of("EV-O8", "O8"));
    }

    @ParameterizedTest(name = "{0} maps to {1}")
    @MethodSource("goldenPairs")
    void eachGoldenEventReproducesItsGoldenOrder(String eventId, String orderId) {
        JsonNode event = JSON.readTree(GoldenPayloads.byId(eventId));
        JsonNode expected = JSON.readTree(GoldenPayloads.byId(orderId));

        OrderDraft draft = assertInstanceOf(OrderDraft.class, PaymentEventMapper.map(event),
                eventId + " must produce an order");

        assertEquals(expected.get("type").asString(), draft.type());
        assertEquals(expected.get("reason").asString(), draft.reason());
        assertEquals(expected.get("order_group_id").asString(), draft.orderGroupId());
        assertEquals(java.time.Instant.parse(expected.get("effective_at").asString()), draft.effectiveAt(),
                "effective_at must be the event's occurrence time, compared as an instant rather than as text: "
                        + "Instant.toString() drops trailing zero milliseconds, so the string forms differ while the "
                        + "instants are identical");
        // The event id is the idempotency key, which is what makes redelivery produce one order (I8).
        assertEquals(expected.get("source").get("idempotency_key").asString(), draft.idempotencyKey());
        assertEquals(entriesOf(expected), draft.entries(), "entries must match the golden exactly, in order");
        assertEquals(metadataOf(expected), JSON.readTree(draft.metadataJson() == null ? "{}" : draft.metadataJson()),
                "metadata must match the golden");
    }

    @ParameterizedTest(name = "{0} produces no order")
    @MethodSource("noOrderEvents")
    void eventsThatLeaveTheBooksUnchangedProduceNoOrder(String eventId, String metric) {
        JsonNode event = JSON.readTree(GoldenPayloads.byId(eventId));
        NoOrder result = assertInstanceOf(NoOrder.class, PaymentEventMapper.map(event));
        assertEquals(metric, result.metricName());
    }

    static Stream<Arguments> noOrderEvents() {
        // A decline leaves the receivable owed and a rejected payout leaves the payable owed: in both cases nothing
        // moved, so an order would be inventing a movement.
        return Stream.of(
                Arguments.of("EV-CHARGE_DECLINED", "charges_declined_total"),
                Arguments.of("EV-REFUND_FAILED", "refunds_failed_total"),
                Arguments.of("EV-PAYOUT_REJECTED", "payouts_rejected_total"));
    }

    @Test
    void payoutFailedAfterAcceptanceReversesTheDisbursement() {
        // PAYOUT_FAILED has no golden order (the manifest records it as unpaired), so this asserts the master row
        // directly: provider payout_clearing +A, driver payable -A, which is exactly the reverse of payout.accepted.
        JsonNode event = JSON.readTree(GoldenPayloads.byId("EV-PAYOUT_FAILED"));
        OrderDraft draft = assertInstanceOf(OrderDraft.class, PaymentEventMapper.map(event));

        assertEquals("DISBURSEMENT", draft.type());
        assertEquals("payout.failed", draft.reason());
        assertEquals(List.of(
                        OrderCandidate.Entry.of("provider:fakebank", "payout_clearing", "KWD", 12345),
                        OrderCandidate.Entry.of("driver:D3", "payable", "KWD", -12345)),
                draft.entries());
        assertEquals(JSON.readTree("{\"failure_code\":\"R03\"}"), JSON.readTree(draft.metadataJson()));
    }

    @Test
    void aSettlementWhoseTotalsDisagreeIsPoison() {
        // Booking it would mean inventing the difference. It is quarantined instead, and the partition keeps moving.
        var event = (tools.jackson.databind.node.ObjectNode) JSON.readTree(GoldenPayloads.byId("EV-O6"));
        event.put("fee_minor", 103);   // net 2098 + fee 103 != gross 2200

        Poison poison = assertInstanceOf(Poison.class, PaymentEventMapper.map(event));
        assertEquals("settlement_mismatch", poison.code());
    }

    @Test
    void anEventTypeWithNoMappingRowIsPoison() {
        var event = (tools.jackson.databind.node.ObjectNode) JSON.readTree(GoldenPayloads.byId("EV-O2"));
        event.put("event_type", "CHARGE_REVERSED");

        Poison poison = assertInstanceOf(Poison.class, PaymentEventMapper.map(event));
        assertEquals("unknown_event_type", poison.code());
    }

    @Test
    void everyMappedDraftPassesTheSameValidationAsAnApiOrder() {
        // The mapper must not be able to produce an order the API would have rejected (D01-5, D01-6).
        for (String eventId : List.of("EV-O2", "EV-O4", "EV-O5", "EV-O6", "EV-O7", "EV-O8", "EV-PAYOUT_FAILED")) {
            JsonNode event = JSON.readTree(GoldenPayloads.byId(eventId));
            OrderDraft draft = assertInstanceOf(OrderDraft.class, PaymentEventMapper.map(event));
            List<Violation> violations =
                    VALIDATOR.validate(new OrderCandidate(draft.type(), draft.reason(), draft.entries()));
            assertTrue(violations.isEmpty(), eventId + " produced an invalid order: " + violations);
        }
    }

    private static List<OrderCandidate.Entry> entriesOf(JsonNode order) {
        return order.get("entries").valueStream()
                .map(entry -> OrderCandidate.Entry.of(entry.get("entity_id").asString(),
                        entry.get("account").asString(), entry.get("currency").asString(),
                        entry.get("amount_minor").asLong()))
                .toList();
    }

    private static JsonNode metadataOf(JsonNode order) {
        JsonNode metadata = order.get("metadata");
        return metadata == null || metadata.isNull() ? JSON.readTree("{}") : metadata;
    }
}
