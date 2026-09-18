# P2 — end-to-end order-to-apply latency across the real outbox → Kafka → apply path

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | P2 (partial: 60 s windows, see §10) |
| Type | performance |
| Owning step and task | S07-T05 |
| Date (UTC) | 2026-09-18 |

## 2. Status

- **Measured**

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | Harness `b463d78540e7cac4803fd970ea8d11ddd1e7307f`; working tree clean at launch (0 changes). Worktree base `505fecc4d17499a8c3d78019c1c75b5de8242768`, older than `main`: the payout and resolver work merged since does not touch the order → outbox → Kafka → ledger apply path measured here |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at that SHA. Runtime: PostgreSQL `postgres:18.6@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280`, Kafka `apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837`, JVM Eclipse Temurin 25.0.4.1+1 (Gradle toolchain), Gradle 9.7.1, Spring Boot 4.1.1, Docker engine 29.8.0 |
| Seeds | `-4567742916743277886` (one study seed; every window derives its arrivals and order ids from it) |
| Hardware | Apple M4, 10 cores, 24 GiB RAM, macOS 26.5.2 (25F84), arm64. **A laptop, measured through the Docker Desktop VM** — the reference machine of [assumption A7](../../zerosum_ledger_mvp_plan.md#assumptions) |

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | 29.8.0. This study uses Testcontainers only; Compose was not invoked by it |
| Docker VM CPUs / memory | 10 CPUs / 8,319,504,384 bytes (7.75 GiB). PostgreSQL limited to 1536 MB per D00-3 |
| Emulated images (non-native architecture) | None; arm64 host, arm64 images |
| Other load on the host during the run | **All six windows overlapped a concurrent chaos run on the same VM.** The coordinator's cross-check against the chaos agent's timeline: every window (07:06:43–07:15:26 UTC) overlapped **chaos M8(b) volume chunk 2** (~07:07–07:18 UTC) — FakeCard charges at `timeout_after_commit_rate=0.2` driven through order-service → instrument-service → fake-providers → webhooks on that agent's Compose stack (8 containers). [2026-09-18-e2e/vm-load.log](2026-09-18-e2e/vm-load.log) agrees: no Compose container restarted during any window, fake-providers and instrument-service rose to ≈ 1–5 % of one core, the whole stack used ≈ 7–30 % of one core per 30 s sample (≈ 1–3 % of the 10-CPU VM), and `kafka-1` reached 92 % of one core in one sample at 07:14:05. Low-intensity load, but not a quiet machine — see §10 |

## 5. Scenario, workload and seeds

**What is real.** The real `libs/outbox` writer and relay (`OutboxRelayLoop`, batch 500, poll interval 50 ms,
send timeout 10 s — the values in order-service's `application.yml`) against order-service's own migrated `outbox`
table; a real broker with the contracted **12 partitions**; the real ledger listener in a Spring context, applying each
poll batch (`max-poll-records: 500`) through the real engine. The producer uses `acks=all` and idempotence, as
order-service does. Nothing stands in for a pipeline stage.

**What is not.** order-service's HTTP layer: the harness appends to the outbox directly, one order per transaction,
which is exactly what the API does inside a POST. **P1 (client-observed acknowledgement) is not measured here.**

**Workload.** Balanced COMMERCE trips, each touching a rider (pool 2,000), a driver (pool 500) and `platform:main` —
the hot entity in every order, the worst case, as in SP1. Each order has its own order group, so records spread over
all 12 partitions. Arrivals are open-model at a constant rate across 4 appender threads; an arrival starting more than
50 ms late is counted, and a window with more than 1 % late arrivals is invalid.

**How latency is measured.** Both ends are PostgreSQL server timestamps from **one container**:
`outbox.created_at` in the `orders` database and `applied_orders.applied_at` in the `ledger` database. There is no
host-to-VM clock offset to estimate and no polling interval to subtract — unlike `tools/simulator`, which disclaims its
own apply time as "a polling observation, not a latency measurement". This is the master's own P2 definition,
[`applied_orders.applied_at − order created_at`](../../zerosum_ledger_mvp_plan.md#stage-budgets). The two edges:

- `created_at` is `now()`, the append transaction's *start*, slightly before the commit that lets the API answer: the
  figure **overstates** by that transaction's duration.
- `applied_at` is `clock_timestamp()` at the row insert *inside* the apply transaction, before the commit that makes the
  balance visible: the figure **understates** by the rest of that transaction. The engine's own `ledger_apply` timer
  bounds it: mean apply batch 7.7–8.3 ms at 200/s and 11.9–13.0 ms at 500/s (§8.1).

The stage split comes from the same rows: `published_at − created_at` is outbox wait plus relay send;
`applied_at − published_at` is the broker hop plus apply.

Parameters: [services/ledger-service/src/test/resources/perf/perf.properties](../../../services/ledger-service/src/test/resources/perf/perf.properties).
Rates 200 and 500 orders/s (the P2 gate rate and the T1 rate); **30 s warm-up and 60 s measured window, 3 repetitions
per rate**; after each window the pipeline is quiesced before the next starts. Durability is intact: `fsync` on, the
D00-3 image, preload and memory limit. The ledger's invariant gauges keep running every 30 s, as in production.

## 6. Exact commands

```sh
git rev-parse HEAD; git status --porcelain | wc -l     # b463d78…, 0
# VM load log, alongside the study (30 s samples of every container)
while true; do echo "$(date -u +%FT%TZ) ps[$(docker ps --format '{{.Names}}={{.Status}}' | tr '\n' ';')] \
  stats[$(docker stats --no-stream --format '{{.Name}}={{.CPUPerc}},{{.MemUsage}}' | tr '\n' ';')]" \
  >> docs/results/perf/2026-09-18-e2e/vm-load.log; sleep 30; done
./gradlew :services:ledger-service:studyTest --tests '*EndToEndLatencyStudy' --rerun -Pzs.perf.runLabel=2026-09-18-e2e
```

JUnit XML: 1 test, 0 failures, 0 errors, 572 s.

## 7. Raw data

- [2026-09-18-e2e/e2e-latency-runs.json](2026-09-18-e2e/e2e-latency-runs.json) — one record per window: UTC start, end
  of arrivals and quiesce; orders appended, measured and missing; late arrivals; p50/p95/p99 of the end-to-end latency
  and of both stages; apply batches and their mean duration; I2–I5; validity.
- [2026-09-18-e2e/vm-load.log](2026-09-18-e2e/vm-load.log) — the VM load record described in §4.

## 8. Results

All 6 windows are valid data points: **125,998 orders measured, 0 missing, 0 quarantined**, I2, I3, I4 and I5 zero after
every window, late arrivals ≤ 0.05 %.

> **Every figure below was measured under concurrent load on a shared VM** (chaos M8(b) chunk 2, §4). Load can only add
> latency, so these figures **overstate** latency and do not flatter it: each is a conservative upper bound on what a
> quiet machine would show, not a clean figure. They are kept exactly as measured.

### 8.1 Per repetition (MEASURED)

| Rate /s | Rep | UTC (start–quiesced) | Orders | Late | p50 ms | p95 ms | p99 ms | Outbox stage p50 / p95 / p99 ms | Kafka + apply p50 / p95 / p99 ms | Apply batches | Mean apply batch ms |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 200 | 1 | 07:06:43–07:07:44 | 11,999 | 0 | 42.8 | 71.6 | 77.2 | 40.2 / 68.7 / 73.8 | 2.6 / 4.6 / 6.3 | 961 | 7.67 |
| 200 | 2 | 07:08:15–07:09:16 | 11,999 | 0 | 44.5 | 74.6 | 79.6 | 41.7 / 71.6 / 76.3 | 2.7 / 4.6 / 5.4 | 919 | 8.25 |
| 200 | 3 | 07:09:46–07:10:48 | 12,000 | 0 | 44.3 | 74.3 | 79.4 | 41.5 / 71.2 / 76.0 | 2.7 / 4.6 / 5.6 | 921 | 8.31 |
| 500 | 1 | 07:11:19–07:12:20 | 30,000 | 8 | 45.8 | 75.9 | 80.9 | 41.3 / 71.1 / 75.4 | 4.3 / 7.4 / 9.9 | 918 | 13.00 |
| 500 | 2 | 07:12:52–07:13:53 | 30,000 | 11 | 42.5 | 70.2 | 74.4 | 38.5 / 65.9 / 69.4 | 4.0 / 6.6 / 8.6 | 979 | 11.94 |
| 500 | 3 | 07:14:24–07:15:26 | 30,000 | 14 | 42.5 | 70.3 | 74.2 | 38.5 / 65.9 / 69.3 | 4.0 / 6.5 / 9.0 | 978 | 12.04 |

### 8.2 Median of repetitions and range

| Rate /s | **p50 ms** | **p95 ms** (range) | **p99 ms** (range) | Outbox stage p50 / p95 | Kafka + apply p50 / p95 |
|---|---|---|---|---|---|
| 200 | **44.3** | **74.3** (71.6–74.6) | **79.4** (77.2–79.6) | 41.5 / 71.2 | 2.7 / 4.6 |
| 500 | **42.5** | **70.3** (70.2–75.9) | **74.4** (74.2–80.9) | 38.5 / 65.9 | 4.0 / 6.6 |

p99 rests on 12,000 (200/s) and 30,000 (500/s) orders per window, so each is a nearest-rank value over at least 120
orders above it; it is reported as measured, not extrapolated.

### 8.3 MEASURED versus ESTIMATE (master §6.2 P2 stage budget, 200 orders/s)

| Stage | ESTIMATE p50 / p95 | MEASURED p50 / p95 (200/s) | Reading |
|---|---|---|---|
| Wait in outbox + relay send | 25 + 5 ms / 60 + 20 ms (p95s do not add) | **41.5 / 71.2 ms** | Over the p50 allocation — see below |
| Broker → consumer, apply, offset ack | 5 + 4 + 1 ms / 30 + 20 + 5 ms | **2.7 / 4.6 ms** | Well under |
| **Total** | TARGET ≤ 100 ms p50, ≤ 250 ms p95; gate ≤ 500 ms p95 | **44.3 / 74.3 ms** | Under target and gate |

**The outbox stage is ~90 % of the latency, and the estimate under-allocated it for a specific reason.** The budget's
25 ms p50 assumes an order waits, on average, half a 50 ms poll interval. But `OutboxRelayLoop` sleeps the interval
*after* each non-full batch, so the cycle is the interval **plus** the batch's own select-send-acknowledge-commit. The
apply side confirms the cycle length: about 920–980 apply batches per 60 s window at both rates, one per relay cycle of
roughly 62 ms. Half a 62 ms cycle plus the send accounts for the measured ~41 ms. The one lever that moves P2 here is
the relay poll interval (SP2's tuning question), not the ledger.

**The ledger already batches on this path.** At 200/s each apply transaction carried about 12.5 orders; at 500/s about
33 — because the listener hands each Kafka poll to the engine in one call. The `ledger.apply.default-batch-size: 1` in
`application.yml` is declared and validated but never read by `LedgerApplyEngine`, so it does not make the pipeline
per-order. See [batched-vs-per-order.md](batched-vs-per-order.md).

## 9. Gate or threshold compared against

[Master §8.9 go/no-go](../../zerosum_ledger_mvp_plan.md#go-no-go), P2 hard gate at 200 orders/s: **pass**, with the
range across repetitions entirely below the gate, so no straddle. The §6.2 P2 TARGET: **pass** for p50 and p95.

**T1 (500 orders/s sustained for 10 minutes): Not run.** Three 60 s windows at 500/s with every order applied is not
the T1 condition, which is a 10-minute window with lag < 5 s at its end. P2 p95 at 500/s is reported above only as a
latency measurement at that rate.

## 10. Deviations and limitations

- **Concurrent load label: all six windows.** All six windows overlapped chaos M8(b) chunk 2 (low-intensity load,
  ≈ 1–5 % of one core per service by the load log). The figures are measured under concurrent load, so they are
  conservative: they overstate latency and do not flatter it. The numbers are kept as measured. A quiet-machine re-run
  has not been done; the coordinator has offered to re-run if one is needed.
- **Shorter windows than the matrix.** The master's pipeline row is 2 min warm-up + 10 min × 3; this is 30 s + 60 s × 3.
  Repetitions and the median-of-repetitions rule are not reduced. A 10-minute window would expose slow drift (table
  growth, autovacuum, GC) that 60 s cannot.
- **HTTP excluded; P1 not measured.** order-service's API adds parsing, validation and the order-store insert in the
  same transaction as the outbox row. That cost is in P1's budget, not here.
- **One of each.** One relay, one ledger listener, one database server. That is the deployed topology (ADR-0008), not
  a scaled one.
- **Worst-case hot entity.** Every order touches `platform:main`. At 500/s this did not bind — mean apply batch 12 ms
  in a 62 ms cycle — so the ledger is not the limit at these rates.
