package dev.zerosum.ledger.apply;

import java.time.Duration;
import java.util.List;

/**
 * The result of one apply call (D02-3): one outcome per input record, plus the timings and retry counts SP1 (D02-10)
 * and the stress harness (D02-11) record. No production metrics are registered here; metric names belong to S07 (D07-1).
 *
 * @param outcomes           one per input record, in input order
 * @param total              wall time of the whole call, including retries
 * @param lockWait           time spent acquiring entity locks, summed over attempts
 * @param attempts           transaction attempts, including the first
 * @param deadlockRetries    retries caused by SQLSTATE 40P01
 * @param lockTimeoutRetries retries caused by SQLSTATE 55P03
 * @param connectionRetries  retries caused by SQLSTATE class 08
 */
public record ApplyBatchResult(List<ApplyOutcome> outcomes, Duration total, Duration lockWait, int attempts,
        int deadlockRetries, int lockTimeoutRetries, int connectionRetries) {

    public long countOf(ApplyOutcome.Status status) {
        return outcomes.stream().filter(o -> o.status() == status).count();
    }

    public int totalRetries() {
        return deadlockRetries + lockTimeoutRetries + connectionRetries;
    }
}
