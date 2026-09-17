// decision: D05-5 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.store;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which transitions emit a payment event, and what that event says (D05-5, master §5.4, schema D01-8).
 *
 * <p><strong>Each event type occurs at most once per attempt.</strong> That is what makes {@code event_id}
 * deterministic — {@code <attempt_id>:<event_type>} — so a replayed transition produces the same id and the
 * downstream mapper drops it as a duplicate instead of creating a second money order.
 *
 * <p>Three rules in the table are easy to get wrong:
 * <ul>
 *   <li>{@code PAYOUT_ACCEPTED} is emitted on the <em>non-terminal</em> transition into {@code PENDING}. Waiting for
 *       a terminal state would leave the ledger unaware of money already committed to a driver.</li>
 *   <li>A refusal at submission ({@code SUBMITTING → FAILED}) is {@code PAYOUT_REJECTED}, which creates no order.
 *       {@code PAYOUT_FAILED} is emitted only from {@code PENDING} — after acceptance — because by then an order
 *       exists that has to be reversed.</li>
 *   <li>{@code CHARGE_DECLINED} and {@code REFUND_FAILED} are emitted although they create no order, so the outcome
 *       is visible downstream rather than inferred from silence.</li>
 * </ul>
 *
 * <p>The payload is assembled key by key rather than serialized from a record. The D01-8 schema expresses several
 * rules as <em>absence</em> ({@code "source_order_id": false} for payouts, {@code "failure_code": false} for
 * successes), and an explicit {@code null} violates those exactly as a value would. Building the map makes the wire
 * form visible here and independent of any mapper setting elsewhere in the service.
 */
public final class PaymentEvents {

    static final String SCHEMA = "zerosum.payment_event.v1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Outcomes the schema requires a {@code failure_code} on; every other type must omit it. */
    private static final Set<String> FAILURE_EVENTS = Set.of(
            "CHARGE_DECLINED", "REFUND_FAILED", "PAYOUT_REJECTED", "PAYOUT_FAILED", "PAYOUT_RETURNED");

    /** Types that come from a COMMERCE order; payouts come from a run and must omit {@code source_order_id}. */
    private static final Set<String> ORDER_SOURCED = Set.of(
            "CHARGE_SUCCEEDED", "CHARGE_DECLINED", "REFUND_SUCCEEDED", "REFUND_FAILED");

    /** Types the provider accepted, which therefore carry its reference. */
    private static final Set<String> PROVIDER_REFERENCED = Set.of(
            "CHARGE_SUCCEEDED", "REFUND_SUCCEEDED", "PAYOUT_ACCEPTED", "PAYOUT_FAILED", "PAYOUT_SETTLED",
            "PAYOUT_RETURNED");

    private PaymentEvents() {
    }

    /**
     * The event type a transition emits, or empty when it emits none.
     *
     * @param from the status the attempt left; {@code PAYOUT_REJECTED} and {@code PAYOUT_FAILED} are told apart by it
     */
    public static Optional<String> eventTypeFor(String kind, String from, String to) {
        return Optional.ofNullable(switch (kind) {
            case "CHARGE" -> switch (to) {
                case "SUCCEEDED" -> "CHARGE_SUCCEEDED";
                case "DECLINED" -> "CHARGE_DECLINED";
                default -> null;
            };
            case "REFUND" -> switch (to) {
                case "SUCCEEDED" -> "REFUND_SUCCEEDED";
                case "FAILED" -> "REFUND_FAILED";
                default -> null;
            };
            case "PAYOUT" -> switch (to) {
                case "PENDING" -> "PAYOUT_ACCEPTED";
                // Refused before acceptance versus failed after it: the first never created an order, the second did.
                case "FAILED" -> "PENDING".equals(from) ? "PAYOUT_FAILED" : "PAYOUT_REJECTED";
                case "SETTLED" -> "PAYOUT_SETTLED";
                case "RETURNED" -> "PAYOUT_RETURNED";
                default -> null;
            };
            default -> throw new IllegalArgumentException("unknown attempt kind: " + kind);
        });
    }

    /** Deterministic by construction: the same transition replayed yields the same id (master §5.4, §0.3 C5). */
    public static String eventId(String attemptId, String eventType) {
        return attemptId + ":" + eventType;
    }

    /**
     * Builds the payload from attempt data only.
     *
     * <p>Refuses to build an event that would violate D01-8 rather than emitting one the consumer will reject: by
     * the time a bad payload reaches the outbox it is already committed beside a real status change, and the money
     * it describes has already moved.
     */
    public static String toJson(Attempt attempt, String eventType, Instant occurredAt) {
        var event = new LinkedHashMap<String, Object>();
        event.put("schema", SCHEMA);
        event.put("event_id", eventId(attempt.attemptId(), eventType));
        event.put("event_type", eventType);
        event.put("attempt_id", attempt.attemptId());
        event.put("order_group_id", attempt.orderGroupId());
        event.put("entity_id", attempt.entityId());
        event.put("provider", attempt.provider());
        event.put("money", Map.of("currency", attempt.currency(), "amount_minor", attempt.amountMinor()));
        event.put("occurred_at", occurredAt.toString());

        if (ORDER_SOURCED.contains(eventType)) {
            event.put("source_order_id", require(attempt.sourceOrderId(), eventType, "source_order_id"));
        }
        if (PROVIDER_REFERENCED.contains(eventType)) {
            event.put("provider_ref", require(attempt.providerRef(), eventType, "provider_ref"));
        } else if (attempt.providerRef() != null) {
            event.put("provider_ref", attempt.providerRef());
        }
        if (FAILURE_EVENTS.contains(eventType)) {
            event.put("failure_code", require(attempt.failureCode(), eventType, "failure_code"));
        }
        return JSON.writeValueAsString(event);
    }

    private static String require(String value, String eventType, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(eventType + " requires " + field + ", but the attempt has none");
        }
        return value;
    }

    /** The attempt fields an event is built from. Read back inside the transition's own transaction. */
    public record Attempt(String attemptId, String kind, String orderGroupId, String sourceOrderId, String entityId,
            String provider, String instrumentToken, String currency, long amountMinor, String status,
            String providerRef, String failureCode, long version) {
    }
}
