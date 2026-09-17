# S05 — payment instruments and fake providers: what was built and what was measured

Date: 2026-09-16. Branch: `step/05-instruments`.

This step was deliberately cut down; the cut and its consequences are in
[docs/scope-decisions.md](../../scope-decisions.md). This note records only what actually ran.

## Measured

All counts below are from the Gradle XML test results of the runs described, not from reading the code.

| Task | Suite | Tests | Failures | Skipped |
|---|---|---|---|---|
| S05-T04 | `PaymentInstrumentContractTest` (unit) | 13 | 0 | 0 |
| S05-T01 | `FakeCardIT` (integration) | 7 | 0 | 0 |
| S05-T01 | `FakeCardIdempotencyIT` (integration) | 3 | 0 | 0 |
| S05-T02 | `FakeBankIT` (integration) | 3 | 0 | 0 |
| S05-T02 | `FakeBankLifecycleIT` (integration) | 3 | 0 | 0 |
| S05-T05/T06 | `FakeCardContractTest` (unit, shared suite) | 9 | 0 | 2 |
| S05-T05/T06 | `FakeBankContractTest` (unit, shared suite) | 9 | 0 | 2 |
| S05-T06 | `InstrumentBoundaryTest` (ArchUnit) | 2 | 0 | 0 |

instrument-service unit total: **33 tests, 0 failures, 4 skipped**. The four skips are the shared suite's
settlement-report and webhook-parsing cases aborting on JUnit assumptions, once per adapter: `capability
'settlementReports' is not implemented: the report generator is S06-T01 (D06-1)` and `webhook receipt is deferred with
S05-T03: no sender exists and no HMAC secret is wired`. They are reported as skipped, never as passed, which is the
point of using an assumption rather than omitting the case — M7(a) names webhook parsing, so an absent case would have
hidden the gap instead of reporting it.

Integration total: **16 tests, 0 failures, 0 skipped**, against a real PostgreSQL 18.6 container initialized with the
Compose `infra/postgres` scripts, with the service connecting as `fakeproviders_app` — the same role it uses in the
running stack, so a missing grant fails in the test rather than in deployment.

**Concurrency burst size: 16** (`FakeCardIdempotencyIT.BURST`). Sixteen concurrent charges sharing one idempotency
key produced exactly one `card_charges` row. The guarantee comes from the primary key on
`(provider, idempotency_key)`, not from a check-then-insert, which is what makes it hold under concurrency rather
than only in a single-threaded test.

**Suite naming.** The step register names `FakeCardChargeIT`, `FakeCardIdempotencyIT` and `FakeCardRefundIT`. The
charge and refund cases are in one `FakeCardIT` here; the refund cases are
`refundsAreCappedAtTheCapture` and `declinedChargeCannotBeRefunded`.

## Project-wide verification at the end of S05

Both suites were **force-executed** (`--rerun-tasks`), not reported from Gradle's up-to-date cache, because a cached
pass proves only that nothing changed:

| Layer | Tests | Failures | Skipped |
|---|---|---|---|
| Unit (`test`, 7 modules) | 223 | 0 | 4 |
| Integration (`integrationTest`, 6 modules, 7m54s) | 180 | 0 | 0 |

The 4 skips are the settlement-report and webhook-parsing assumptions described above. Integration covers order-service (55),
ledger-service (88), libs/outbox (14), fake-providers (16), infra/tests (6) and the SP3 stack spike (1).

## A defect the contract suite caught

The shared suite's read-timeout case failed on both adapters the first time it ran, and the bug was real rather than a
test artifact. A read timeout surfaces from `RestClient.exchange` as an `UncheckedIOException` thrown while reading the
response body, **not** as the `ResourceAccessException` thrown while sending. The adapters caught only the latter, so a
timed-out charge propagated an exception instead of returning `SubmitResult.Unknown` — the single outcome the whole
design exists to produce, failing in the one situation it was built for.

Fixed at the root: every call routes through `ProviderHttp.post`/`get`, so both failure shapes are normalized there
into one `ProviderUnreachable` carrying a timeout flag. Five call sites were covered by one guard, and no adapter has
to remember which exception type to catch.

## Not delivered, and what that costs

- **S05-T03 (fault knobs, webhook sender) — deferred.** There is no `PUT /admin/faults/{provider}`, no
  `GET /admin/truth`, and **nothing delivers webhooks**. The `provider_events` table is written on every terminal
  outcome, so enabling delivery later needs no schema change, but today an adapter would learn a payout's fate only
  by lookup. Consequence: the fault matrix F6–F8 cannot be exercised, and tests read ground truth from the database
  directly instead of through an admin endpoint.
- **M7 is not claimed, despite both adapters existing and passing the shared suite.** The suite drives them against a
  JDK `HttpServer` stub, never against the running fake-providers service. Nothing here proves that
  `FakeCardInstrument` and the real FakeCard agree on a single field name: the stub's payloads were written by the
  same hand as the adapter, so they agree by construction rather than by evidence. S04 already demonstrated what this
  gap costs — three live-stack defects that 132 green tests could not see. **M7(b) is met**: the ArchUnit rule uses the
  master's own wording — no class *outside* the adapter package may depend on one — and it passes on the real classes
  while firing on a deliberate canary placed in the core package.
- **No cross-service integration test exists.** Closing the M7 gap needs instrument-service talking to a running
  fake-providers over HTTP, which is Compose-level work this cut does not include.
- **Settlement reports — declared only.** `PaymentInstrument.settlementReport` and the `SettlementReport` type exist
  because the interface needs them; no provider implements one. S06 owns the content.
- **Idempotency replay is byte-identical by construction**, storing the rendered response whole. This is asserted on
  the charge id and the `Idempotent-Replayed` header, not by comparing raw bytes.

## Gaps a reader should not mistake for passes

- **G2 is unevaluated.** Nothing in this step measured it.
- **M8(b), M8(c), M9, M10 are unmet** — they depend on the deferred resolver and reconciliation work.
- The lifecycle tests run with `banking-day=0s`, so they prove the transitions and the scheduler wiring, **not** any
  real timing behaviour. A payout's real-world delay is simulated, and the simulation is not evidence about
  production latency.
