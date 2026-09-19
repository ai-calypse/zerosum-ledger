// decision: D05-3, D05-5 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.webhooks;

import dev.zerosum.auth.ChaosGuard;
import dev.zerosum.instrument.core.ProviderEvent;
import dev.zerosum.instrument.core.ProviderStatus;
import dev.zerosum.instrument.store.AttemptStateMachines;
import dev.zerosum.instrument.store.AttemptStateMachines.Event;
import dev.zerosum.instrument.store.AttemptStateMachines.Kind;
import dev.zerosum.instrument.store.AttemptStateMachines.Outcome;
import dev.zerosum.instrument.store.AttemptStateMachines.State;
import dev.zerosum.instrument.store.AttemptTransitions;
import dev.zerosum.instrument.store.IllegalTransitions;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a provider event and applies what it means, in one transaction (S05-T11, master §5.10).
 *
 * <p><strong>Recorded before applied.</strong> The {@code provider_events} row goes in first, keyed by the provider's
 * own event id, and the transition follows in the same transaction. Both commit or neither does, so there is never a
 * status change whose cause has no record, nor a record of an event that was never acted on.
 *
 * <p><strong>Dedupe is the primary key, not a check.</strong> {@code ON CONFLICT DO NOTHING} on
 * (provider, provider event id) is what makes a webhook delivered five times — or twice at the same instant — apply
 * once. A select-then-insert would pass its own tests and duplicate under concurrency, which downstream is a second
 * money order for one movement of money.
 *
 * <p>Every outcome of the D05-5 table is honoured, and none of them drops the event:
 * <ul>
 *   <li><strong>Applied</strong> — the transition service writes the status, the history row and the payment event.</li>
 *   <li><strong>IgnoredStale</strong> — logged as {@code ignored_stale_event}; the record is kept, the status is not
 *       touched (master §5.10).</li>
 *   <li><strong>Illegal</strong> — logged and counted by {@link IllegalTransitions}. The record is kept and the
 *       response is still 2xx: an event this service can never apply would otherwise be redelivered for ever, and
 *       the row plus the counter are what make it visible instead.</li>
 *   <li><strong>AheadOfState</strong> — recorded here and resolved by lookup afterwards (§0.3 C21). The handler does
 *       no provider call itself, so it stays inside the master §5.11 two-second budget.</li>
 * </ul>
 */
