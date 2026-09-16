package dev.zerosum.ledger.apply;

import dev.zerosum.contracts.ContractSchemas;
import dev.zerosum.money.OrderCandidate;
import dev.zerosum.money.Violation;
import dev.zerosum.money.ZeroSumValidator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decodes a payload and runs the checks the ledger owns, outside the transaction (D02-3):
 *
 * <ol>
 *   <li>parse JSON; a non-integer lexical amount is rejected here, because JSON Schema accepts {@code 2500.0} (D01-8);</li>
 *   <li>validate against the money-order schema version (D01-8);</li>
 *   <li>apply the ‡ rules only, through {@link ZeroSumValidator.RuleSet#LEDGER} (D01-5, CR-S02-02). The currency
 *       allow-list, order type, reason and entry counts belong to order-service.</li>
 * </ol>
 */
@Component
public class OrderDecoder {

    /** A decode result: either an order to apply, or the quarantine code and detail for the record. */
    public sealed interface Result {

        record Decoded(DecodedOrder order) implements Result {
        }

        record Rejected(UUID orderId, QuarantineCode code, String detail) implements Result {
        }
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final ZeroSumValidator validator = ZeroSumValidator.defaults();

    public Result decode(byte[] payload) {
        JsonNode root;
        try {
            root = json.readTree(new String(payload, StandardCharsets.UTF_8));
        } catch (JacksonException e) {
            return new Result.Rejected(null, QuarantineCode.UNDECODABLE_PAYLOAD, "not JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            return new Result.Rejected(null, QuarantineCode.UNDECODABLE_PAYLOAD, "payload is not a JSON object");
        }
        UUID orderId = readUuid(root, "order_id");

        var errors = ContractSchemas.validate(ContractSchemas.MONEY_ORDER_V1, new String(payload, StandardCharsets.UTF_8));
        if (!errors.isEmpty()) {
            return new Result.Rejected(orderId, QuarantineCode.SCHEMA_INVALID,
                    errors.size() + " schema errors, first: " + errors.get(0).instanceLocation() + " " + errors.get(0).message());
        }
        // The schema treats 2500.0 as an integer; the ledger does not (D01-8).
        for (JsonNode entry : root.get("entries")) {
            if (!entry.get("amount_minor").isIntegralNumber()) {
                return new Result.Rejected(orderId, QuarantineCode.SCHEMA_INVALID,
                        "amount_minor is not an integer literal: " + entry.get("amount_minor"));
            }
        }

        List<DecodedOrder.Entry> entries = new ArrayList<>();
        List<OrderCandidate.Entry> candidateEntries = new ArrayList<>();
        for (JsonNode entry : root.get("entries")) {
            String entityId = entry.get("entity_id").asString();
            String account = entry.get("account").asString();
            String currency = entry.get("currency").asString();
            long amount = entry.get("amount_minor").asLong();
            entries.add(new DecodedOrder.Entry(entityId, account, currency, amount));
            candidateEntries.add(OrderCandidate.Entry.of(entityId, account, currency, amount));
        }

        List<Violation> violations = validator.validate(
                new OrderCandidate(root.get("type").asString(), root.get("reason").asString(), candidateEntries),
                ZeroSumValidator.RuleSet.LEDGER);
        if (!violations.isEmpty()) {
            return new Result.Rejected(orderId, QuarantineCode.STRUCTURALLY_INVALID, violations.toString());
        }

        Instant createdAt;
        try {
            createdAt = Instant.parse(root.get("created_at").asString());
        } catch (DateTimeParseException e) {
            return new Result.Rejected(orderId, QuarantineCode.SCHEMA_INVALID, "created_at is not an instant");
        }
        if (orderId == null) {
            return new Result.Rejected(null, QuarantineCode.UNDECODABLE_PAYLOAD, "order_id is not a UUID");
        }
        return new Result.Decoded(new DecodedOrder(orderId, root.get("order_group_id").asString(),
                root.at("/source/system").asString(), root.at("/source/idempotency_key").asString(), createdAt,
                List.copyOf(entries)));
    }

    private static UUID readUuid(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return UUID.fromString(node.asString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
