# ADR-0010 — Providers are reached through one interface

- **Status:** Accepted
- **Date:** 2026-09-16
- **Decision owner:** S05 (D05-1) — [docs/step_05_instruments_fake_providers.md#decisions-and-outputs](../step_05_instruments_fake_providers.md#decisions-and-outputs)

## Context

Two simulated providers behave nothing alike. FakeCard answers synchronously: a charge is approved or declined in the
response. FakeBank accepts a payout with `202` and resolves it minutes later by webhook, and may reverse it after
settlement. A third provider would differ again.

The question is where that difference is allowed to live. If it reaches the state machines, then every one of them
grows a branch per provider, and adding a provider means editing code that moves money — the change most likely to
introduce a silent accounting error.

There is a second problem the interface has to answer. When a request times out, the provider may or may not have
moved money. That outcome is not an error and not a success, and a design that offers only those two forces the
caller to guess.

## Decision

1. **One interface, `PaymentInstrument`.** Core code names operations — charge, disburse, refund, lookup — never a
   provider. Adapters translate in both directions and hold no business rules.
2. **Capabilities are data, not documentation.** `Capabilities` states what an adapter supports. Callers consult it;
   calling an unsupported operation throws `UnsupportedCapabilityException`, which is a programming error rather than
   a runtime branch.
3. **`Unknown` is a first-class result.** `SubmitResult` is sealed over Succeeded, Pending, Declined and Unknown, so a
   `switch` with no default branch stops compiling when an outcome is added. An uncertain outcome is resolved by
   `lookup`, never by assuming.
4. **`NotFound` and `Unavailable` stay distinct** in `LookupResult`. "The provider has no record of this" permits a
   resubmission; "I could not ask" does not. Collapsing the two turns an outage into a double charge.
5. **Recovery without idempotency keys needs a quiet period.** A provider that offers neither idempotency keys nor a
   safe-resubmit quiet period cannot be constructed, because an uncertain outcome could never be resolved safely.
6. **Statuses are normalized** to `ProviderStatus` at the adapter boundary. No provider's own wording reaches a state
   machine.
7. **The registry collects beans**, keyed by provider id, and fails startup when two adapters claim the same id.
   There is no factory to edit, so adding a provider is adding a bean.

## Alternatives considered

- **A service class per provider** (`FakeCardPaymentService`, `FakeBankPayoutService`). Rejected: every caller then
  chooses a provider by type, and the choice spreads through the codebase.
- **One interface plus an `if (provider == FAKEBANK)` in callers.** Rejected: it keeps the interface and discards the
  benefit. Capability flags exist so that branching is on what a provider *can do*, not on which one it is.
- **A single adapter type with nullable fields for the async case.** Rejected: nullable fields make "does this apply
  here?" a runtime question the compiler cannot help with.
- **Returning the provider's own status strings.** Rejected: it moves provider vocabulary into the ledger, where a
  provider renaming a status becomes an accounting bug.
- **Boolean or exception-based submit results.** Rejected: neither can express "may or may not have happened", which
  is the outcome the design exists to handle.

## Consequences

- **A third adapter needs no core change** — a bean implementing the interface, and nothing else.
- **Callers must check capabilities first.** This is the cost of making capabilities explicit, and it is enforced by
  the exception rather than by review.
- **Uncertain outcomes are slower to resolve.** A provider without idempotency keys requires lookup, then a quiet
  period, then resubmission. That delay is deliberate: the alternative is a duplicate payment.
- **Amounts are positive magnitudes** in commands; direction is carried by the operation. This keeps the instrument
  layer aligned with the sign convention in ADR-0003 instead of introducing a second one.
- **`SettlementReport` is declared, not designed.** S05 defines only what the interface method needs; its lines and
  totals belong to S06, which extends it through the change procedure. Filling it in here would freeze a shape that
  step has not designed.
