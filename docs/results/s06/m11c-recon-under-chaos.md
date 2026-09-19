# S06-M11c — reconciliation under A0 chaos, two settlement cycles per run

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | S06-M11c (master §3.1 M11 (c); invariant I12, with I1–I4, I6, I6b, I7, I10 at the end of every run) |
| Type | fault injection |
| Owning step and task | S06 (M11 (c), recorded "Not run" in [reconciliation.md](reconciliation.md) and [reconciliation-live.md](reconciliation-live.md)) |
| Date (UTC) | 2026-09-18, 23:25–23:59 |

## 2. Status

- **Measured**. There were 5 runs with distinct seeds, each run being 2 settlement cycles of 300 trips, all on one fresh stack. All 5 passed.

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | `b1ec393e4269106d77f37330a678798477603b48`, working tree clean (0 changes) for all 5 runs, as each run's provenance block records. The images were built from this commit (`up -d --build`) and the service jars were assembled at `5558a5a`. Only `infra/tests` changed between the two commits, so the service code is identical. `5558a5a` is `main` at `9726d41`, merged with this branch, which contains the SettlementReports race fix `1eb70c4`. |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at the SHA above. Runtime-reported image IDs: order-service `sha256:a5658d14…`, ledger-service `sha256:a1cc7d54…`, instrument-service `sha256:6261b12a…`, fake-providers `sha256:8bcadca5…` (full IDs in every run JSON). PostgreSQL 18.6, Kafka 4.3.1 (digests in [raw/m11c-host.txt](raw/m11c-host.txt)); test JVM OpenJDK 25.0.4.1. |
| Seeds | Run *k* uses base seed 1100000*k*. FakeCard's profile seed is `base × 10 + cycle`, which gives 110000011/110000012, 110000021/110000022, 110000031/110000032, 110000041/110000042 and 110000051/110000052. The trip fares and kill points are drawn from `SplittableRandom(cycle seed)`. Every cycle asserts that its seed had never been used on the stack (`fault_rows_for_seed_before = 0`). |
| Hardware | Apple Mac16,12 (M4), 10 cores, 24 GiB RAM, macOS 26.5.2, aarch64 |

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | Docker 29.8.0, Compose 5.5.1 |
| Docker VM CPUs / memory | 10 CPUs, 7.75 GiB (8,319,504,384 bytes) |
| Emulated images (non-native architecture) | none (aarch64) |
| Other load on the host during the run | Not controlled. The coordinator granted this agent the stack exclusively for the runs; other agents' builds may have run on the host. No timings here are gated. |

Containers run in UTC: `date` inside fake-providers and instrument-service printed UTC, and `TZ` is unset. This matters because both services compute a report's day window with the JVM default zone.

## 5. Scenario, workload and seeds

Test: [`infra/tests/src/test/java/dev/zerosum/infra/recon/ReconUnderChaosE2ETest.java`](../../../infra/tests/src/test/java/dev/zerosum/infra/recon/ReconUnderChaosE2ETest.java),
`@Tag("chaos")`. It runs only under `chaosTest`, never under `e2eTest`. The stack is `docker-compose.yml` +
`docker-compose.demo.yml` with fresh volumes, created once for the 5 runs.

**Per run:** register `tok_card_ok` cards for 50 fresh riders. Pick the latest two consecutive past UTC days that nothing on the stack occupies: no report, no reconciliation run, no charge and no attempt created on either. Then run two cycles, cycle 1 on the earlier day and cycle 2 on the later one.

**Per cycle:**

1. **Chaos profile on FakeCard**, under the cycle's own seed:
   - F7: `timeout_after_commit_rate` 0.2 and `http_500_rate` 0.05.
   - F8 knobs on FakeCard's webhooks: `webhook_duplicate_rate` 0.3, `webhook_reorder_rate` 0.3, `webhook_drop_rate` 0.1.
   - F10: `report_missing_line_rate`, `report_off_by_one_rate` and `report_duplicate_line_rate` at 0.05 each.

   The profile stays active through load, quiesce and the reconciliation run, so the report fetch itself draws FakeCard's 500s and lost responses.
