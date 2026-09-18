# S07 — the ≈ 5,000 orders/s batched ESTIMATE, tested to a verdict

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | batched-apply ESTIMATE ([master §6.5](../../zerosum_ledger_mvp_plan.md#bottlenecks)); SP4 option (c), batching combined with entity spread |
| Type | performance |
| Date (UTC) | 2026-09-18, 22:50:33–23:05:04 |

## 2. Status

- **Measured, on a quiet machine.** The ESTIMATE is **met**: 5,133 orders/s on one hot account with 500-order
  transactions, and 5,671 orders/s with batching plus entity spread.

## 3. Provenance

| Item | Value |
|---|---|
| Git commit SHA | `417b412`. Tree clean at the start; no repository file edited during the runs apart from the studies' own output directories. The study JSONs do not record the SHA themselves |
| Harness | `ApplyThroughputStudy` unchanged; `417b412` only made `zs.perf.entityWriters` and `zs.perf.entityBatchSize` overridable, so batching and entity spread can run together |
| Hardware | Apple M4, 10 cores, 24 GiB, on mains power; Docker Desktop VM 10 CPUs / 7.75 GiB; PostgreSQL 18.6 at 1,536 MB, `fsync` on |
| Other load | none: `vm-load.log` in each run directory shows only the study's own Testcontainers. The editor, Docker Desktop and Finder were the only open applications |

Windows: 45 s after 20 s warm-up, 3 repetitions, median reported, as in every other apply study.

## 4. Results

### 4.1 Bigger batches, one writer, one hot account ([2026-09-18-quiet-batch-large/](2026-09-18-quiet-batch-large/))

| Batch size | Orders/s (median, range) | Transaction p50 | Lock-wait p95 |
|---|---|---|---|
| 100 *(from [quiet-rerun.md](quiet-rerun.md))* | 4,002 (3,982–4,138) | 24.7 ms | 0.45 ms |
| 200 | 4,884 (4,840–4,920) | 40.6 ms | 0.56 ms |
| **500** | **5,133** (5,056–5,133) | 97.3 ms | 0.85 ms |

**The ESTIMATE's number holds; its mechanism does not.** The master assumed 100 orders in 20 ms. A 100-order
transaction takes about 25 ms, so 100-order batches reach 4,002. Larger batches amortise the fixed per-transaction
cost (the entity locks, the round trips, the commit's `fsync`), and 500-order transactions pass 5,000 on a single
hot account. The cost: each commit takes about 97 ms, so a single order waits longer for its balance.

### 4.2 Batching combined with entity spread, 100 platform entities (`2026-09-18-quiet-spread-w8-b100/`, `…-w32-b100/`)

| Writers × batch | Orders/s (median, range) | Transaction p50 | Lock-wait p95 | Lock-timeout retries | Quarantined |
|---|---|---|---|---|---|
| **8 × 100** | **5,671** (5,649–6,278) | 120 ms | 247 ms | 0 | 0 |
| 32 × 100 | 2,533 (2,402–2,578) | 261 ms | 4.95 s | 211 | 0 |

- **8 writers × 100-order batches over 100 entities is the fastest configuration measured: 5,671 orders/s.**
- **More writers made it worse.** The workload assigns platform entities round-robin (`platform:p0000`–`p0099`), so
  one 100-order batch touches **all 100** of them. With 32 concurrent batches, every batch queues behind every other on shared
  entity locks: lock-wait p95 is 4.95 s. **Batching and spreading do not compose naïvely.** They need
  shard-aligned batches, with each writer (each Kafka partition consumer) owning a disjoint set of platform shards.
  That is the design implication; it was not built or measured.
- CR-S07-01 held again: 211 lock-queue timeouts at 32 × 100, all retried, 0 quarantined, every window valid.

## 5. Verdict against the ESTIMATE

| ESTIMATE | Measured | Verdict |
|---|---|---|
| ≈ 5,000 orders/s batched (100 orders per 20 ms) | 5,133 on one hot account at 500 per transaction; 5,671 with 8 × 100 over 100 entities; 4,002 at 100 per transaction | **Met at 500-order batches or with spread.** The "100 per 20 ms" mechanism is 100 per ~25 ms |

## 6. Limitations

- One database server and one laptop. 45 s windows, not the master's 3 minutes.
- Entity spread is emulated in the workload (platform entities `platform:p0000`–`p0099`, assigned round-robin), not
  implemented as sharding.
- Larger batches trade per-order latency for throughput; this study measures throughput, not the end-to-end effect.
