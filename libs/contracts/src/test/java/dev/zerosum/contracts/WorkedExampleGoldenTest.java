package dev.zerosum.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.zerosum.money.ChartOfAccounts;
import dev.zerosum.money.FeeCalculator;
import dev.zerosum.money.Money;
import dev.zerosum.money.OrderCandidate;
import dev.zerosum.money.ZeroSumValidator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** M1(d) scope set by master §0.3 O6: golden O1–O7 plus the O8 variant against docs/zerosum_ledger_mvp_plan.md#worked-example. */
class WorkedExampleGoldenTest {

    private static JsonNode order(String id) {
        return JsonTestSupport.parse(GoldenPayloads.byId(id));
    }

    private static OrderCandidate candidate(JsonNode order) {
        List<OrderCandidate.Entry> entries = JsonTestSupport.list(order.get("entries")).stream()
                .map(e -> OrderCandidate.Entry.of(e.get("entity_id").asString(), e.get("account").asString(),
                        e.get("currency").asString(), e.get("amount_minor").asLong()))
                .toList();
        return new OrderCandidate(order.get("type").asString(), order.get("reason").asString(), entries);
    }

    @Test
    void everyGoldenOrderPassesTheValidatorAndSumsToZeroPerCurrency() {
        for (int i = 1; i <= 8; i++) {
            String id = "O" + i;
            JsonNode o = order(id);
            assertEquals(List.of(), ZeroSumValidator.defaults().validate(candidate(o)), id);
            Map<String, Long> sums = new TreeMap<>();
            o.get("entries").forEach(e -> sums.merge(e.get("currency").asString(), e.get("amount_minor").asLong(), Math::addExact));
            sums.forEach((currency, sum) -> assertEquals(0L, sum, id + " " + currency));
        }
    }

    @Test
    void cumulativeBalancesMatchTheExpectedBalanceFixtures() {
        for (JsonNode scenario : JsonTestSupport.parse(GoldenPayloads.expectedBalances()).get("scenarios")) {
            Map<String, Long> balances = new TreeMap<>();
            for (JsonNode id : scenario.get("orders")) {
                order(id.asString()).get("entries").forEach(e -> balances.merge(
                        e.get("entity_id").asString() + "/" + e.get("account").asString() + "/" + e.get("currency").asString(),
                        e.get("amount_minor").asLong(), Math::addExact));
            }
            Map<String, Long> expected = new TreeMap<>();
            for (JsonNode b : scenario.get("balances")) {
                String key = b.get("entity_id").asString() + "/" + b.get("account").asString() + "/" + b.get("currency").asString();
                expected.put(key, b.get("signed_minor").asLong());
                assertEquals(b.get("presented_minor").asLong(),
                        ChartOfAccounts.presentOnNormalSide(b.get("account").asString(), b.get("signed_minor").asLong()), key);
            }
            String id = scenario.get("id").asString();
            assertEquals(expected, balances, id);
            assertEquals(0L, balances.values().stream().mapToLong(Long::longValue).sum(), id + " global sum");
        }
    }

    @Test
    void feeCalculatorOnTheCapturedAmountEqualsTheSettlementProcessingFee() {
        // Example FakeCard schedule, a simulation parameter: 290 bps + 30 minor units (docs/zerosum_ledger_mvp_plan.md#worked-example).
        long captured = JsonTestSupport.parse(GoldenPayloads.byId("O2")).at("/entries/0/amount_minor").asLong();
        Money fee = FeeCalculator.fee(Money.of(captured, "USD"), 290, Money.of(30, "USD"));
        long o6Fee = JsonTestSupport.list(order("O6").get("entries")).stream()
                .filter(e -> e.get("account").asString().equals("processing_fees"))
                .mapToLong(e -> e.get("amount_minor").asLong()).sum();
        assertEquals(o6Fee, fee.amountMinor());
    }

    @Test
    void pairedEventsMatchTheirOrdersAndMapperOrdersFollowC7() {
        JsonNode manifest = JsonTestSupport.parse(GoldenPayloads.manifest());
        for (JsonNode entry : manifest.get("orders")) {
            JsonNode o = order(entry.get("id").asString());
            if (entry.get("source").asString().equals("writer")) {
                assertEquals("trip-simulator", o.at("/source/system").asString(), "writer source.system (flagged for D03-4)");
                continue;
            }
            JsonNode e = JsonTestSupport.parse(GoldenPayloads.byId(entry.get("paired_event").asString()));
            // §0.3 C7: mapper-created orders come from instrument-service, keyed by the event_id.
            assertEquals("instrument-service", o.at("/source/system").asString());
            assertEquals(e.get("event_id").asString(), o.at("/source/idempotency_key").asString());
            assertEquals(e.get("order_group_id").asString(), o.get("order_group_id").asString());
            assertEquals(expectedEntries(e), entriesOf(o), entry.get("id").asString());
        }
        assertNull(order("O1").get("adjusts_order_id").asString(null));
        assertEquals(order("O1").get("order_id").asString(), order("O3").get("adjusts_order_id").asString());
        assertEquals(order("O1").get("order_group_id").asString(), order("O3").get("order_group_id").asString());
    }

    @Test
    void timestampsIncreaseFromO1ToO8() {
        String previous = "";
        for (int i = 1; i <= 8; i++) {
            String created = order("O" + i).get("created_at").asString();
            assertEquals(1, Integer.signum(created.compareTo(previous)), "O" + i);
            previous = created;
        }
    }

    /** Entries the master §5.5 mapping row produces for a golden event, as "entity/account/currency/amount" lines. */
    private static List<String> expectedEntries(JsonNode e) {
        String p = "provider:" + e.get("provider").asString();
        String type = e.get("event_type").asString();
        if (type.equals("SETTLEMENT_RECEIVED")) {
            String c = e.get("currency").asString();
            return List.of("platform:main/cash/" + c + "/" + e.get("net_minor").asLong(),
                    "platform:main/processing_fees/" + c + "/" + e.get("fee_minor").asLong(),
                    p + "/clearing/" + c + "/" + -e.get("gross_minor").asLong());
        }
        String entity = e.get("entity_id").asString();
        String c = e.at("/money/currency").asString();
        long a = e.at("/money/amount_minor").asLong();
        return switch (type) {
            case "CHARGE_SUCCEEDED" -> List.of(p + "/clearing/" + c + "/" + a, entity + "/receivable/" + c + "/" + -a);
            case "REFUND_SUCCEEDED" -> List.of(entity + "/receivable/" + c + "/" + a, p + "/clearing/" + c + "/" + -a);
            case "PAYOUT_ACCEPTED" -> List.of(entity + "/payable/" + c + "/" + a, p + "/payout_clearing/" + c + "/" + -a);
            case "PAYOUT_FAILED" -> List.of(p + "/payout_clearing/" + c + "/" + a, entity + "/payable/" + c + "/" + -a);
            case "PAYOUT_SETTLED" -> List.of(p + "/payout_clearing/" + c + "/" + a, "platform:main/cash/" + c + "/" + -a);
            case "PAYOUT_RETURNED" -> List.of("platform:main/cash/" + c + "/" + a, entity + "/payable/" + c + "/" + -a);
            default -> throw new AssertionError("event type produces no order: " + type);
        };
    }

    private static List<String> entriesOf(JsonNode order) {
        return JsonTestSupport.list(order.get("entries")).stream()
                .map(e -> e.get("entity_id").asString() + "/" + e.get("account").asString() + "/"
                        + e.get("currency").asString() + "/" + e.get("amount_minor").asLong())
                .toList();
    }
}
