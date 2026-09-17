# S05-T12 — sweepers, the `UNKNOWN` resolver, and deciding what to do when we do not know

Date: 2026-09-17. Branch: `worktree-agent-acea59a1fb658574e`.

This is the task that decides what happens when we do not know whether money moved. Every claim below is therefore
asserted against **provider ground truth** — the provider's own record of what it holds — rather than against our
own bookkeeping. "The attempt says `SUCCEEDED`" and "the payer was charged exactly once" are different claims, and a
resolver that got it wrong would satisfy the first while breaking the second.

## Measured

All counts are parsed from the Gradle XML results (`build/test-results/*/TEST-*.xml`). This build sets
`failOnNoDiscoveredTests = false`, so `BUILD SUCCESSFUL` on its own would prove only that nothing ran. A piped
`./gradlew … | tail` reports *tail's* exit code — in zsh the build's own code is `$pipestatus[1]`, not
`${PIPESTATUS[0]}` — and this run was checked both ways: the first baseline run printed `BUILD FAILED` while the
pipeline exited `0`, which is the false green this repository has shipped before.

| Suite | Layer | Tests | Failures | Skipped |
|---|---|---|---|---|
| `ResolutionScheduleTest` (3 top-level plus 2 nested groups) | unit | 9 | 0 | 0 |
| `ResolverFakeCardIT` | integration | 4 | 0 | 0 |
| `ResolverFakeBankIT` | integration | 4 | 0 | 0 |
| `ResolverFakeBankIT :: the §0.3 O9 alert signals` (nested) | integration | 2 | 0 | 0 |
| `QuiesceNoStuckAttemptsIT` | integration | 1 | 0 | 0 |
| **instrument-service, whole unit layer** | unit | **143** | **0** | **1** |
| **instrument-service, whole integration layer** | integration | **70** | **0** | **0** |
| **Whole repository, unit layer** | unit | **360** | **0** | **1** |
| **Whole repository, integration layer** | integration | **290** | **1** | **0** |

instrument-service went from a measured baseline of 134 unit and 59 integration to 143 and 70, and the repository
from 351 and 279 to 360 and 290. The additions reconcile exactly — **9 unit and 11 integration**, the same figure at
both levels — so no existing test was replaced or quietly dropped. The single unit skip is still
`FakeBankContractTest :: settlement reports`, unchanged.

**Gradle exit code was 1** (`BUILD FAILED`), from the one failing test this task did not touch:
`LedgerOpenApiContractIT.theFreshnessResponseValidatesEvenWhenItCannotBeComputed` in ledger-service, which fails when
a Kafka broker is listening on `127.0.0.1:9092`. It is being fixed separately. **Every instrument-service suite
passed: 70 integration and 143 unit, 0 failures.**

Commands, exactly as run:

```sh
./gradlew :services:instrument-service:test                     # exit 0
./gradlew :services:instrument-service:integrationTest          # exit 0
./gradlew test integrationTest --continue                       # exit 1, the ledger test above
```

The exit codes were captured without a pipe (`./gradlew … > log 2>&1; echo $?`) rather than read from a piped
`tail`, for the reason given above.

**Suite naming.** The register names `AlertSignalGaugesIT`; it is the nested `the §0.3 O9 alert signals` group of
`ResolverFakeBankIT` here, so it shares that class's database and Spring context instead of starting another pair.

## What each claim is asserted against

### FakeCard — an idempotent provider is retried under the same key

**Met.** With the provider taking the money and then going quiet past the read timeout, the charge becomes `UNKNOWN`
and is then resolved by re-sending the same request under the same attempt id:

- the attempt ends `SUCCEEDED`, through the history `SUBMITTING → UNKNOWN → SUCCEEDED` — it never jumps to a guessed
  terminal state;
- **provider ground truth holds exactly one charge**, however many times we asked;
- exactly one `CHARGE_SUCCEEDED` payment event exists. One movement of money, one event; a second would become a
  second money order downstream.

### FakeBank — a provider with no idempotency keys, and the two-condition rule

**Met**, in all three shapes the bank's answer can take:

- **Commit then timeout** → the lookup **finds** the payout, so our state is settled to `PENDING` and **no second
  payout is sent**. Ground truth holds exactly one, and the assertion is held for two seconds afterwards, because a
  duplicate would appear after the first rather than before it.
- **Reset before commit** → the lookup returns `NotFound`, and **nothing is resubmitted while the quiet period is
  unexpired**, asserted by holding that condition rather than by observing a single instant. The clock is then moved
  past the quiet period and the payout is resubmitted, ending `PENDING` with **exactly one payout** in ground truth
  and the history `SUBMITTING → UNKNOWN → CREATED → SUBMITTING → PENDING` — through the one backward arrow the payout
  machine draws.
- **`Unavailable` never resubmits.** This case is deliberately set up so that everything *except* the lookup argues
  for resubmission: the last answer was `NotFound` and the clock is moved **ten times** past the quiet period. Nothing
  is sent. When the bank can answer again the same attempt resolves normally, so it was waiting rather than
  abandoned.

**The quiet period is tested at its real 60-second value** (ADR-0010), by moving a controllable clock rather than by
shrinking the configuration. Shrinking it would have exercised a configuration no deployment runs, and would have
passed just as happily against code comparing the wrong duration.

