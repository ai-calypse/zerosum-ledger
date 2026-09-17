package dev.zerosum.instrument.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.zerosum.contracts.ContractSchemas;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * S05-T07: the D05-5 emission table and the payload it produces.
 *
 * <p>Covers the register's {@code PaymentEventSchemaTest} and {@code PayoutEventEmissionTest}; the mapping is
 * recorded in the evidence note.
 */
class PaymentEventEmissionTest {

    private static final Instant AT = Instant.parse("2026-09-15T10:04:11.702Z");

    private static final Set<String> FAILURES = Set.of(
            "CHARGE_DECLINED", "REFUND_FAILED", "PAYOUT_REJECTED", "PAYOUT_FAILED", "PAYOUT_RETURNED");

    /** An attempt shaped for the event type under test: payouts carry no source order, failures carry a code. */
    private static PaymentEvents.Attempt attemptFor(String eventType) {
        boolean payout = eventType.startsWith("PAYOUT");
        return new PaymentEvents.Attempt(UUID.randomUUID().toString(), payout ? "PAYOUT" : "CHARGE", "trip_8f2c",
                payout ? null : UUID.randomUUID().toString(), payout ? "driver:D1" : "rider:R1", "fakecard",
                "tok_card_ok", "USD", 2500, "SUCCEEDED", "ch_123",
                FAILURES.contains(eventType) ? "insufficient_funds" : null, 3);
    }

    @Nested
    @DisplayName("the emission table (PayoutEventEmissionTest)")
    class EmissionTable {

        @Test
        @DisplayName("a payout refused at submission emits only PAYOUT_REJECTED")
        void refusalAtSubmission() {
            assertThat(PaymentEvents.eventTypeFor("PAYOUT", "SUBMITTING", "FAILED")).contains("PAYOUT_REJECTED");
        }

        @Test
        @DisplayName("a payout that fails after acceptance emits PAYOUT_ACCEPTED, then PAYOUT_FAILED")
        void failureAfterAcceptance() {
            // The distinction is the from-status, and it matters downstream: a rejection created no order, while a
            // failure after acceptance has one that must be reversed.
            assertThat(PaymentEvents.eventTypeFor("PAYOUT", "SUBMITTING", "PENDING")).contains("PAYOUT_ACCEPTED");
            assertThat(PaymentEvents.eventTypeFor("PAYOUT", "PENDING", "FAILED")).contains("PAYOUT_FAILED");
        }

        @Test
        @DisplayName("the settle-then-return path emits each event once, in order")
        void settleThenReturn() {
            assertThat(PaymentEvents.eventTypeFor("PAYOUT", "PENDING", "SETTLED")).contains("PAYOUT_SETTLED");
            assertThat(PaymentEvents.eventTypeFor("PAYOUT", "SETTLED", "RETURNED")).contains("PAYOUT_RETURNED");
        }

        @Test
        @DisplayName("declines and refund failures are emitted although they create no order")
        void outcomesWithoutOrders() {
            assertThat(PaymentEvents.eventTypeFor("CHARGE", "SUBMITTING", "DECLINED")).contains("CHARGE_DECLINED");
            assertThat(PaymentEvents.eventTypeFor("REFUND", "SUBMITTING", "FAILED")).contains("REFUND_FAILED");
        }

        @Test
        @DisplayName("bookkeeping transitions emit nothing")
        void noEventForBookkeeping() {
            // Moving into SUBMITTING or UNKNOWN says nothing about money, so it must not reach the ledger.
            assertThat(PaymentEvents.eventTypeFor("CHARGE", "CREATED", "SUBMITTING")).isEmpty();
            assertThat(PaymentEvents.eventTypeFor("CHARGE", "SUBMITTING", "UNKNOWN")).isEmpty();
            assertThat(PaymentEvents.eventTypeFor("PAYOUT", "UNKNOWN", "CREATED")).isEmpty();
        }

