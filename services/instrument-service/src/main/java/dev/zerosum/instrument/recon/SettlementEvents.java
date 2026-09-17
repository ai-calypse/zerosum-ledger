// decision: D06-3 — docs/step_06_reconciliation_verifier.md#decisions-and-outputs
package dev.zerosum.instrument.recon;

import dev.zerosum.contracts.ContractSchemas;
import dev.zerosum.instrument.core.SettlementReport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code SETTLEMENT_RECEIVED} payload (D06-3, D01-8, §0.3 C6).
 *
 * <p>One event per report <em>and currency</em>. The identity is
 * {@code settlement:<provider>:<report_id>:<currency>}, which is simultaneously the event id, the order group and the
 * partition key (§0.3 C6, ADR-0007). Being derived rather than generated is what makes a re-run harmless: the mapper
 * keys the money order on the event id, so the same day reconciled twice produces one SETTLEMENT order rather than
 * two, and the provider's clearing account drains once.
 *
 * <p>The payload is assembled key by key, like {@link dev.zerosum.instrument.store.PaymentEvents}, because D01-8
 * expresses the settlement variant partly through <em>absence</em>: {@code attempt_id}, {@code entity_id} and
 * {@code money} are forbidden, and an explicit {@code null} would violate that exactly as a value would.
 */
public final class SettlementEvents {

    public static final String EVENT_TYPE = "SETTLEMENT_RECEIVED";

    static final String SCHEMA = "zerosum.payment_event.v1";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SettlementEvents() {
    }

    /** decision: §0.3 C6 — also the order group and the partition key. */
    public static String eventId(String provider, String reportId, String currency) {
        return "settlement:" + provider + ":" + reportId + ":" + currency;
    }

    /**
     * Builds and validates one currency's settlement event.
     *
     * <p>Validated against the D01-8 schema here rather than by the caller, so an invalid payload cannot reach the
     * outbox at all: an outbox row commits beside the reconciliation run, and by the time a consumer rejects it the
     * run already claims the day was settled.
     *
     * @throws IllegalStateException if the totals do not satisfy {@code net + fee = gross}, or the payload fails D01-8
     */
    public static String toJson(String provider, String reportId, SettlementReport.Totals totals, Instant occurredAt) {
        if (Math.subtractExact(totals.grossMinor(), totals.feeMinor()) != totals.netMinor()) {
            // The mapper re-checks this too (D03-6). Checked on both sides deliberately: this side must never emit it,
            // and that side must never book it, because either alone would be a single point of invention.
            throw new IllegalStateException("net + fee must equal gross before emission: " + totals.netMinor() + " + "
                    + totals.feeMinor() + " != " + totals.grossMinor());
        }

        String eventId = eventId(provider, reportId, totals.currency());
        var event = new LinkedHashMap<String, Object>();
        event.put("schema", SCHEMA);
        event.put("event_id", eventId);
        event.put("event_type", EVENT_TYPE);
        // The settlement's own identity, not a trip: a settlement belongs to no order group but its own (§0.3 C6).
        event.put("order_group_id", eventId);
        event.put("provider", provider);
        event.put("report_id", reportId);
        event.put("currency", totals.currency());
        event.put("gross_minor", totals.grossMinor());
        event.put("fee_minor", totals.feeMinor());
        event.put("net_minor", totals.netMinor());
        event.put("occurred_at", occurredAt.toString());

        String json = JSON.writeValueAsString(event);
        List<ContractSchemas.SchemaError> errors = ContractSchemas.validate(ContractSchemas.PAYMENT_EVENT_V1, json);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("the settlement event does not satisfy D01-8: " + errors);
        }
        return json;
    }
}
