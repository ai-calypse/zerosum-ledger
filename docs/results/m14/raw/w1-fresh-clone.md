# w1-fresh-clone — seeded scenario run

<!--
decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness
Written by tools/simulator. Do not edit by hand: re-run the tool with the seed below, which reproduces the same orders and recaptures the provenance block.
-->

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | w1-fresh-clone |
| Type | verification (seeded scenario run) |
| Owning step and task | M13 (a), M13 (c) — evidence harness |
| Date (UTC) | 2026-09-18 |

## 2. Status

- **Measured**

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | `eb8b1636d1e0e11188d02ccfa0fd0de79dbb4131`; working tree clean (0 changes) |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at the SHA above. Runtime-reported: JVM OpenJDK 64-Bit Server VM 25.0.4.1 (Eclipse Adoptium) |
| Seeds | 4273 |
| Hardware | Mac16,12, 10 cores, 24.0 GiB RAM (25769803776 bytes), Mac OS X 26.5.2, aarch64 |

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | not captured by the tool: it speaks HTTP to two services and does not know how they are hosted |
| Docker VM CPUs / memory | not captured; see above |
| Emulated images (non-native architecture) | not captured; see above |
| Other load on the host during the run | not captured. The apply times below are wall-clock observations, not an isolated measurement, and must not be read as a latency benchmark |

## 5. Scenario, workload and seeds

W1 (master §1.4): each run completes trips through the real order API. Every order is a COMMERCE `trip.completed` whose entries are a rider receivable (debit), a driver payable (credit) and platform revenue (credit), summing to zero in the run's currency.

Target: order-service `http://127.0.0.1:8081`, ledger-service `http://127.0.0.1:8082`.

Parameters: **1 runs × 3 trips**, commission 2000 bps, currency USD. Orders come from `TripSequenceGenerator` (D01-10, ADR-0009); riders and drivers are namespaced by the run seed so repeated runs never share an entity, while `platform:main` is deliberately shared, as it is in production.

Base seed **4242**; run *r* uses `seed + 31*r`. Every seed: 4273.

## 6. Exact commands

```sh
./gradlew :tools:simulator:run --args="--scenario w1-trip-completed --runs 1 --seed 4242 --env-file /private/tmp/claude-501/-Users-yakshgandhi-Documents-Development-fintech-blogs/8d2f12f4-2483-4d36-9402-1628da230ac5/scratchpad/zsl-fresh/.env --out /private/tmp/claude-501/-Users-yakshgandhi-Documents-Development-fintech-blogs/8d2f12f4-2483-4d36-9402-1628da230ac5/scratchpad/m14a-out --label w1-fresh-clone"
```

## 7. Raw data

[w1-fresh-clone.json](w1-fresh-clone.json) — the same run, with every order's idempotency key, order id, response class and observed apply time.

## 8. Results

| Run | Seed | Orders | Created | Replayed | Applied | Max observed apply |
|---|---|---|---|---|---|---|
| 1 | 4273 | 3 | 3 | 0 | 3/3 | 281 ms |

Driver balances, checked against the sum of each run's own deltas:

| Run | Entity | Account | Expected | In the ledger | Match |
|---|---|---|---|---|---|
| 1 | driver:D0s10b1 | payable | -95816 | -95816 | yes |

Ledger's own invariants endpoint after the last run: `consistent = true`, `unresolved_quarantined_count = 0`.

**Overall: PASS**

## 9. Gate or threshold compared against

M13 (a) in [master §3.1](../../zerosum_ledger_mvp_plan.md#must-have): a single command runs a named scenario for N seeded runs and writes JSON + Markdown to `docs/results/`. **Pass**

## 10. Deviations and limitations

- **Apply time is a polling observation, not a latency measurement.** It is the wall-clock gap between the POST returning and the first successful balance poll, so it is quantised to the poll interval and includes this tool's own scheduling. P2 and T1 in S07 are the latency evidence; these figures are not.
- **`platform:main` is not balance-checked.** It is shared across every run, so it has no expected value here. The global per-currency sum covers it, and that is `tools/verifier`'s I2 — run it after this.
- **The ledger's invariants line is the service's opinion of itself**, read from its own endpoint. The independent check is `tools/verifier`, which reads the database directly as the read-only role.
- **One scenario shape.** W1 only: no adjustments, refunds, payouts or provider path, and no injected faults. Those are S08's ablations and fault matrix, which are not built.
