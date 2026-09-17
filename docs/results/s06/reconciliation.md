# S06 — settlement reports, reconciliation and typed breaks

Date: 2026-09-17. Branch: `worktree-agent-aafdb115b4c00c6df`.

This step was deliberately trimmed to its spine; the cut and what it costs are in
[docs/scope-decisions.md](../../scope-decisions.md#s06). This note records only what actually ran.

## Measured

Counts are read from `build/test-results/*/TEST-*.xml`, not from the build's exit status — this build sets
`failOnNoDiscoveredTests = false`, so a green build on its own proves nothing about whether tests ran.

| Task | Suite | Layer | Tests | Failures | Skipped |
|---|---|---|---|---|---|
| S06-T01 | `SettlementReportIT` | integration | 5 | 0 | 0 |
| S06-T01 | `DiscrepancyKnobIT` | integration | 5 | 0 | 0 |
| S06-T01 | `FakeCardContractTest` (shared suite, D05-10) | unit | 9 | 0 | **1** |
| S06-T02 | `SettlementMatcherTest` | unit | 13 | 0 | 0 |
| S06-T02/T03 | `ReconciliationRunIT` | integration | 6 | 0 | 0 |

**Project-wide, after the change** (`./gradlew test integrationTest --continue`, 8m 18s, BUILD SUCCESSFUL):

| Layer | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| Unit (`test`, 10 modules) | 339 | 0 | 0 | 3 |
| Integration (`integrationTest`, 7 modules) | 254 | 0 | 0 | 0 |

**The skip count legitimately dropped from 4 to 3.** Before this step the shared provider contract suite aborted its
settlement-report case on an assumption for *both* adapters. FakeCard now declares the capability and implements it,
so that case **runs** for FakeCard instead of being reported as not applicable: `FakeCardContractTest` went from 2
skips to 1 (webhook parsing only), and `FakeBankContractTest` keeps 2, because FakeBank genuinely has no settlement
reports and its `settlementReports` stays false. This is the intended effect of the change, not a suite that stopped
reporting a gap.

Against the quoted baseline of 326 unit (4 skipped) and 239 integration: unit is +13 (`SettlementMatcherTest`), and
integration is +16 (5 + 5 + 6). **One test of arithmetic does not reconcile and is not being smoothed over:** 254
measured minus 16 new implies 238 pre-existing integration tests, one fewer than the quoted 239. Nothing in this
change removes or disables a test, and every suite above is green; the one-test difference between the quoted
baseline and what this machine measures was not chased down.

## M11, criterion by criterion

| Criterion | Status | Evidence |
|---|---|---|
| (a) A FakeCard settlement report produces a SETTLEMENT order booking net cash and fees | **Met, with one stated gap** | `ReconciliationRunIT.settlementReportProducesASettlementOrder`: one run, one outbox row, payload valid against D01-8, and `report_id`, `currency`, `gross_minor`, `fee_minor`, `net_minor`, `event_id` and `order_group_id` all equal to golden `EV-O6` loaded from `libs/contracts`. **The gap:** the assertion stops at the outbox row. The booking of +net to `platform:main/cash`, +fee to `processing_fees` and −gross off `provider:fakecard/clearing` is performed by order-service's `PaymentEventMapper`, which implements the SETTLEMENT row and has its own golden test; **no end-to-end run was observed** in this step. |
| (b) Injected discrepancies each produce a typed break | **Met** | Each knob is proven to fire and to be recorded (`DiscrepancyKnobIT`), and each maps to its own break type (`SettlementMatcherTest`), asserted as the **type** rather than as a count. |
| (c) A0 chaos runs end with 0 unexplained breaks after 2 settlement cycles | **Not run** | No chaos orchestration exists, and no scheduler advances settlement cycles, so cycles cannot elapse without explicit API calls. Recorded unevaluated — neither met nor failed. |

## The knob-to-break map (D06-2)

Every discrepancy the simulator can inject maps to exactly **one** break type. This is the part of M11(b) worth
testing, because a matcher that returned a single generic "mismatch" would satisfy an assertion that merely counted
breaks while being useless to an operator — a missing line is chased with the provider, an amount mismatch is a fee
or rounding question, and a duplicate line is money claimed twice.

| Knob | Break type | Asserted in |
|---|---|---|
| `report_missing_line_rate` | `MISSING_IN_REPORT` | `SettlementMatcherTest.missingLineIsMissingInReport` |
| `report_off_by_one_rate` | `AMOUNT_MISMATCH` | `SettlementMatcherTest.offByOneIsAmountMismatch` |
| `report_duplicate_line_rate` | `DUPLICATE_LINE` | `SettlementMatcherTest.duplicateLineIsDuplicate`, `ReconciliationRunIT.typedBreakIsPersistedAndListed` |

Four further types — `MISSING_IN_LEDGER`, `KIND_MISMATCH`, `CURRENCY_MISMATCH`, `LINE_WITHOUT_SUCCESS` — and the
report-level `REPORT_ARITHMETIC` are covered by their own cases. `TIMING` exists so that an unresolved attempt is
left `OPEN` rather than called wrong, which is what stops a capture settling near a day boundary from raising an
alert on its own.

## Three defects this task surfaced

All three were found by running the code, not by reading it, and all three were silent.

**1. A Java text block ate the space before a SQL predicate.** The idempotency lookup was built as
`"... FROM reconciliation_runs WHERE """ + where`. Java strips trailing white space from **every** line of a text
block, so the content ended `...WHERE` and produced `WHEREidempotency_key = :key`. Every single run failed. The
symptom was a bare HTTP 500 with nothing in the log, because `BadSqlGrammarException` is not
`DataAccessResourceFailureException` and the problem-detail handler therefore never saw it. The separator is now
concatenated explicitly, and `ReconciliationRunIT` sets `server.error.include-message=always` so the next unhandled
exception names itself instead of costing another debugging cycle.

**2. `@Transactional` was inert on a self-invoked method.** `reconcile()` called `commit()` on `this`, so Spring's
proxy never applied and no transaction was active. This one fails loudly rather than silently — `OutboxWriter` is
`Propagation.MANDATORY` precisely so that an outbox row can never be written outside the transaction that records
the state it describes — but the annotation read as though it worked. Replaced with an explicit
`TransactionTemplate`, which also makes the "fetch the report first, *then* open the transaction" boundary visible
in the code rather than implied by an annotation.

**3. An unquoted `key: value` inside an OpenAPI description broke the spec.** A description containing
`` `Idempotent-Replayed: true` `` parsed as a nested mapping and made the whole file unreadable, which broke
`AttemptEndpointsIT`'s conformance check — a test from S05 catching a regression in a file S06 edited, which is what
that test is for.

## What this does not do

- **No reconciliation scheduler and no settlement-cycle grace rule.** Timing breaks are reported `OPEN` within their
  own run and never carried across cycles, so a break is never promoted to `UNEXPLAINED` by the passage of time.
  This is the direct reason **M11(c) is unevaluated**, and the scheduler is the first thing to add back.
- **No reconciliation metrics.** S07's reconciliation alert row and the Money-invariants reconciliation panel stay
  blocked, exactly as S07 recorded.
- **No verifier work.** `tools/verifier` still evaluates I2–I4 only (CR-S09-01). **I9** (clearing residual equals
  in-flight plus open breaks) and **I12** (every injected discrepancy has its mapped break) are therefore **not
  evaluated**: the knob-to-break map above is asserted by this step's own tests, not by an independent tool reading
  the fault log. That independence is the whole point of I12, and it does not exist yet.
- **No W5/W6 scenario files.** Blocked on the D05-12 catalog and runner, which the S05 cut deferred.
- **The provider in `ReconciliationRunIT` is a stub, not the running FakeCard container.** The adapter's fidelity to
  the real FakeCard wire format is the contract suite's job; the same gap S05 recorded for M7 applies here.
- **Reports are keyed to the UTC calendar date.** The simulated banking-day knob compresses FakeBank's payout
  lifecycle but does not compress FakeCard's settlement day, so tests close a day by backdating the provider's own
  rows rather than by waiting.
