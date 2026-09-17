# S05-T10 — payout runs: the path that sends money out

Date: 2026-09-17. Branch: `worktree-agent-a70ce08957b1150e7`.

## Measured

All counts are parsed from the Gradle XML results (`build/test-results/*/TEST-*.xml`) of the run described below.
This build sets `failOnNoDiscoveredTests = false`, so `BUILD SUCCESSFUL` on its own would prove only that nothing
ran — and a piped `./gradlew … | tail` reports *tail's* exit code, which has produced a false green in this
repository before. Gradle's own exit code was captured separately and is reported below.

| Suite | Layer | Tests | Failures | Skipped |
|---|---|---|---|---|
| `PipelineFreshnessTest` | unit | 4 | 0 | 0 |
| `PayoutRunIT` (top level + 6 nested groups) | integration | 14 | 0 | 0 |
| `PayoutKillSwitchIT` | integration | 1 | 0 | 0 |
| **Whole repository, unit layer** | unit | **351** | **0** | **1** |
| **Whole repository, integration layer** | integration | **279** | **1** | **0** |

The unit layer went from the measured baseline of 347 to 351, and the integration layer from 264 to 279. The
additions reconcile exactly: 4 unit and 15 integration, so no existing test was replaced or quietly dropped. The
single unit skip is still `FakeBankContractTest :: settlement reports`, unchanged.

