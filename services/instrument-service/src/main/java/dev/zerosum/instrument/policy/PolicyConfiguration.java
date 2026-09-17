// decision: D05-6 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the collection policy's consumer-side machinery (D05-6, D04-4).
 *
 * <p>{@code @EnableScheduling} is required for {@link ListenerResumeProbe}: without it the annotation is inert and a
 * paused listener stays paused forever, which is worse than not pausing at all.
 */
@Configuration
@EnableScheduling
class PolicyConfiguration {

    /**
     * The bounded executor submissions run on.
     *
     * <p>Bounded in both directions on purpose. The thread count caps how many provider calls can be outstanding, and
     * the queue caps how far behind the policy may fall before it stops accepting work — at which point attempts stay
     * in {@code CREATED} and the S05-T12 sweeper submits them. An unbounded queue would instead absorb an outage
     * silently and then replay hours of charges at a provider that had just come back.
     *
     * <p>{@code AbortPolicy} rather than {@code CallerRunsPolicy}: caller-runs would execute the provider call on the
     * Kafka listener thread, which is exactly the blocking this executor exists to prevent.
     */
    @Bean(destroyMethod = "close")
    ExecutorService policySubmissions(@Value("${zs.policy.submit-threads}") int threads,
            @Value("${zs.policy.submit-queue}") int queueSize) {
        var executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), runnable -> {
                    var thread = new Thread(runnable, "policy-submit");
                    // Daemon: shutdown is driven by close() below within the graceful-shutdown budget, and a
                    // non-daemon thread stuck on a provider would outlive the JVM's own exit.
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    @Bean
    PauseOnFailureErrorHandler policyPauseOnFailureErrorHandler(MeterRegistry meters) {
        return new PauseOnFailureErrorHandler(meters);
    }
}
