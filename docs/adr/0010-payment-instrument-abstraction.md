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

---

## Amendment — the quiet period, as implemented (S05-T12, D05-9)

- **Added:** 2026-09-17, by S05-T12.
- **Status:** unchanged (Accepted). This amendment records the *implementation* of decision 5 above, which until now
  had no code attached to it.

Decision 5 says a provider without idempotency keys needs a quiet period. It did not say how long, measured from
when, or what else has to be true before a resubmission is allowed. Those are the questions that decide whether a
driver is paid once or twice, so they are answered here rather than in a comment.

### The chosen quiet period, and why

**60 seconds**, from master §5.10, configured at `zs.instruments.quiet-period` and reaching the resolver only as
`Capabilities.safeResubmitQuietPeriod` — the resolver never names FakeBank, it asks the capability.

The number is not arithmetic; it is a bound on someone else's behaviour. It has to exceed the longest time FakeBank
can still act on a request we have already given up on. FakeBank's own `max_processing_delay_ms` is that time, so the
rule is simply that the delay stays below the quiet period. That is **enforced, not documented**: `FaultKnobs`
refuses a profile whose `max_processing_delay_ms` reaches the quiet period (the S05-T02 configuration test), so the
simulator cannot be configured into a state whose duplicate payouts would then be blamed on the resolver.

### The two-condition resubmission rule

A payout is resubmitted only when **both** hold:

1. at least `safeResubmitQuietPeriod` has passed since the most recent submission, **and**
2. a lookup returns `NotFound`.

Neither is sufficient alone, and the reasons differ. Condition 2 alone is the obvious trap: `NotFound` a second after
sending means the bank has not recorded the request *yet*, not that it never will. Condition 1 alone is worse — it is
a timer deciding to move money with nobody asked.

The two conditions are folded into a **single event**, `QUIET_PERIOD_ELAPSED`, which the resolver raises only once it
has established both. The state machine therefore has one arrow back to `CREATED`, reachable one way. A design with
an arrow per condition would let a future caller take the timer half on its own.

`Unavailable` is **not** `NotFound`, per decision 4, and this is where that distinction earns its keep: an
unreachable bank leaves the attempt exactly where it is and schedules another check. An outage must never be able to
present itself as evidence that nothing happened.

The clock for condition 1 runs from the **most recent submission**, read from the append-only `attempt_transitions`
history rather than from any field a later write could move. A service restarted mid-resubmission finds the attempt
in `SUBMITTING`, sweeps it to `UNKNOWN`, and measures the quiet period from the submission that is actually
outstanding — not from the first one.

### Residual risk

**A bank that processes a request more than 60 seconds after receiving it can still be paid twice.** The lookup said
`NotFound`, the quiet period expired, we resubmitted, and the original then landed. Nothing in this design detects
that at the moment of resubmission; it is caught afterwards, as a duplicate client reference in provider ground truth
and by reconciliation (I7).

The risk is **reduced, not eliminated**, and the reduction is entirely the processing-delay bound. That is why the
bound is enforced by a test rather than trusted, and why the resolver refuses to resubmit on *any* lookup that finds
something — including one that finds several, which means a duplicate has already happened and a third payout would
only deepen it.

Carried in H.5 as a known limitation and in the release honesty items, because a residual risk that lives only in an
ADR is one nobody reads before shipping.

### How S08 relates

- **A3** (§0.3 E7) resubmits **the same attempt with a fresh provider idempotency key** rather than creating a new
  attempt — the attempt uniqueness constraint is what forces that, and it keeps one attempt to one movement of money.
  A3 is therefore a different recovery from this one: it applies where keys exist, this applies where they do not.
- **F8** exercises this path by holding FakeBank past the read timeout, which is the condition that produces the
  `UNKNOWN` attempts the resolver resolves.
- **F3**'s breakpoint hook (§0.3 E9) is added by S08-T02 as a seam on D05-5, by change request. No seam for it is
  added here: a hook with no caller is untested code on the path that moves money.
