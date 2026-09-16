package dev.zerosum.ledger.sp1;

import java.util.ArrayList;
import java.util.List;

/**
 * One measured SP1 window: the throughput and timing it produced, the retries it needed and the invariant check that
 * followed it (D02-10).
 *
 * <p>A run is a data point only when it is {@link #valid()}: any I2-I5 violation, or any batch whose retries were
 * exhausted, means the run is investigated rather than reported.
 */
record Sp1Run(int writers, int repetition, int windowSeconds, long appliedOrders, double ordersPerSecond,
        long applyP50Micros, long applyP95Micros, long lockWaitP50Micros, long lockWaitP95Micros,
        long deadlockRetries, long lockTimeoutRetries, long connectionRetries, long retriesExhausted,
        long i2Violations, long i3Violations, long i4Violations, long i5Violations) {

    boolean valid() {
        return retriesExhausted == 0 && i2Violations == 0 && i3Violations == 0 && i4Violations == 0 && i5Violations == 0;
    }

    String summary() {
        return String.format("writers=%d rep=%d applied=%d ordersPerSecond=%.1f applyP50us=%d applyP95us=%d "
                        + "lockWaitP50us=%d lockWaitP95us=%d deadlock=%d lockTimeout=%d connection=%d exhausted=%d "
                        + "i2=%d i3=%d i4=%d i5=%d valid=%s",
                writers, repetition, appliedOrders, ordersPerSecond, applyP50Micros, applyP95Micros,
                lockWaitP50Micros, lockWaitP95Micros, deadlockRetries, lockTimeoutRetries, connectionRetries,
                retriesExhausted, i2Violations, i3Violations, i4Violations, i5Violations, valid());
    }

    String toJson() {
        return String.format("{\"writers\": %d, \"repetition\": %d, \"window_seconds\": %d, \"applied_orders\": %d, "
                        + "\"orders_per_second\": %.2f, \"apply_p50_micros\": %d, \"apply_p95_micros\": %d, "
                        + "\"lock_wait_p50_micros\": %d, \"lock_wait_p95_micros\": %d, \"deadlock_retries\": %d, "
                        + "\"lock_timeout_retries\": %d, \"connection_retries\": %d, \"retries_exhausted\": %d, "
                        + "\"i2_violations\": %d, \"i3_violations\": %d, \"i4_violations\": %d, \"i5_violations\": %d, "
                        + "\"valid\": %s}",
                writers, repetition, windowSeconds, appliedOrders, ordersPerSecond, applyP50Micros, applyP95Micros,
                lockWaitP50Micros, lockWaitP95Micros, deadlockRetries, lockTimeoutRetries, connectionRetries,
                retriesExhausted, i2Violations, i3Violations, i4Violations, i5Violations, valid());
    }

    /** Median of repetitions, which is how the master says to report variance alongside the min-max range. */
    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int size = sorted.size();
        if (size == 0) {
            return 0;
        }
        return size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }
}
