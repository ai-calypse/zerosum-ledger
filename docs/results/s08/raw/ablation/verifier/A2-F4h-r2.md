# A2-F4h-r2 — invariants I1, I2, I3, I4, I6, I6b, I7

<!--
decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness
Written by tools/verifier. Do not edit by hand: re-run the tool, which recaptures the provenance block.
-->

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | A2-F4h-r2 |
| Type | verification |
| Owning step and task | M13 (a), M13 (c) — evidence harness |
| Date (UTC) | 2026-09-19 |

## 2. Status

- **Measured**

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | `41b0960d7d7a2d2151ae95731e4015c927fab4c5`; working tree clean (0 changes) |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at the SHA above. Runtime-reported: PostgreSQL PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on aarch64-unknown-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit, JVM Java HotSpot(TM) 64-Bit Server VM 26.0.1 (Oracle Corporation) |
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



Targets, each connected as `verifier`: `ledger` jdbc:postgresql://127.0.0.1:5432/ledger (`transaction_read_only = on`); `orders` jdbc:postgresql://127.0.0.1:5432/orders (`transaction_read_only = on`); `instruments` jdbc:postgresql://127.0.0.1:5432/instruments (`transaction_read_only = on`); `providers` jdbc:postgresql://127.0.0.1:5432/fakeproviders (`transaction_read_only = on`).

Ledger scope actually examined: **entities=251 accounts=251 changelog_rows=17937 applied_orders=5979**. Each database was read inside one read-only repeatable-read
transaction, and the transactions were opened back to back before any check ran.

Seeds: none (non-generative evidence).

## 6. Exact commands

```sh
./gradlew :tools:verifier:run --args="--jdbc-url jdbc:postgresql://127.0.0.1:5432/ledger --orders-jdbc-url jdbc:postgresql://127.0.0.1:5432/orders --instruments-jdbc-url jdbc:postgresql://127.0.0.1:5432/instruments --providers-jdbc-url jdbc:postgresql://127.0.0.1:5432/fakeproviders --checks I1,I2,I3,I4,I6,I6b,I7 --env-file /Users/yakshgandhi/Documents/Development/fintech-blogs/zerosum-ledger/.env --out /Users/yakshgandhi/Documents/Development/fintech-blogs/zerosum-ledger/infra/tests/build/evidence/ablation/verifier --label A2-F4h-r2"
```

## 7. Raw data

[A2-F4h-r2.json](A2-F4h-r2.json) — the same run, with every check's metrics and its sample of offending rows.

## 8. Results

| Invariant | What it checks | Result | Violations | Metrics |
|---|---|---|---|---|
| I1 | every money order is zero-sum per currency | **PASS** | 0 | orders_checked=6000, unbalanced_orders=0 |
| I2 | global per-currency sum of ledger balances is 0 | **PASS** | 0 | nonzero_currencies=0 |
| I3 | account balance = Σ changelog deltas, and balance_after_minor is a correct running sum | **PASS** | 0 | accounts_mismatched=0, running_sum_rows_wrong=0 |
| I4 | changelog seq is gapless per entity | **PASS** | 0 | entities_with_gaps=0 |
| I6 | order ids in the orders DB = applied_orders, and 0 unresolved quarantine rows | **VIOLATED** | 21 | orders_in_store=6000, applied_orders=5979, missing_orders=21, extra_applied=0, unresolved_quarantined=0 |
| I6b | every ledger balance = Σ entries for that account across all orders in the order store | **VIOLATED** | 39 | accounts_compared=251, accounts_drifting=39, drift_abs_minor=125988 |
| I7 | provider ground truth ↔ attempts: one provider success per attempt, none unclaimed | **PASS** | 0 | attempts=0, provider_success_records=0, duplicate_charges=0, duplicate_minor=0, success_without_provider_record=0, provider_success_without_attempt=0, provider_success_on_failed_attempt=0 |

**Overall: FAIL — I6, I6b violated**

Offending rows:

- **I6** (first 10 of 21): `not applied: 01a0b8e6-3f9a-7b06-b5f4-a450c2c04e08`, `not applied: 01a0b8e6-3fa5-7462-a4a1-ae363cf567b4`, `not applied: 01a0b8e6-3fae-7d92-a23b-6c33b3dc76b1`, `not applied: 01a0b8e6-3fb8-717f-b680-d4206f00be0c`, `not applied: 01a0b8e6-3fc4-70d1-a16b-3dbcd6721c2a`, `not applied: 01a0b8e6-3fcd-73ff-a058-29eeeef1dc0b`, `not applied: 01a0b8e6-3fd6-7600-9a9f-ecd50fdc2089`, `not applied: 01a0b8e6-3fe0-7255-921c-8bd07c78b1dd`, `not applied: 01a0b8e6-3feb-75d7-b24c-fb4ad0f2e919`, `not applied: 01a0b8e6-3ff4-79b8-a65d-cada5c918ff9`
- **I6b** (first 10 of 39): `rider:a2f4hr2r162/receivable/USD ledger=71700 orders=76435`, `driver:a2f4hr2d11/payable/USD ledger=-268787 orders=-271214`, `rider:a2f4hr2r189/receivable/USD ledger=89317 orders=93179`, `driver:a2f4hr2d18/payable/USD ledger=-264486 orders=-271316`, `driver:a2f4hr2d6/payable/USD ledger=-275080 orders=-278170`, `driver:a2f4hr2d29/payable/USD ledger=-302064 orders=-306633`, `rider:a2f4hr2r11/receivable/USD ledger=63885 orders=67511`, `rider:a2f4hr2r90/receivable/USD ledger=86549 orders=90817`, `rider:a2f4hr2r116/receivable/USD ledger=71032 orders=73192`, `driver:a2f4hr2d35/payable/USD ledger=-267407 orders=-273695`

## 9. Gate or threshold compared against

The invariant catalog in [master §8.3](../../zerosum_ledger_mvp_plan.md#invariants): I1, I2, I3, I4, I6, I6b, I7 evaluated.
**Miss.**

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
