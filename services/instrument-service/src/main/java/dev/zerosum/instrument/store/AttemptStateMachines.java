// decision: D05-5 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.store;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The charge, refund and payout state machines as immutable data (D05-5, master §5.10).
 *
 * <p>Every status change in this service is decided here and nowhere else. {@link AttemptTransitions} refuses any
 * move this file does not draw, so an attempt cannot reach a status by a path nobody wrote down.
 *
 * <p>Three rules from master §5.10 are encoded literally, and the wording matters:
 *
 * <ul>
 *   <li><strong>Non-advancing events are ignored.</strong> "For example, {@code payout.settled} arriving after
 *       {@code RETURNED} is logged as {@code ignored_stale_event}, with no state change." Encoded as
 *       {@link Outcome.IgnoredStale}: the event claims a status the attempt is already at or past.</li>
 *   <li><strong>Events that arrive ahead of the current state (v1.2, §0.3 C21).</strong> "For example,
 *       {@code payout.returned} while the attempt is still {@code PENDING}: record the event, then resolve the
 *       attempt's true state with a provider lookup and apply the intermediate transitions in order. Never drop such
 *       an event." Encoded as {@link Outcome.AheadOfState} plus {@link #resolutionPath}, which gives the intermediate
 *       statuses in order so every payment event along the way is emitted exactly once — a payout that returns while
 *       we still think it is pending must emit {@code PAYOUT_SETTLED} and then {@code PAYOUT_RETURNED}, never one of
 *       them and never either twice.</li>
 *   <li><strong>An event with no row for the current state is illegal:</strong> rejected, logged with attempt id,
 *       state and event, and counted ({@link IllegalTransitions}).</li>
 * </ul>
 *
 * <p>The 891 (kind, state, event) decisions are derived rather than listed, and every input to the derivation is
 * itself explicit data: each machine's <em>progression</em> (how far along each status is, straight from the diagram),
 * its <em>arrows</em>, its <em>aliases</em> — "a refund uses {@code FAILED} in place of {@code DECLINED}" — and, on
 * each {@link Event}, the status it claims the attempt has reached. Listing 891 rows by hand would not be more
 * honest; it would be 891 chances to mistype one and only find out in production.
 *
 * <p>A new state or event cannot slip in undecided: a state no machine places and an event that claims nothing
 * without being named in {@link #NON_ADVANCING_EVENTS} both fail at class initialization, and {@code
 * TransitionTableTest} holds its own transcription of the master diagrams, so it fails too.
 *
 * <p><strong>Commands are never ahead of state.</strong> A provider observation that outruns us can be caught up by
 * asking the provider; a command we issued (submit, cancel, the review cut-off) cannot — there is nothing to look up,
 * so an unreachable command is illegal. That is what makes "cancel while {@code SUBMITTING}" a refusal (the
 * {@code not_cancellable} conflict) rather than a lookup.
 */
public final class AttemptStateMachines {

    /** Which machine an attempt runs on; the {@code kind} column of {@code payment_attempts}. */
    public enum Kind {
        CHARGE, REFUND, PAYOUT;

        public static Kind of(String kind) {
            return valueOf(kind);
        }
    }

    /** The statuses in the {@code payment_attempts} status check constraint (D05-4). */
    public enum State {
        CREATED, SUBMITTING, PENDING, UNKNOWN, SUCCEEDED, DECLINED, FAILED, SETTLED, RETURNED, CANCELLED, NEEDS_REVIEW
    }

    /** Where an event came from. See the class note: only an observation can be ahead of our state. */
    private enum Origin {
        /** Something we decided to do. */
        COMMAND,
        /** Something the provider told us, through a submit result, a retry, a lookup or a webhook. */
        OBSERVATION
    }

