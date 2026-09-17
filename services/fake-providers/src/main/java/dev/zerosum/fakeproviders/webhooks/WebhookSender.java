package dev.zerosum.fakeproviders.webhooks;

import dev.zerosum.fakeproviders.faults.Decision;
import dev.zerosum.fakeproviders.faults.FaultProfiles;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drains {@code provider_events} as signed webhooks (D05-3, S05-T03).
 *
 * <p>The log is the queue. An event becomes {@code delivered_at} only after a 2xx, so a receiver that answered 500,
 * or never answered at all, leaves the event exactly where it was and the redelivery schedule picks it up again.
 * Marking on send would lose outcomes silently, which is the one failure this design must not have.
 *
 * <p>With no receiver URL configured the sender is inert and events simply accumulate: instrument-service has no
 * webhook endpoint until S05-T11, and a sender that hammered a nonexistent URL would fill the fault log with
 * failures that say nothing about the simulator.
 */
@Component
class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient db;
    private final FaultProfiles profiles;
    private final WebhookReorder reorder;
    private final Clock clock;
    private final RestClient http;
    private final String receiverUrl;
    private final List<String> secrets;
    private final List<Duration> schedule;
    private final Duration reorderHold;
    private final int batchSize;

    WebhookSender(JdbcClient db, FaultProfiles profiles, WebhookReorder reorder, Clock clock,
            @Value("${zs.webhooks.receiver-url:}") String receiverUrl,
            // decision: D05-3, D00-8 — current,previous during rotation; the sender always signs with the first.
            @Value("${zs.webhooks.secrets:}") String secrets,
            // decision: D05-2 — the compressed redelivery schedule from master §5.9.
            @Value("${zs.webhooks.redelivery:1s,5s,30s,2m,10m}") List<Duration> schedule,
            @Value("${zs.webhooks.reorder-hold:2s}") Duration reorderHold,
            @Value("${zs.webhooks.timeout:2s}") Duration timeout,
            @Value("${zs.webhooks.batch-size:100}") int batchSize) {
        this.db = db;
        this.profiles = profiles;
        this.reorder = reorder;
        this.clock = clock;
        this.receiverUrl = receiverUrl == null ? "" : receiverUrl.strip();
        this.secrets = secrets == null || secrets.isBlank() ? List.of()
                : Arrays.stream(secrets.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        this.schedule = schedule;
        this.reorderHold = reorderHold;
        this.batchSize = batchSize;
        // Explicit timeouts: a receiver that accepts the connection and never answers would otherwise park this
        // poller forever and stop every other event being delivered.
        this.http = RestClient.builder()
                .requestFactory(dev.zerosum.fakeproviders.webhooks.HttpFactories.withTimeouts(timeout, timeout))
                .build();
    }

    @Scheduled(fixedDelayString = "${zs.webhooks.poll-interval:1s}")
    void drain() {
        if (receiverUrl.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<PendingEvent> due = due(now);
        if (due.isEmpty()) {
            return;
        }
        WebhookReorder.Plan plan = reorder.plan(due);
        plan.hold().forEach(event -> hold(event, now));
        plan.deliver().forEach(event -> deliver(event, now));
    }

    private void deliver(PendingEvent event, Instant now) {
        byte[] body = JSON.writeValueAsBytes(payload(event));

        if (profiles.fires(event.provider(), Decision.WEBHOOK_DROP, event.eventId())) {
            // The attempt never leaves the process. The event stays undelivered, so the schedule retries it: a drop
            // is a delay, not a loss, which is exactly what a receiver has to be built to survive.
            reschedule(event, now, null);
            return;
        }

        Integer status = post(body);
        if (profiles.fires(event.provider(), Decision.WEBHOOK_DUPLICATE, event.eventId())) {
            // The same event id twice. Deduplication is the receiver's job (master §5.11, TB2), and this is what
            // proves it does it.
            post(body);
        }

        if (status != null && status >= 200 && status < 300) {
            db.sql("UPDATE provider_events SET delivered_at = ?, last_delivery_status = ?, "
                            + "delivery_attempts = delivery_attempts + 1 "
                            + "WHERE event_id = ? AND delivered_at IS NULL")
                    .params(Timestamp.from(now), status, event.eventId())
                    .update();
        } else {
            reschedule(event, now, status);
        }
    }

    private Integer post(byte[] body) {
        if (secrets.isEmpty()) {
            throw new IllegalStateException("zs.webhooks.secrets must be configured to deliver signed webhooks");
        }
        try {
            return http.post().uri(receiverUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    // A fresh timestamp per attempt, so a redelivery is not rejected as stale by the 300 s tolerance.
                    .header(WebhookSigner.HEADER, WebhookSigner.header(clock.instant(), body, secrets.getFirst()))
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode().value(), false);
        } catch (RuntimeException unreachable) {
            log.debug("webhook delivery failed at the transport level", unreachable);
            return null;
        }
    }

    private void reschedule(PendingEvent event, Instant now, Integer status) {
        // Past the end of the schedule the last interval repeats: giving up would mark an outcome as undeliverable
        // when the receiver may simply be down for longer than ten minutes.
        Duration next = schedule.get(Math.min(event.attempts(), schedule.size() - 1));
        db.sql("UPDATE provider_events SET delivery_attempts = delivery_attempts + 1, next_attempt_at = ?, "
                        + "last_delivery_status = ? WHERE event_id = ?")
                .params(Timestamp.from(now.plus(next)), status, event.eventId())
                .update();
    }

    private void hold(PendingEvent event, Instant now) {
        db.sql("UPDATE provider_events SET reorder_held = true, next_attempt_at = ? WHERE event_id = ?")
                .params(Timestamp.from(now.plus(reorderHold)), event.eventId())
                .update();
    }

    private List<PendingEvent> due(Instant now) {
        return db.sql("""
                SELECT e.event_id, e.provider, e.event_type, e.provider_ref, e.client_reference, e.amount_minor,
                       e.currency, e.failure_code, e.occurred_at, e.delivery_attempts, e.reorder_held,
                       EXISTS (SELECT 1 FROM provider_events s
                               WHERE s.provider_ref = e.provider_ref AND s.delivered_at IS NULL
                                 AND s.reorder_held AND s.event_id <> e.event_id) AS sibling_held
                FROM provider_events e
                WHERE e.delivered_at IS NULL AND e.next_attempt_at <= ?
                ORDER BY e.occurred_at, e.event_id
                LIMIT ?
                """)
                .params(Timestamp.from(now), batchSize)
                .query((rs, rowNum) -> new PendingEvent(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7), rs.getString(8),
                        rs.getTimestamp(9).toInstant(), rs.getInt(10), rs.getBoolean(11), rs.getBoolean(12)))
                .list();
    }

    private static Map<String, Object> payload(PendingEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", event.eventId());
        payload.put("provider", event.provider());
        payload.put("event_type", event.eventType());
        payload.put("provider_ref", event.providerRef());
        payload.put("client_reference", event.clientReference());
        payload.put("amount_minor", event.amountMinor());
        payload.put("currency", event.currency());
        payload.put("failure_code", event.failureCode());
        payload.put("occurred_at", event.occurredAt().toString());
        return payload;
    }
}
