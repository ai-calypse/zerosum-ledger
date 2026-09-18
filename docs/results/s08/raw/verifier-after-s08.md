# verifier-after-s08 — ledger invariants I2, I3, I4

<!--
decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness
Written by tools/verifier. Do not edit by hand: re-run the tool, which recaptures the provenance block.
-->

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | verifier-after-s08 |
| Type | verification |
| Owning step and task | M13 (a), M13 (c) — evidence harness |
| Date (UTC) | 2026-09-18 |

## 2. Status

- **Measured**

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | `63324a0ce70d159744819856c607b2c5e5a6a1d4`; working tree clean (0 changes) |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at the SHA above. Runtime-reported: PostgreSQL PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on aarch64-unknown-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit, JVM OpenJDK 64-Bit Server VM 25.0.4.1 (Eclipse Adoptium) |
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

after-S08-crash-volume-and-ablation-runs



Target `jdbc:postgresql://127.0.0.1:5432/ledger`, connected as `verifier`, session `transaction_read_only = on`.

Scope actually examined: **entities=499 accounts=500 changelog_rows=56544 applied_orders=22598**. I2, I3 and I4 ran inside one read-only repeatable-read transaction,
so every figure below describes the same snapshot.

Seeds: none (non-generative evidence).

## 6. Exact commands

```sh
./gradlew :tools:verifier:run --args="--jdbc-url jdbc:postgresql://127.0.0.1:5432/ledger --env-file /Users/yakshgandhi/Documents/Development/fintech-blogs/zerosum-ledger/.claude/worktrees/agent-af4b3bebb1938eea4/.env --out /Users/yakshgandhi/Documents/Development/fintech-blogs/zerosum-ledger/.claude/worktrees/agent-af4b3bebb1938eea4/docs/results/s08/raw --label verifier-after-s08 --description after-S08-crash-volume-and-ablation-runs"
```

## 7. Raw data

[verifier-after-s08.json](verifier-after-s08.json) — the same run, with the full violation list per invariant.

## 8. Results

| Invariant | What it checks | Result | Violations |
|---|---|---|---|
| I2 | global per-currency sum of ledger balances is 0 | **PASS** | 0 |
| I3 | account balance = Σ changelog deltas, and balance_after_minor is a correct running sum | **PASS** | 0 |
| I4 | changelog seq is gapless per entity | **PASS** | 0 |

**Overall: PASS — every invariant checked holds**

## 9. Gate or threshold compared against

The invariant catalog in [master §8.3](../../zerosum_ledger_mvp_plan.md#invariants): I2, I3 and I4.
**Pass.**

## 10. Deviations and limitations

- **Three invariants of twelve.** I1, I5–I12 and R1 are not evaluated. I5 (the hash chain) needs the
  per-entity `verify` endpoint; I1 and I6–I12 are cross-store checks against the orders and instruments
  databases, which S06 owns and which is not built.
- **A snapshot, not a watch.** The result describes the instant the transaction opened. It is evidence
  about a quiesced ledger; run against a live one it says only that the books balanced at that moment.
- **The SQL is a copy** of ledger-service's `InvariantQueries` (D02-8), so that the verifier can audit a
  database whose service is not running. The two must be changed together.
