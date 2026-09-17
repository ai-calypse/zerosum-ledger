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

- **S05-T03 (fault knobs, webhook sender) — deferred at the time of writing; delivered on 2026-09-17.** See
  [S05-T03 below](#s05-t03) for what now exists and what is still missing. The paragraph that followed here said
  there was no `PUT /admin/faults/{provider}`, no `GET /admin/truth` and nothing delivering webhooks; all three now
  exist, and the fault matrix F6–F8 has the knobs it needs.
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

<a id="s05-t03"></a>

## S05-T03 — fault knobs, seeded randomness, signed webhooks, ground truth

Date: 2026-09-17. Branch: `worktree-agent-ad00029d875b5be44`.

### Measured

Force-executed (`--rerun-tasks`), counts read from `services/fake-providers/build/test-results/*/TEST-*.xml`, not
from the build's exit status — this build sets `failOnNoDiscoveredTests = false`, so a green build proves nothing
about whether tests ran.

| Task | Suite | Tests | Failures | Skipped |
|---|---|---|---|---|
| S05-T03 | `WebhookSignerTest` (unit) | 6 | 0 | 0 |
| S05-T03 | `AdminSecurityIT` (integration) | 8 | 0 | 0 |
| S05-T03 | `FaultKnobDeterminismIT` (integration) | 6 | 0 | 0 |
| S05-T03 | `WebhookSenderIT` (integration) | 5 | 0 | 0 |
| S05-T03 | `DemoPublicAdminIT` (integration) | 2 | 0 | 0 |
| S05-T01/T02 (regression) | `FakeCardIT`, `FakeCardIdempotencyIT`, `FakeBankIT`, `FakeBankLifecycleIT` | 16 | 0 | 0 |

fake-providers integration total: **37 tests, 0 failures, 0 skipped**. The 16 tests from S05-T01/T02 are unchanged
and still pass. Project-wide unit layer, force-executed: **240 tests, 0 failures, 4 skipped** (the same four
settlement-report and webhook-parsing assumptions described above).

### What now exists

- **`PUT /admin/faults/{provider}`** replaces a provider's knob profile whole and persists it in `fault_profiles`:
  `latency_p50_ms`, `latency_p95_ms`, `http_500_rate`, `reset_before_commit_rate`, `timeout_after_commit_rate`,
  `webhook_duplicate_rate`, `webhook_reorder_rate`, `webhook_drop_rate`, `return_rate`,
  `simulated_banking_day_seconds`, `max_processing_delay_ms`, `seed`. Rates outside [0, 1], negative delays, unknown
  field names and a `max_processing_delay_ms` at or above the ADR-0010 quiet period are refused with 400 naming the
  field, and the previous profile stays active.
- **Seeded randomness**: one `L64X128MixRandom` stream per provider *and decision*, re-derived on every activation.
  Same seed plus same request order gives identical outcomes; that is asserted, not asserted-by-inspection.
- **Signed webhook sender** draining `provider_events`: `ZS-Signature: t=…,v1=…`, HMAC-SHA256 over `<t>.<raw body>`,
  duplicate/drop/reorder simulation, redelivery on 1s/5s/30s/2min/10min, `delivered_at` set only after a 2xx.
- **`GET /admin/truth?entity_id=`** returns charges, refunds and payouts, the injected-fault log (§0.3 E2) and the
  webhook redelivery queue (§0.3 E3). Admin token only, and the whole controller is absent under `demo-public` (TB4).

**Knob → test.** `http_500_rate`, `timeout_after_commit_rate`, `reset_before_commit_rate`, `latency_p50_ms`/`p95`,
`return_rate`, `simulated_banking_day_seconds` and `seed` in `FaultKnobDeterminismIT`; `webhook_duplicate_rate`,
`webhook_drop_rate` and `webhook_reorder_rate` in `WebhookSenderIT`; `max_processing_delay_ms` is exercised in
`FaultKnobDeterminismIT.bankKnobsDriveTheLifecycle` and its ADR-0010 bound is asserted in `AdminSecurityIT`.

### Two defects the tests caught

- **A rejected knob payload did not say what was wrong.** Boot's default error body carries the status and path but
  not the reason, so `PUT /admin/faults/{provider}` answered a bare 400 — the spec's edge case is 400 *with the
  offending field*. `server.error.include-message` changed nothing under Boot 4.1. Fixed with an
  `AdminExceptionHandler` scoped to the admin package, so the provider APIs' error shapes are untouched.
- **A "reset" the client noticed a minute later, and a latency fault that was never logged.** Aborting by promising
  a body and returning normally left the connection open until a socket timeout: measured at **60.4 s**, against
  0.041 s now that the filter throws once the headers are out. Promising a single byte was also not enough — the
  container truncated its own error page to exactly that byte and the client read a valid response. Separately,
  latency was routed through the rate-comparison path, which has no rate for it, so every injected delay was missing
  from the fault log; `FaultProfiles.record` now logs sampled faults directly.

### Not delivered

- **Restart mid-redelivery is not tested.** The schedule is in `provider_events` rather than in a timer, so it is
  persisted by construction, but no test stops and restarts the context to prove it.
- **No packet-level reset.** A servlet filter cannot send an RST; the fault is a premature end of stream, which the
  adapters classify identically. A real reset needs the `chaos` profile's toxiproxy (S08).
- **Nothing receives the webhooks yet.** `ZS_WEBHOOK_RECEIVER_URL` is deliberately unset in Compose: the receiver is
  S05-T11. The sender is inert until it exists, and events accumulate undelivered.
- **Settlement-report discrepancy knobs** (`report_missing_line_rate`, …) are S06-T01; `FaultKnobs` documents the
  extension point they are added through (§0.3 C23).
- **Reproducibility covers request order, not concurrent interleaving.** Two requests racing draw from a stream in
  whatever order they reach it.
