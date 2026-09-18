# Architecture

What this system actually is, as built — not as planned. Where the two differ, this document follows the code and the
recorded evidence. The master plan ([docs/zerosum_ledger_mvp_plan.md](zerosum_ledger_mvp_plan.md)) is the proposal;
[docs/scope-decisions.md](scope-decisions.md) records every place the build deliberately diverged from it.

## The problem

A payments ledger has one job that cannot be compromised: money must not appear or disappear. Everything else —
throughput, latency, features — is negotiable. The design follows from that single constraint.

## Shape

```mermaid
flowchart LR
    client([client]) -->|POST /v1/money-orders| order[order-service]
    order -->|same transaction| outbox[(outbox table)]
    outbox -->|relay, after broker ack| kafka{{Kafka}}
    kafka --> ledger[ledger-service]
    ledger --> ledgerdb[(ledger + changelog)]
    instrument[instrument-service] -->|PaymentInstrument| fake[fake-providers]
    ledger -.->|OTLP| lgtm[(Grafana / Tempo / Prometheus)]
    order -.->|OTLP| lgtm
```

Each service owns its database, with a migrating owner role and a restricted runtime role. No service reads another's
tables.

## The decisions that shape it

| Decision | Why | ADR |
|---|---|---|
| Entries are signed; debits positive, credits negative | One convention end to end, so a sign error is a test failure rather than an accounting opinion | [0003](adr/0003-sign-convention.md) |
| Balance policy lives outside the ledger | The ledger records what happened; deciding whether a payout is allowed is a different question with different inputs | [0004](adr/0004-balance-policy-outside-ledger.md) |
| Per-entity locks, taken in sorted order | Two orders touching the same entities cannot deadlock if everyone locks in the same sequence | [0005](adr/0005-ledger-entity-locks.md) |
| order-service is the only writer | One writer means one place where the zero-sum rule is enforced | [0006](adr/0006-order-service-is-the-single-writer.md) |
| Partition key is the order group | Kafka orders within a partition, so an adjustment can never overtake the order it adjusts | [0007](adr/0007-partition-key.md) |
| Polling outbox, single relay | The API accepts orders while the broker is down; the row is marked published only after the broker acknowledges | [0008](adr/0008-polling-outbox-single-relay.md) |
| One `PaymentInstrument` interface, capabilities as data | Core code names operations, never providers, so a third adapter changes no state machine | [0010](adr/0010-payment-instrument-abstraction.md) |

All ten ADRs are Accepted. See [docs/adr/](adr/).

## Properties worth naming

- **Zero-sum is enforced twice**: by the validator, and by a deferred database constraint that fires at `COMMIT`. An
  insert that bypasses the application still cannot leave the books unbalanced.
- **Append-only is enforced against the owner too**, not just the runtime role. Privileges alone would leave the
  migrating role able to mutate history.
- **Idempotency is a database property.** The unique constraint is what makes a concurrent burst produce one row; a
  check-then-insert passes single-threaded tests and duplicates under load.
- **Uncertain outcomes are a first-class result.** A provider timeout is neither success nor failure. `SubmitResult`
  is sealed over Succeeded, Pending, Declined and Unknown, so a `switch` that forgets one stops compiling.
- **Failure reports absence, not zero.** A lag gauge that cannot be computed publishes NaN, because a gauge stuck at
  zero reads as perfect health.

## Acceptance criteria: M1–M14

Compiled from `docs/results/**`, `docs/scope-decisions.md` and test sources. **Plan text is never evidence.** A
criterion is MET only where a named test or recorded run demonstrates it.

**32 MET · 8 PARTIAL · 3 NOT MET · 1 UNKNOWN**, of 44 lettered sub-criteria. One of the three NOT MET is M11(c),
recorded as NOT RUN: neither met nor failed.

S08 moved four rows on measured runs against the live stack, each recomputed from its raw data before it was
promoted: M4(a) and M4(c) (a real `kill -9`), M8(b) (10,000 charges), and M14(b) (the third OpenAPI spec). M13(b)
stays NOT MET even though two ablations were emulated, because the switches the criterion names do not exist.

M13(a) and M11(a) moved to MET on 2026-09-18 only once each was observed against the running system: the W1
scenario through the real order API, and a settlement report followed all the way into the ledger. Neither was
promoted while its last hop ran against a stub.

