package dev.zerosum.ledger.perf;

/**
 * One measured apply window: what it produced, what it had to retry, and the invariant check that followed it
 * (S07-T05).
 *
 * <p>A run is a data point only when it is {@link #valid()}, which follows SP1's rule exactly: any I2-I5 violation, any
 * batch whose retries were exhausted, any duplicate and any quarantined record means the window is investigated rather
 * than reported. Duplicates and quarantines are impossible in a correct run here — every submission carries a fresh
 * order id and a generated order is always valid — so a non-zero count is a broken harness, not a slow one.
 *
 * <p>{@code i5Violations == -1} means the whole-ledger chain sweep for this window's block has not run yet. It is
 * written that way into the raw data after every window, and replaced when the block ends, so a crash mid-block leaves
 * honest data rather than a window that claims an unperformed check.
 */
record PerfRun(String measurement, int writers, int batchSize, int entityCount, int repetition, int windowSeconds,
        long appliedOrders, double ordersPerSecond, long batchP50Micros, long batchP95Micros, long lockWaitP50Micros,
        long lockWaitP95Micros, long batches, long duplicates, long quarantined, long deadlockRetries,
        long lockTimeoutRetries, long connectionRetries, long retriesExhausted, long i2Violations, long i3Violations,
        long i4Violations, long i5Violations) {

    boolean valid() {
        return retriesExhausted == 0 && duplicates == 0 && quarantined == 0
                && i2Violations == 0 && i3Violations == 0 && i4Violations == 0 && i5Violations == 0;
    }

    /** The same run with the block's whole-ledger I5 result filled in. */
    PerfRun withI5(long violations) {
        return new PerfRun(measurement, writers, batchSize, entityCount, repetition, windowSeconds, appliedOrders,
                ordersPerSecond, batchP50Micros, batchP95Micros, lockWaitP50Micros, lockWaitP95Micros, batches,
                duplicates, quarantined, deadlockRetries, lockTimeoutRetries, connectionRetries, retriesExhausted,
                i2Violations, i3Violations, i4Violations, violations);
    }

    /** Mean apply cost per order in this window, which is the batch duration divided over the orders it carried. */
    double microsPerOrderAtP50() {
        return batchSize == 0 ? 0 : (double) batchP50Micros / batchSize;
    }

    String summary() {
        return String.format("%s writers=%d batch=%d entities=%d rep=%d applied=%d ordersPerSecond=%.1f "
                        + "batchP50us=%d batchP95us=%d lockWaitP50us=%d lockWaitP95us=%d batches=%d dup=%d quar=%d "
                        + "deadlock=%d lockTimeout=%d connection=%d exhausted=%d i2=%d i3=%d i4=%d i5=%d valid=%s",
                measurement, writers, batchSize, entityCount, repetition, appliedOrders, ordersPerSecond,
                batchP50Micros, batchP95Micros, lockWaitP50Micros, lockWaitP95Micros, batches, duplicates, quarantined,
                deadlockRetries, lockTimeoutRetries, connectionRetries, retriesExhausted, i2Violations, i3Violations,
                i4Violations, i5Violations, valid());
    }

    String toJson() {
        return String.format("{\"measurement\": \"%s\", \"writers\": %d, \"batch_size\": %d, \"entity_count\": %d, "
                        + "\"repetition\": %d, \"window_seconds\": %d, \"applied_orders\": %d, "
                        + "\"orders_per_second\": %.2f, \"batch_p50_micros\": %d, \"batch_p95_micros\": %d, "
                        + "\"micros_per_order_at_p50\": %.2f, \"lock_wait_p50_micros\": %d, "
                        + "\"lock_wait_p95_micros\": %d, \"batches\": %d, \"duplicates\": %d, \"quarantined\": %d, "
                        + "\"deadlock_retries\": %d, \"lock_timeout_retries\": %d, \"connection_retries\": %d, "
                        + "\"retries_exhausted\": %d, \"i2_violations\": %d, \"i3_violations\": %d, "
                        + "\"i4_violations\": %d, \"i5_violations\": %d, \"valid\": %s}",
                measurement, writers, batchSize, entityCount, repetition, windowSeconds, appliedOrders, ordersPerSecond,
                batchP50Micros, batchP95Micros, microsPerOrderAtP50(), lockWaitP50Micros, lockWaitP95Micros, batches,
                duplicates, quarantined, deadlockRetries, lockTimeoutRetries, connectionRetries, retriesExhausted,
                i2Violations, i3Violations, i4Violations, i5Violations, valid());
    }
}