2. **Load.** 300 seeded COMMERCE trips go through `POST /v1/money-orders`:
   - Fares are 500–3,499 minor units with a 20 % fee. Arrivals are open-model at 3 per second, from 8 callers.
   - In-flight work is capped at 120 so the collection policy's queue never overflows.
   - Each caller follows the F1 contract. On a transport error, a 5xx or a 409, it retries with the **same key and the same body** until it gets 201, or 200 as a replay.
   - Each trip becomes an attempt, a real FakeCard charge, and a `COLLECTION` order in the ledger.
3. **F3:** `docker kill -s KILL` of instrument-service once 25–40 % of the cycle's orders are posted. The kill waits, for at most 15 more orders, until one of the cycle's attempts is `SUBMITTING`, so it lands between a provider call and the state update it would have written. The container is restarted after 1.0–2.7 s (seeded) and waited on until healthy.
4. **F1:** `docker kill -s KILL` of order-service once 60–75 % of the orders are posted, then restarted the same way. Orders past that point are held until the kill has happened, so it cannot land after the last order. They are posted into a dead order-service and must retry their way through.
5. **Quiesce**, per [master §8.3](../../zerosum_ledger_mvp_plan.md#invariants). It is polled every ~2 s, and all of these must hold on **two consecutive polls**:
   - The callers have stopped.
   - Both outboxes are empty: `published_at IS NULL` counts 0 in orders and in instruments.
   - Consumer lag is 0, summed over every partition of every group from `kafka-consumer-groups.sh --describe --all-groups` inside the broker.
   - No attempt anywhere is in `CREATED`, `SUBMITTING`, `PENDING` or `UNKNOWN`.
   - No pending webhook redelivery: FakeCard/FakeBank `provider_events` with `delivered_at IS NULL` count 0.
   - Every one of the cycle's orders has its attempt.

   The maximum wait is 900 s. Not reaching quiesce fails the run as "not quiesced" and is never a pass.
6. **Close the day, simulated by backdating** both sides of this cycle only: its `payment_attempts.created_at` and its FakeCard `card_charges.created_at`. The method is [raw/recon_live.py](raw/recon_live.py)'s, with one difference. Every row moves by the same interval, but the interval is not a whole number of days: it maps the cycle's load start to 08:00 UTC of the target day. So a cycle that crossed a real UTC midnight would still land on one day, in its original order. The test asserts 0 moved rows fall outside that day.
7. **`POST /v1/reconciliation-runs`** for that day as admin. It is retried with the same idempotency key while it returns 5xx, because the report endpoint itself faults under the profile.
8. **Per-cycle I12.** Every break of the run is matched against the FakeCard fault-log injections for that report. The key is (mapped break type, provider reference), counted as a multiset. The type map is missing line → `MISSING_IN_REPORT`, off-by-one → `AMOUNT_MISMATCH`, duplicate line → `DUPLICATE_LINE`. A `MISSING_IN_REPORT` break has no report line, so its provider reference is the attempt's. A break's status never explains it.

**After both cycles:**
- FakeCard's profile is restored to `{"seed":0}` in a `finally` block.
- A final quiesce waits for the two `SETTLEMENT` orders to be applied.
- **`tools/verifier` runs in-process**, through its real entry point `VerifierMain.run`, over all four databases as the read-only `verifier` role, with `--checks I1,I2,I3,I4,I6,I6b,I7,I10,I12`. It must exit 0.

**What the run requires.** It fails unless all of the following hold:
- Every cycle quiesced.
- Every cycle had both kills, each with exit code 137.
- Every F7/F8 knob fired at least once (fault-log rows for the cycle's seed).
- Every cycle reconciled `COMPLETED` with more than 0 injections, **0 unexplained breaks, 0 undetected injections, and 0 duplicate successful charges for its attempts**.
- The verifier exited 0.

## 6. Exact commands

```sh
# the worktree has no .env of its own (gitignored); it points at the main checkout's
ln -s <main checkout>/.env .env
./gradlew assemble
docker compose -f docker-compose.yml -f docker-compose.demo.yml down -v
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build --wait     # fresh volumes, at b1ec393

for k in 1 2 3 4 5; do
  ./gradlew :infra:tests:chaosTest --tests '*ReconUnderChaosE2ETest' --rerun \
    -Dzs.recon.trips=300 -Dzs.recon.seed=1100000$k -Dzs.recon.label=m11c-run$k
done

# once more over the whole stack, from the command line, with the shared CLI contract
./gradlew :tools:verifier:run --args="--jdbc-url jdbc:postgresql://127.0.0.1:5432/ledger \
  --orders-jdbc-url jdbc:postgresql://127.0.0.1:5432/orders \
  --instruments-jdbc-url jdbc:postgresql://127.0.0.1:5432/instruments \
  --providers-jdbc-url jdbc:postgresql://127.0.0.1:5432/fakeproviders \
  --checks I1,I2,I3,I4,I6,I6b,I7,I10,I12 --env-file <worktree>/.env \
  --out <worktree>/docs/results/s06/raw --label m11c-verifier-final \
  --description after-the-five-M11c-evidence-runs-on-one-fresh-stack"

# the read-only SELECTs in raw/m11c-stack-final.txt, then:
docker compose -f docker-compose.yml -f docker-compose.demo.yml down -v

python3 docs/results/s06/raw/m11c_aggregate.py      # regenerates every table in §8
```

The runs were driven one at a time: runs 2–5 started only after the previous run's JUnit XML showed `failures="0"`.
JUnit `time` per run: 448.987, 330.461, 487.399, 304.445 and 348.825 s.

## 7. Raw data

All files are in [raw/](raw/).

| File | Contents |
|---|---|
| `m11c-run<k>.json`, k = 1…5 | Provenance block, then a summary with one object per cycle: profile, seed, load window, kills (exit code, attempts `SUBMITTING` at the kill, downtime, time to healthy), quiesce (every condition at the last poll, seconds waited), attempt statuses, attempts that went `UNKNOWN`, duplicate charges, the backdating shift and rows moved, report lines clean and served, the reconciliation response, breaks by type and status, injections by type, explained, unexplained and undetected, the mismatch sample (empty in all 10), and FakeCard fault-log counts by type. Also: the final quiesce, the verifier's exit code and its result lines. |
| `m11c-run<k>-verifier.json` / `.md` | The verifier's own output for that run, written by the tool. |
| `m11c-run<k>-TEST.xml` | JUnit XML. Each is `tests="1" skipped="0" failures="0" errors="0"`. |
| `m11c-verifier-final.json` / `.md` | The standalone CLI verifier over the whole stack after run 5. |
| `m11c-stack-final.txt` | The final read-only SELECTs (restored profile, orders by type, clearing balance, the ten runs, the signed break sum) and the `down -v`, verbatim. |
| `m11c-host.txt` | Docker, image and host details, and the containers' clock. Its `created=` values are container creation times, not image build times. |
| `m11c_aggregate.py` | Generates every table below from the files above. |

## 8. Results

Generated by `raw/m11c_aggregate.py`. "Explained" means matched to a fault-log injection of the mapped type, provider
reference and report.

### 8.1 Per cycle

| Run | Cycle | Report date | Trips | Post retries | Replays after retry | Kills (SUBMITTING at F3 kill) | Went UNKNOWN | Attempt statuses | Quiesce s | Report lines clean/served | Recon HTTP tries | Lines matched | Breaks | Injected (missing/off-by-one/dup) | Explained | Unexplained | Undetected | Duplicate charges |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | 2026-09-16 | 300 | 77 | 0 | 2 (2) | 17 | SUCCEEDED 300 | 39.1 | 300/301 | 2 | 269 | 43 | 11/20/12 | 43 | 0 | 0 | 0 |
| 1 | 2 | 2026-09-17 | 300 | 59 | 0 | 2 (1) | 17 | SUCCEEDED 300 | 178.6 | 300/306 | 1 | 277 | 40 | 11/12/17 | 40 | 0 | 0 | 0 |
| 2 | 1 | 2026-09-14 | 300 | 39 | 0 | 2 (1) | 21 | SUCCEEDED 300 | 49.2 | 300/302 | 1 | 271 | 44 | 13/16/15 | 44 | 0 | 0 | 0 |
| 2 | 2 | 2026-09-15 | 300 | 77 | 0 | 2 (1) | 11 | SUCCEEDED 300 | 63.4 | 300/294 | 1 | 273 | 36 | 15/12/9 | 36 | 0 | 0 | 0 |
| 3 | 1 | 2026-09-12 | 300 | 73 | 0 | 2 (1) | 13 | SUCCEEDED 300 | 184.9 | 300/287 | 1 | 259 | 50 | 22/19/9 | 50 | 0 | 0 | 0 |
| 3 | 2 | 2026-09-13 | 300 | 83 | 0 | 2 (1) | 18 | SUCCEEDED 300 | 65.6 | 300/302 | 2 | 276 | 36 | 10/14/12 | 36 | 0 | 0 | 0 |
| 4 | 1 | 2026-09-10 | 300 | 74 | 0 | 2 (1) | 14 | SUCCEEDED 300 | 32.8 | 300/299 | 1 | 268 | 50 | 19/13/18 | 50 | 0 | 0 | 0 |
| 4 | 2 | 2026-09-11 | 300 | 47 | 0 | 2 (1) | 14 | SUCCEEDED 300 | 49.3 | 300/297 | 1 | 267 | 44 | 14/19/11 | 44 | 0 | 0 | 0 |
| 5 | 1 | 2026-09-08 | 300 | 82 | 0 | 2 (1) | 24 | SUCCEEDED 300 | 71.9 | 300/298 | 1 | 280 | 31 | 13/7/11 | 31 | 0 | 0 | 0 |
| 5 | 2 | 2026-09-09 | 300 | 96 | 0 | 2 (1) | 21 | SUCCEEDED 300 | 61.6 | 300/299 | 2 | 277 | 31 | 9/14/8 | 31 | 0 | 0 | 0 |

- **Every break has one of the three injected types:** 146 `AMOUNT_MISMATCH` (status `UNEXPLAINED`), 122 `DUPLICATE_LINE` (`UNEXPLAINED`) and 137 `MISSING_IN_REPORT` (`OPEN`). No `TIMING`, `MISSING_IN_LEDGER`, `LINE_WITHOUT_SUCCESS`, `KIND_MISMATCH`, `CURRENCY_MISMATCH` or `REPORT_ARITHMETIC` break occurred in any cycle.
- **The counts close:** 3,000 clean lines − 137 dropped + 122 duplicated = 2,985 served lines, and 3,000 − 137 missing − 146 off by one = 2,717 matched. Both match the table.
- **F3 kills:** each landed with 1 or 2 of the cycle's attempts `SUBMITTING`, 11 in total over 10 kills. The F1 kills were not aimed and found 0–4. Every kill exited 137, and every restarted container was healthy 5.33–5.40 s after `docker start`.
- **F1 retries:** callers retried 707 times in total, all while order-service was down. **None of those retries was answered by a replay (200).** No kill landed on a request whose order had committed but whose response was lost; see §10.
- **The reconciliation POST needed 2 tries in 3 of 10 cycles:** the report fetch drew an injected fault, the POST returned 5xx, and the retry with the same key succeeded.

### 8.2 Faults fired per cycle (FakeCard fault log, this cycle's seed, from profile activation)

| Run | Cycle | timeout_after_commit | http_500 | webhook_drop | webhook_duplicate | webhook_reorder | report_missing_line | report_off_by_one | report_duplicate_line |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | 66 | 19 | 29 | 95 | 112 | 11 | 20 | 12 |
| 1 | 2 | 69 | 16 | 38 | 92 | 121 | 11 | 12 | 17 |
| 2 | 1 | 56 | 19 | 26 | 98 | 114 | 13 | 16 | 15 |
| 2 | 2 | 66 | 12 | 38 | 104 | 148 | 15 | 12 | 9 |
| 3 | 1 | 67 | 12 | 39 | 86 | 145 | 22 | 19 | 9 |
| 3 | 2 | 87 | 19 | 39 | 82 | 131 | 10 | 14 | 12 |
| 4 | 1 | 46 | 16 | 18 | 101 | 111 | 19 | 13 | 18 |
| 4 | 2 | 67 | 14 | 26 | 101 | 126 | 14 | 19 | 11 |
| 5 | 1 | 75 | 24 | 29 | 105 | 130 | 13 | 7 | 11 |
| 5 | 2 | 69 | 21 | 32 | 91 | 116 | 9 | 14 | 8 |
| **Total** |  | **668** | **172** | **314** | **955** | **1254** | **137** | **146** | **122** |

- `timeout_after_commit` and `http_500` are drawn per request to FakeCard: charge submissions, the resolver's idempotent retries, lookups and the report fetch. They are not drawn per trip.
- The webhook rows are per delivery attempt.
- In total, 170 attempts went through `UNKNOWN` (11–24 per cycle), and every one resolved to `SUCCEEDED`.

### 8.3 Per run — the verifier at the end of the run (cumulative over the stack)

| Run | Final quiesce s | Verifier exit | I6 missing/extra/quarantined | I6b drift (minor) | I7 duplicate charges | I12 breaks | I12 explained | I12 unexplained | I12 undetected | Wall s (first load to final quiesce) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 4.114 | 0 | 0/0/0 | 0 | 0 | 83 | 83 | 0 | 0 | 449 |
| 2 | 6.217 | 0 | 0/0/0 | 0 | 0 | 163 | 163 | 0 | 0 | 331 |
| 3 | 4.148 | 0 | 0/0/0 | 0 | 0 | 249 | 249 | 0 | 0 | 488 |
| 4 | 6.215 | 0 | 0/0/0 | 0 | 0 | 343 | 343 | 0 | 0 | 305 |
| 5 | 4.229 | 0 | 0/0/0 | 0 | 0 | 405 | 405 | 0 | 0 | 349 |

- **Result in every run:** each run's verifier JSON has `"result": "PASS"`, with all 9 checks `PASS` (I1, I2, I3, I4, I6, I6b, I7, I10, I12) and none failed or skipped.
- **The CLI run after run 5** ([raw/m11c-verifier-final.md](raw/m11c-verifier-final.md)) agrees:
  - 6,010 orders in the store, all 6,010 applied: 3,000 `COMMERCE`, 3,000 `COLLECTION`, 10 `SETTLEMENT`.
  - 0 drift over 259 accounts.
  - 3,000 attempts against 3,000 provider successes, 0 duplicates.
  - 10 reconciliation runs, 405 breaks = 405 injected = 405 explained.
  - All four sessions were `transaction_read_only = on`.

### 8.4 Totals over 5 runs, 10 settlement cycles

| Trips | Kills | Breaks | Injected | Explained | Unexplained | Undetected | Duplicate charges | Quiesce s median (min–max) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 3000 | 20 | 405 | 405 | 405 | 0 | 0 | 0 | 62.5 (32.8–184.9) |

**Observation, not a gate (I9 is not evaluated):** after run 5 the ledger's `provider:fakecard/clearing` balance was **16,997**. The signed sum of all 405 breaks is also 16,997: + our amount for each missing line, − the reported amount for each duplicate, + (ours − reported) for each amount mismatch. The residual left on the clearing account is exactly the money the reports disagreed about ([raw/m11c-stack-final.txt](raw/m11c-stack-final.txt)). No attempt was in flight at that point.

## 9. Gate or threshold compared against

- [Master §3.1 M11 (c)](../../zerosum_ledger_mvp_plan.md#must-have), "A0 chaos runs end with 0 unexplained breaks after 2 settlement cycles": **Pass** in 5 of 5 runs, with 0 unexplained breaks over 10 cycles.
- [Master §8.9 hard gate 4](../../zerosum_ledger_mvp_plan.md#go-no-go), 100 % detection of injected settlement discrepancies (I12): **Pass** over these runs. All 405 injections of all three types produced their typed break.
- The I6 and I7 part of this run's pass condition (no lost or extra orders, no unresolved quarantine, no duplicate charges): **Pass** in 5 of 5.
- Master §8.8 honesty: with 0 failures in n = 5 runs, the 95 % upper bound on the per-run failure probability is about 3/5 = **60 %** by the rule of three. That is the honest statement of what 5 runs show. It is far weaker than the master's 20-runs-per-fault design (≈ 15 %).

## 10. Deviations and limitations

- **Run size.** The master sizes a chaos run at 10,000 trips, with 20 runs per fault ([§8.8](../../zerosum_ledger_mvp_plan.md#sample-sizes)). Here there were 5 runs × 2 cycles × 300 trips, 3,000 trips in all. Nothing is extrapolated from that.
- **The day close is simulated by backdating,** as in [reconciliation-live.md](reconciliation-live.md): both sides of one cycle are moved into a past UTC day. The stack never ran across a UTC midnight, and FakeCard's own clock never closed a day.
- **Reconciliation was triggered by the test, not a scheduler.** The master's v1.2 quiesce wording asks for "reconciliation runs triggered on a schedule". No scheduler exists (the S06 cut in [scope-decisions](../../scope-decisions.md#s06-reconciliation--trimmed-to-its-spine-2026-09-17)), so a settlement cycle here is the test's call to `POST /v1/reconciliation-runs` for a closed day. Consequently no timing break was ever carried across cycles. None occurred, so the missing grace rule was not exercised either.
- **F8 is applied to FakeCard, not FakeBank.** The master's F8 names FakeBank's duplicate, reorder and drop knobs plus `return_rate`. This scenario moves no payouts, since FakeBank has no settlement reports to reconcile. So the same three webhook knobs were set on FakeCard at F8's rates, and `return_rate` was not exercised.
- **F1 is one kill per cycle, not one every 20–40 s.** No kill landed on an order that had committed but whose response was lost: 0 replays after 707 retries. The replay-after-kill path is shown instead by [S08-M4a](../s08/m4a-crash-recovery.md). This run shows only that callers retrying through a dead order-service lose and duplicate nothing (I6, I1).
- **F3 is a `docker kill` aimed at a moment with an attempt `SUBMITTING`,** not the master's breakpoint hook in the `chaos` profile, which does not exist. Each F3 kill found 1–2 such attempts, but the evidence does not trace each one individually through the sweeper.
- **Quiesce stability** is two consecutive polls about 2–5 s apart, not a configurable window. Consumer lag is read from the broker CLI, not the admin client.
- **One stack for all 5 runs.** The runs are sequential on shared databases, not independent stacks. Each run's verifier is cumulative, so run *k*'s I12 covers runs 1…*k*. Each cycle's own I12 figures in §8.1 are scoped to its report.
- **Reproducibility is per seed and request order, not bit-exact under concurrency.** This is FakeCard's stated boundary (`FaultProfiles`). The 8 concurrent callers and the pipeline interleave differently on every run.
- **Two smoke runs preceded the evidence and are not part of it.** They ran on a separate stack, destroyed with `down -v` before the evidence stack was created.
  - Smoke 1 (60 trips per cycle, seed 90000001) passed every reconciliation check, 13/13 breaks explained. But it posted each cycle in about 1.5 s, and **neither kill fired**. That exposed a harness defect, fixed in `b931050`: paced arrivals, and holding orders past the F1 point until the kill.
  - Smoke 2 (150 trips per cycle, seed 90000002) passed with both kills in both cycles, 43/43 breaks explained. Its F3 kills found only 0 and 1 attempts `SUBMITTING`, which led to aiming F3 (`b1ec393`).
- **The report-generation race** (the loser of two concurrent first requests logged injections that were never served) was fixed in `1eb70c4` before these runs, with a regression test (`DiscrepancyKnobIT.theLosingDraftLeavesNoFaultLogRows`). Without the fix it would have made I12 report false "undetected" injections. In every cycle the injection count equals the break count, so no extra fault-log rows were written. For the 3 retried fetches the evidence does not record which fault each drew: an injected 500 fails before the report is generated, while a lost response fails after the report is stored.
- **Not evaluated by the verifier:** I5, I8, I9, I11 and R1. The clearing observation in §8.4 is not an I9 evaluation.
- Single host, simulated provider. These are environment results, not production claims.
