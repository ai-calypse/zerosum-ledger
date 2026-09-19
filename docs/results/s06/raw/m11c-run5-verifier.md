# m11c-run5-verifier — invariants I1, I2, I3, I4, I6, I6b, I7, I10, I12

<!--
decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness
Written by tools/verifier. Do not edit by hand: re-run the tool, which recaptures the provenance block.
-->

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | m11c-run5-verifier |
| Type | verification |
| Owning step and task | M13 (a), M13 (c) — evidence harness |
| Date (UTC) | 2026-09-18 |

## 2. Status

- **Measured**

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | `b1ec393e4269106d77f37330a678798477603b48`; working tree clean (0 changes) |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at the SHA above. Runtime-reported: JVM OpenJDK 64-Bit Server VM 25.0.4.1 (Eclipse Adoptium), PostgreSQL PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on aarch64-unknown-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit |
| Seeds | none (non-generative evidence) |
| Hardware | Mac16,12, 10 cores, 24.0 GiB RAM (25769803776 bytes), Mac OS X 26.5.2, aarch64 |

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | n/a — the verifier connects over JDBC and starts no containers |
| Docker VM CPUs / memory | n/a — as above |
| Emulated images (non-native architecture) | n/a — as above |
| Other load on the host during the run | not captured; the verifier only reads, and reports no timings that load could distort |

## 5. Scenario, workload and seeds

After M11(c) run rcba6de139 (m11c-run5), both settlement cycles quiesced.



Targets, each connected as `verifier`: `ledger` jdbc:postgresql://127.0.0.1:5432/ledger (`transaction_read_only = on`); `orders` jdbc:postgresql://127.0.0.1:5432/orders (`transaction_read_only = on`); `instruments` jdbc:postgresql://127.0.0.1:5432/instruments (`transaction_read_only = on`); `providers` jdbc:postgresql://127.0.0.1:5432/fakeproviders (`transaction_read_only = on`).

Ledger scope actually examined: **entities=257 accounts=259 changelog_rows=15030 applied_orders=6010**. Each database was read inside one read-only repeatable-read
transaction, and the transactions were opened back to back before any check ran.

Seeds: none (non-generative evidence).

## 6. Exact commands

```sh
./gradlew :tools:verifier:run --args="--jdbc-url jdbc:postgresql://127.0.0.1:5432/ledger --orders-jdbc-url jdbc:postgresql://127.0.0.1:5432/orders --instruments-jdbc-url jdbc:postgresql://127.0.0.1:5432/instruments --providers-jdbc-url jdbc:postgresql://127.0.0.1:5432/fakeproviders --checks I1,I2,I3,I4,I6,I6b,I7,I10,I12 --env-file /Users/yakshgandhi/Documents/Development/fintech-blogs/zerosum-ledger/.claude/worktrees/agent-adbc94b5cf6954827/.env --out /Users/yakshgandhi/Documents/Development/fintech-blogs/zerosum-ledger/.claude/worktrees/agent-adbc94b5cf6954827/infra/tests/build/evidence --label m11c-run5-verifier --description After M11(c) run rcba6de139 (m11c-run5), both settlement cycles quiesced."
```

## 7. Raw data

[m11c-run5-verifier.json](m11c-run5-verifier.json) — the same run, with every check's metrics and its sample of offending rows.

## 8. Results

| Invariant | What it checks | Result | Violations | Metrics |
|---|---|---|---|---|
| I1 | every money order is zero-sum per currency | **PASS** | 0 | orders_checked=6010, unbalanced_orders=0 |
| I2 | global per-currency sum of ledger balances is 0 | **PASS** | 0 | nonzero_currencies=0 |
| I3 | account balance = Σ changelog deltas, and balance_after_minor is a correct running sum | **PASS** | 0 | accounts_mismatched=0, running_sum_rows_wrong=0 |
| I4 | changelog seq is gapless per entity | **PASS** | 0 | entities_with_gaps=0 |
| I6 | order ids in the orders DB = applied_orders, and 0 unresolved quarantine rows | **PASS** | 0 | orders_in_store=6010, applied_orders=6010, missing_orders=0, extra_applied=0, unresolved_quarantined=0 |
| I6b | every ledger balance = Σ entries for that account across all orders in the order store | **PASS** | 0 | accounts_compared=259, accounts_drifting=0, drift_abs_minor=0 |
| I7 | provider ground truth ↔ attempts: one provider success per attempt, none unclaimed | **PASS** | 0 | attempts=3000, provider_success_records=3000, duplicate_charges=0, duplicate_minor=0, success_without_provider_record=0, provider_success_without_attempt=0, provider_success_on_failed_attempt=0 |
| I10 | 0 attempts in SUBMITTING/UNKNOWN older than 5 min; 0 NEEDS_REVIEW | **PASS** | 0 | stuck_over_5_min=0, needs_review=0 |
| I12 | every break matches an injected report discrepancy, and every injection has its break | **PASS** | 0 | reconciliation_runs=10, breaks=405, injected_discrepancies=405, explained_breaks=405, unexplained_breaks=0, undetected_injections=0 |

**Overall: PASS — every invariant checked holds**

No offending rows.

## 9. Gate or threshold compared against

The invariant catalog in [master §8.3](../../zerosum_ledger_mvp_plan.md#invariants): I1, I2, I3, I4, I6, I6b, I7, I10, I12 evaluated.
**Pass.**

## 10. Deviations and limitations

- **Not evaluated by this tool:** I5 (hash chain; needs the per-entity `verify` endpoint), I8, I9, I11
  and R1.
- **One snapshot per database, not one across them.** The four transactions open milliseconds apart, so
  the cross-store checks (I6, I6b, I7, I12) are evidence about a quiesced system only.
- **I7 reads provider ground truth from the fakeproviders database** (the tables `/admin/truth` reads),
  keyed by `client_reference` = attempt id; charges and refunds count when `SUCCEEDED`, payouts unless
  `FAILED`.
- **I12 explains a break only by an injected discrepancy** in the fault log of the mapped type, for the same
  provider reference in the same report. A break's `OPEN` status does not explain it.
- **The I2–I4 SQL is a copy** of ledger-service's `InvariantQueries` (D02-8), so that the verifier can
  audit a database whose service is not running. The two must be changed together.
