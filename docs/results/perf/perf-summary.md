# S07 performance evidence — summary

The three measurements SP1 deliberately did not make (S07-T04/T05/T06). Each has its own report with methodology,
provenance (M13 c), raw data and a MEASURED-versus-ESTIMATE table:

| Measurement | Report | Raw data |
|---|---|---|
| End-to-end order-to-apply latency (P2) across the real outbox → Kafka → apply path | [e2e-latency.md](e2e-latency.md) | [2026-09-18-e2e/](2026-09-18-e2e/) |
| Batched versus per-order apply throughput (the ~5,000 orders/s ESTIMATE; SP4 option (a)) | [batched-vs-per-order.md](batched-vs-per-order.md) | [2026-09-18-batch-a/](2026-09-18-batch-a/), [2026-09-18-batch-b/](2026-09-18-batch-b/), [2026-09-17-interrupted/](2026-09-17-interrupted/) |
| Throughput across N distinct platform entities (SP4 option (b), workload-level emulation) | [entity-spread.md](entity-spread.md) | [2026-09-18-entity/](2026-09-18-entity/) |

**Machine.** Apple M4, 10 cores, 24 GiB, macOS 26.5.2 — a laptop, measured through its Docker Desktop VM (10 CPUs,
7.75 GiB), PostgreSQL 18.6 at the D00-3 1536 MB limit, **durability on** (`fsync` intact). Harness `86ece1b`
(worktree base `98f33603`, older than `main`; nothing merged since touches the measured paths).

**Every window was measured under concurrent load on a shared VM.** Another agent's chaos run (M8(b) volume chunks
2–5: FakeCard charges with `timeout_after_commit` faults through its own Compose stack) overlapped every timestamped
window, by the coordinator's cross-check against that agent's timeline. Load can only take throughput away and add
latency, so every figure here is **a conservative bound, not a clean figure**: throughput a lower bound, latency an
upper bound. A quiet-machine re-run has not been done.

## Estimates: which held

| ESTIMATE ([master §6.5](../../zerosum_ledger_mvp_plan.md#bottlenecks), [§6.2](../../zerosum_ledger_mvp_plan.md#stage-budgets)) | MEASURED (under load) | Verdict |
|---|---|---|
| 200–333 orders/s per-order on the hot entity | 527.1 (1 writer), 707.4 (32 writers) | **Exceeded** — the whole transaction is 1.2–1.7 ms, not a 3–5 ms lock hold |
| ≈ 5,000 orders/s batched (100 orders per 20 ms) | Best timestamped median 3,981.1 (32 × 50); 3,242.2 at 1 × 100 | **Does not hold as stated** — ~80 % at best; a 100-order transaction took 26.4 ms, close to the assumed 20 ms |
| P2 at 200 orders/s: p50 ≤ 100 ms, p95 ≤ 250 ms (gate ≤ 500 ms) | p50 44.3 ms, p95 74.3 ms | **Held**, gate passes |
| Outbox stage p50 25 ms (wait) + 5 ms (send) | 41.5 ms | **Over** — the relay sleeps 50 ms *after* each batch, so its cycle is ~62 ms, not 50 |

## Findings beyond the numbers

- **A valid order can be quarantined under hot-entity contention.** At 32 writers × 100-order batches on one entity, the
  entity-lock statement exceeded the 5 s statement timeout; SQLSTATE 57014 is not in the retry classifier's transient
  set, so the engine isolated 58 batches and quarantined 3 valid orders. No money lost, I2–I5 held. Not reachable with
  the deployed single-threaded listener. **Fixed** as [CR-S07-01](../../scope-decisions.md#cr-s07-01--a-statement-timeout-while-queueing-for-entity-locks-quarantined-valid-money-fixed)
  (`f9baa0e`): a 57014 raised while acquiring entity locks is now retried, with a regression test that fails without
  the fix. The study was not re-run afterwards ([batched-vs-per-order.md §8.3](batched-vs-per-order.md#83-the-32--100-configuration-quarantined-valid-orders)).
- **`ledger.apply.default-batch-size` is dead configuration.** It is validated but never read; the pipeline already
  applies each Kafka poll as one batch (~12.5 orders per transaction at 200/s, ~33 at 500/s).
- **Past ~100 platform entities, lock contention is gone** (lock-wait p95 3 ms) and the per-order transaction itself is
  the limit — so batching, not more sharding, is the next lever.

## Not measured, and why

- **T1** (500 orders/s sustained for 10 minutes, lag < 5 s): **Not run** — windows were 60 s.
- **P1** (client-observed API acknowledgement): **not measured** — the harness appends to the outbox directly; order-service's HTTP layer is excluded.
- **Batching combined with entity spread** (SP4 option (c)): **Not run**.
- **Any quiet-machine figure**: every window overlapped concurrent load; nothing is extrapolated to a quiet machine.
- Windows are shorter than the master matrix (45–60 s after 20–30 s warm-up, against 3–10 min after 2 min);
  repetitions (3) and the median-of-repetitions rule are unchanged.

## Numbers that can be quoted

Each is median-of-repetitions with its scope. Each is labelled **measured under concurrent load on a shared VM — a
conservative bound, not a clean figure**. None is a best-of.

1. **Order-to-balance latency p95 74 ms (p50 44 ms, p99 79 ms) at 200 orders/s**, across the real transactional outbox →
   Kafka (12 partitions) → ledger apply path, both ends timed on one PostgreSQL clock, durability on, on an Apple M4
   laptop's Docker VM. HTTP excluded. Windows 07:06:43–07:10:48 UTC 2026-09-18; overlapped chaos M8(b) chunk 2 — an
   upper bound on latency.
2. **Batching the ledger apply gives about 6× the per-order throughput on a single hot account**: 527 → 3,242 orders/s
   at one writer with 100-order transactions (and 755 → 4,358 in an independent untimestamped run), durability on, same
   machine. Windows 07:31:22–07:34:18 and 07:46:10–07:49:09 UTC 2026-09-18; overlapped chaos M8(b) chunks 3–5 — lower
   bounds on throughput.
3. **Spreading the hot account over 100 sub-accounts raises per-order throughput 2.3× (707 → 1,647 orders/s at 32
   writers) and cuts lock-wait p95 from 147 ms to 3 ms** — a workload-level emulation of sharding, not implemented
   sharding; durability on, same machine. Windows 07:17:02–07:19:58 and 07:23:44–07:26:42 UTC 2026-09-18; overlapped
   chaos M8(b) chunks 2–3 — lower bounds on throughput.

**Not quotable:** "5,000 orders/s" (not reached); anything from the 2026-09-17 interrupted run on its own (no window
times); T1 and P1 (not measured).