        @Test
        @DisplayName("an unknown kind fails loudly rather than silently emitting nothing")
        void unknownKindThrows() {
            assertThatThrownBy(() -> PaymentEvents.eventTypeFor("TRANSFER", "CREATED", "SUCCEEDED"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("no event type is emitted twice across a payout's whole life")
        void noTypeTwiceAcrossALifetime() {
            List<String> emitted = new ArrayList<>();
            // The full W3 path, including a resubmission after an uncertain outcome.
            List.of(new String[] {"CREATED", "SUBMITTING"}, new String[] {"SUBMITTING", "UNKNOWN"},
                            new String[] {"UNKNOWN", "CREATED"}, new String[] {"CREATED", "SUBMITTING"},
                            new String[] {"SUBMITTING", "PENDING"}, new String[] {"PENDING", "SETTLED"},
                            new String[] {"SETTLED", "RETURNED"})
                    .forEach(step -> PaymentEvents.eventTypeFor("PAYOUT", step[0], step[1]).ifPresent(emitted::add));

            assertThat(emitted).containsExactly("PAYOUT_ACCEPTED", "PAYOUT_SETTLED", "PAYOUT_RETURNED");
            assertThat(emitted).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("the payload (PaymentEventSchemaTest)")
    class Payload {

        @Test
        @DisplayName("every emitted event type validates against the D01-8 schema")
        void everyEventTypeValidates() {
            List<String> types = List.of("CHARGE_SUCCEEDED", "CHARGE_DECLINED", "REFUND_SUCCEEDED", "REFUND_FAILED",
                    "PAYOUT_ACCEPTED", "PAYOUT_REJECTED", "PAYOUT_FAILED", "PAYOUT_SETTLED", "PAYOUT_RETURNED");

            for (String type : types) {
                String json = PaymentEvents.toJson(attemptFor(type), type, AT);

                assertThat(ContractSchemas.validate(ContractSchemas.PAYMENT_EVENT_V1, json))
                        .as("%s validates: %s", type, json)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a payout carries no source order, and a success carries no failure code")
        void absenceIsPartOfTheContract() {
            String payout = PaymentEvents.toJson(attemptFor("PAYOUT_SETTLED"), "PAYOUT_SETTLED", AT);
            String success = PaymentEvents.toJson(attemptFor("CHARGE_SUCCEEDED"), "CHARGE_SUCCEEDED", AT);

            // The schema expresses these as "false", meaning the key must be absent. An explicit null would fail it
            // exactly as a value would, which is why the payload is built key by key.
            assertThat(payout).doesNotContain("source_order_id");
            assertThat(success).doesNotContain("failure_code");
        }

        @Test
        @DisplayName("an event that would violate the contract is refused before it reaches the outbox")
        void refusesToBuildAnInvalidEvent() {
            // A declined charge with no failure code. By the time a bad payload is committed beside a real status
            // change, the money it describes has already moved.
            var withoutCode = new PaymentEvents.Attempt(UUID.randomUUID().toString(), "CHARGE", "trip_8f2c",
                    UUID.randomUUID().toString(), "rider:R1", "fakecard", "tok", "USD", 2500, "DECLINED",
                    null, null, 1);

            assertThatThrownBy(() -> PaymentEvents.toJson(withoutCode, "CHARGE_DECLINED", AT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("failure_code");
        }

        @Test
        @DisplayName("the event id is deterministic, so a replayed transition is recognisably the same event")
        void eventIdIsDeterministic() {
            PaymentEvents.Attempt attempt = attemptFor("CHARGE_SUCCEEDED");

            String id = PaymentEvents.eventId(attempt.attemptId(), "CHARGE_SUCCEEDED");
            String later = PaymentEvents.toJson(attempt, "CHARGE_SUCCEEDED", AT.plusSeconds(60));

            // Same attempt, same type, different clock: the id must not move, or a redelivery becomes a second order.
            assertThat(id).isEqualTo(attempt.attemptId() + ":CHARGE_SUCCEEDED");
            assertThat(later).contains("\"event_id\":\"" + id + "\"");
        }
    }
}
