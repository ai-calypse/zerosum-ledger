package dev.zerosum.ledger.perf;

import dev.zerosum.ledger.apply.ApplyBatchResult;
import dev.zerosum.ledger.apply.ApplyOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Accumulates one measured apply window (S07-T05).
 *
 * <p>Timings come from the apply entrypoint's own {@link ApplyBatchResult}, exactly as SP1 takes them, so this study
 * and SP1 are measuring the same clock rather than two different instrumentations that happen to agree.
 *
 * <p>Duplicates and quarantines are counted, not ignored. Every submission carries a fresh order id, so a non-zero
 * count means the harness is broken — a window whose throughput is partly dedupe work is not a throughput measurement.
 */
final class PerfWindow {

    final AtomicLong applied = new AtomicLong();
    final AtomicLong duplicates = new AtomicLong();
    final AtomicLong quarantined = new AtomicLong();
    final AtomicLong batches = new AtomicLong();
    final AtomicLong deadlockRetries = new AtomicLong();
    final AtomicLong lockTimeoutRetries = new AtomicLong();
    final AtomicLong connectionRetries = new AtomicLong();
    final AtomicLong retriesExhausted = new AtomicLong();

    private final ConcurrentLinkedQueue<Long> batchMicros = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> lockWaitMicros = new ConcurrentLinkedQueue<>();

    void record(ApplyBatchResult result) {
        batchMicros.add(result.total().toNanos() / 1_000);
        lockWaitMicros.add(result.lockWait().toNanos() / 1_000);
        deadlockRetries.addAndGet(result.deadlockRetries());
        lockTimeoutRetries.addAndGet(result.lockTimeoutRetries());
        connectionRetries.addAndGet(result.connectionRetries());
        applied.addAndGet(result.countOf(ApplyOutcome.Status.APPLIED));
        duplicates.addAndGet(result.countOf(ApplyOutcome.Status.DUPLICATE));
        quarantined.addAndGet(result.countOf(ApplyOutcome.Status.QUARANTINED));
        batches.incrementAndGet();
    }

    double ordersPerSecond(int windowSeconds) {
        return windowSeconds == 0 ? 0 : (double) applied.get() / windowSeconds;
    }

    long batchP50Micros() {
        return percentile(batchMicros, 50);
    }

    long batchP95Micros() {
        return percentile(batchMicros, 95);
    }

    long lockWaitP50Micros() {
        return percentile(lockWaitMicros, 50);
    }

    long lockWaitP95Micros() {
        return percentile(lockWaitMicros, 95);
    }

    long sampleCount() {
        return batchMicros.size();
    }

    static long percentile(ConcurrentLinkedQueue<Long> samples, int percentile) {
        return percentileOf(samples.stream().mapToLong(Long::longValue).sorted().toArray(), percentile);
    }

    /** Nearest-rank percentile of an already sorted array; 0 when there is nothing to report. */
    static long percentileOf(long[] sorted, int percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    /** Median of repetitions, the variance rule the master sets for every performance result. */
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
