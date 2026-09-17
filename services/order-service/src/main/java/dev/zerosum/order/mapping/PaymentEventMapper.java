package dev.zerosum.order.mapping;

import dev.zerosum.money.OrderCandidate;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Payment event to money order (D03-6, ADR-0006, master §5.5).
 *
 * <p>A pure function: no clock, no I/O, no Spring. The effective time comes from the event's {@code occurred_at}, so
 * replaying an event a week later produces the identical order — which is what makes redelivery safe and the golden
 * comparisons meaningful.
 *
 * <p>Amounts arrive as a positive magnitude; the sign convention is applied here (ADR-0003), and each row is written
 * so the two or three entries sum to zero per currency by construction.
 */
public final class PaymentEventMapper {

    /** The platform's own entity, which holds cash and fee accounts. */
    private static final String PLATFORM = "platform:main";

    private PaymentEventMapper() {
    }

    /** What an event becomes. Poison is separate from "no order": one is a defect, the other a normal outcome. */
    public sealed interface MappingResult permits OrderDraft, NoOrder, Poison {
    }

    /**
     * An order ready for the store. Carries no order id or created time: those are the database's (D03-1).
     *
     * @param idempotencyKey the event id, so repeated delivery produces exactly one order (I8)
     */
    public record OrderDraft(String idempotencyKey, String orderGroupId, String type, String reason,
            List<OrderCandidate.Entry> entries, String metadataJson, Instant effectiveAt) implements MappingResult {
    }

    /** The event is valid and deliberately produces no order (§0.3 C5). */
    public record NoOrder(String eventType, String metricName) implements MappingResult {
    }

    /** The event cannot become a valid order. It is quarantined, never skipped. */
    public record Poison(String code, String detail) implements MappingResult {
    }

    public static MappingResult map(JsonNode event) {
        String type = text(event, "event_type");
        if (type == null) {
            return new Poison("missing_event_type", "the event carries no event_type");
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(text(event, "occurred_at"));
        } catch (DateTimeParseException | NullPointerException bad) {
            return new Poison("bad_occurred_at", "occurred_at must be an RFC 3339 timestamp");
        }

        return switch (type) {
            // Declines and failures before acceptance leave the books unchanged: the receivable or the payable is
            // still owed, so inventing an entry would move money that never moved.
            case "CHARGE_DECLINED" -> new NoOrder(type, "charges_declined_total");
            case "REFUND_FAILED" -> new NoOrder(type, "refunds_failed_total");
            case "PAYOUT_REJECTED" -> new NoOrder(type, "payouts_rejected_total");

            case "CHARGE_SUCCEEDED" -> attempt(event, occurredAt, "COLLECTION", "charge.succeeded",
                    provider(event), "clearing", entity(event), "receivable", null);
            case "REFUND_SUCCEEDED" -> attempt(event, occurredAt, "REFUND", "refund.succeeded",
                    entity(event), "receivable", provider(event), "clearing", null);
            case "PAYOUT_ACCEPTED" -> attempt(event, occurredAt, "DISBURSEMENT", "payout.accepted",
                    entity(event), "payable", provider(event), "payout_clearing", null);
            // Only ever emitted after PAYOUT_ACCEPTED, so it reverses that order (§0.3 C5). A decline at submission
            // arrives as PAYOUT_REJECTED and creates nothing, which is why the mapper needs no history to be correct.
            case "PAYOUT_FAILED" -> attempt(event, occurredAt, "DISBURSEMENT", "payout.failed",
                    provider(event), "payout_clearing", entity(event), "payable",
                    metadata("failure_code", text(event, "failure_code")));
            case "PAYOUT_SETTLED" -> attempt(event, occurredAt, "DISBURSEMENT", "payout.settled",
                    provider(event), "payout_clearing", PLATFORM, "cash", null);
            case "PAYOUT_RETURNED" -> attempt(event, occurredAt, "DISBURSEMENT", "payout.returned",
                    PLATFORM, "cash", entity(event), "payable",
                    metadata("return_code", text(event, "failure_code")));

            case "SETTLEMENT_RECEIVED" -> settlement(event, occurredAt);

            // A type the D01-8 schema does not define. Quarantined rather than ignored, so a producer shipping a new
            // event type is discovered immediately instead of losing orders silently.
            default -> new Poison("unknown_event_type", "no mapping row for event type " + type);
        };
    }

    /**
     * The two-entry rows: one account is debited and the other credited by the same magnitude, so the order is
     * zero-sum by construction rather than by a later check.
     */
    private static MappingResult attempt(JsonNode event, Instant occurredAt, String type, String reason,
            String plusEntity, String plusAccount, String minusEntity, String minusAccount, String metadataJson) {
        JsonNode money = event.get("money");
        if (money == null) {
            return new Poison("missing_money", "an attempt event must carry a money object");
        }
        String currency = text(money, "currency");
        long amount = money.get("amount_minor").asLong();
        if (amount <= 0) {
            return new Poison("non_positive_amount", "amount_minor must be a positive magnitude, was " + amount);
        }
        if (plusEntity == null || minusEntity == null) {
            return new Poison("missing_entity", "the event is missing an entity or provider for this mapping row");
        }
        return new OrderDraft(text(event, "event_id"), text(event, "order_group_id"), type, reason,
                List.of(OrderCandidate.Entry.of(plusEntity, plusAccount, currency, amount),
                        OrderCandidate.Entry.of(minusEntity, minusAccount, currency, -amount)),
                metadataJson, occurredAt);
    }

    /**
     * Settlement (§0.3 C6, E1): one event per provider, report and currency. The report's totals are booked <em>as
     * reported</em> — a residual on the provider's clearing account is a reconciliation break for I9 to surface, never
     * something the mapper quietly adjusts away.
     */
    private static MappingResult settlement(JsonNode event, Instant occurredAt) {
        long gross = event.get("gross_minor").asLong();
        long fee = event.get("fee_minor").asLong();
        long net = event.get("net_minor").asLong();
        if (net + fee != gross) {
            // Not a rounding question: a report whose own totals disagree cannot be booked without inventing money.
            return new Poison("settlement_mismatch",
                    "net + fee must equal gross: " + net + " + " + fee + " != " + gross);
        }
        String currency = text(event, "currency");
        return new OrderDraft(text(event, "event_id"), text(event, "order_group_id"), "SETTLEMENT",
                "settlement.received",
                List.of(OrderCandidate.Entry.of(PLATFORM, "cash", currency, net),
                        OrderCandidate.Entry.of(PLATFORM, "processing_fees", currency, fee),
                        OrderCandidate.Entry.of(provider(event), "clearing", currency, -gross)),
                metadata("report_id", text(event, "report_id")), occurredAt);
    }

    private static String provider(JsonNode event) {
        String provider = text(event, "provider");
        return provider == null ? null : "provider:" + provider;
    }

    private static String entity(JsonNode event) {
        return text(event, "entity_id");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** A one-key metadata object. Written by hand so the stored form is visible and cannot drift with a mapper setting. */
    private static String metadata(String key, String value) {
        return value == null ? null : "{\"" + key + "\":\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