@Service
public class WebhookReceiver {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiver.class);

    /** Master §6.4 naming; provisional under D05-14 until D07-1 owns the registry. */
    public static final String COUNTER = "instrument_webhooks_total";

    /** What became of one delivery. Named so the endpoint can say it, and a test can assert it. */
    public enum Disposition {
        APPLIED, DUPLICATE, IGNORED_STALE, ILLEGAL, AHEAD_OF_STATE, UNMATCHED
    }

    /**
     * @param attemptId   the attempt the event named, or null when no attempt matched
     * @param failureCode the provider's failure or return code, carried to the resolver so the catch-up transition
     *                    that needs one has it
     */
    public record Received(Disposition disposition, UUID attemptId, String failureCode) {
    }

    private record Attempt(UUID attemptId, Kind kind, State status, long version) {
    }

    private final JdbcClient db;
    private final AttemptTransitions transitions;
    private final IllegalTransitions illegal;
    private final MeterRegistry meters;
    /** decision: D08-3 — the A5 ablation: a duplicate delivery is applied again. Off outside chaos. */
    private final boolean skipDedupe;

    WebhookReceiver(JdbcClient db, AttemptTransitions transitions, IllegalTransitions illegal, MeterRegistry meters,
            ChaosGuard.Active chaos) {
        this.skipDedupe = chaos.on("A5");
        this.db = db;
        this.transitions = transitions;
        this.illegal = illegal;
        this.meters = meters;
    }

    /**
     * @param rawBody the bytes that arrived, stored as the record. The normalized event is our reading of them; the
     *                payload is the provider's own statement, and that is what evidence has to be.
     */
    @Transactional
    public Received receive(ProviderEvent event, byte[] rawBody) {
        Optional<Attempt> attempt = attemptOf(event.clientReference());

        // decision: D08-3 — A5 ablation (master §8.5): the primary key still refuses a second row, but its answer is
        // ignored and the delivery is applied as if new. Predicted harmless: the state machine and the order-level
        // idempotency key (event_id) are the layers behind it.
        if (!record(event, attempt.map(Attempt::attemptId).orElse(null), rawBody) && !skipDedupe) {
            // Already recorded, so it was already applied by whichever delivery got here first.
            return done(event, new Received(Disposition.DUPLICATE, attempt.map(Attempt::attemptId).orElse(null), null));
        }

        if (attempt.isEmpty()) {
            // Recorded with a null attempt id, which is the unmatched marker: the row stays visible to S06 and the
            // verifier. 2xx, so the provider stops redelivering something no attempt of ours can ever match.
            log.warn("webhook {} from {} names client reference {}, which matches no attempt", event.providerEventId(),
                    event.provider(), event.clientReference());
            return done(event, new Received(Disposition.UNMATCHED, null, null));
        }

        return done(event, apply(event, attempt.get()));
    }

    /**
     * Asks the D05-5 table what this event means for the attempt as it stands, and writes the answer.
     *
     * <p>The loop is the race with a sweeper or another delivery: the guarded update lets one of them win, and the
     * loser re-reads and re-evaluates the table rather than retrying a decision that was made about a status the
     * attempt has already left. Bounded, because a contended attempt that never settles is a defect to see in the
     * logs, not a thread to spin.
     */
    private Received apply(ProviderEvent event, Attempt attempt) {
        Attempt at = attempt;
        for (int attemptNumber = 0; attemptNumber < 3; attemptNumber++) {
            Outcome outcome = AttemptStateMachines.decide(at.kind(), at.status(), webhookEvent(event.status()));
            switch (outcome) {
                case Outcome.Applied applied -> {
                    var result = transitions.apply(new AttemptTransitions.Transition(at.attemptId(), at.version(),
                            at.status().name(), applied.next().name(), cause(event), event.providerRef(),
                            event.failureCode().orElse(null), null));
                    if (result instanceof AttemptTransitions.Result.LostRace) {
                        at = attemptOf(event.clientReference()).orElseThrow();
                        continue;
                    }
                    return new Received(Disposition.APPLIED, at.attemptId(), null);
                }
                case Outcome.IgnoredStale ignored -> {
                    // Master §5.10: "logged as ignored_stale_event, with no state change".
                    log.info("ignored_stale_event: webhook {} reported {} for attempt {}, already {}",
                            event.providerEventId(), event.status(), at.attemptId(), at.status());
                    return new Received(Disposition.IGNORED_STALE, at.attemptId(), null);
                }
                case Outcome.Illegal ignored -> {
                    illegal.record(at.attemptId(), at.kind(), at.status(), webhookEvent(event.status()));
                    return new Received(Disposition.ILLEGAL, at.attemptId(), null);
                }
                case Outcome.AheadOfState ahead -> {
                    // §0.3 C21: recorded here, resolved by lookup after this commits, never dropped.
                    log.info("webhook {} is ahead of attempt {} ({} claims {}); resolving by lookup",
                            event.providerEventId(), at.attemptId(), at.status(), ahead.claimed());
                    return new Received(Disposition.AHEAD_OF_STATE, at.attemptId(),
                            event.failureCode().orElse(null));
                }
            }
        }
        // Three losses in a row means something else is moving this attempt continuously. The event is recorded, so
        // nothing is lost; saying so beats looping.
        log.warn("webhook {} lost the race for attempt {} repeatedly; the event stays recorded",
                event.providerEventId(), at.attemptId());
        return new Received(Disposition.IGNORED_STALE, at.attemptId(), null);
    }

    /** @return false when the event was already recorded, which is the whole of duplicate handling */
    private boolean record(ProviderEvent event, UUID attemptId, byte[] rawBody) {
        return db.sql("""
                INSERT INTO provider_events (provider, provider_event_id, attempt_id, payload)
                VALUES (:provider, :eventId, :attemptId, :payload::jsonb)
                ON CONFLICT (provider, provider_event_id) DO NOTHING
                """)
                .param("provider", event.provider().id())
                .param("eventId", event.providerEventId())
                .param("attemptId", attemptId)
                .param("payload", new String(rawBody, StandardCharsets.UTF_8))
                .update() == 1;
    }

    /** The client reference is the attempt id as we sent it; anything else matches nothing. */
    private Optional<Attempt> attemptOf(String clientReference) {
        UUID attemptId;
        try {
            attemptId = UUID.fromString(clientReference);
        } catch (IllegalArgumentException notOurs) {
            return Optional.empty();
        }
        return db.sql("SELECT attempt_id, kind, status, version FROM payment_attempts WHERE attempt_id = :id")
                .param("id", attemptId)
                .query((rs, rowNum) -> new Attempt(rs.getObject(1, UUID.class), Kind.of(rs.getString(2)),
                        State.valueOf(rs.getString(3)), rs.getLong(4)))
                .optional();
    }

    private Received done(ProviderEvent event, Received received) {
        Counter.builder(COUNTER)
                .description("Provider webhooks received, by what the D05-5 table said to do with them")
                .tag("provider", event.provider().id())
                .tag("disposition", received.disposition().name())
                .register(meters)
                .increment();
        return received;
    }

    /** The provider's own reference and event id, never the payload or the signature header (D00-8 redaction). */
    private static String cause(ProviderEvent event) {
        return "webhook " + event.providerEventId() + " reported " + event.status();
    }

    private static Event webhookEvent(ProviderStatus status) {
        return switch (status) {
            case SUCCEEDED -> Event.WEBHOOK_SUCCEEDED;
            case PENDING -> Event.WEBHOOK_PENDING;
            case DECLINED -> Event.WEBHOOK_DECLINED;
            case FAILED -> Event.WEBHOOK_FAILED;
            case SETTLED -> Event.WEBHOOK_SETTLED;
            case RETURNED -> Event.WEBHOOK_RETURNED;
        };
    }
}