    /**
     * Everything that can happen to an attempt (S05-T08: submit, each submit result, sweeper timeout, retry result,
     * lookup found / not-found / unavailable, quiet period elapsed, each webhook status, cancel, review cut-off).
     *
     * <p>Each constant declares the status it claims the attempt has reached, or {@code null} when it claims none.
     * A machine may rename a claimed status through its aliases, so adapters keep one normalized vocabulary
     * ({@code ProviderStatus}) and each machine reads it in its own terms.
     */
    public enum Event {
        /** Move a created attempt into submission, then call the provider. */
        SUBMIT(State.SUBMITTING, Origin.COMMAND),
        SUBMIT_SUCCEEDED(State.SUCCEEDED, Origin.OBSERVATION),
        SUBMIT_PENDING(State.PENDING, Origin.OBSERVATION),
        SUBMIT_DECLINED(State.DECLINED, Origin.OBSERVATION),
        SUBMIT_UNKNOWN(State.UNKNOWN, Origin.OBSERVATION),
        /** The sweeper found an attempt in submission past the timeout threshold (master §5.11). */
        SWEEPER_TIMEOUT(State.UNKNOWN, Origin.COMMAND),
        RETRY_SUCCEEDED(State.SUCCEEDED, Origin.OBSERVATION),
        RETRY_PENDING(State.PENDING, Origin.OBSERVATION),
        RETRY_DECLINED(State.DECLINED, Origin.OBSERVATION),
        RETRY_UNKNOWN(State.UNKNOWN, Origin.OBSERVATION),
        LOOKUP_FOUND_SUCCEEDED(State.SUCCEEDED, Origin.OBSERVATION),
        LOOKUP_FOUND_PENDING(State.PENDING, Origin.OBSERVATION),
        LOOKUP_FOUND_DECLINED(State.DECLINED, Origin.OBSERVATION),
        LOOKUP_FOUND_FAILED(State.FAILED, Origin.OBSERVATION),
        LOOKUP_FOUND_SETTLED(State.SETTLED, Origin.OBSERVATION),
        LOOKUP_FOUND_RETURNED(State.RETURNED, Origin.OBSERVATION),
        /** The provider has no record of the attempt. On its own it moves nothing; see {@link #QUIET_PERIOD_ELAPSED}. */
        LOOKUP_NOT_FOUND(null, Origin.OBSERVATION),
        /** The lookup itself failed. The true state is still unknown, so nothing may move on it. */
        LOOKUP_UNAVAILABLE(null, Origin.OBSERVATION),
        /**
         * Both conditions of the master's resubmission rule hold: at least {@code safeResubmitQuietPeriod} has passed
         * since submission <em>and</em> a lookup returned {@code NotFound}. The resolver raises this only when both
         * are true — folding them into one event is what keeps "the quiet period passed" from ever resubmitting a
         * payout the bank is still holding (ADR-0010).
         */
        QUIET_PERIOD_ELAPSED(State.CREATED, Origin.COMMAND),
        WEBHOOK_SUCCEEDED(State.SUCCEEDED, Origin.OBSERVATION),
        WEBHOOK_PENDING(State.PENDING, Origin.OBSERVATION),
        WEBHOOK_DECLINED(State.DECLINED, Origin.OBSERVATION),
        WEBHOOK_FAILED(State.FAILED, Origin.OBSERVATION),
        WEBHOOK_SETTLED(State.SETTLED, Origin.OBSERVATION),
        WEBHOOK_RETURNED(State.RETURNED, Origin.OBSERVATION),
        /** Operator or policy cancellation. Allowed only from {@code CREATED} (master §5.11). */
        CANCEL(State.CANCELLED, Origin.COMMAND),
        /** Unresolved after 24 h (master §5.10), which raises an alert. */
        REVIEW_CUTOFF(State.NEEDS_REVIEW, Origin.COMMAND);

        private final State claims;
        private final Origin origin;

        Event(State claims, Origin origin) {
            this.claims = claims;
            this.origin = origin;
        }

        /** The status this event asserts the attempt has reached, before a machine's aliases are applied. */
        public Optional<State> claims() {
            return Optional.ofNullable(claims);
        }
    }

    /** What the table says about one (state, event) pair. */
    public sealed interface Outcome {

        /** The transition is allowed. {@code paymentEvent} is the D05-5 event type it emits, if any. */
        record Applied(State next, Optional<String> paymentEvent) implements Outcome {
        }

        /** Non-advancing: recorded and logged, no state change (master §5.10 {@code ignored_stale_event}). */
        record IgnoredStale() implements Outcome {
        }

        /** No row for this state: rejected, logged with attempt id, state and event, and counted. */
        record Illegal() implements Outcome {
        }

        /**
         * §0.3 C21. The event is recorded, the true state is resolved by provider lookup, and the intermediate
         * transitions are applied in order ({@link #resolutionPath}). It is never dropped and never applied blindly:
         * {@code claimed} is what the provider's message asserts, and the lookup is what decides.
         */
        record AheadOfState(State claimed) implements Outcome {
        }

        Outcome IGNORED_STALE = new IgnoredStale();
        Outcome ILLEGAL = new Illegal();
    }

    /**
     * Events that claim no status, listed rather than defaulted: a new event is refused at class initialization
     * unless it either claims a status or is named here with a reason. "The provider has no record of it" and "I
     * could not ask" both leave the attempt exactly where it is; acting on either would move money on an absence of
     * evidence, so the resolver decides what to do next.
     */
    private static final Set<Event> NON_ADVANCING_EVENTS =
            EnumSet.of(Event.LOOKUP_NOT_FOUND, Event.LOOKUP_UNAVAILABLE);

