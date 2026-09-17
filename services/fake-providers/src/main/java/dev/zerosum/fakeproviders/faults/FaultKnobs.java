package dev.zerosum.fakeproviders.faults;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The fault-knob schema of one provider (D05-2, master §5.9 "Fault knobs").
 *
 * <p>Wire names are snake_case, like the rest of this service's API, and every knob is optional: an absent knob takes
 * its no-fault default, so {@code {"seed": 7}} is a valid profile that injects nothing. A profile is replaced whole,
 * never merged, because a merge would let a knob survive a replacement nobody meant to keep it through.
 *
 * <p><strong>Extension point (§0.3 C23).</strong> S06-T01 adds the settlement-report discrepancy knobs
 * ({@code report_missing_line_rate}, {@code report_off_by_one_rate}, {@code report_duplicate_line_rate}) additively:
 * add a component to this record, its name to {@link #FIELDS}, one line to {@link #from} and one to {@link #toMap},
 * and a value to {@link Decision}. Nothing else changes — profiles already stored stay valid because the new knobs
 * default to zero, and each decision draws from its own seeded stream, so a new knob does not shift the outcomes of
 * the existing ones.
 */
public record FaultKnobs(double latencyP50Ms, double latencyP95Ms, double http500Rate, double resetBeforeCommitRate,
        double timeoutAfterCommitRate, double webhookDuplicateRate, double webhookReorderRate, double webhookDropRate,
        double returnRate, double reportMissingLineRate, double reportOffByOneRate, double reportDuplicateLineRate,
        Long simulatedBankingDaySeconds, long maxProcessingDelayMs, long seed) {

    /** The profile a provider runs with until an operator sets one: every rate zero, so behaviour is unchanged. */
    public static final FaultKnobs NONE = new FaultKnobs(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0);

    private static final List<String> FIELDS = List.of("latency_p50_ms", "latency_p95_ms", "http_500_rate",
            "reset_before_commit_rate", "timeout_after_commit_rate", "webhook_duplicate_rate", "webhook_reorder_rate",
            "webhook_drop_rate", "return_rate", "report_missing_line_rate", "report_off_by_one_rate",
            "report_duplicate_line_rate", "simulated_banking_day_seconds", "max_processing_delay_ms", "seed");

    public FaultKnobs {
        // Validated in the record itself rather than only at the endpoint: a profile read back from the database
        // after a botched migration must fail here too, not become a silently impossible fault rate.
        rate("http_500_rate", http500Rate);
        rate("reset_before_commit_rate", resetBeforeCommitRate);
        rate("timeout_after_commit_rate", timeoutAfterCommitRate);
        rate("webhook_duplicate_rate", webhookDuplicateRate);
        rate("webhook_reorder_rate", webhookReorderRate);
        rate("webhook_drop_rate", webhookDropRate);
        rate("return_rate", returnRate);
        rate("report_missing_line_rate", reportMissingLineRate);
        rate("report_off_by_one_rate", reportOffByOneRate);
        rate("report_duplicate_line_rate", reportDuplicateLineRate);
        nonNegative("latency_p50_ms", latencyP50Ms);
        nonNegative("latency_p95_ms", latencyP95Ms);
        nonNegative("max_processing_delay_ms", maxProcessingDelayMs);
        if (simulatedBankingDaySeconds != null && simulatedBankingDaySeconds < 0) {
            throw invalid("simulated_banking_day_seconds must be zero or greater, was " + simulatedBankingDaySeconds);
        }
        if (latencyP95Ms < latencyP50Ms) {
            throw invalid("latency_p95_ms must be at least latency_p50_ms, was " + latencyP95Ms + " < " + latencyP50Ms);
        }
    }

    /**
     * Parses a knob payload, rejecting anything it does not recognise.
     *
     * @param processingDelayLimit the quiet period from ADR-0010 (D05-9). A maximum processing delay at or above it
     *                             would make the resolver's resubmission rule unsafe, so the simulator refuses to be
     *                             configured into a state whose duplicates would be blamed on the resolver.
     */
    public static FaultKnobs from(Map<String, Object> body, Duration processingDelayLimit) {
        if (body == null) {
            throw invalid("a knob profile is required");
        }
        Set<String> unknown = new TreeSet<>(body.keySet());
        FIELDS.forEach(unknown::remove);
        if (!unknown.isEmpty()) {
            // Named rather than ignored: a typo in a knob name is otherwise a chaos run that injects nothing and
            // reports success.
            throw invalid("unknown field: " + String.join(", ", unknown));
        }
        FaultKnobs knobs = new FaultKnobs(
                number(body, "latency_p50_ms", 0),
                number(body, "latency_p95_ms", 0),
                number(body, "http_500_rate", 0),
                number(body, "reset_before_commit_rate", 0),
                number(body, "timeout_after_commit_rate", 0),
                number(body, "webhook_duplicate_rate", 0),
                number(body, "webhook_reorder_rate", 0),
                number(body, "webhook_drop_rate", 0),
                number(body, "return_rate", 0),
                number(body, "report_missing_line_rate", 0),
                number(body, "report_off_by_one_rate", 0),
                number(body, "report_duplicate_line_rate", 0),
                body.containsKey("simulated_banking_day_seconds")
                        ? (long) number(body, "simulated_banking_day_seconds", 0) : null,
                (long) number(body, "max_processing_delay_ms", 0),
                (long) number(body, "seed", 0));

        if (knobs.maxProcessingDelayMs() >= processingDelayLimit.toMillis()) {
            throw invalid("max_processing_delay_ms must stay below the " + processingDelayLimit.toMillis()
                    + " ms safe-resubmit quiet period (ADR-0010), was " + knobs.maxProcessingDelayMs());
        }
        return knobs;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("latency_p50_ms", latencyP50Ms);
        map.put("latency_p95_ms", latencyP95Ms);
        map.put("http_500_rate", http500Rate);
        map.put("reset_before_commit_rate", resetBeforeCommitRate);
        map.put("timeout_after_commit_rate", timeoutAfterCommitRate);
        map.put("webhook_duplicate_rate", webhookDuplicateRate);
        map.put("webhook_reorder_rate", webhookReorderRate);
        map.put("webhook_drop_rate", webhookDropRate);
        map.put("return_rate", returnRate);
        map.put("report_missing_line_rate", reportMissingLineRate);
        map.put("report_off_by_one_rate", reportOffByOneRate);
        map.put("report_duplicate_line_rate", reportDuplicateLineRate);
        map.put("simulated_banking_day_seconds", simulatedBankingDaySeconds);
        map.put("max_processing_delay_ms", maxProcessingDelayMs);
        map.put("seed", seed);
        return map;
    }

    /** The simulated banking day: the knob when set, otherwise the deployment's configured default. */
    public Duration bankingDay(Duration configured) {
        return simulatedBankingDaySeconds == null ? configured : Duration.ofSeconds(simulatedBankingDaySeconds);
    }

    private static double number(Map<String, Object> body, String field, double fallback) {
        Object value = body.get(field);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number n)) {
            throw invalid(field + " must be a number, was " + value);
        }
        return n.doubleValue();
    }

    private static void rate(String field, double value) {
        if (!(value >= 0 && value <= 1)) {
            throw invalid(field + " must be within [0, 1], was " + value);
        }
    }

    private static void nonNegative(String field, double value) {
        if (!(value >= 0)) {
            throw invalid(field + " must be zero or greater, was " + value);
        }
    }

    private static ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
