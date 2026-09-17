// decision: D05-6 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The collection policy's counters (D05-6; names provisional under D05-14 until D07-1 owns the catalogue).
 *
 * <p>{@link #policyError} is the alert-worthy one. An order the policy cannot act on — a rider with no registered
 * token, a provider no adapter serves — is not an error to retry and not a reason to stop the partition: it is a
 * configuration problem that silently stops collecting money, so it is counted rather than thrown.
 */
@Component
public class PolicyMetrics {

    private static final Logger log = LoggerFactory.getLogger(PolicyMetrics.class);

    public static final String ORDERS = "instrument_policy_orders_total";
    public static final String ATTEMPTS = "instrument_policy_attempts_created_total";
    public static final String ERRORS = "instrument_policy_errors_total";
    public static final String QUARANTINED = "instrument_policy_quarantined_total";
    public static final String WITHHELD = "instrument_policy_submissions_withheld_total";

    private final MeterRegistry meters;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    PolicyMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    void orderSeen(String outcome) {
        counter(ORDERS, "outcome", outcome).increment();
    }

    void attemptCreated(String kind, boolean blocked) {
        counter(ATTEMPTS, "kind", blocked ? kind + "_BLOCKED" : kind).increment();
    }

    /** An order the policy cannot act on: logged with the entity so it can be fixed, and counted so it is noticed. */
    void policyError(String reason, String entityId, String orderId) {
        log.warn("collection policy cannot act on order {}: {} (entity {})", orderId, reason, entityId);
        counter(ERRORS, "reason", reason).increment();
    }

    void quarantined(String code) {
        counter(QUARANTINED, "code", code).increment();
    }

    /** A submission the collections kill switch held back; the attempt exists and waits in {@code CREATED}. */
    void withheld(String reason) {
        counter(WITHHELD, "reason", reason).increment();
    }

    private Counter counter(String name, String tag, String value) {
        return counters.computeIfAbsent(name + '|' + tag + '|' + value,
                key -> Counter.builder(name).tag(tag, value).register(meters));
    }
}