    private static final Map<Kind, Machine> MACHINES = new EnumMap<>(Map.of(
            Kind.CHARGE, chargeMachine(Kind.CHARGE, State.DECLINED),
            // "A refund uses FAILED in place of DECLINED" (master §5.10). One alias rather than a second table:
            // two copies of the same machine drift the first time only one of them is corrected.
            Kind.REFUND, chargeMachine(Kind.REFUND, State.FAILED),
            Kind.PAYOUT, payoutMachine()));

    static {
        for (Event event : Event.values()) {
            if (event.claims == null && !NON_ADVANCING_EVENTS.contains(event)) {
                throw new IllegalStateException(event + " claims no status; say which status it claims, or name it "
                        + "in NON_ADVANCING_EVENTS with the reason it advances nothing");
            }
        }
        Set<State> unplaced = EnumSet.allOf(State.class);
        MACHINES.values().forEach(machine -> unplaced.removeAll(machine.states()));
        if (!unplaced.isEmpty()) {
            throw new IllegalStateException("every state must sit on some machine's progression; unplaced: " + unplaced);
        }
    }

    private AttemptStateMachines() {
    }

    /**
     * Charges and refunds (master §5.10, first diagram). {@code CANCELLED} is not drawn there: it comes from the
     * cross-cutting rule that an attempt may be cancelled from {@code CREATED} (master §5.11), which S05-T09 needs
     * for a refund blocked on a capture that was then declined.
     *
     * @param failure the status this machine calls a refusal — {@code DECLINED} for a charge, {@code FAILED} for a
     *                refund, which is also what every declined-claiming event is aliased onto
     */
    private static Machine chargeMachine(Kind kind, State failure) {
        return new Machine(kind,
                List.of(Set.of(State.CREATED), Set.of(State.SUBMITTING), Set.of(State.UNKNOWN),
                        Set.of(State.SUCCEEDED, failure, State.NEEDS_REVIEW, State.CANCELLED)),
                failure == State.DECLINED ? Map.of() : Map.of(State.DECLINED, failure),
                List.of(
                        arc(State.CREATED, State.SUBMITTING),
                        arc(State.CREATED, State.CANCELLED),
                        arc(State.SUBMITTING, State.SUCCEEDED),
                        arc(State.SUBMITTING, failure),
                        arc(State.SUBMITTING, State.UNKNOWN),
                        // "UNKNOWN --> SUCCEEDED: idempotent retry, lookup, or webhook" — whichever arrives first
                        // resolves it.
                        arc(State.UNKNOWN, State.SUCCEEDED),
                        arc(State.UNKNOWN, failure),
                        arc(State.UNKNOWN, State.NEEDS_REVIEW)));
    }

    /** Payouts (master §5.10, second diagram). */
    private static Machine payoutMachine() {
        return new Machine(Kind.PAYOUT,
                List.of(Set.of(State.CREATED), Set.of(State.SUBMITTING), Set.of(State.UNKNOWN), Set.of(State.PENDING),
                        Set.of(State.SETTLED, State.FAILED, State.NEEDS_REVIEW, State.CANCELLED),
                        Set.of(State.RETURNED)),
                // A bank that refuses a payout is recorded as FAILED; there is no separate DECLINED status here.
                Map.of(State.DECLINED, State.FAILED),
                List.of(
                        arc(State.CREATED, State.SUBMITTING),
                        arc(State.CREATED, State.CANCELLED),
                        arc(State.SUBMITTING, State.PENDING),
                        // Refused before acceptance. D05-5 emits PAYOUT_REJECTED here, which creates no order.
                        arc(State.SUBMITTING, State.FAILED),
                        arc(State.SUBMITTING, State.UNKNOWN),
                        arc(State.UNKNOWN, State.PENDING),
                        // The only backward arrow in any machine: ADR-0010's two-condition resubmission path. There
                        // is deliberately no UNKNOWN -> FAILED arrow, so a payout can never reach FAILED without
                        // passing through PENDING — which is what stops a failure from emitting PAYOUT_FAILED for a
                        // payout the ledger never saw accepted.
                        arc(State.UNKNOWN, State.CREATED),
                        arc(State.UNKNOWN, State.NEEDS_REVIEW),
                        arc(State.PENDING, State.SETTLED),
                        // Failed after acceptance. D05-5 emits PAYOUT_FAILED here: by now an order exists to reverse.
                        arc(State.PENDING, State.FAILED),
                        arc(State.SETTLED, State.RETURNED)));
    }

    /** One arrow of a master §5.10 diagram, as {@code [from, to]}. */
    private static List<State> arc(State from, State to) {
        return List.of(from, to);
    }

