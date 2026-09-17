package dev.zerosum.instrument.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.zerosum.instrument.core.Commands.ChargeCommand;
import dev.zerosum.instrument.core.Commands.DisburseCommand;
import dev.zerosum.instrument.core.Commands.LookupQuery;
import dev.zerosum.instrument.core.Commands.RefundCommand;
import dev.zerosum.instrument.core.InstrumentExceptions.UnknownProviderException;
import dev.zerosum.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** T04's definition of done: the abstraction holds its own invariants before any provider implements it. */
class PaymentInstrumentContractTest {

    private static final Instant DEADLINE = Instant.parse("2026-01-01T00:00:00Z");
    private static final Money TEN_USD = Money.of(1000, "USD");

    @Nested
    @DisplayName("capabilities")
    class CapabilityRules {

        @Test
        @DisplayName("a missing quiet period while idempotency keys are false fails construction")
        void quietPeriodRequiredWithoutIdempotencyKeys() {
            assertThatThrownBy(() -> new Capabilities(true, false, true, false, true, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("safe resubmit quiet period");
        }

        @Test
        @DisplayName("idempotency keys make the quiet period unnecessary")
        void idempotentProviderNeedsNoQuietPeriod() {
            assertThatCode(() -> new Capabilities(true, false, true, true, true, null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a provider that can neither charge nor disburse is rejected")
        void mustMoveMoneySomehow() {
            assertThatThrownBy(() -> new Capabilities(false, false, true, true, true, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("provider identity")
    class ProviderIdentity {

        @Test
        @DisplayName("the ledger entity id is derived, not restated")
        void derivesEntityId() {
            assertThat(new ProviderId("fakecard").entityId()).isEqualTo("provider:fakecard");
        }

        @Test
        @DisplayName("ids that could never appear in a valid payment event are rejected at construction")
        void rejectsUnusableIds() {
            assertThatThrownBy(() -> new ProviderId("FakeCard")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProviderId("")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("commands")
    class CommandRules {

        @Test
        @DisplayName("every command needs the attempt id that doubles as the idempotency key")
        void attemptIdRequired() {
            assertThatThrownBy(() -> new ChargeCommand(null, "tok", TEN_USD, DEADLINE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("idempotency key");
        }

        @Test
        @DisplayName("a refund must name the charge it reverses")
        void refundNeedsOriginalReference() {
            assertThatThrownBy(() -> new RefundCommand(UUID.randomUUID(), " ", TEN_USD, DEADLINE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("amounts are positive magnitudes; direction is the operation, not the sign")
        void amountsArePositive() {
            assertThatThrownBy(() -> new DisburseCommand(UUID.randomUUID(), "tok", Money.of(0, "USD"), DEADLINE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a deadline is required so a slow provider cannot block a caller forever")
        void deadlineRequired() {
            assertThatThrownBy(() -> new ChargeCommand(UUID.randomUUID(), "tok", TEN_USD, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deadline");
        }
    }

    @Nested
    @DisplayName("result types")
    class ResultExhaustiveness {

        /**
         * Compiles only while the switch covers every permitted subtype. There is deliberately no default branch: if
         * a new outcome is added later, this stops compiling, which is exactly the signal a state machine needs.
         */
        @Test
        @DisplayName("every submit outcome is handled without a default branch")
        void submitResultsAreExhaustive() {
            List<SubmitResult> all = List.of(new SubmitResult.Succeeded("ref"), new SubmitResult.Pending("ref"),
                    new SubmitResult.Declined("do_not_honor"), new SubmitResult.Unknown("timeout"));

            List<String> handled = all.stream().map(result -> switch (result) {
                case SubmitResult.Succeeded s -> "final:" + s.providerRef();
                case SubmitResult.Pending p -> "awaiting:" + p.providerRef();
                case SubmitResult.Declined d -> "refused:" + d.code();
                case SubmitResult.Unknown u -> "resolve-by-lookup:" + u.reason();
            }).toList();

            assertThat(handled).containsExactly("final:ref", "awaiting:ref", "refused:do_not_honor",
                    "resolve-by-lookup:timeout");
        }

        @Test
        @DisplayName("not-found and unavailable stay distinct, because only one permits a resubmission")
        void lookupResultsAreExhaustive() {
            List<LookupResult> all = List.of(new LookupResult.Found(ProviderStatus.SUCCEEDED, "ref"),
                    new LookupResult.NotFound(), new LookupResult.Unavailable("connection refused"));

            List<Boolean> mayResubmit = all.stream().map(result -> switch (result) {
                case LookupResult.Found ignored -> false;          // it landed; resubmitting would double it
                case LookupResult.NotFound ignored -> true;        // it never landed
                case LookupResult.Unavailable ignored -> false;    // still unknown; an outage is not an answer
            }).toList();

            assertThat(mayResubmit).containsExactly(false, true, false);
        }
    }

    @Nested
    @DisplayName("provider registry")
    class Registry {

        @Test
        @DisplayName("two adapters claiming one provider id fail startup rather than racing at runtime")
        void duplicateProviderIdsFailFast() {
            assertThatThrownBy(() -> new ProviderRegistry(List.of(new StubInstrument("fakecard"),
                            new StubInstrument("fakecard"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("two payment instruments claim provider 'fakecard'");
        }

        @Test
        @DisplayName("adapters are resolved by id, and an unknown provider is a typed failure")
        void resolvesByProviderId() {
            var registry = new ProviderRegistry(List.of(new StubInstrument("fakecard"), new StubInstrument("fakebank")));

            assertThat(registry.get("fakebank").provider()).isEqualTo(new ProviderId("fakebank"));
            assertThat(registry.registered()).containsExactly(new ProviderId("fakecard"), new ProviderId("fakebank"));
            assertThatThrownBy(() -> registry.get("nosuch")).isInstanceOf(UnknownProviderException.class);
        }
    }

    /** Minimal adapter: the registry only ever asks for the provider id. */
    private record StubInstrument(String id) implements PaymentInstrument {

        @Override
        public ProviderId provider() {
            return new ProviderId(id);
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, true, true, true, true, Duration.ofSeconds(30));
        }

        @Override
        public SubmitResult charge(ChargeCommand command) {
            return new SubmitResult.Succeeded("stub");
        }

        @Override
        public SubmitResult disburse(DisburseCommand command) {
            return new SubmitResult.Succeeded("stub");
        }

        @Override
        public SubmitResult refund(RefundCommand command) {
            return new SubmitResult.Succeeded("stub");
        }

        @Override
        public LookupResult lookup(LookupQuery query) {
            return new LookupResult.NotFound();
        }

        @Override
        public ProviderEvent parseWebhook(WebhookRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SettlementReport settlementReport(LocalDate reportDate) {
            return new SettlementReport(provider(), reportDate);
        }
    }
}