**Gradle exit code was 1** (`BUILD FAILED`), from one failing test that this task did not touch — see
[the defect below](#defect-found-a-host-dependent-ledger-test). Every instrument-service suite passed: 59
integration tests, 0 failures, 0 skips.

Commands, exactly as run:

```sh
./gradlew test integrationTest --continue      # exit 1, see the defect below
./gradlew :services:instrument-service:integrationTest --rerun   # determinism re-run
```

## What the claims are asserted against

This is the path that sends money *out*, where a duplicate is money gone, so each claim is asserted against the
strongest witness available rather than against our own bookkeeping.

### M10(a) — at most one in-flight payout per (driver, currency)

**Met.** Enforced by the `one_inflight_payout` partial unique index from `V2__instruments_schema.sql`, which this
task uses rather than re-implements: the insert is `ON CONFLICT DO NOTHING` and there is deliberately **no**
"does this driver already have a payout?" query anywhere in the service.

Asserted **with concurrency, not a second sequential call**. Four runs with four distinct idempotency keys start
together against one funded driver:

- exactly **one** response reports `PAID`, and the other three report `SKIPPED_IN_FLIGHT`;
- the table holds exactly **one** payout attempt for that driver, and exactly one in the statuses the index covers;
- exactly one `PAYOUT_ACCEPTED` payment event exists, and a follow-up hold confirms no second attempt appears.

A sequential test would pass just as happily against a check-then-insert implementation — which is precisely the
implementation that pays a driver twice under load — so the sequential case is kept only as a readability check
alongside the concurrent one.

### M10(c) — refuse when the pipeline is stale, and when payouts are frozen

**Met.** Freshness is the **sum** of order-service's outbox oldest age (D03-7), the ledger's oldest unapplied age
(D04-5) and this service's own outbox oldest age (D03-5), each on its §0.3 C12 timestamp basis, refused above 5 s.

- A 9 s ledger age → `409 ledger_stale`, **no attempt created**, and the run recorded as `REFUSED` with its code.
- Three stages of 3 s each — **none individually over the threshold** — are also refused. This is the case that
  distinguishes a summed implementation from one that takes the worst stage, and it is asserted both through HTTP
  and as a pure unit test of the arithmetic.
- The ledger answering `status: error`, and order-service answering 503, are each refused with the same code:
  fail closed, because a stage that cannot be measured is not a stage that may be assumed healthy.
- Kill switch off → `409 payouts_disabled`, no attempt, and **the bank's own request count is unchanged** — "we
  did not write down a payout" and "we did not pay anybody" are different claims, and only the second is what a
  freeze promises.

### M10(b) — a returned payout re-credits the driver's payable through a new order

**Evidence produced; the mapping itself is not this service's.** instrument-service publishes facts only and never
creates money orders (ADR-0006, D03-6). The mapping from `PAYOUT_RETURNED` to a compensating order that credits
`driver/payable` and debits `platform:main/cash` lives in **order-service's payment-event mapper**, and is pinned
there by golden order O8 (`libs/contracts` manifest, `WorkedExampleGoldenTest`).

What is proved here is the half this task owns: a payout created by a real run, driven to `RETURNED` through the
real signed-webhook endpoint, emits

```
PAYOUT_ACCEPTED  →  PAYOUT_SETTLED  →  PAYOUT_RETURNED
```

each **exactly once**, in that order, each validating against the D01-8 schema. The `PAYOUT_RETURNED` payload
carries the driver's `entity_id`, the amount, `failure_code: R01`, a deterministic
`event_id = <attempt_id>:PAYOUT_RETURNED`, and **no** `source_order_id` — a payout comes from a run, not an order,
and D01-8 forbids the field. The ordering matters as much as the count: the ledger must see the payout settle
before it sees it come back, or it would be asked to reverse an order that was never created.

I did **not** observe the resulting COLLECTION/compensating order, because no order-service runs in these tests.
That remains order-service's own evidence.

## Deliberate limits of this evidence

- **Payment events are asserted as outbox rows, not as messages on Kafka.** These tests run with no broker
  (`zs.policy.consumer.enabled=false`), so what is proved is that each transition wrote its event in the same
  transaction. That the relay then delivers them to the topic is S05-T09's claim, proved against a real broker in
  `CollectionPolicyIT`; re-proving it here would cost a container per class.
- **order-service and ledger-service are stubbed** (`ServiceStub`, a JDK `HttpServer`). Their freshness and
  balance endpoints have their own tests; what is under test here is what a payout run *does* with the three ages
  and the balance. The stub does record the outbound `Authorization` header, so the reader-token wiring — which
  nothing else in this service exercises — is asserted rather than assumed.
- **No live-stack run.** Everything here is Testcontainers and stubs. Given this repository's record of defects
  that only a running stack revealed, the payout endpoint should be exercised against the real Compose topology
  before M10 is considered closed on deployment evidence.

## Defect found: a host-dependent ledger test

`LedgerOpenApiContractIT.theFreshnessResponseValidatesEvenWhenItCannotBeComputed` **fails on any machine where the
developer's Compose stack is running**, and it failed in this run.

The test asserts that `/v1/freshness` reports `status: error`, and its own comment states the premise: *"This
context has no broker (`ledger.consumer.enabled=false`, nothing at localhost:9092)"*. But `docker-compose.yml`
publishes the broker on exactly that address:

```
ports:
  - "127.0.0.1:9092:9092"
```

With the stack up, `lsof` shows Docker listening on `127.0.0.1:9092` and the port accepts connections, so
ledger-service's `AdminClient` reaches a **real** broker, computes a real lag, and correctly answers `ok`. The
assertion then fails on a premise the host has invalidated.

This is not caused by S05-T10: the change touches no file under `services/ledger-service/` and no module it
depends on, and every other test in that class passed. It is a pre-existing environment dependency in that test,
which would also make CI green and a developer's machine red for reasons unrelated to their change.

**Not fixed here**, deliberately: the fix belongs to whoever owns D04-5's evidence, and the honest repairs — point
the test at a port nothing can occupy, or assert the fail-closed path against an injected calculator rather than a
real socket — both change a test this task has no mandate over. Recorded rather than patched, because silencing it
would remove a real assertion about failing closed.

## Known limitation carried forward

A downward adjustment still in the pipeline can leave a driver negative after a payout (driver debt). This is
inherited from the master's payout policy and ADR-0004 and is recorded in H.5; the freshness check bounds how much
can be in flight but does not close the window. It is measured later as R1.
