# S07 performance evidence — summary

The three measurements SP1 deliberately did not make (S07-T04/T05/T06). Each has its own report with methodology,
provenance (M13 c), raw data and a MEASURED-versus-ESTIMATE table:

| Measurement | Report | Raw data |
|---|---|---|
| End-to-end order-to-apply latency (P2) across the real outbox → Kafka → apply path | [e2e-latency.md](e2e-latency.md) | [2026-09-18-e2e/](2026-09-18-e2e/) |
| Batched versus per-order apply throughput (the ~5,000 orders/s ESTIMATE; SP4 option (a)) | [batched-vs-per-order.md](batched-vs-per-order.md) | [2026-09-18-batch-a/](2026-09-18-batch-a/), [2026-09-18-batch-b/](2026-09-18-batch-b/), [2026-09-17-interrupted/](2026-09-17-interrupted/) |
| Throughput across N distinct platform entities (SP4 option (b), workload-level emulation) | [entity-spread.md](entity-spread.md) | [2026-09-18-entity/](2026-09-18-entity/) |

**Machine.** Apple M4, 10 cores, 24 GiB, macOS 26.5.2 — a laptop, measured through its Docker Desktop VM (10 CPUs,
7.75 GiB), PostgreSQL 18.6 at the D00-3 1536 MB limit, **durability on** (`fsync` intact). Harness `b463d78`
(worktree base `505fecc4`, older than `main`; nothing merged since touches the measured paths).

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
  (`87f0710`): a 57014 raised while acquiring entity locks is now retried, with a regression test that fails without
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

**Superseded by the quiet-machine re-run** ([quiet-rerun.md](quiet-rerun.md), 2026-09-18 10:07–10:56 UTC, nothing else
on the Docker VM). Quote these; the under-load figures above stay as the record of that run. Each is a median of 3
windows, durability on, Apple M4 laptop through Docker Desktop.

1. **Order-to-ledger latency p50 44.2 ms, p95 72.9 ms, p99 77.3 ms at 200 orders/s**, across the real transactional
   outbox → Kafka (12 partitions) → ledger apply path, both ends on one PostgreSQL clock. HTTP excluded.
2. **Sustained 500 orders/s for 10 minutes: 300,000 orders, 0 missing, p99 76.2 ms, drained 1 s after load stopped**
   (T1, one 600 s window).
3. **Batched ledger apply: 5,133 orders/s on a single hot account** with 500-order transactions, and **5,671 orders/s**
   with 8 writers × 100-order batches over 100 platform entities ([five-thousand.md](five-thousand.md)); 100-order
   transactions give 4,002, 5.4× per-order (740).
4. **Spreading the hot account over 100 sub-accounts: 2.9× throughput (568 → 1,647 orders/s at 32 writers), lock-wait
   p95 213 → 2.8 ms**. A workload-level emulation of sharding, not implemented sharding.

5. **API acknowledgement (P1): p50 0.94 ms, p95 1.63 ms, p99 2.70 ms at 200 requests/s**, client-observed with k6's
   constant-arrival-rate executor on the Compose network; clean to 1,000 requests/s (p99 13 ms), saturated at 2,000
   ([p1-api-ack.md](p1-api-ack.md)).

**Not quotable:** the "100 orders per 20 ms" mechanism behind the 5,000 estimate (a 100-order transaction takes about
25 ms); anything from the 2026-09-17 interrupted run on its own; anything from P1 ladder 1 at 1,000 or 2,000 requests/s
(taken before the memory fix).
