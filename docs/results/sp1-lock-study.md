# SP1 — per-order apply throughput against one hot entity

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | SP1 |
| Type | spike (performance) |
| Owning step and task | S02-T07 |
| Date (UTC) | 2026-09-16 |

## 2. Status

- **Measured**

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | `9234ff45411234b5b86be2b8f7df34fb8c0f1451`; working tree clean at launch (0 changes) |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../adr/0002-stack-and-pinned-versions.md) at that SHA. Runtime-reported: PostgreSQL `postgres:18.6@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280`, JVM Eclipse Temurin 25.0.4.1+1-LTS, Docker engine 29.8.0, Docker Compose v5.5.1 |
| Seeds | `-8011099974981670798` — one study seed; each window derives its workload from it and the raw data records it |
| Hardware | Apple M4, 10 cores, 24 GiB RAM, macOS 26.5.2, arm64. This is the reference machine described in [assumption A7](../zerosum_ledger_mvp_plan.md#assumptions), so no off-reference labelling is needed |

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | 29.8.0 / v5.5.1 |
| Docker VM CPUs / memory | 10 CPUs / 8,319,504,384 bytes (7.75 GiB); PostgreSQL limited to 1536 MB per D00-3 |
| Emulated images (non-native architecture) | None. The host is arm64 and the PostgreSQL image runs natively, so no emulation distorts these numbers |
| Other load on the host during the run | `docker ps` was empty before the study; no CI run was triggered during it, because a CI job starts containers on the same daemon. The Gradle daemon and one PostgreSQL container were the only workloads |

## 5. Scenario, workload and seeds

Per-order apply (batch size 1) through the same `LedgerApplyEngine` entrypoint the service uses, driven by the S02-T03
harness. Every generated order is a COMMERCE trip touching three entities — a rider from a pool of 2,000, a driver from
a pool of 500, and `platform:main` in **every** order, which is the hot entity this spike is about. Payloads are
pre-generated before each window so the client is never the bottleneck, and each writer holds its own connection so the
study measures lock contention rather than pool waits.

Parameters come from the master and live in
[services/ledger-service/src/test/resources/sp1/sp1.properties](../../services/ledger-service/src/test/resources/sp1/sp1.properties):
writers 1, 4, 12 and 32; a 180 s measured window; a 120 s warm-up before every window,
excluded from statistics; 3 repetitions per writer count. Total run time 1 h 07 m 29 s.

**Durability is intact.** The database is started as D00-3 configures it — the pinned image, the `pg_stat_statements`
preload and the 1536 MB limit — with `fsync` on. Ordinary ledger tests run `fsync=off` for speed, but commit fsync is
part of what a lock hold time is made of, so a number measured that way would be a smoke check rather than SP1 data.

## 6. Exact commands

```sh
# provenance, captured before the run
git rev-parse HEAD; git status --porcelain | wc -l
docker info --format '{{.ServerVersion}} {{.NCPU}} {{.MemTotal}}'

# the study itself (tag "study"; no CI job runs it — CR-S02-05 to D00-10)
./gradlew :services:ledger-service:studyTest --tests '*Sp1LockStudy' --rerun -i
```

## 7. Raw data

[sp1/sp1-runs.json](sp1/sp1-runs.json) — one record per window, written as each window completed, carrying the writer
count, repetition, applied orders, orders/s, apply p50/p95, lock-wait p50/p95, retry counts by class, the I2–I5
violation counts taken after the window, and whether the run counts as a data point.

## 8. Results

All 12 windows are valid data points: **zero** deadlock, lock-timeout, connection and exhausted-retry events, and
I2, I3, I4 and I5 each reported zero violations after every window.

### 8.1 Per writer count (MEASURED)

| Writers | Rep 1 | Rep 2 | Rep 3 | **Median orders/s** | Range (min–max) | Apply p50 | Apply p95 | Lock wait p95 |
|---|---|---|---|---|---|---|---|---|
| 1 | 550.1 | 541.5 | 545.8 | **545.8** | 541.5–550.1 | 0.48–0.51 ms | 1.2–1.3 ms | 0.1–0.1 ms |
| 4 | 422.8 | 384.8 | 351.1 | **384.8** | 351.1–422.8 | 0.96–0.98 ms | 3.6–3.7 ms | 1.8–1.9 ms |
| 12 | 451.3 | 444.1 | 495.1 | **451.3** | 444.1–495.1 | 2.05–2.23 ms | 13.3–16.6 ms | 11.3–14.4 ms |
| 32 | 577.5 | 606.8 | 665.8 | **606.8** | 577.5–665.8 | 4.50–5.04 ms | 60.1–77.1 ms | 57.8–74.6 ms |

Lock-wait p50 was 0 µs at every writer count: most orders take the entity lock immediately and the waiting shows up
entirely in the tail.

### 8.2 Ceiling

The ceiling is the highest median-of-repetitions across writer counts, which is **606.8 orders/s at 32 writers**
(that count's own repetitions ranged 577.5–665.8 orders/s).

### 8.3 MEASURED versus ESTIMATE

| Source | Label | Value | Basis |
|---|---|---|---|
| [master §6.5](../zerosum_ledger_mvp_plan.md#bottlenecks) | **ESTIMATE** | 200–333 orders/s, per-order | A hot-entity lock assumed held 3–5 ms, giving 1000/5 to 1000/3 |
| [master §6.5](../zerosum_ledger_mvp_plan.md#bottlenecks) | **ESTIMATE** | ≈ 5,000 orders/s, batched | 100 orders per 20 ms transaction |
| This study | **MEASURED** | 606.8 orders/s, per-order | Median-of-repetitions ceiling from §8.1 |

The MEASURED per-order ceiling is roughly 1.8–3× the ESTIMATE. The estimate's arithmetic is sound; its input was
conservative for this hardware. It assumed a 3–5 ms lock hold, whereas MEASURED apply p50 at one writer — the whole
transaction, not just the lock — is 0.51 ms, about six to ten times shorter.
An M4 with a local container and fast storage commits far quicker than the estimate assumed.

### 8.4 Bottleneck interpretation

The hot entity behaves exactly like a serialising resource. From 1 to 32 writers, throughput rises only
**1.11×** while apply p50 grows **8.8×**
and lock-wait p95 grows from about 0.1 ms to about 59–75 ms. Added writers buy almost no capacity and pay for it in
latency, because every order must serialise on `platform:main`.

The curve is not monotonic: throughput dips at 4 writers (384.8 median) below the single-writer figure
(545.8) before recovering at 12 and 32. The most plausible reading is that contention overhead appears
first, and is then more than offset by commit batching — with many writers committing concurrently, PostgreSQL
amortises WAL flushes across them, so aggregate throughput climbs even as each order waits longer. This is a
measurement of that combined effect, not of the lock alone; separating the two would need WAL-level instrumentation
that belongs to S07, not to this spike.

## 9. Gate or threshold compared against

The SP1 decision criterion in [master §4.8](../zerosum_ledger_mvp_plan.md#spikes), which S02 records as D02-10.

**Pass.** The measured ceiling is above the criterion, and the repetition range at the ceiling's writer count lies
entirely above it, so the ranges do not straddle the threshold and the tie-breaking rule for an ambiguous result does
not apply. S3 (batched apply) is **not** promoted to must-have by this study.

## 10. Deviations and limitations

- **Apply-only.** This measures the ledger apply path in isolation, with orders handed straight to the engine. It
  excludes the Kafka hop and the outbox, so it is not a pipeline number: a ceiling above the criterion here does not by
  itself show that T1 (end-to-end order-to-balance) is met. P2 and T1 are measured in S07.
- **One hot entity, one currency.** Every order touched `platform:main` in USD, which is the worst case the spike asks
  about. A workload spread over several platform sub-entities would contend less; that is what SP4 explores.
- **Latency at high writer counts is large.** Apply p95 reaches 60–77 ms at 32 writers. That is well inside the
  order-to-balance budget, but it is apply-only and will grow once the pipeline is added.
- **The first attempt at this study was discarded**, not reported: it ran with mis-forwarded override properties and
  died with an `OutOfMemoryError` in the whole-ledger I5 sweep after two windows. The harness faults it exposed were
  fixed (bounded streaming for the sweep, per-window raw-data writes, and an assertion that requested overrides
  actually reach the test JVM) and the study was rerun from scratch. No data from that attempt appears here.
- **Group commit is inferred, not measured.** The non-monotonic throughput curve in §8.4 is interpreted, and the
  interpretation is not itself evidence.
