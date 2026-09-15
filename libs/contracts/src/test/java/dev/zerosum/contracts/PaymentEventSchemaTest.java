package dev.zerosum.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.ContractSchemas.SchemaError;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PaymentEventSchemaTest {

    /** docs/zerosum_ledger_mvp_plan.md#event-contracts, v1.2 list (§0.3 C5). */
    private static final List<String> C5_EVENT_TYPES = List.of(
            "CHARGE_SUCCEEDED", "CHARGE_DECLINED", "REFUND_SUCCEEDED", "REFUND_FAILED", "PAYOUT_ACCEPTED",
            "PAYOUT_REJECTED", "PAYOUT_FAILED", "PAYOUT_SETTLED", "PAYOUT_RETURNED", "SETTLEMENT_RECEIVED");

    private static List<JsonNode> manifestEvents() {
        return JsonTestSupport.list(JsonTestSupport.parse(GoldenPayloads.manifest()).get("events"));
    }

    @Test
    void everyGoldenEventIsValid() {
        for (JsonNode entry : manifestEvents()) {
            String id = entry.get("id").asString();
            assertEquals(List.of(), ContractSchemas.validate(ContractSchemas.PAYMENT_EVENT_V1, GoldenPayloads.byId(id)), id);
        }
    }

    @Test
    void eventTypeEnumIsExactlyTheC5ListAndEveryTypeHasAPositiveFixture() {
        JsonNode schema = JsonTestSupport.parse(GoldenPayloads.resource("schemas/zerosum.payment_event.v1.schema.json"));
        assertEquals(C5_EVENT_TYPES,
                JsonTestSupport.list(schema.at("/properties/event_type/enum")).stream().map(JsonNode::asString).toList());
        TreeSet<String> covered = new TreeSet<>();
        for (JsonNode entry : manifestEvents()) {
            JsonNode event = JsonTestSupport.parse(GoldenPayloads.byId(entry.get("id").asString()));
            assertEquals(entry.get("event_type").asString(), event.get("event_type").asString());
            covered.add(event.get("event_type").asString());
        }
        assertEquals(new TreeSet<>(C5_EVENT_TYPES), covered);
    }

    @Test
    void goldenEventsFollowTheEventIdAndSettlementRules() {
        for (JsonNode entry : manifestEvents()) {
            JsonNode e = JsonTestSupport.parse(GoldenPayloads.byId(entry.get("id").asString()));
            String type = e.get("event_type").asString();
            if (type.equals("SETTLEMENT_RECEIVED")) {
                // §0.3 C6: event_id = settlement:<provider>:<report_id>:<currency>, which is also the order group.
                String expected = "settlement:" + e.get("provider").asString() + ":" + e.get("report_id").asString()
                        + ":" + e.get("currency").asString();
                assertEquals(expected, e.get("event_id").asString());
                assertEquals(expected, e.get("order_group_id").asString());
                assertEquals(e.get("gross_minor").asLong(), e.get("net_minor").asLong() + e.get("fee_minor").asLong(),
                        "net + fee = gross");
            } else {
                // §0.3 C5: event_id = <attempt_id>:<event_type>.
                assertEquals(e.get("attempt_id").asString() + ":" + type, e.get("event_id").asString());
            }
        }
    }

    @Test
    void everyNegativeFixtureIsRejectedWithItsExpectedKeyword() {
        JsonNode cases = JsonTestSupport.parse(GoldenPayloads.resource("negative/payment-event.json")).get("cases");
        for (JsonNode testCase : cases) {
            String name = testCase.get("name").asString();
            String doc = JsonTestSupport.applyCase(GoldenPayloads.byId(testCase.get("base").asString()), testCase);
            List<SchemaError> errors = ContractSchemas.validate(ContractSchemas.PAYMENT_EVENT_V1, doc);
            assertFalse(errors.isEmpty(), name + " should be invalid");
            String expected = testCase.get("expected_keyword").asString();
            assertTrue(errors.stream().anyMatch(err -> expected.equals(err.keyword())), name + ": expected " + expected + " in " + errors);
        }
    }
}
