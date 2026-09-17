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

## Not delivered, and what that costs

- **S05-T03 (fault knobs, webhook sender) — deferred.** There is no `PUT /admin/faults/{provider}`, no
  `GET /admin/truth`, and **nothing delivers webhooks**. The `provider_events` table is written on every terminal
  outcome, so enabling delivery later needs no schema change, but today an adapter would learn a payout's fate only
  by lookup. Consequence: the fault matrix F6–F8 cannot be exercised, and tests read ground truth from the database
  directly instead of through an admin endpoint.
- **S05-T05 onward — not started.** No adapters implement `PaymentInstrument` yet, so **M7 is not claimed**: the
  interface exists and is tested against a stub, which is not the same as two real providers behind one contract.
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
