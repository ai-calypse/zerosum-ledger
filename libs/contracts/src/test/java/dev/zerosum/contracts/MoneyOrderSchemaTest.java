package dev.zerosum.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.ContractSchemas.SchemaError;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MoneyOrderSchemaTest {

    @Test
    void everyGoldenOrderIsValid() {
        for (int i = 1; i <= 8; i++) {
            String id = "O" + i;
            assertEquals(List.of(), ContractSchemas.validate(ContractSchemas.MONEY_ORDER_V1, GoldenPayloads.byId(id)), id);
        }
    }

    @Test
    void everyNegativeFixtureIsRejectedWithItsExpectedKeyword() {
        JsonNode cases = JsonTestSupport.parse(GoldenPayloads.resource("negative/money-order.json")).get("cases");
        assertTrue(cases.size() >= 10);
        for (JsonNode testCase : cases) {
            String name = testCase.get("name").asString();
            String doc = JsonTestSupport.applyCase(GoldenPayloads.byId("O1"), testCase);
            List<SchemaError> errors = ContractSchemas.validate(ContractSchemas.MONEY_ORDER_V1, doc);
            assertFalse(errors.isEmpty(), name + " should be invalid");
            String expected = testCase.get("expected_keyword").asString();
            assertTrue(errors.stream().anyMatch(e -> expected.equals(e.keyword())), name + ": expected " + expected + " in " + errors);
        }
    }

    @Test
    void documentsThatAZeroFractionIntegerPassesTheSchema() {
        // JSON Schema 2020-12 treats 2500.0 as an integer, so the schema can't reject decimal notation. Consumers'
        // JSON parsers must reject non-integer lexical amounts (D01-8; S02, S03).
        String doc = GoldenPayloads.byId("O1").replace("\"amount_minor\": 2500", "\"amount_minor\": 2500.0");
        assertTrue(doc.contains("2500.0"));
        assertEquals(List.of(), ContractSchemas.validate(ContractSchemas.MONEY_ORDER_V1, doc));
    }

    @Test
    void schemaTypeEnumAndLimitsMatchTheLibraryConstants() {
        JsonNode schema = JsonTestSupport.parse(GoldenPayloads.resource("schemas/zerosum.money_order.v1.schema.json"));
        assertEquals(dev.zerosum.money.ValidationLimits.TYPES,
                JsonTestSupport.list(schema.at("/properties/type/enum")).stream().map(JsonNode::asString).toList());
        assertEquals(dev.zerosum.money.ValidationLimits.MIN_ENTRIES, schema.at("/properties/entries/minItems").asInt());
        assertEquals(dev.zerosum.money.ValidationLimits.MAX_ENTRIES, schema.at("/properties/entries/maxItems").asInt());
        assertEquals(dev.zerosum.money.ValidationLimits.REASON_MAX_LENGTH, schema.at("/properties/reason/maxLength").asInt());
        assertEquals(dev.zerosum.money.ValidationLimits.REASON_PATTERN.pattern(), schema.at("/properties/reason/pattern").asString());
        assertEquals(dev.zerosum.money.ValidationLimits.ENTITY_ID_PATTERN.pattern(), schema.at("/$defs/entity_id/pattern").asString());
        assertEquals(dev.zerosum.money.ValidationLimits.ENTITY_ID_MAX_LENGTH, schema.at("/$defs/entity_id/maxLength").asInt());
        assertEquals(dev.zerosum.money.ValidationLimits.MAX_ABS_AMOUNT_MINOR, schema.at("/$defs/signed_minor_amount/maximum").asLong());
        assertEquals(-dev.zerosum.money.ValidationLimits.MAX_ABS_AMOUNT_MINOR, schema.at("/$defs/signed_minor_amount/minimum").asLong());
        assertEquals(List.copyOf(dev.zerosum.money.CurrencyRules.defaults().allowedCodes()),
                JsonTestSupport.list(schema.at("/$defs/currency_code/enum")).stream().map(JsonNode::asString).sorted().toList());
    }
}