    /**
     * The table decision for one (state, event) pair. Total: every pair of every machine has an answer, and an
     * unrecognised combination is {@link Outcome#ILLEGAL} rather than an exception, because this is consulted on the
     * webhook path, where an unexpected message must be refused and counted rather than turned into a 500.
     */
    public static Outcome decide(Kind kind, State current, Event event) {
        Machine machine = MACHINES.get(kind);
        if (!machine.states().contains(current)) {
            return Outcome.ILLEGAL;
        }
        State claimed = machine.claimOf(event);
        if (claimed == null) {
            return Outcome.IGNORED_STALE;
        }
        if (!machine.states().contains(claimed)) {
            // A status this machine does not have: a card charge reported as `settled`, say. That is a provider or
            // adapter contract violation, and it is worth an alert rather than a shrug.
            return Outcome.ILLEGAL;
        }
        if (machine.allows(current, claimed)) {
            return new Outcome.Applied(claimed, paymentEvent(kind, current, claimed));
        }
        if (machine.rank(claimed) <= machine.rank(current)) {
            return Outcome.IGNORED_STALE;
        }
        return event.origin == Origin.OBSERVATION ? new Outcome.AheadOfState(claimed) : Outcome.ILLEGAL;
    }

    /** Whether this machine draws an arrow from one status to another. */
    public static boolean allows(Kind kind, State from, State to) {
        return MACHINES.get(kind).allows(from, to);
    }

    /**
     * The statuses to move through, in order, to catch up from {@code from} to a state resolved by lookup — the
     * "apply the intermediate transitions in order" half of §0.3 C21. Excludes {@code from}, includes {@code to}.
     *
     * <p>Only forward arrows are walked, so a resolution can never take the resubmission arrow back to
     * {@code CREATED} and loop. Empty when there is no forward path, which the caller must treat as unresolvable
     * rather than as "nothing to do".
     */
    public static List<State> resolutionPath(Kind kind, State from, State to) {
        Machine machine = MACHINES.get(kind);
        if (from == to || !machine.states().contains(from) || !machine.states().contains(to)) {
            return List.of();
        }
        Map<State, State> cameFrom = new EnumMap<>(State.class);
        Deque<State> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            State at = queue.removeFirst();
            if (at == to) {
                return trace(cameFrom, from, to);
            }
            for (State next : machine.forwardTargets(at)) {
                if (next != from && !cameFrom.containsKey(next)) {
                    cameFrom.put(next, at);
                    queue.addLast(next);
                }
            }
        }
        return List.of();
    }

    /** The D05-5 payment event a transition emits, if any. */
    public static Optional<String> paymentEvent(Kind kind, State from, State to) {
        return PaymentEvents.eventTypeFor(kind.name(), from.name(), to.name());
    }

    private static List<State> trace(Map<State, State> cameFrom, State from, State to) {
        List<State> path = new ArrayList<>();
        for (State at = to; at != from; at = cameFrom.get(at)) {
            path.add(at);
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }

    /** One machine: how far along each status is, what a claimed status is called here, and the diagram's arrows. */
    private static final class Machine {

        private final Map<State, Integer> ranks = new EnumMap<>(State.class);
        private final Map<State, State> claimAliases;
        private final Map<State, Set<State>> arcs = new EnumMap<>(State.class);

        /**
         * @param progression the diagram's ordering, in rank groups: statuses in one group are equally far along, so
         *                    an event claiming one of them while the attempt sits at another is non-advancing rather
         *                    than a contradiction to act on
         */
        Machine(Kind kind, List<Set<State>> progression, Map<State, State> claimAliases, List<List<State>> arrows) {
            this.claimAliases = claimAliases;
            for (int rank = 0; rank < progression.size(); rank++) {
                for (State state : progression.get(rank)) {
                    if (this.ranks.put(state, rank) != null) {
                        throw new IllegalStateException(kind + " places " + state + " at two ranks");
                    }
                }
            }
            for (List<State> arrow : arrows) {
                State from = arrow.get(0);
                State to = arrow.get(1);
                requirePlaced(kind, from);
                requirePlaced(kind, to);
                arcs.computeIfAbsent(from, state -> EnumSet.noneOf(State.class)).add(to);
            }
        }

        private void requirePlaced(Kind kind, State state) {
            if (!ranks.containsKey(state)) {
                throw new IllegalStateException(
                        kind + " has an arrow through " + state + ", which is not on its progression");
            }
        }

        Set<State> states() {
            return ranks.keySet();
        }

        int rank(State state) {
            return ranks.get(state);
        }

        State claimOf(Event event) {
            State claimed = event.claims;
            return claimed == null ? null : claimAliases.getOrDefault(claimed, claimed);
        }

        boolean allows(State from, State to) {
            return arcs.getOrDefault(from, Set.of()).contains(to);
        }

        Set<State> forwardTargets(State from) {
            Set<State> targets = EnumSet.noneOf(State.class);
            arcs.getOrDefault(from, Set.<State>of()).stream()
                    .filter(to -> rank(to) > rank(from))
                    .forEach(targets::add);
            return targets;
        }
    }
}