### M8(c) — after quiesce, nothing is left stuck

**Met.** Twelve charges, half of which commit and then go silent, are created and then **left alone**: nothing in the
test drives the sweeper, because "the resolver resolves when asked" and "the service recovers unattended" are
different claims and only the second is what M8(c) promises. After the scheduled sweeper drains them:

- **0 attempts remain in `SUBMITTING` or `UNKNOWN`**;
- **0 reached `NEEDS_REVIEW`** — everything was genuinely resolved, not given up on;
- all 12 are `SUCCEEDED`, and **each has exactly one charge in provider ground truth** and exactly one payment event.

### The §0.3 O9 alert signals

**Met.** A deliberately stuck attempt raises `unknown_attempts_oldest_seconds` above its real age, and the gauge drops
once the attempt resolves, at which point `pending_payouts_oldest_seconds` reports the payout now waiting on the
bank. A separate case drives an attempt past the review cut-off and asserts `attempts_needs_review` rises.

`NEEDS_REVIEW` is asserted to have **no automatic exit**: the status is held for two seconds after it is reached. If
the sweeper kept working on such an attempt, a guessed terminal state would eventually reach the ledger as a real
money order.

## Defects found

### 1. A test that passed for the wrong reason would have been silent (found and fixed here)

The controllable clock was first written as a nested `@TestConfiguration` on the **abstract base class**. Spring Boot
detects those on the test class it is running, not on an inherited base, so the override never applied and the
application kept its real clock. The symptom was a quiet-period test timing out with the attempt still `UNKNOWN` —
which reads as a resolver bug, not a wiring one.

Fixing it by `@Import` then exposed the second half: the test bean method was also called `clock()`, colliding with
the application's own bean name and failing every context with `BeanDefinitionOverrideException`. Both are fixed —
explicit `@Import`, and a differently named `@Primary` bean so the application's real wiring stays registered — and
the base now **asserts in `@BeforeEach` that the injected clock is the test clock**, so the same silent failure
cannot recur.

### 2. `PayoutSubmitter` is package-private, so there are two payout submission paths

`payouts.PayoutSubmitter#submit` is exactly what the old-`CREATED` sweep and the quiet-period resubmission need to
call, and `PayoutSubmitter`'s own javadoc anticipates this task calling it — but both the class and the method are
package-private, and this task's brief forbids editing `..instrument.payouts`. The call is therefore restated in
`recovery.PayoutResubmission`.

**This is a maintenance cost, not a duplicate-payout risk**, and the distinction matters: the once-only guarantee is
not in either class. It is the guarded `CREATED → SUBMITTING` transition in `AttemptTransitions`, which both paths go
through and which is not duplicated — two callers of one guard cannot both win. Both paths also read the same
`payouts-enabled` switch, so a freeze still freezes. Recorded in H.5; the fix is to make that method public, or
extract one submission port, and delete `PayoutResubmission`.

## What was deliberately not done

- **`UnknownOutcomeScaleIT` / M8(b).** M8(b) is 10,000 charges at `timeout_after_commit_rate=0.2`; it belongs to the
  long-running CI job. It was **not** written and **no such run was made**, so **M8(b) is Not run, not met**. The
  12-attempt case in `QuiesceNoStuckAttemptsIT` asserts the same per-attempt property at 1/800th of the volume and
  must not be read as evidence for M8(b).
- **The restart test.** Killing the context with attempts in `SUBMITTING` and `UNKNOWN` and asserting they resolve
  after restart was not written. The property it would prove is structural — the sweeper selects on status, not on
  who wrote it, and the schedule is recomputed from the append-only history rather than from memory — but structure
  is not evidence, so this is Not run.
- **The FakeBank duplicate-detected metric.** D05-5 asks for a counter when a lookup returns more than one payout.
  The adapter logs it but does not count it, and emitting it needs either a `LookupResult.Found` contract change in
  `instrument.core` or a `MeterRegistry` inside the adapters — both ripple past this task. The **safety** half is
  implemented and covered: any lookup that finds something forbids resubmission outright, so a duplicate can never be
  deepened into a third payout.
- **The real `services/fake-providers` fault knobs.** The verification asks for `PUT /admin/faults/{provider}`.
  instrument-service's tests do not depend on that module, and using it would mean a second Spring context and a
  second database container per class. `FakeProviderTruth` reproduces the wire contract the adapters actually parse
  and the two knobs that matter (`timeout_after_commit`, `reset_before_commit`), in-process. The real knobs remain
  the path for live Compose verification.
- **`provider_call_seconds`.** Left blocked in the registry: it times every adapter call and belongs to the S05-T05
  adapter path, not to the recovery sweep.

## Negative control

`BUILD SUCCESSFUL` is not evidence, and neither is a suite that has only ever passed. The three integration failures
during development were each traced to a cause and fixed rather than accommodated — the clock wiring above, and two
tests of mine that were wrong rather than the code: one asserted a process-wide gauge was globally zero while another
test legitimately held an attempt in `NEEDS_REVIEW`, and two waited for a schedule boundary half an hour away,
because the master's gaps widen by design as an attempt ages.
