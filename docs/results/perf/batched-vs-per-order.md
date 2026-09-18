# Batched versus per-order apply throughput (the ~5,000 orders/s ESTIMATE; SP4 option (a))

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | SP4-a (batched apply) |
| Type | performance |
| Owning step and task | S07-T06 (measurement only; no configuration was changed) |
| Date (UTC) | 2026-09-17 (interrupted run), 2026-09-18 (timestamped runs) |

## 2. Status

- **Measured**

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | Timestamped runs: harness `b463d78540e7cac4803fd970ea8d11ddd1e7307f`, working tree clean at launch. Interrupted run: harness `813eae8b07d65f30f9c6a51df17799e1a63f8131`, working tree clean at launch — identical measurement code, before per-window timestamps were added. Worktree base for both: `505fecc4d17499a8c3d78019c1c75b5de8242768`, older than `main`; the work merged since does not touch the ledger apply path |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at that SHA. Runtime: PostgreSQL `postgres:18.6@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280`, JVM Eclipse Temurin 25.0.4.1+1 (Gradle toolchain), Gradle 9.7.1, Docker engine 29.8.0 |
| Seeds | Interrupted run `6195560421540639378`; run A `-3349792232827965418`; run B `4537063549066979895`; entity-spread (the 32-writer per-order row) `285598337035934583` |
| Hardware | Apple M4, 10 cores, 24 GiB RAM, macOS 26.5.2 (25F84), arm64. **A laptop, measured through the Docker Desktop VM** — the reference machine of [assumption A7](../../zerosum_ledger_mvp_plan.md#assumptions) |

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | 29.8.0. These studies use Testcontainers only; Compose was not invoked by them |
| Docker VM CPUs / memory | 10 CPUs / 8,319,504,384 bytes (7.75 GiB). PostgreSQL limited to 1536 MB per D00-3 |
| Emulated images | None; arm64 host, arm64 image |
| Other load on the host during the run | **Every window in this report overlapped concurrent load from another agent on the same VM.** The interrupted run (2026-09-17, 22:09–22:59 UTC) ran while that agent was running `kill -9` cycles on order-service and a FakeCard volume run; it recorded no per-window timestamps, so no window in it can be shown clean. The 2026-09-18 runs overlapped chaos M8(b) volume chunks 3–5 (~07:18–07:55 UTC; FakeCard charges at `timeout_after_commit_rate=0.2` through that agent's Compose stack); per-window load from each run's `vm-load.log` is in §8 |

## 5. Scenario, workload and seeds

**Question.** The master ESTIMATES that per-order apply tops out at ~200–333 orders/s on the hot entity, and that
"batched apply at 100 orders per 20 ms transaction would lift the ceiling to about **5,000 orders/s**"
([§6.5](../../zerosum_ledger_mvp_plan.md#bottlenecks)). SP1 measured per-order only. This measures batch sizes 1, 10,
50 and 100 at 1 and 32 writers.

**Workload — SP1's, unchanged.** Balanced COMMERCE trips touching a rider (pool 2,000), a driver (pool 500) and
`platform:main` in **every** order: the worst case, one hot entity. A batch of N orders is one call to the same
`LedgerApplyEngine.apply` the Kafka listener uses, so it is one transaction that locks every entity the batch touches in
sorted order, applies all N orders and commits once.

**Method (SP1's).** Pre-generated payload shapes, a fresh order id stamped per submission (never a duplicate), each
writer on its own connection; **20 s warm-up, 45 s measured window, 3 repetitions**; a fresh durable database per
configuration block; I2–I4 after every window and I5 (full hash-chain sweep) once per block over every row it wrote. The
engine's timeouts are the production values (`lock-timeout 2s`, `statement-timeout 5s`, 10 attempts). Durability is
intact: `fsync` on, the D00-3 image, preload and 1536 MB limit.

**Which run measured what.** One grid run was killed partway (2026-09-17). The 2026-09-18 re-runs were split so each
could be committed as soon as it finished:

| Configuration | Source |
|---|---|
| 1 writer, batch 1 | run A (`2026-09-18-batch-a/`) |
| 32 writers, batch 1 | the entity-spread study's N=1 row — the identical configuration (`2026-09-18-entity/`) |
| 1 and 32 writers, batch 50 and 100 | run B (`2026-09-18-batch-b/`) |
| batch 10 (1 and 32 writers); every configuration a second time | the interrupted run (`2026-09-17-interrupted/`) — untimestamped, curve shape only |

Parameters: [services/ledger-service/src/test/resources/perf/perf.properties](../../../services/ledger-service/src/test/resources/perf/perf.properties).

## 6. Exact commands

```sh
git rev-parse HEAD; git status --porcelain | wc -l     # b463d78…, 0 (timestamped runs)
# vm-load.log sampler as in e2e-latency.md §6, one log per run directory
./gradlew :services:ledger-service:studyTest --tests '*ApplyThroughputStudy.batchedVersusPerOrderApply' --rerun \
  -Pzs.perf.runLabel=2026-09-18-batch-a -Pzs.perf.batchSizes=1 -Pzs.perf.batchWriters=1
./gradlew :services:ledger-service:studyTest --tests '*ApplyThroughputStudy.batchedVersusPerOrderApply' --rerun \
  -Pzs.perf.runLabel=2026-09-18-batch-b -Pzs.perf.batchSizes=50,100 -Pzs.perf.batchWriters=32,1
# interrupted run, harness 813eae8, full grid, killed after 18 of 24 windows
./gradlew :services:ledger-service:studyTest --tests '*ApplyThroughputStudy' --tests '*EndToEndLatencyStudy' --rerun
```

## 7. Raw data

- [2026-09-18-batch-a/batch-size-runs.json](2026-09-18-batch-a/batch-size-runs.json) and its `vm-load.log`
- [2026-09-18-batch-b/batch-size-runs.json](2026-09-18-batch-b/batch-size-runs.json) and its `vm-load.log`
- [2026-09-18-entity/entity-spread-runs.json](2026-09-18-entity/entity-spread-runs.json) (the N=1 row) and its `vm-load.log`
- [2026-09-17-interrupted/batch-size-runs.json](2026-09-17-interrupted/batch-size-runs.json) — no timestamps, no load log

Each record carries the window's UTC start and end (except the interrupted run), applied orders, orders/s, apply-batch
p50/p95, lock-wait p50/p95, retries by class, duplicates, quarantines, I2–I5 and validity.

## 8. Results

> **Every figure below was measured under concurrent load on a shared VM — a conservative lower bound, not a clean
> figure.** Load can only take throughput away. §8.4 gives each window's overlap.

### 8.1 Timestamped runs, 2026-09-18 (MEASURED, one hot entity)

| Writers | Batch | Rep 1 | Rep 2 | Rep 3 | **Median orders/s** | Range | Apply p50 / p95 ms (per batch) | µs per order at p50 | Lock wait p95 ms | Valid |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 668.9 | 388.1 | 527.1 | **527.1** | 388.1–668.9 | 1.74 / 2.86 | 1,741 | 0.23 | 3 of 3 |
| 1 | 50 | 3,577.8 | 3,298.9 | 3,457.8 | **3,457.8** | 3,298.9–3,577.8 | 13.3 / 20.9 | 266 | 0.42 | 3 of 3 |
| 1 | 100 | 3,157.8 | 3,242.2 | 3,673.3 | **3,242.2** | 3,157.8–3,673.3 | 26.4 / 46.3 | 264 | 0.70 | 3 of 3 |
| 32 | 1 | 844.8 | 707.4 | 525.6 | **707.4** | 525.6–844.8 | 22.8 / 150.0 | — | 146.8 | 3 of 3 (entity-spread N=1) |
| 32 | 50 | 3,981.1 | 3,981.1 | 4,191.1 | **3,981.1** | 3,981.1–4,191.1 | 122.5 / 2,334 | — | 2,311.6 | 3 of 3 |
| 32 | 100 | 3,140.0 | 3,522.2 | 3,786.7 | **not an operating point** | — | 196.0 / 3,972 | — | 3,912.6 | **2 of 3**: rep 1 quarantined a valid order (§8.3) |

Apply and lock-wait columns are the median across repetitions of each window's own percentile. "µs per order" is the
median batch duration divided by the orders it carried, shown for one writer, where it is the transaction's own cost
rather than queueing.

### 8.2 Interrupted run, 2026-09-17 (curve shape only — untimestamped, under load)

| Writers | Batch | Rep 1 | Rep 2 | Rep 3 | Median orders/s | Range |
|---|---|---|---|---|---|---|
| 1 | 1 | 708.2 | 791.9 | 755.1 | 755.1 | 708.2–791.9 |
| 1 | 10 | 2,502.4 | 1,933.3 | 1,927.3 | 1,933.3 | 1,927.3–2,502.4 |
| 1 | 50 | 2,342.2 | 4,020.0 | 4,041.1 | 4,020.0 | 2,342.2–4,041.1 |
| 1 | 100 | 4,557.8 | 4,293.3 | 4,357.8 | 4,357.8 | 4,293.3–4,557.8 |
| 32 | 1 | 849.8 | 799.2 | 770.8 | 799.2 | 770.8–849.8 |
| 32 | 10 | 4,121.1 | 4,091.3 | 3,896.9 | 4,091.3 | 3,896.9–4,121.1 |

All 18 windows valid, zero retries of any class. This run overlapped the other agent's `kill -9` cycles and volume run
and recorded no per-window times, so none of it is a quotable figure; it is shown because it is the only measurement of
batch 10, and because it agrees with the timestamped run on the shape of the curve.

### 8.3 The 32 × 100 configuration quarantined valid orders

At 32 writers with 100-order batches on one hot entity, the sorted `SELECT … FOR UPDATE` in `LedgerStore.lockEntities`
exceeded the 5 s `statement-timeout` **122 times**. PostgreSQL reports that as SQLSTATE 57014, which
`RetryClassifier.TRANSIENT_STATES` (`40P01, 40001, 55P03, 57P01, 57P03` and class `08`) does not include. The engine
therefore treated a lock-queue wait as a non-transient failure: **58 batches** fell into one-record-at-a-time isolation,
and **3 valid orders were quarantined** as `UNEXPECTED_DATABASE_ERROR` — the isolated single-record retry also waited more
than 5 s behind 31 writers each holding `platform:main` for a 100-order batch. One quarantine fell inside rep 1's measured
window, which makes that window invalid; two fell in warm-ups. Isolations per measured window: 32, 9 and 1.

No money was lost and I2–I5 held — a quarantined order is not applied at all — but a valid order sat in quarantine,
which on the Kafka path is a DLQ record and a `ledger_quarantined_total > 0` page. The 32 × 50 block and every
1-writer block had **zero** isolations and zero quarantines. Evidence:
[2026-09-18-batch-b/engine-warnings.log](2026-09-18-batch-b/engine-warnings.log) (every engine WARN line and the first
quarantine's stack).

**Scope.** The deployed listener is one consumer thread (D04-3), so the pipeline never has 32 concurrent apply
transactions on one entity; [e2e-latency.md](e2e-latency.md) shows one apply batch per ~62 ms relay cycle. This
configuration is reachable only if listener concurrency is raised while every order still touches one hot entity.

**Recorded cap (S07-T06 edge case).** On one hot entity, 32 concurrent writers × 50-order batches is the largest
configuration measured that stays inside the timeouts (lock-wait p95 2.3 s against a 5 s statement timeout — not a
comfortable margin); 32 × 100 is outside them. **Fixed after this study** by the first of the two candidate changes:
a 57014 raised while acquiring entity locks is now transient and retried
([CR-S07-01](../../scope-decisions.md#cr-s07-01--a-statement-timeout-while-queueing-for-entity-locks-quarantined-valid-money-fixed),
commit `87f0710`, with a regression test that fails without it). The study was **not re-run** after the fix, so the
32 × 100 windows stay invalid and no post-fix throughput is claimed for that configuration.

### 8.4 Per-window concurrent load

The coordinator's cross-check against the chaos agent's timeline places chaos M8(b) chunks 3–5 at ~07:18–07:55 UTC, so
**every 2026-09-18 window above overlapped it**. The per-window compose-stack CPU from each run's `vm-load.log` (% of one
core, mean of the 30 s samples) shows how uneven that load was:

| Configuration | Rep 1 | Rep 2 | Rep 3 |
|---|---|---|---|
| 1 × 1 | 07:31:22–07:32:07, 73 % | 07:32:28–07:33:13, 95 % | 07:33:33–07:34:18, 87 % |
| 32 × 50 | 07:35:22–07:36:07, 86 % | 07:36:29–07:37:14, 25 % | 07:37:38–07:38:23, 30 % |
| 32 × 100 | 07:39:01–07:39:47, 12 % | 07:40:09–07:40:55, 24 % | 07:41:18–07:42:04, 61 % |
| 1 × 50 | 07:42:36–07:43:21, 102 % | 07:43:43–07:44:28, 86 % | 07:44:50–07:45:35, 94 % |
| 1 × 100 | 07:46:10–07:46:55, 9 % | 07:47:17–07:48:02, 16 % | 07:48:24–07:49:09, 14 % |
| 32 × 1 | see [entity-spread.md §8.3](entity-spread.md), N=1 row | | |

**Low CPU is not a quiet machine.** The 1 × 100 block ran with the stack at 9–16 % of a core, yet its median (3,242.2)
is below the interrupted run's 4,357.8 for the same configuration. The load log records CPU and memory, not disk I/O,
and a single-writer batch is bound by its commit fsync on the disk the VM shares with the chaos agent's PostgreSQL. No
window here is presented as clean.

### 8.5 MEASURED versus ESTIMATE

| Source | Label | Value | Basis |
|---|---|---|---|
| [master §6.5](../../zerosum_ledger_mvp_plan.md#bottlenecks) | **ESTIMATE** | 200–333 orders/s, per-order | A 3–5 ms hot-entity lock hold |
| This study, per-order | **MEASURED under load** | 527.1 (1 writer) and 707.4 (32 writers) orders/s | §8.1 |
| [master §6.5](../../zerosum_ledger_mvp_plan.md#bottlenecks) | **ESTIMATE** | ≈ 5,000 orders/s, batched | 100 orders per 20 ms transaction |
| This study, batched | **MEASURED under load** | 3,242.2 (1 × 100), 3,457.8 (1 × 50), 3,981.1 (32 × 50) orders/s | §8.1 |

- **The per-order ESTIMATE is exceeded.** Every per-order median, even under load, is above 333 orders/s: the whole
  transaction takes 1.2–1.7 ms at one writer, against the 3–5 ms lock hold the estimate assumed.
- **The batched ESTIMATE does not hold as stated.** Its input nearly does — a 100-order transaction took 26.4 ms p50
  here (21.5 ms in the interrupted run) against the assumed 20 ms — but the best timestamped median is **3,981.1
  orders/s, about 80 % of 5,000**, and the best figure from any run is 4,357.8 (untimestamped, 87 %). These are lower
  bounds measured under concurrent load; whether a quiet machine reaches 5,000 is **not measured and not claimed**.
- **Batching is the lever, and it is large.** At one writer, 100-order batches carry **~6× the per-order throughput**
  (3,242.2 vs 527.1 timestamped; 4,357.8 vs 755.1 interrupted), because the per-order cost falls from ~1,740 µs to
  ~264 µs as one commit and one lock round-trip are shared across the batch.
- **Concurrency adds little on one hot entity, and costs latency.** At batch 50, 32 writers carry 3,981.1 against one
  writer's 3,457.8 (+15 %), while the batch p95 rises from 21 ms to 2.3 s: the batches queue on `platform:main`.

### 8.6 `ledger.apply.default-batch-size` is not read

`application.yml` sets `ledger.apply.default-batch-size: 1` and `LedgerApplyProperties` validates it, but
`LedgerApplyEngine` never reads it: `apply(records)` applies whatever list it is given. The Kafka listener hands each
poll (`max-poll-records: 500`) to it in one call, so **the deployed pipeline already applies in batches** —
[e2e-latency.md](e2e-latency.md) measured ~12.5 orders per apply transaction at 200 orders/s and ~33 at 500. SP4 option (a)
is therefore already in effect on the pipeline, bounded by the poll, not by this setting. The dead setting is worth
removing or wiring through a change request against D02-3; it was not changed here.

## 9. Gate or threshold compared against

- [SP1 criterion](../../zerosum_ledger_mvp_plan.md#spikes) (ceiling < 500 orders/s makes batched apply must-have): not
  re-decided here. Every per-order median in this study is above 500 orders/s (lowest 527.1, measured under load), which
  agrees with SP1's pass.
- [SP4 criterion](../../zerosum_ledger_mvp_plan.md#spikes) (sustain 500 orders/s at p95 order-to-balance ≤ 1 s): this
  study measures apply throughput, not sustained order-to-balance latency, so it is **not applicable alone**. Together
  with [e2e-latency.md](e2e-latency.md) (P2 p95 70.3 ms at 500 orders/s over 60 s windows) it indicates option (a) —
  already in effect — has ample headroom; the 10-minute sustained condition was **Not run**.

## 10. Deviations and limitations

- **Concurrent load: every window.** Measured under concurrent load on a shared VM, conservative lower bounds, not clean
  figures (§8.4). The interrupted run has no per-window times at all.
- **One invalid window.** 32 × 100 rep 1 quarantined a valid order and is not a data point; the configuration is reported
  as outside the engine's timeout envelope rather than given a median (§8.3).
- **Grid assembled from three invocations.** A killed run forced the split in §5. Each configuration's three repetitions
  come from one invocation against one fresh database, so no median mixes runs.
- **Batch 10 has only untimestamped data**; 1 and 32 writers are the only writer counts.
- **Shorter windows than the matrix** (45 s after 20 s warm-up, against 180 s after 120 s), with repetitions and the
  median rule unchanged.
- **Apply-only**, one hot entity, one currency. The Kafka hop and outbox are excluded; see [e2e-latency.md](e2e-latency.md).
