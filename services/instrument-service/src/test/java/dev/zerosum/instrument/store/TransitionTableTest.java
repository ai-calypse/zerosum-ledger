package dev.zerosum.instrument.store;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.zerosum.instrument.store.AttemptStateMachines.Event;
import dev.zerosum.instrument.store.AttemptStateMachines.Kind;
import dev.zerosum.instrument.store.AttemptStateMachines.Outcome;
import dev.zerosum.instrument.store.AttemptStateMachines.State;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * M8(a): every state × event pair of every machine, generated rather than listed, against an expectation built here
 * from the master's own diagrams and prose.
 *
 * <p>The transcription below is deliberately a second copy. If it merely called the table it would agree with any
 * mistake the table made; written out from [docs/zerosum_ledger_mvp_plan.md#state-machines] it disagrees when the
 * table drifts from the document, which is the only failure worth catching here.
 *
 * <p>It is also the guard the task asks for against silent additions: a new {@code State} or {@code Event} constant
 * is not in these maps, so this test fails until someone decides where it belongs.
 */
class TransitionTableTest {

    /** Master §5.10, first diagram, plus the §5.11 cancellation rule. A refund uses FAILED in place of DECLINED. */
    private static final Map<Kind, Set<List<State>>> ARROWS = new EnumMap<>(Map.of(
            Kind.CHARGE, chargeArrows(State.DECLINED),
            Kind.REFUND, chargeArrows(State.FAILED),
            Kind.PAYOUT, Set.of(
                    List.of(State.CREATED, State.SUBMITTING),
                    List.of(State.CREATED, State.CANCELLED),
                    List.of(State.SUBMITTING, State.PENDING),
                    List.of(State.SUBMITTING, State.FAILED),
                    List.of(State.SUBMITTING, State.UNKNOWN),
                    List.of(State.UNKNOWN, State.PENDING),
                    List.of(State.UNKNOWN, State.CREATED),
                    List.of(State.UNKNOWN, State.NEEDS_REVIEW),
                    List.of(State.PENDING, State.SETTLED),
                    List.of(State.PENDING, State.FAILED),
                    List.of(State.SETTLED, State.RETURNED))));

    /** How far along each status is, from the diagrams' shape. Statuses in one group are equally far along. */
    private static final Map<Kind, List<Set<State>>> PROGRESSION = new EnumMap<>(Map.of(
            Kind.CHARGE, chargeProgression(State.DECLINED),
            Kind.REFUND, chargeProgression(State.FAILED),
            Kind.PAYOUT, List.of(Set.of(State.CREATED), Set.of(State.SUBMITTING), Set.of(State.UNKNOWN),
                    Set.of(State.PENDING),
                    Set.of(State.SETTLED, State.FAILED, State.NEEDS_REVIEW, State.CANCELLED),
                    Set.of(State.RETURNED))));

    /** What each event claims the attempt has reached, before a machine renames it. */
    private static final Map<Event, State> CLAIMS = claims();

    /** Events we issue rather than observe. A command can never be "ahead of state": there is nothing to look up. */
    private static final Set<Event> COMMANDS = EnumSet.of(
            Event.SUBMIT, Event.SWEEPER_TIMEOUT, Event.QUIET_PERIOD_ELAPSED, Event.CANCEL, Event.REVIEW_CUTOFF);

    /** A refund and a payout both call a refusal FAILED (master §5.10). */
    private static final Map<Kind, Map<State, State>> ALIASES = new EnumMap<>(Map.of(
            Kind.CHARGE, Map.of(),
            Kind.REFUND, Map.of(State.DECLINED, State.FAILED),
            Kind.PAYOUT, Map.of(State.DECLINED, State.FAILED)));

    private static Set<List<State>> chargeArrows(State failure) {
        return Set.of(
                List.of(State.CREATED, State.SUBMITTING),
                List.of(State.CREATED, State.CANCELLED),
                List.of(State.SUBMITTING, State.SUCCEEDED),
                List.of(State.SUBMITTING, failure),
                List.of(State.SUBMITTING, State.UNKNOWN),
                List.of(State.UNKNOWN, State.SUCCEEDED),
                List.of(State.UNKNOWN, failure),
                List.of(State.UNKNOWN, State.NEEDS_REVIEW));
    }

    private static List<Set<State>> chargeProgression(State failure) {
        return List.of(Set.of(State.CREATED), Set.of(State.SUBMITTING), Set.of(State.UNKNOWN),
                Set.of(State.SUCCEEDED, failure, State.NEEDS_REVIEW, State.CANCELLED));
    }

    private static Map<Event, State> claims() {
        Map<Event, State> claims = new EnumMap<>(Event.class);
        claims.put(Event.SUBMIT, State.SUBMITTING);
        claims.put(Event.SUBMIT_SUCCEEDED, State.SUCCEEDED);
        claims.put(Event.SUBMIT_PENDING, State.PENDING);
        claims.put(Event.SUBMIT_DECLINED, State.DECLINED);
        claims.put(Event.SUBMIT_UNKNOWN, State.UNKNOWN);
        claims.put(Event.SWEEPER_TIMEOUT, State.UNKNOWN);
        claims.put(Event.RETRY_SUCCEEDED, State.SUCCEEDED);
        claims.put(Event.RETRY_PENDING, State.PENDING);
        claims.put(Event.RETRY_DECLINED, State.DECLINED);
        claims.put(Event.RETRY_UNKNOWN, State.UNKNOWN);
        claims.put(Event.LOOKUP_FOUND_SUCCEEDED, State.SUCCEEDED);
        claims.put(Event.LOOKUP_FOUND_PENDING, State.PENDING);
        claims.put(Event.LOOKUP_FOUND_DECLINED, State.DECLINED);
        claims.put(Event.LOOKUP_FOUND_FAILED, State.FAILED);
        claims.put(Event.LOOKUP_FOUND_SETTLED, State.SETTLED);
        claims.put(Event.LOOKUP_FOUND_RETURNED, State.RETURNED);
        claims.put(Event.LOOKUP_NOT_FOUND, null);
        claims.put(Event.LOOKUP_UNAVAILABLE, null);
        claims.put(Event.QUIET_PERIOD_ELAPSED, State.CREATED);
        claims.put(Event.WEBHOOK_SUCCEEDED, State.SUCCEEDED);
        claims.put(Event.WEBHOOK_PENDING, State.PENDING);
        claims.put(Event.WEBHOOK_DECLINED, State.DECLINED);
        claims.put(Event.WEBHOOK_FAILED, State.FAILED);
        claims.put(Event.WEBHOOK_SETTLED, State.SETTLED);
        claims.put(Event.WEBHOOK_RETURNED, State.RETURNED);
        claims.put(Event.CANCEL, State.CANCELLED);
        claims.put(Event.REVIEW_CUTOFF, State.NEEDS_REVIEW);
        return claims;
    }

    static Stream<Arguments> machinesAndStates() {
        // Generated, not listed: every kind against every status the database allows, including statuses that are
        // not on that machine at all — which must be refused rather than quietly accepted.
        return Stream.of(Kind.values())
                .flatMap(kind -> Stream.of(State.values()).map(state -> Arguments.of(kind, state)));
    }

    @ParameterizedTest(name = "a {0} attempt in {1}")
    @MethodSource("machinesAndStates")
    @DisplayName("every event, in every state, on every machine")
    void everyPairMatchesTheDiagrams(Kind kind, State state) {
        Map<Event, String> mismatches = new LinkedHashMap<>();
        for (Event event : Event.values()) {
            String actual = render(AttemptStateMachines.decide(kind, state, event));
            String expected = expected(kind, state, event);
            if (!expected.equals(actual)) {
                mismatches.put(event, "expected " + expected + " but the table said " + actual);
            }
        }
        assertThat(mismatches).as("%s in %s", kind, state).isEmpty();
    }

    @Test
    @DisplayName("the whole Cartesian product has a decision, and none of them is null")
    void theProductIsCovered() {
        List<String> decided = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            for (State state : State.values()) {
                for (Event event : Event.values()) {
                    assertThat(AttemptStateMachines.decide(kind, state, event))
                            .as("%s/%s/%s", kind, state, event).isNotNull();
                    decided.add(kind + "/" + state + "/" + event);
                }
            }
        }
        assertThat(decided).hasSize(Kind.values().length * State.values().length * Event.values().length);
    }

    @Test
    @DisplayName("a new state or event cannot be added without a decision here")
    void everyConstantIsAccountedFor() {
        assertThat(CLAIMS.keySet()).as("every event is transcribed in this test").isEqualTo(EnumSet.allOf(Event.class));

        Set<State> placed = EnumSet.noneOf(State.class);
        PROGRESSION.values().forEach(progression -> progression.forEach(placed::addAll));
        assertThat(placed).as("every state sits on some machine's progression").isEqualTo(EnumSet.allOf(State.class));
    }

    @Nested
    @DisplayName("the rules the master states in prose")
    class WrittenRules {

        @Test
        @DisplayName("payout.settled after RETURNED is ignored as stale, with no state change")
        void settledAfterReturnedIsStale() {
            // The master's own example of a non-advancing event.
            assertThat(AttemptStateMachines.decide(Kind.PAYOUT, State.RETURNED, Event.WEBHOOK_SETTLED))
                    .isInstanceOf(Outcome.IgnoredStale.class);
        }

        @Test
        @DisplayName("a duplicate success webhook after SUCCEEDED emits no second payment event")
        void duplicateTerminalWebhookIsStale() {
            assertThat(AttemptStateMachines.decide(Kind.CHARGE, State.SUCCEEDED, Event.WEBHOOK_SUCCEEDED))
                    .isInstanceOf(Outcome.IgnoredStale.class);
        }

        @Test
        @DisplayName("payout.returned while PENDING resolves by lookup, through SETTLED, emitting each event once")
        void returnedWhilePendingIsAheadOfState() {
            // The master's own example of an event ahead of the current state (§0.3 C21).
            assertThat(AttemptStateMachines.decide(Kind.PAYOUT, State.PENDING, Event.WEBHOOK_RETURNED))
                    .isEqualTo(new Outcome.AheadOfState(State.RETURNED));

            List<State> path = AttemptStateMachines.resolutionPath(Kind.PAYOUT, State.PENDING, State.RETURNED);
            assertThat(path).containsExactly(State.SETTLED, State.RETURNED);
            assertThat(eventsAlong(Kind.PAYOUT, State.PENDING, path))
                    .as("settled then returned, each exactly once")
                    .containsExactly("PAYOUT_SETTLED", "PAYOUT_RETURNED");
        }

        @Test
        @DisplayName("a payout that failed while UNKNOWN catches up through PENDING, so acceptance is emitted first")
        void failureFoundWhileUnknownPassesThroughPending() {
            // Without the intermediate PENDING the table would emit PAYOUT_FAILED for a payout the ledger never saw
            // accepted, and the mapper would reverse an order that was never created.
            assertThat(AttemptStateMachines.decide(Kind.PAYOUT, State.UNKNOWN, Event.LOOKUP_FOUND_FAILED))
                    .isEqualTo(new Outcome.AheadOfState(State.FAILED));

            List<State> path = AttemptStateMachines.resolutionPath(Kind.PAYOUT, State.UNKNOWN, State.FAILED);
            assertThat(path).containsExactly(State.PENDING, State.FAILED);
            assertThat(eventsAlong(Kind.PAYOUT, State.UNKNOWN, path))
                    .containsExactly("PAYOUT_ACCEPTED", "PAYOUT_FAILED");
        }

        @Test
        @DisplayName("a payout refused at submission emits PAYOUT_REJECTED, which creates no order")
        void refusalAtSubmissionIsRejection() {
            assertThat(AttemptStateMachines.decide(Kind.PAYOUT, State.SUBMITTING, Event.SUBMIT_DECLINED))
                    .isEqualTo(new Outcome.Applied(State.FAILED, java.util.Optional.of("PAYOUT_REJECTED")));
        }

        @Test
        @DisplayName("cancellation is legal only from CREATED")
        void cancelOnlyFromCreated() {
            for (Kind kind : Kind.values()) {
                assertThat(AttemptStateMachines.decide(kind, State.CREATED, Event.CANCEL))
                        .as("%s cancelled before submission", kind)
                        .isEqualTo(new Outcome.Applied(State.CANCELLED, java.util.Optional.empty()));
                // The not_cancellable conflict the endpoint returns: never a lookup, because a cancel is ours to
                // decide and there is nothing at the provider to reconcile it against.
                assertThat(AttemptStateMachines.decide(kind, State.SUBMITTING, Event.CANCEL))
                        .as("%s cancelled after submission", kind).isInstanceOf(Outcome.Illegal.class);
            }
        }

        @Test
        @DisplayName("a status the machine does not have is illegal, not ignored")
        void foreignStatusIsIllegal() {
            // A card charge reported as settled is a provider or adapter contract violation, and worth an alert.
            assertThat(AttemptStateMachines.decide(Kind.CHARGE, State.SUBMITTING, Event.WEBHOOK_SETTLED))
                    .isInstanceOf(Outcome.Illegal.class);
        }

        @Test
        @DisplayName("a lookup that learned nothing moves nothing, and is not counted as a defect")
        void lookupWithoutAnAnswerIsIgnored() {
            assertThat(AttemptStateMachines.decide(Kind.PAYOUT, State.UNKNOWN, Event.LOOKUP_UNAVAILABLE))
                    .isInstanceOf(Outcome.IgnoredStale.class);
            assertThat(AttemptStateMachines.decide(Kind.CHARGE, State.UNKNOWN, Event.LOOKUP_NOT_FOUND))
                    .isInstanceOf(Outcome.IgnoredStale.class);
        }

        @Test
        @DisplayName("the resubmission arrow is the only backward move, and no resolution walks it")
        void resubmissionIsForwardOnly() {
            assertThat(AttemptStateMachines.decide(Kind.PAYOUT, State.UNKNOWN, Event.QUIET_PERIOD_ELAPSED))
                    .isEqualTo(new Outcome.Applied(State.CREATED, java.util.Optional.empty()));
            assertThat(AttemptStateMachines.resolutionPath(Kind.PAYOUT, State.PENDING, State.CREATED))
                    .as("a lookup can never walk an attempt backwards into resubmission").isEmpty();
        }

        private List<String> eventsAlong(Kind kind, State from, List<State> path) {
            List<String> emitted = new ArrayList<>();
            State at = from;
            for (State next : path) {
                AttemptStateMachines.paymentEvent(kind, at, next).ifPresent(emitted::add);
                at = next;
            }
            return emitted;
        }
    }

    @Nested
    @DisplayName("an illegal transition is logged and counted")
    class IllegalTransitionRecording {

        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
        private Logger logger;

        @BeforeEach
        void captureLogs() {
            logger = (Logger) LoggerFactory.getLogger(IllegalTransitions.class);
            captured.start();
            logger.addAppender(captured);
        }

        @AfterEach
        void stopCapturing() {
            logger.detachAppender(captured);
            captured.stop();
        }

        @Test
        @DisplayName("both a log line naming the attempt and a counter an alert can watch")
        void logsAndCounts() {
            UUID attemptId = UUID.randomUUID();
            new IllegalTransitions(meters).record(attemptId, Kind.PAYOUT, State.SUBMITTING, Event.CANCEL);

            assertThat(captured.list).hasSize(1);
            ILoggingEvent line = captured.list.get(0);
            assertThat(line.getLevel().toString()).isEqualTo("WARN");
            assertThat(line.getFormattedMessage())
                    .contains(attemptId.toString())
                    .contains("SUBMITTING")
                    .contains("CANCEL");

            assertThat(meters.find(IllegalTransitions.COUNTER)
                    .tag("kind", "PAYOUT").tag("state", "SUBMITTING").tag("event", "CANCEL")
                    .counter().count())
                    .as("a defect refusing one transition in a thousand is invisible in logs and obvious in a rate")
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("a refusal from the transition service records the attempted target")
        void recordsTheAttemptedTarget() {
            UUID attemptId = UUID.randomUUID();
            new IllegalTransitions(meters).record(attemptId, Kind.CHARGE, State.CREATED, "->SUCCEEDED");

            assertThat(captured.list.get(0).getFormattedMessage()).contains("->SUCCEEDED");
            assertThat(meters.find(IllegalTransitions.COUNTER).tag("event", "->SUCCEEDED").counter().count())
                    .isEqualTo(1.0);
        }
    }

    /** The expected outcome, derived from the transcription above rather than from the table under test. */
    private static String expected(Kind kind, State current, Event event) {
        List<Set<State>> progression = PROGRESSION.get(kind);
        Set<State> states = EnumSet.noneOf(State.class);
        progression.forEach(states::addAll);

        if (!states.contains(current)) {
            return "ILLEGAL";
        }
        State claims = CLAIMS.get(event);
        if (claims == null) {
            return "IGNORED_STALE";
        }
        State claimed = ALIASES.get(kind).getOrDefault(claims, claims);
        if (!states.contains(claimed)) {
            return "ILLEGAL";
        }
        if (ARROWS.get(kind).contains(List.of(current, claimed))) {
            return "APPLIED->" + claimed;
        }
        if (rank(progression, claimed) <= rank(progression, current)) {
            return "IGNORED_STALE";
        }
        return COMMANDS.contains(event) ? "ILLEGAL" : "AHEAD_OF_STATE(" + claimed + ")";
    }

    private static int rank(List<Set<State>> progression, State state) {
        for (int rank = 0; rank < progression.size(); rank++) {
            if (progression.get(rank).contains(state)) {
                return rank;
            }
        }
        throw new IllegalStateException(state + " is not on this progression");
    }

    private static String render(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Applied applied -> "APPLIED->" + applied.next();
            case Outcome.IgnoredStale ignored -> "IGNORED_STALE";
            case Outcome.Illegal illegal -> "ILLEGAL";
            case Outcome.AheadOfState ahead -> "AHEAD_OF_STATE(" + ahead.claimed() + ")";
        };
    }
}
