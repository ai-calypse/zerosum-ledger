// decision: D05-13 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.api;

import dev.zerosum.auth.Role;
import dev.zerosum.instrument.store.AttemptQueries;
import dev.zerosum.instrument.store.AttemptTransitions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator endpoints for one attempt (master §5.6, D05-13): read it with its history, or cancel it.
 *
 * <p>These are the first authenticated endpoints in this service, so S05-T08 wires {@code libs/auth} here (§0.3 C9):
 * reading is reader-level, cancelling is admin-level, because a cancellation changes money state.
 *
 * <p>Responses are snake_case through this service's global naming strategy, so the record components stay plain.
 */
@RestController
class AttemptController {

    private final AttemptQueries attempts;
    private final AttemptTransitions transitions;

    AttemptController(AttemptQueries attempts, AttemptTransitions transitions) {
        this.attempts = attempts;
        this.transitions = transitions;
    }

    /** An attempt and every status it has been through, in sequence order. */
    record AttemptDetail(AttemptQueries.Attempt attempt, List<AttemptQueries.Transition> transitions) {
    }

    /** The newest attempts first, optionally of one kind (S09 dashboard). Reader role. */
    @GetMapping("/v1/payment-attempts")
    List<AttemptQueries.Attempt> recent(HttpServletRequest request,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "limit", required = false) String limit) {
        InstrumentAuthorization.require(request, Role.READER);
        if (kind != null && !KINDS.contains(kind)) {
            throw InstrumentApiException.invalidQuery("kind must be one of " + KINDS + ", was " + kind);
        }
        return attempts.recent(kind, InstrumentApiException.requireLimit(limit, 50));
    }

    record AttemptsSummary(List<AttemptQueries.StatusTotal> totals) {
    }

    /** How many attempts are in each status, per kind, provider and currency (S09 dashboard). Reader role. */
    @GetMapping("/v1/payment-attempts/summary")
    AttemptsSummary summary(HttpServletRequest request) {
        InstrumentAuthorization.require(request, Role.READER);
        return new AttemptsSummary(attempts.statusTotals());
    }

    private static final java.util.Set<String> KINDS = new java.util.TreeSet<>(List.of("CHARGE", "REFUND", "PAYOUT"));

    @GetMapping("/v1/payment-attempts/{attemptId}")
    AttemptDetail read(HttpServletRequest request, @PathVariable String attemptId) {
        InstrumentAuthorization.require(request, Role.READER);
        return detail(parse(attemptId));
    }

    /**
     * Cancels an attempt that has not been submitted (master §5.11: "Attempt cancellation is allowed only from
     * {@code CREATED}").
     *
     * <p>The status check is not the protection — the transition guard is. Between reading {@code CREATED} here and
     * writing {@code CANCELLED}, a policy thread may submit the attempt; the guarded update lets exactly one of them
     * win, and the loser reports the conflict with the status the attempt is actually in.
     */
    @PostMapping("/v1/payment-attempts/{attemptId}/cancel")
    AttemptDetail cancel(HttpServletRequest request, @PathVariable String attemptId) {
        InstrumentAuthorization.require(request, Role.ADMIN);
        UUID id = parse(attemptId);
        AttemptQueries.Attempt attempt = attempts.find(id).orElseThrow(() -> InstrumentApiException.attemptNotFound(id));
        if (!"CREATED".equals(attempt.status())) {
            throw InstrumentApiException.notCancellable(attempt.status());
        }

        var result = transitions.apply(new AttemptTransitions.Transition(id, attempt.version(), "CREATED", "CANCELLED",
                "cancelled through the admin endpoint", null, null, null));
        if (result instanceof AttemptTransitions.Result.LostRace) {
            String now = attempts.find(id).map(AttemptQueries.Attempt::status).orElseThrow(
                    () -> InstrumentApiException.attemptNotFound(id));
            throw InstrumentApiException.notCancellable(now);
        }
        return detail(id);
    }

    private AttemptDetail detail(UUID attemptId) {
        AttemptQueries.Attempt attempt = attempts.find(attemptId)
                .orElseThrow(() -> InstrumentApiException.attemptNotFound(attemptId));
        return new AttemptDetail(attempt, attempts.history(attemptId));
    }

    private static UUID parse(String attemptId) {
        try {
            return UUID.fromString(attemptId);
        } catch (IllegalArgumentException malformed) {
            // A malformed id is a client mistake, not a missing attempt, and saying so saves the caller from
            // hunting for a row that could never have existed.
            throw InstrumentApiException.invalidAttemptId(attemptId);
        }
    }
}
