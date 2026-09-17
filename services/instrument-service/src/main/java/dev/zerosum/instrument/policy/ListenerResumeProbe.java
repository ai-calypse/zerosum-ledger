// decision: D04-4 — docs/step_04_kafka_pipeline.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resumes the paused policy listener once the database answers again (D04-4).
 *
 * <p>Unattended by design: the S08 faults expect the service to come back without anyone logging in. A pause that
 * needed a human would turn a thirty-second database restart into collections stopping until someone noticed.
 *
 * <p>The probe queries this service's own pool — the resource whose absence caused the pause. Probing anything else
 * would risk resuming straight back into the same failure.
 */
@Component
class ListenerResumeProbe {

    private static final Logger log = LoggerFactory.getLogger(ListenerResumeProbe.class);

    private final KafkaListenerEndpointRegistry registry;
    private final JdbcTemplate template;
    private final PauseOnFailureErrorHandler errorHandler;

    ListenerResumeProbe(KafkaListenerEndpointRegistry registry, JdbcTemplate template,
            PauseOnFailureErrorHandler errorHandler) {
        this.registry = registry;
        this.template = template;
        this.errorHandler = errorHandler;
    }

    @Scheduled(fixedDelayString = "${zs.policy.consumer.resume-probe-interval}")
    void resumeIfHealthy() {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (!container.isPauseRequested()) {
                continue;
            }
            try {
                template.queryForObject("SELECT 1", Integer.class);
            } catch (RuntimeException stillFailing) {
                // Still down. Stay paused: resuming now would fail the same record again and re-pause.
                log.debug("resume probe failed; the policy listener stays paused", stillFailing);
                return;
            }
            container.resume();
            errorHandler.markResumed();
            log.info("database is answering again; the policy listener resumed from its saved position");
        }
    }
}
