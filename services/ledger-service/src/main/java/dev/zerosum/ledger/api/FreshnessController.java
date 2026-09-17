package dev.zerosum.ledger.api;

import dev.zerosum.ledger.kafka.FreshnessCalculator;
import dev.zerosum.ledger.kafka.FreshnessView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How stale the ledger is (D04-5). Reader role, like every other ledger endpoint.
 *
 * <p>S05's payout run refuses above a 5 s pipeline freshness, summing this age with the outbox ages. The ages compose
 * because each stage measures a different clock source (§0.3 C12): outbox ages come from the row's {@code created_at},
 * this one from the Kafka record timestamp of the oldest unapplied record, so no stage is counted twice.
 *
 * <p>Responses are snake_case through this service's global naming strategy, so the record components stay plain.
 */
@RestController
class FreshnessController {

    private final FreshnessView freshness;

    FreshnessController(FreshnessView freshness) {
        this.freshness = freshness;
    }

    /**
     * @param status                     {@code ok} only when every number below was measured; {@code error} otherwise
     * @param totalLagRecords            records behind across all partitions
     * @param partitionLag               per-partition lag, so one stuck partition is visible rather than averaged away
     * @param oldestUnappliedAgeSeconds  age of the oldest record not yet applied
     * @param listenerPaused             whether the apply listener is currently paused
     * @param computedAt                 when this was measured
     * @param error                      why, when the status is error
     */
    record FreshnessResponse(String status, Long totalLagRecords, Map<Integer, Long> partitionLag,
            Double oldestUnappliedAgeSeconds, boolean listenerPaused, String computedAt, String error) {
    }

    @GetMapping("/v1/freshness")
    FreshnessResponse freshness(HttpServletRequest request) {
        LedgerAuthorization.requireReader(request);
        FreshnessCalculator.Freshness current = freshness.current();

        if (current.status() == FreshnessCalculator.Status.ERROR) {
            // Fail closed: the numbers are omitted entirely rather than reported as zero. A caller that cannot see a
            // figure will refuse; a caller shown a fabricated zero would proceed against balances nobody checked.
            return new FreshnessResponse("error", null, Map.of(), null, current.listenerPaused(),
                    current.computedAt().toString(), current.error());
        }
        return new FreshnessResponse("ok", current.totalLagRecords(), current.partitionLag(),
                current.oldestUnappliedAgeSeconds(), current.listenerPaused(), current.computedAt().toString(), null);
    }

    /** Kept for the OpenAPI drift check to reference; the list is the documented status vocabulary. */
    static List<String> statuses() {
        return List.of("ok", "error");
    }
}
