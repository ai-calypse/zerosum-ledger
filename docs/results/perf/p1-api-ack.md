# P1 — client-observed acknowledgement of `POST /v1/money-orders`

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | P1 ([master §6.1](../../zerosum_ledger_mvp_plan.md#stage-budgets), perf matrix row "API ack") |
| Type | performance |
| Date (UTC) | ladder 1 (found a defect): 2026-09-18 22:27–22:50; **ladder 2 (the result): 2026-09-18 23:59–00:23** |

## 2. Status

- **Measured.** At the P1 gate rate of 200 requests/s the client-observed p95 is **1.63 ms** against a 30 ms target.
  The first ladder found that order-service was killed for running out of memory at 1,000 requests/s. It was fixed
  (`9726d41`), and ladder 2 is the measurement.

## 3. Provenance

| Item | Value |
|---|---|
| Git commit SHA | ladder 2: `cbb1246` (includes the fix `9726d41`), tree clean, images built by `make up` at the start of the run |
| Tool | k6 1.3.0, `grafana/k6@sha256:3ddc8b1a…eb77d`, **constant-arrival-rate** executor (open model: late responses show up as latency and `dropped_iterations`, never as a lower offered load) |
| Script | [infra/perf/p1-order-ack.js](../../../infra/perf/p1-order-ack.js), runner [infra/perf/run-p1.sh](../../../infra/perf/run-p1.sh) |
| Where the client runs | a k6 container on the Compose network, calling `order-service:8081` directly: client-to-server transit is the Docker bridge, as the §6.2 budget assumes |
| Hardware | Apple M4, 10 cores, 24 GiB, on mains power; Docker Desktop VM 10 CPUs / 7.75 GiB; PostgreSQL 18.6 at 1,536 MB, `fsync` on |
| Other load | none: `vm-load.log` shows only the stack and each window's k6 container. The editor, Docker Desktop and Finder were the only open applications |

**What each request does.** A COMMERCE trip order with a unique idempotency key and order group: bearer-token auth,
JSON parse, validation, then one transaction inserting the order, its three entries and its outbox row, with the
deferred zero-sum trigger and `COMMIT` (fsync). Responses are `201 Created`. Riders and drivers cycle through pools of
2,000 and 500.

**Windows.** Per rate: 3 × (a 30 s warm-up k6 run, discarded, then a 60 s measured run). The master's matrix is 2 min
warm-up + 10 min × 3; this is shorter, and the report says so.

## 4. Results, ladder 2 ([2026-09-18-p1-ladder2/](2026-09-18-p1-ladder2/))

Median of 3 windows, with the range.

| Offered rate | Requests | p50 | p95 | p99 | Max | Failed | Dropped iterations |
|---|---|---|---|---|---|---|---|
| 100/s | 18,003 | 1.25 ms | 2.27 ms (2.21–2.27) | 3.24 ms (3.20–3.34) | 24 ms | 0 | 0 |
| **200/s** | 36,002 | **0.94 ms** | **1.63 ms** (1.61–1.69) | **2.70 ms** (2.62–2.98) | 87 ms | 0 | 0 |
| 500/s | 90,002 | 0.61 ms | 1.50 ms (1.43–1.99) | 3.29 ms (2.88–8.59) | 62 ms | 0 | 0 |
| 1,000/s | 180,002 | 0.68 ms | 3.33 ms (1.65–12.91) | 13.28 ms (4.91–52.49) | 188 ms | 0 | 0 |
| 2,000/s | 354,820 | 1.66 ms | 81.86 ms (30.88–87.38) | 778 ms (116–884) | 1,455 ms | 0 | 5,183 (1.5 %) |

- **Every one of the 678,829 requests returned 201.** After the ladder, the ledger's invariants were consistent, lag
  was 0 and nothing was quarantined ([invariants-after.json](2026-09-18-p1-ladder2/invariants-after.json),
  [freshness-after.json](2026-09-18-p1-ladder2/freshness-after.json)), so everything accepted was also applied.
- **2,000/s is past saturation** on this machine: latency climbs to a p99 of 778 ms, and k6 could not start 1.5 % of
  the scheduled requests on time. Up to 1,000/s the service keeps up with p99 ≤ 13 ms.

## 5. Gate

[Master §8.9](../../zerosum_ledger_mvp_plan.md#go-no-go): "P1 client p95 ≤ 30 ms at 200 req/s". **Pass: 1.63 ms**, with
p50 0.94 ms (TARGET ≤ 10) and p99 2.70 ms (TARGET ≤ 75).

## 6. The defect ladder 1 found, and its fix

[2026-09-18-p1-ladder1/](2026-09-18-p1-ladder1/). Up to 500/s ladder 1 matched ladder 2 (200/s p95 1.78 ms). At
1,000/s, order-service's resident memory climbed to 753 of its 768 MiB container limit and the container was killed.
The 2,000/s step then failed against a service that no longer existed (k6: `lookup order-service … no such host`).

Diagnosis ([oom-diagnosis/](2026-09-18-p1-ladder1/oom-diagnosis/)): the JVM itself accounted for about 385 MB (heap
~233 MB, metaspace and code ~152 MB). The rest was native memory. **233 live threads** (Tomcat's default 200) were
contending for a **10-connection** Hikari pool, and each busy thread grew glibc malloc arenas outside anything the JVM
reports. The fix (`9726d41`) sized Tomcat to the pool (`server.tomcat.threads.max: 32`) and set `MALLOC_ARENA_MAX=2` in the
shared service image. Under the same load (a diagnosis run, not evidence), peak memory fell from ~750 to 527 MiB and
the service survived 1,000/s and 2,000/s.

## 7. Limitations

- **Memory headroom is thin at saturation.** In ladder 2's 2,000/s step order-service peaked at **722 MiB of 768**.
  It survived, but a longer run past saturation, or more heap, could reach the limit. Lowering
  `-XX:MaxRAMPercentage` from 60, or raising the container limit, is the next step; neither was measured here.
- **`fsync` on Docker Desktop.** The commit is durable as far as PostgreSQL knows, but the VM's virtual disk may
  acknowledge a flush from the host's cache. Commit times on a dedicated server with a real disk can be higher.
- **Client and server share one machine**; k6 and the stack compete for the same 10 CPUs at the higher rates.
- Windows are 60 s × 3 after a 30 s warm-up, not 10 min × 3 after 2 min.