| Criterion | Status | Evidence |
|---|---|---|
| M1(a) 10,000 seeded orders validate | MET | `ValidatorGenerativeTest`; `s01-t06-generative.txt` (5000 valid / 5000 invalid) |
| M1(b) JPY 0 and KWD 3 digits from the ISO table | MET | `CurrencyRulesTest`; `s01-t01-money-table.txt` |
| M1(c) ArchUnit: no float/double; BigDecimal only in fees | MET | `MoneyArchitectureTest` (each rule fires on a canary) |
| M1(d) Worked example O1–O7 and the O8 variant | MET | `WorkedExampleGoldenTest`; `WorkedExampleApplyIT` |
| M2(a) Mutation fails for app *and* owner role | MET | `OrdersImmutabilityIT` (42501 for both) |
| M2(b) Unbalanced insert fails at COMMIT | MET | `OrdersImmutabilityIT` (23514, raw JDBC) |
| M2(c) `adjusts_order_id` must exist | MET | `OrderStoreIT` |
| M3(a) Same key and body replays | MET | `MoneyOrderApiIT` (byte-identical body) |
| M3(b) Same key, different body → 422 | MET | `MoneyOrderApiIT` |
| M3(c) 50 concurrent, one key → one row | MET | `OrderStoreIT` (created=1, replayed=49) — store level; HTTP level used 20 |
| M3(d) Missing key → 400 | MET | `MoneyOrderApiIT` |
| M3(e) Keys never expire | **UNKNOWN** | No test asserts it; only source comments. A design property, not evidence |
| M4(a) `kill -9` → published within 5 s | MET | [m4a-crash-recovery.md](results/s08/m4a-crash-recovery.md): `docker kill -s KILL` of order-service with 20 committed, unpublished orders (proved from the database before each kill), 5 repetitions. **100 / 100** published after restart and applied once; last publish 126–148 ms after Spring's Started line, 3,750–3,822 ms after container start — inside 5 s by either reading. The window is held open by pausing Kafka, not hit by timing; warm restarts only |
| M4(b) Only the relay publishes | MET | `PublishPathArchitectureTest` (failed on real code first, forcing a fix) |
| M4(c) Duplicate publishes harmless downstream | MET | The M4(a) crash produced **real** duplicates: sends the killed producer had already written reached the broker, the restarted relay sent them again, and the topic held 21–24 copies of 20 orders per run (17 duplicates in all). `applied_orders` stayed at exactly 20 per run and the rider receivable at exactly 20 × fare. Reached through a real crash, not the D03-5 injection point CR-S04-02 asked for, which still does not exist |
| M5(a) 3× publish = same balances | MET | `DuplicateDeliveryIT` through a real broker. End-to-end variant not delivered |
| M5(b) No changelog `seq` gaps | MET | `s02-t03-stress.txt` (incl. 32×20,000 at 30% duplicates) |
| M5(c) Per-currency sum is 0 | MET | `InvariantsApiIT`; `sp1-runs.json` (0 violations, 12 windows) |
| M5(d) Balance = sum of deltas | MET | `VerifyApiIT` (a corrupted ledger is reported, not hidden) |
| M6(a) Changelog links order and idempotency key | MET | `ChangelogApiIT`; also asserted by `MoneyPathE2ETest` |
| M6(b) `verify` rebuilds and matches | MET | `VerifyApiIT`, `HashChainTamperIT`, independent Python reference |
| M6(c) Audit walk ≤ 3 calls | MET (manual) | Executed by hand against the live stack. **Not a test**, and not reproducible without re-running it |
| M7(a) Both adapters pass one shared suite | PARTIAL | `PaymentInstrumentContractSuite` passes for both, and its webhook-parsing case now **runs** rather than skipping (S05-T11). Still against a stub, never the real service, which is why this is not MET |
| M7(b) Provider-boundary ArchUnit rule | MET | `InstrumentBoundaryTest`; fires on a canary |
| M8(a) State × event table test | MET | `TransitionTableTest` (S05-T08): the state×event product is **generated, not hand-listed**, so adding a state or event without a table decision fails the build. Illegal transitions are logged and counted, asserted with a captured appender and a meter registry |
| M8(b) 10,000 charges at 0.2 timeout rate | MET | [m8b-card-timeout-volume.md](results/s08/m8b-card-timeout-volume.md): 5 seeded runs × 2,000 = **10,000** charges through the real money path at `timeout_after_commit_rate` 0.2. Every attempt has **exactly one** successful provider charge, 0 stray charges, every rider's receivable back to 0; all **1,946** injected timeouts matched one-to-one to a lost response, 0 unexplained. Recomputed from the per-attempt CSVs by the coordinator. The webhook settled every lost response before the resolver was needed; a separate 1,000-charge run with every webhook dropped sent 229 attempts to `UNKNOWN`, all resolved with one charge each, slowest 196.6 s |
| M8(c) Nothing stuck in UNKNOWN > 5 min | MET | `QuiesceNoStuckAttemptsIT`: after load stops nothing remains in SUBMITTING or UNKNOWN and every attempt was charged exactly once in provider ground truth. `ResolverFakeCardIT` (4) and `ResolverFakeBankIT` (4) cover idempotent retry, the two-condition quiet-period resubmission, and — the one that matters most — **a lookup that cannot answer never causes a resubmission**, however long the quiet period has passed |
| M9(a) Bad signature / stale timestamp → 400 | MET | `WebhookSignatureTest` (7) and `WebhookReceiverIT` (9): a forged, tampered, unsigned or stale delivery is **400 and writes nothing**; tampering by one digit fails; the 300 s tolerance is rejected in either direction; both secrets verify during rotation |
| M9(b) 30% duplicates + 30% reordering | PARTIAL | The **dedupe half** is evidenced: one event delivered many times, sequentially and concurrently, is recorded once and applied once (`WebhookReceiverIT`). The criterion asks for 30% duplicates *plus 30% reordering at volume*, and no reorder-rate run exists — the sender's reorder knob (S05-T03) has never been driven |
| M10(a) One in-flight payout per (driver, currency) | MET | Four payout runs racing for one driver produce **exactly one** payout, and the losers say so (`PayoutRunIT`). Enforced by the `one_inflight_payout` partial unique index — `OneInflightPayoutIT` shows the database itself refuses a second — not by an application check that two concurrent runs could both pass. Also observed live: a payout run returned 201 COMPLETED, one driver PAID, reaching PENDING with one payout in FakeBank's ground truth |
| M10(b) A returned payout re-credits the payable through a new order | PARTIAL | Settled then returned are each emitted **exactly once**, each valid against D01-8 (`PayoutRunIT`). Evidence stops at the payment event: the compensating order is order-service's mapper, pinned by golden O8, and no run has been observed producing it. Held at PARTIAL on the same standard as M11(a) |
| M10(c) Refuses with 409 `ledger_stale` when freshness exceeds 5 s | MET | `PayoutRunIT` and `PipelineFreshnessTest`: staleness above 5 s is 409 `ledger_stale` and creates no attempt; **three individually fresh stages that add up past 5 s are still refused** — the case that distinguishes a summed check from a max one; an unmeasurable stage fails closed with the same code; exactly 5 s still runs |
| M11(a) Settlement report → SETTLEMENT order | MET | Observed end to end on the running stack ([reconciliation-live.md](results/s06/reconciliation-live.md)): 20 real card charges through the production path, FakeCard's own report (not a stub), `201 COMPLETED` with 20 lines matched and 0 breaks, then a `SETTLEMENT` order in order-service (`cash +28,044 · processing_fees +1,456 · clearing −29,500`) applied by the ledger, leaving the provider's clearing account at exactly 0. A second caller with a different key got the same run back. The day close is **simulated by backdating** both sides of the run's own rows, as the ITs do. `ReconciliationRunIT` (6) covers the builder against golden O6 |
| M11(b) Injected discrepancies → typed breaks | MET | Each knob maps to its **own** break type — missing→`MISSING_IN_REPORT`, off-by-one→`AMOUNT_MISMATCH`, duplicate→`DUPLICATE_LINE` — and the tests assert the *type*, not the count, so a matcher that flagged everything as "mismatch" would fail (`SettlementMatcherTest` 13, `DiscrepancyKnobIT` 5). **Also observed against the running FakeCard**: 8 breaks of all three types, the report's totals settled as reported, and the 146 residual left on clearing and flagged by `/v1/invariants` ([reconciliation-live.md](results/s06/reconciliation-live.md)). [docs/results/s06/reconciliation.md](results/s06/reconciliation.md) |
| M11(c) Reconciliation under chaos | **NOT RUN** | No A0 chaos orchestration, and no scheduler advances settlement cycles, so "0 unexplained breaks after 2 cycles" cannot be evaluated. Neither met nor failed. |
| M12(a) One trace across the pipeline | PARTIAL | `s04-trace-propagation.md`: connected **by links**, deliberately not claimed as one parent-child trace |
| M12(b) Dashboards for flow, invariants, providers | PARTIAL | Flow and invariants dashboards load from the repository. There is **no providers Grafana dashboard**; provider state is visible only in the operator Explorer's provider panel, which is not what the criterion asks for |
| M12(c) Alert rules fire in a test | PARTIAL | 6 rules provisioned; **1 of 5 conditions observed firing** (outbox backlog, t+212 s under a real Kafka outage) |
| M13(a) One command runs a named scenario | MET | `./gradlew :tools:simulator:run --args="--scenario w1-trip-completed --runs 20 --seed 4242"` against the running stack: 20 seeded runs, 60 orders created and applied, driver balances matched, invariants consistent, **PASS**, on a clean tree ([w1-live-2026-09-18.md](results/m13/w1-live-2026-09-18.md)). The read-only verifier then checked I2–I4 over 61 applied orders: PASS ([verifier-live-2026-09-18.md](results/m13/verifier-live-2026-09-18.md)). `VerifierIT` (6) and `SimulatorStubRunTest` (6) cover the tools themselves |
| M13(b) Ablations A1–A4 | **NOT MET** | The S08-T03 ablation switches do not exist in `services/` or `libs/`, so no ablation runs as specified. Two are **emulated** and labelled as such ([m13b-ablations.md](results/s08/m13b-ablations.md)): A2 (no outbox, by deleting the crashed orders' outbox rows) lost 67 of 100 committed orders in 5 / 5 runs, and the ledger's own `/v1/invariants` still said consistent — only the cross-database count caught it; A4 (zero-sum triggers disabled in a rolled-back transaction) accepted an order summing to +1 in 5 / 5 runs, rejected with 23514 when enabled. A1 and A3 not run |
| M13(c) Results record hardware, versions, SHA, seeds | PARTIAL | The convention exists; SP1/SP3 and both M13 results comply. It is now *enforced by code*: `libs/evidence` captures the block — including whether the tree was dirty — and both tools write it, so it cannot be typed in stale. Still a convention, not a check: nothing fails a build for omitting it |
| M14(a) Fresh clone → W1 in ≤ 10 min | PARTIAL | **41.1 s** from `git clone` of the public repo to a completed W1 scenario (`make env`, `make up` until all 8 containers are healthy, one seeded run: PASS) ([fresh-clone.md](results/m14/fresh-clone.md)). **With warm caches**: Gradle compiled all 56 tasks from source, but its dependency cache, the JDK and Docker base images were already present. A cold machine was not timed, which is why this is not MET |
| M14(b) Architecture doc, ADRs, OpenAPI | MET | This document, 10 ADRs, and **three** OpenAPI specs (`openapi/order-service.yaml`, `ledger-service.yaml`, `instrument-service.yaml`), each checked against the running service by a conformance test |
| M14(c) Demo video | **NOT MET** | Not recorded |

### Two contradictions worth stating

1. **M12(a).** The S07 register ticks the exit criterion while its own evidence table records the single-trace check as
   "Not run". The executed evidence classifies the result as *connected by links*, which is weaker than the single
   trace M12(a) specifies. Recorded here as PARTIAL.
2. **M7(a).** Both adapters pass the shared suite, which is what the criterion asks for. But the suite drives them
   against a stub whose payloads were written by the same hand as the adapters, so they agree by construction rather
   than by evidence. `docs/results/s05/providers.md` states plainly that M7 is not claimed.

## What the tests actually cover

| Layer | Count | What it runs against |
|---|---|---|
| Unit | 360 (0 failed, 1 skipped) | No containers |
| Integration | 303 (0 failed, 0 skipped) | Real PostgreSQL and Kafka via Testcontainers |
| End-to-end | 1 (0 failed) | `MoneyPathE2ETest`, on the running Compose stack |
| Chaos | 4 (each 0 failed in its recorded run) | The running stack, destructively: `kill -9`, a paused broker, 10,000-charge fault volume, two ablation emulations. Tagged `chaos`, run only by `chaosTest`, never by `make demo`. XML per run in [results/s08/raw/](results/s08/raw/) |
| Study | measurement runs | Timeboxed performance windows (`studyTest`), output committed as evidence in [results/perf/](results/perf/perf-summary.md) |

Unit and integration counts are from `./gradlew test integrationTest --rerun-tasks` on 2026-09-18 (09:51–10:06 UTC,
commit `ee4be74`), with no Compose stack running, read out of
`build/test-results/*/TEST-*.xml` for the thirteen modules in `settings.gradle.kts` rather than from
`BUILD SUCCESSFUL`: this build sets `failOnNoDiscoveredTests = false`, so a green build is not by itself evidence that
anything ran. Earlier figures here went stale repeatedly, because parallel work makes a count true only for the tree it
was taken on. The eleven most recent integration tests cover the dashboard's read-only endpoints; the two before them are the
Explorer's safety-contract test and the CR-S07-01 regression test.

The single remaining skip is deliberate, and it is the only one left of four. The adapter contract suite aborts its
**settlement-report** case on an assumption naming the missing capability — for **FakeBank only**, which genuinely
has no settlement reports. S05-T11 removed the two webhook-parsing skips by implementing the capability, and S06
removed FakeCard's by implementing settlement reports. That is what a skip is for: it disappears when the gap
closes, rather than quietly passing all along.

**The e2e layer is one test.** It covers the money path only. Crash recovery, the provider path, fault injection and
reconciliation have no end-to-end coverage at all, and the e2e job runs only on a schedule or manual dispatch, never
on a pull request.
