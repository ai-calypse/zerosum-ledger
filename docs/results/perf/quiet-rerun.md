# S07 performance — the quiet-machine re-run, and T1

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | P2 (quiet), T1, batched-vs-per-order (quiet), entity spread (quiet) |
| Type | performance |
| Date (UTC) | 2026-09-18, 10:07:51–10:56:06 |

## 2. Status

- **Measured, on a quiet machine.** Every figure in [perf-summary.md](perf-summary.md) and the three earlier reports was
  measured while a chaos run shared the Docker VM. This re-runs the headline configurations with **nothing else on
  the VM**, and runs T1 for the first time.

## 3. Provenance

| Item | Value |
|---|---|
| Git commit SHA | `d0eb638`. The tree was clean when the runner started (10:07:51), and no repository file was edited until all four studies had finished (10:56:06) apart from the studies' own output directories. These study JSONs do not record the SHA themselves |
| Harness | unchanged from [perf-summary.md](perf-summary.md): `EndToEndLatencyStudy`, `ApplyThroughputStudy`, parameters from `perf.properties` |
| Seeds | recorded per study in its JSON |
| Hardware | Apple M4, 10 cores, 24 GiB, macOS 26.5.2; Docker Desktop VM 10 CPUs / 7.75 GiB; PostgreSQL 18.6 at 1,536 MB, `fsync` on |

## 4. Quiet, verified

The runner refused to start while any container was running, and `vm-load.log` in each run directory samples
`docker ps` and `docker stats` every 30 s. **No Compose container appears in any sample** of the four logs: only
each study's own Testcontainers (PostgreSQL, Kafka, the reaper). The one overlap is the previous study's containers
still shutting down in the first sample of the next study, inside its warm-up.

## 5. Results (median of 3 windows, range in brackets)

### 5.1 End-to-end latency, P2 ([2026-09-18-quiet-e2e/](2026-09-18-quiet-e2e/))

Same scenario as [e2e-latency.md](e2e-latency.md): real outbox relay → Kafka (12 partitions) → ledger apply, both
ends timed on one PostgreSQL clock, HTTP excluded. 60 s windows after 30 s warm-up.

| Rate | p50 | p95 | p99 | Orders | Missing | Late arrivals |
|---|---|---|---|---|---|---|
| 200 orders/s | **44.2 ms** | **72.9 ms** | **77.3 ms** | 36,000 | 0 | 0 |
| 500 orders/s | 43.2 ms | 71.1 ms | 75.1 ms | 90,000 | 0 | 20 (0.02 %) |

Within 2.1 ms of the figures measured under chaos load (44.3 / 74.3 / 79.4 at 200/s). That was the prediction:
the latency is set by the relay's 50 ms poll cycle, not by contention.

### 5.2 T1: 500 orders/s sustained for 10 minutes ([2026-09-18-quiet-t1/](2026-09-18-quiet-t1/))

| Window | Orders | Missing | Late arrivals | Held the rate | p50 / p95 / p99 | Quarantined | Drained |
|---|---|---|---|---|---|---|---|
| 10:18:03–10:28:03 | **300,000** | **0** | 226 (0.08 %, limit 1 %) | yes | 43.5 / 71.6 / **76.2 ms** | 0 | **1 s** after the window closed |

**T1 is met** ([master go/no-go](../../zerosum_ledger_mvp_plan.md#go-no-go): 500 orders/s for 10 minutes with lag
under 5 s at the end). The p99 over 300,000 orders is 76 ms, and every order was applied within a second of the load
stopping.

*Classification note.* The harness files any run that overrides the window length or repetition count as a smoke
check, and writes it to `build/perf-smoke`, because such a run is not the P2 matrix. T1 **is** a single 10-minute
window by definition. The runner copied its JSON into the evidence directory, and its `full_study: false` flag is
left as written.

### 5.3 Batched versus per-order apply, one hot account ([2026-09-18-quiet-batch/](2026-09-18-quiet-batch/))

| Writers × batch | Orders/s | Lock-wait p95 | Lock-timeout retries | Quarantined | Valid |
|---|---|---|---|---|---|
| 1 × 1 (per-order) | 740 (612–838) | 0.13 ms | 0 | 0 | yes |
| 1 × 50 | 3,816 (3,759–3,880) | 0.29 ms | 0 | 0 | yes |
| **1 × 100** | **4,002** (3,982–4,138) | 0.45 ms | 0 | 0 | yes |
| 32 × 1 | 616 (614–617) | 175 ms | 0 | 0 | yes |
| 32 × 50 | 3,553 (3,494–4,052) | 2.6 s | 4 | 0 | yes |
| 32 × 100 | 3,504 (3,391–3,642) | 4.3 s | **12** | **0** | **yes** |

- **Batching is 5.4× per-order** at one writer (740 → 4,002), and the best quiet figure is 4,002 orders/s. The
  ~5,000 orders/s ESTIMATE is still **not reached**: 80 % of it.
- **More writers on one hot account make it slower, not faster**: 32 writers queue on the same entity lock
  (lock-wait p95 175 ms per-order, 4.3 s at 100-order batches). Spreading the account (§5.4) is the way out.
- **CR-S07-01 confirmed at load.** 32 × 100 is the configuration that quarantined 3 valid orders before the fix. Here
  it hit the same lock-queue timeouts, **12 times, and every one was retried**: 0 quarantined, all windows valid.

### 5.4 Spreading the hot account ([2026-09-18-quiet-entity/](2026-09-18-quiet-entity/))

32 writers, per-order.

| Platform entities | Orders/s | Lock-wait p95 |
|---|---|---|
| 1 | 568 (558–581) | 213 ms |
| 100 | **1,647** (1,638–1,651) | **2.8 ms** |

**2.9×** the throughput, and lock-wait p95 down 76×, by spreading the platform side over 100 entities (a
workload-level emulation of sharding, not implemented sharding).

## 6. What changed from the under-load figures

| Figure | Under chaos load | Quiet |
|---|---|---|
| P2 at 200/s p50 / p95 / p99 | 44.3 / 74.3 / 79.4 ms | 44.2 / 72.9 / 77.3 ms |
| Batched, best | 3,981 (32 × 50) | 4,002 (1 × 100) |
| Per-order → batched, 1 writer | 527 → 3,242 | 740 → 4,002 |
| Entity spread, 32 writers | 707 → 1,647 | 568 → 1,647 |
| 32 × 100 | invalid: 3 valid orders quarantined | valid, 0 quarantined (fix in place) |

The quiet N=1 per-order figure (568) is **lower** than the one measured under load (707, range 526–845). That
earlier range was unstable within its own block, as [entity-spread.md](entity-spread.md) recorded. The quiet range is
tight (558–581), so the quiet figure is the one to quote.

## 7. Limitations

- **Still a laptop**, through Docker Desktop's VM, with one database server and one broker.
- Windows are 45–60 s after 20–30 s warm-up for the grids, shorter than the master matrix. T1 is the one 10-minute
  window.
- P1 (client-observed API acknowledgement) is still not measured: the harness appends to the outbox directly.
