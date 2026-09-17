package dev.zerosum.instrument.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.instrument.policy.CollectionPolicy.GroupState;
import dev.zerosum.instrument.policy.CollectionPolicy.Intent;
import dev.zerosum.instrument.policy.CollectionPolicy.RiderDelta;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The collection policy as a decision, with no database and no broker (D05-6).
 *
 * <p>Every rule here is a statement about money, and each one has a way of being wrong that costs a real payer: a
 * charge that happens twice, a refund larger than the capture, an adjustment that returns money nobody took. They are
 * tested against the golden payloads (D01-9) where a golden payload exists, so the netting is exercised on the exact
 * bytes the pipeline carries rather than on a hand-written approximation of them.
 */
class CollectionPolicyTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Nothing charged, nothing refunded, nothing in flight — a group the policy has never touched. */
    private static final GroupState UNTOUCHED = new GroupState(0, 0, 0, false, false, false);

    private static JsonNode order(String id) {
        return JSON.readTree(GoldenPayloads.byId(id));
    }

    @Nested
    @DisplayName("what an order asks for")
    class Deltas {

        @Test
        @DisplayName("O1, a completed trip, nets to one charge of the rider's receivable")
        void goldenTripIsOneCharge() {
            List<RiderDelta> deltas = CollectionPolicy.riderDeltas(order("O1"));

            assertThat(deltas).containsExactly(new RiderDelta("rider:R1", "USD", 2_500));
            assertThat(CollectionPolicy.decide(deltas.get(0), UNTOUCHED))
                    .containsExactly(new Intent("CHARGE", "rider:R1", "USD", 2_500, false,
                            "collect the rider receivable"));
        }

        @Test
        @DisplayName("O3, a downward fare adjustment, nets to a negative delta of the adjusted amount")
        void goldenAdjustmentIsANegativeDelta() {
            assertThat(CollectionPolicy.riderDeltas(order("O3")))
                    .containsExactly(new RiderDelta("rider:R1", "USD", -300));
        }

        @Test
        @DisplayName("an order that is not COMMERCE is ignored entirely")
        void nonCommerceIsIgnored() {
            // O2 is the COLLECTION order the mapper writes from our own CHARGE_SUCCEEDED event. Acting on it would
            // charge the rider a second time for the collection we just made.
            assertThat(CollectionPolicy.riderDeltas(order("O2"))).isEmpty();
            assertThat(CollectionPolicy.riderDeltas(order("O5"))).isEmpty();
        }

        @Test
        @DisplayName("several receivable entries for one rider net into a single intent")
        void multipleEntriesNet() {
            JsonNode order = commerce("""
                    {"entity_id":"rider:R1","account":"receivable","currency":"USD","amount_minor":2500},
                    {"entity_id":"rider:R1","account":"receivable","currency":"USD","amount_minor":-400},
                    {"entity_id":"driver:D1","account":"payable","currency":"USD","amount_minor":-2100}
                    """);

            assertThat(CollectionPolicy.riderDeltas(order))
                    .containsExactly(new RiderDelta("rider:R1", "USD", 2_100));
        }

        @Test
        @DisplayName("entries that net to zero produce nothing at all")
        void zeroNetDeltaProducesNothing() {
            JsonNode order = commerce("""
                    {"entity_id":"rider:R1","account":"receivable","currency":"USD","amount_minor":2500},
                    {"entity_id":"rider:R1","account":"receivable","currency":"USD","amount_minor":-2500}
                    """);

            assertThat(CollectionPolicy.riderDeltas(order)).isEmpty();
        }

        @Test
        @DisplayName("one rider in two currencies yields one intent per currency (§0.3 C22)")
        void twoCurrenciesYieldTwoIntents() {
            JsonNode order = commerce("""
                    {"entity_id":"rider:R1","account":"receivable","currency":"USD","amount_minor":2500},
                    {"entity_id":"rider:R1","account":"receivable","currency":"EUR","amount_minor":1800}
                    """);

            // Netting across currencies would add numbers that are not addable; not keeping them apart would make
            // the second attempt collide on (kind, source_order_id, entity_id, currency) and silently never happen.
            assertThat(CollectionPolicy.riderDeltas(order)).containsExactly(
                    new RiderDelta("rider:R1", "USD", 2_500),
                    new RiderDelta("rider:R1", "EUR", 1_800));
        }

        @Test
        @DisplayName("only the rider's receivable counts, not the driver's payable or the platform's revenue")
        void onlyRiderReceivableCounts() {
            JsonNode order = commerce("""
                    {"entity_id":"driver:D1","account":"payable","currency":"USD","amount_minor":-2000},
                    {"entity_id":"platform:main","account":"revenue","currency":"USD","amount_minor":-500},
                    {"entity_id":"rider:R1","account":"payable","currency":"USD","amount_minor":2500}
                    """);

            assertThat(CollectionPolicy.riderDeltas(order)).isEmpty();
        }
    }

    @Nested
    @DisplayName("what the policy decides")
    class Decisions {

        private static final RiderDelta ADJUSTMENT = new RiderDelta("rider:R1", "USD", -300);

        @Test
        @DisplayName("an adjustment against a settled capture refunds the adjusted amount")
        void refundOfTheAdjustedAmount() {
            var captured = new GroupState(2_500, 0, 0, false, false, true);

            assertThat(CollectionPolicy.decide(ADJUSTMENT, captured))
                    .containsExactly(new Intent("REFUND", "rider:R1", "USD", 300, false, "return the adjusted amount"));
        }

        @Test
        @DisplayName("a refund never exceeds what is refundable")
        void refundIsCappedAtRefundable() {
            // 2500 captured, 2400 already returned: only 100 is left however large the adjustment.
            var nearlyRefunded = new GroupState(2_500, 2_400, 0, false, false, true);

            assertThat(CollectionPolicy.decide(new RiderDelta("rider:R1", "USD", -900), nearlyRefunded))
                    .containsExactly(new Intent("REFUND", "rider:R1", "USD", 100, false, "return the adjusted amount"));
        }

        @Test
        @DisplayName("refunds already in flight are subtracted, so two adjustments cannot each refund the capture")
        void refundsInFlightAreSubtracted() {
            var withRefundInFlight = new GroupState(2_500, 0, 2_500, false, false, true);

            // The unrefundable remainder creates nothing; it stays visible as rider credit in the ledger.
            assertThat(CollectionPolicy.decide(ADJUSTMENT, withRefundInFlight)).isEmpty();
        }

        @Test
        @DisplayName("an adjustment while the capture is unresolved creates a blocked refund of the full amount")
        void inFlightChargeBlocksTheRefund() {
            var chargeInFlight = new GroupState(0, 0, 0, true, false, true);

            // Not capped here: refundable is zero while the capture is unresolved, so capping now would turn a
            // legitimate adjustment into nothing at all. It is sized when the capture resolves.
            assertThat(CollectionPolicy.decide(ADJUSTMENT, chargeInFlight))
                    .containsExactly(new Intent("REFUND", "rider:R1", "USD", 300, true,
                            "adjustment while the capture is unresolved"));
        }

        @Test
        @DisplayName("a declined charge creates no refund: the adjustment just reduces the rider's debt")
        void declinedChargeCreatesNothing() {
            var declined = new GroupState(0, 0, 0, false, true, true);

            assertThat(CollectionPolicy.decide(ADJUSTMENT, declined)).isEmpty();
        }

        @Test
        @DisplayName("an adjustment whose charge does not exist yet creates nothing, not a blocked refund")
        void adjustmentWithNoChargeCreatesNothing() {
            // decision: D05-6 — same-group ordering is guaranteed (ADR-0007), so a group with no charge at all is one
            // whose charge the policy never created: no token, or a provider nothing serves. A blocked refund here
            // would wait for a capture that is never coming, and the S05-T12 old-CREATED sweep would eventually
            // submit a refund with no charge to reverse.
            assertThat(CollectionPolicy.decide(ADJUSTMENT, UNTOUCHED)).isEmpty();
        }

        @Test
        @DisplayName("a group whose first charge declined but whose retry captured is still refundable")
        void aLaterSuccessMakesTheGroupRefundable() {
            var declinedThenCaptured = GroupLedger.stateOf(List.of(
                    attempt("CHARGE", "DECLINED", 2_500),
                    attempt("CHARGE", "SUCCEEDED", 2_500)));

            assertThat(declinedThenCaptured.chargeRefused()).isFalse();
            assertThat(CollectionPolicy.decide(ADJUSTMENT, declinedThenCaptured))
                    .containsExactly(new Intent("REFUND", "rider:R1", "USD", 300, false, "return the adjusted amount"));
        }

        @Test
        @DisplayName("an attempt needing review counts as in flight, so its refund waits rather than guessing")
        void needsReviewBlocksRatherThanRefuses() {
            var underReview = GroupLedger.stateOf(List.of(attempt("CHARGE", "NEEDS_REVIEW", 2_500)));

            assertThat(underReview.chargeInFlight()).isTrue();
            assertThat(CollectionPolicy.decide(ADJUSTMENT, underReview))
                    .allMatch(CollectionPolicy.Intent::blockedOnCapture);
        }
    }

    private static AttemptStore.AttemptRow attempt(String kind, String status, long amountMinor) {
        return new AttemptStore.AttemptRow(java.util.UUID.randomUUID(), kind, status, "trip_1", "rider:R1",
                "fakecard", "tok_1", "USD", amountMinor, "ch_1", false, 0);
    }

    /** A COMMERCE order carrying exactly these entries; every other field is the golden O1's. */
    private static JsonNode commerce(String entries) {
        return JSON.readTree("""
                {
                  "schema": "zerosum.money_order.v1",
                  "order_id": "01996a3e-8f10-7c2a-9a1e-3d5c7b2e4f01",
                  "order_group_id": "trip_8f2c",
                  "type": "COMMERCE",
                  "reason": "trip.completed",
                  "adjusts_order_id": null,
                  "source": { "system": "trip-simulator", "idempotency_key": "k" },
                  "entries": [ %s ],
                  "metadata": {},
                  "effective_at": "2026-09-15T10:04:11.201Z",
                  "created_at": "2026-09-15T10:04:11.219Z"
                }
                """.formatted(entries));
    }
}
