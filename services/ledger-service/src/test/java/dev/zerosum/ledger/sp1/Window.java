package dev.zerosum.ledger.sp1;

import dev.zerosum.ledger.apply.ApplyBatchResult;
import dev.zerosum.ledger.apply.ApplyOutcome;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

/**
 * Accumulates one measured SP1 window. Timings come from the apply entrypoint's own timers (D02-3), which is the method
 * the report names: total transaction time and the time spent acquiring entity locks. No production metric is
 * registered here - metric names belong to S07 (D07-1).
 */
final class Window {

    final AtomicLong applied = new AtomicLong();
    final AtomicLong deadlockRetries = new AtomicLong();
    final AtomicLong lockTimeoutRetries = new AtomicLong();
    final AtomicLong connectionRetries = new AtomicLong();
    final AtomicLong retriesExhausted = new AtomicLong();

    private final ConcurrentLinkedQueue<Long> applyMicros = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> lockWaitMicros = new ConcurrentLinkedQueue<>();

    void record(ApplyBatchResult result) {
        applyMicros.add(result.total().toNanos() / 1_000);
        lockWaitMicros.add(result.lockWait().toNanos() / 1_000);
        deadlockRetries.addAndGet(result.deadlockRetries());
        lockTimeoutRetries.addAndGet(result.lockTimeoutRetries());
        connectionRetries.addAndGet(result.connectionRetries());
        applied.addAndGet(result.countOf(ApplyOutcome.Status.APPLIED));
    }

    double ordersPerSecond(int windowSeconds) {
        return windowSeconds == 0 ? 0 : (double) applied.get() / windowSeconds;
    }

    long applyP50Micros() {
        return percentile(applyMicros, 50);
    }

    long applyP95Micros() {
        return percentile(applyMicros, 95);
    }

    long lockWaitP50Micros() {
        return percentile(lockWaitMicros, 50);
    }

    long lockWaitP95Micros() {
        return percentile(lockWaitMicros, 95);
    }

    private static long percentile(ConcurrentLinkedQueue<Long> samples, int percentile) {
        long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    /** Samples recorded, so the report can say how many measurements each percentile rests on. */
    long sampleCount() {
        return applyMicros.size();
    }

    static long sum(LongStream values) {
        return values.sum();
    }
}
