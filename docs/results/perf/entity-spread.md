# Per-order apply throughput across N platform entities (SP4 option (b), workload-level emulation)

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | SP4-b-emulation (entity spread) |
| Type | performance |
| Owning step and task | S07-T06 (measurement only; no sharding was implemented) |
| Date (UTC) | 2026-09-18 |

## 2. Status

- **Measured**

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | Harness `86ece1bd3fca77a7970d0444d2d0fe1fe0083863`; working tree clean at launch (0 changes). Worktree base `98f336037ee846f337529951296ab2c883bb45b4`, older than `main`; the work merged since does not touch the ledger apply path measured here |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at that SHA. Runtime: PostgreSQL `postgres:18.6@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280`, JVM Eclipse Temurin 25.0.4.1+1 (Gradle toolchain), Gradle 9.7.1, Docker engine 29.8.0 |
| Seeds | `285598337035934583` (one study seed; every window derives its payload pool and order ids from it) |
| Hardware | Apple M4, 10 cores, 24 GiB RAM, macOS 26.5.2 (25F84), arm64. **A laptop, measured through the Docker Desktop VM** — the reference machine of [assumption A7](../../zerosum_ledger_mvp_plan.md#assumptions) |

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | 29.8.0. This study uses Testcontainers only; Compose was not invoked by it |
| Docker VM CPUs / memory | 10 CPUs / 8,319,504,384 bytes (7.75 GiB). PostgreSQL limited to 1536 MB per D00-3 |
| Emulated images | None; arm64 host, arm64 image |
| Other load on the host during the run | **Every window overlapped a concurrent chaos run on the same VM**: chaos M8(b) volume chunk 2 (~07:07–07:18 UTC) and chunk 3 (~07:18 onward) — FakeCard charges at `timeout_after_commit_rate=0.2` through another agent's Compose stack (8 containers). Per-window load from [2026-09-18-entity/vm-load.log](2026-09-18-entity/vm-load.log) is in §8.3: no Compose container restarted in any window; the stack used 13–26 % of one core during the N=1 block and 65–138 % of one core on average (peaks 99–234 %) during the N=10, 100 and 1,000 blocks |

## 5. Scenario, workload and seeds

**Question.** SP1 measured the worst case: every order locks `platform:main`, so every apply serialises on one row.
How does per-order throughput change when the platform side of each order is spread over N entities instead?

**Workload.** Balanced COMMERCE trips identical in shape to SP1's: a rider (pool 2,000), a driver (pool 500), and a
platform entity earning the commission. With **N = 1** the platform entity is literally `platform:main`, so that row is
**SP1's exact scenario**. With N = 10, 100 and 1,000 it is `platform:p0000`…, chosen round-robin by pool slot, which the
ledger auto-provisions (D02-5). This is the **workload-level emulation of SP4 option (b)** that S07-T04 instruction 2
asks for: it measures the lock-contention effect of sharding without implementing sharding, so it says nothing about the
cost sharding would add elsewhere (aggregated balance reads, D02-7; verifier aggregation, D06-5).

**Method (SP1's).** Per-order apply (batch size 1) through the same `LedgerApplyEngine` entrypoint the service uses, at
**32 writers** — SP1's ceiling writer count — each with its own connection. Payload shapes are pre-generated before
every window and each submission stamps in a fresh order id, so the client never builds JSON inside a window and never
submits a duplicate. **20 s warm-up, 45 s measured window, 3 repetitions per N**; a fresh durable database per N, so no
block inherits another's table size. I2–I4 after every window; I5 (full hash-chain sweep) once per block over every row
that block wrote. Durability is intact: `fsync` on, the D00-3 image, preload and 1536 MB limit.

Parameters: [services/ledger-service/src/test/resources/perf/perf.properties](../../../services/ledger-service/src/test/resources/perf/perf.properties).

## 6. Exact commands

```sh
git rev-parse HEAD; git status --porcelain | wc -l     # 86ece1b…, 0
# vm-load.log sampler as in e2e-latency.md §6, writing to docs/results/perf/2026-09-18-entity/vm-load.log
./gradlew :services:ledger-service:studyTest --tests '*ApplyThroughputStudy.throughputAcrossManyEntities' --rerun \
  -Pzs.perf.runLabel=2026-09-18-entity
```

JUnit XML: 1 test, 0 failures, 0 errors, 811 s.

## 7. Raw data

- [2026-09-18-entity/entity-spread-runs.json](2026-09-18-entity/entity-spread-runs.json) — one record per window with
  its UTC start and end, applied orders, orders/s, apply-batch p50/p95, lock-wait p50/p95, retries by class, duplicates,
  quarantines, I2–I5 and validity.
- [2026-09-18-entity/vm-load.log](2026-09-18-entity/vm-load.log) — every container on the VM, every 30 s.

## 8. Results

All 12 windows are valid data points: zero duplicates, quarantines, deadlock, lock-timeout, connection and
exhausted-retry events; I2–I5 zero.

> **Every figure below was measured under concurrent load on a shared VM** (chaos M8(b) chunks 2–3, §4) — **a
> conservative lower bound, not a clean figure.** Load can only take throughput away.

### 8.1 Per N (MEASURED, 32 writers, per-order)

| Platform entities | Rep 1 | Rep 2 | Rep 3 | **Median orders/s** | Range | Apply p50 / p95 ms | Lock wait p50 / p95 ms |
|---|---|---|---|---|---|---|---|
| 1 (`platform:main`) | 844.8 | 707.4 | 525.6 | **707.4** | 525.6–844.8 | 22.8 / 150.0 | 20.2 / 146.8 |
| 10 | 1,343.0 | 1,409.0 | 1,368.6 | **1,368.6** | 1,343.0–1,409.0 | 20.9 / 42.3 | 8.3 / 28.5 |
| 100 | 1,695.4 | 1,641.9 | 1,647.1 | **1,647.1** | 1,641.9–1,695.4 | 18.5 / 27.6 | 1.3 / 3.0 |
| 1,000 | 1,586.3 | 1,413.6 | 1,617.8 | **1,586.3** | 1,413.6–1,617.8 | 18.8 / 29.8 | 1.3 / 3.3 |

Apply and lock-wait columns are the median across repetitions of each window's own percentile.

### 8.2 What the curve shows

- **Spreading the hot entity removes the serialisation.** From 1 to 100 entities, median throughput rises **2.3×**
  (707.4 → 1,647.1 orders/s) and lock-wait p95 falls **~49×** (146.8 → 3.0 ms). The ranges at N=1 and N=100 do not
  overlap, so the direction is not noise.
- **Past ~100 entities the lock is no longer the limit.** N=1,000 (1,586.3, range 1,413.6–1,617.8) overlaps N=100's
  range; spreading further buys nothing. With lock wait p50 at 1.3 ms, what remains is the per-order transaction itself:
  apply p50 is 18.5 ms with 32 transactions in flight, and 32 / 18.5 ms ≈ 1,730 orders/s — Little's law agrees with the
  measured plateau. The next lever is fewer commits per order (batching, [batched-vs-per-order.md](batched-vs-per-order.md)),
  not more entities.
- **N=1 is unstable within its own block.** Its three repetitions fell steadily, 844.8 → 707.4 → 525.6, while the load
  log shows the Compose stack at only 13–26 % of one core for that block — lower than during any other block. The
  visible difference is the lock-wait tail (p95 117 → 147 → 236 ms). Table growth within the block is another candidate.
  Neither is proven here; the range is reported as measured.

### 8.3 Per-window load (from vm-load.log; compose stack CPU, % of one core)

| N | Rep 1 | Rep 2 | Rep 3 |
|---|---|---|---|
| 1 | 07:17:02–07:17:47, mean 13 % | 07:18:08–07:18:53, mean 14 % | 07:19:13–07:19:58, mean 26 % |
| 10 | 07:20:22–07:21:07, mean 66 % | 07:21:28–07:22:13, mean 98 % | 07:22:34–07:23:19, mean 87 % |
| 100 | 07:23:44–07:24:29, mean 81 % | 07:24:50–07:25:35, mean 138 % | 07:25:57–07:26:42, mean 96 % |
| 1,000 | 07:27:09–07:27:54, mean 105 % | 07:28:14–07:29:00, mean 126 % | 07:29:21–07:30:06, mean 114 % |

No Compose container restarted near any window.

### 8.4 MEASURED versus ESTIMATE and SP1

| Source | Label | Value | Basis |
|---|---|---|---|
| [master §6.5](../../zerosum_ledger_mvp_plan.md#bottlenecks) | **ESTIMATE** | 200–333 orders/s, per-order, one hot entity | A 3–5 ms lock hold |
| [SP1](../sp1-lock-study.md) | MEASURED (quiet VM) | 606.8 orders/s (577.5–665.8), 32 writers, one hot entity | 180 s windows, one shared database |
| This study, N = 1 | **MEASURED under load** | 707.4 orders/s (525.6–844.8) | Same scenario; 45 s windows, fresh database per block |
| This study, N = 100 | **MEASURED under load** | 1,647.1 orders/s (1,641.9–1,695.4) | Platform side spread over 100 entities |

The N=1 range straddles SP1's, so this study neither confirms nor contradicts SP1's ceiling; it is not a reproduction
(different window length, database lifetime and machine load). Both are far above the master's per-order ESTIMATE.

## 9. Gate or threshold compared against

[SP4 criterion](../../zerosum_ledger_mvp_plan.md#spikes) (sustain 500 orders/s at p95 order-to-balance ≤ 1 s): **not
applicable to this study alone** — it measures apply throughput, not order-to-balance latency at a sustained rate. It
shows that option (b) lifts the apply ceiling from ~700 to ~1,600 orders/s per-order under these conditions. Option (c)
(batching plus spread) was **Not run**.

## 10. Deviations and limitations

- **Concurrent load: every window.** All twelve windows overlapped chaos M8(b) chunks 2–3; measured under concurrent
  load on a shared VM, conservative lower bounds, not clean figures. The N=10–1,000 blocks overlapped the heavier part of
  that load (§8.3).
- **Emulation, not sharding.** No sharding was implemented, and reads that aggregate a sharded platform balance were
  not measured.
- **Shorter windows than the matrix** (45 s after 20 s warm-up, against the master's 180 s after 120 s for this kind of
  study). Repetitions and the median rule are unchanged.
- **Apply-only.** The Kafka hop and outbox are excluded; see [e2e-latency.md](e2e-latency.md) for the pipeline.
- **Per-order only, 32 writers only.** The spread was not combined with batching, and other writer counts were not
  measured.
