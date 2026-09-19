# S08-M13b — ablation experiment A0–A5, B0

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | S08-M13b (master §3.1 M13 (b); ablation table [§8.5](../../zerosum_ledger_mvp_plan.md#ablation); faults [§8.4](../../zerosum_ledger_mvp_plan.md#fault-matrix)) |
| Type | ablation |
| Owning step and task | S08 (S08-T03 seams and guard, S08-T04/T05 runner and runs) |
| Date (UTC) | 2026-09-19: 51-run series 01:06–02:37; F4h series 08:40–09:16 |

## 2. Status

- **Measured: 60 evidence runs** (51 in the pre-registered series, plus 9 for the pre-registered F4h amendment,
  §3.8). 246,696 trips through the real order API. Every run quiesced; 0 harness errors; 0 fault-action errors.
- **M13(b): PARTIAL.** A2, A3, A4 and B0 are **valid**: each produced its predicted failure class at or above its
  threshold. A5 showed no violation, as predicted. **A1 is not valid** under the pre-registered rule. Its protection
  fails exactly as predicted under a ledger crash between commit and acknowledgement (F2, 3 of 3), but no broker fault
  (F4 graceful restart, F4h hard kill) produced a redelivery in 6 runs (§6).
- **The full design (A0) made 0 violations in all 21 control runs**, across seven fault sets.

## 3. Run plan (D08-6), committed before the first evidence run

Pre-registered here, in a commit that precedes every evidence run, as master §8.5 and §0.3 E3–E6 require. Pilot runs
(label prefix `pilot-`) may precede it; they are reported as pilots and never counted.

### 3.1 What is switched off, and how

Each switch is a boolean read once at startup (`zs.chaos.<id>`, env `ZS_CHAOS_<ID>`), default off, honoured only when
the `chaos` profile is active, `demo-public` is not, and `ZS_ALLOW_CHAOS=true`
([`libs/auth/.../ChaosGuard.java`](../../../libs/auth/src/main/java/dev/zerosum/auth/ChaosGuard.java); otherwise the
service refuses to start). Every start logs `ZS-CHAOS service=<svc> active=[...]`, and the harness asserts that line
per service before each run. The overlay is [`docker-compose.chaos.yml`](../../../docker-compose.chaos.yml); every A0
control runs under the same overlay with every switch off.

| Switch | Owner, seam | What it removes |
|---|---|---|
| A1 | ledger-service `LedgerApplyEngine` | The `applied_orders` answer is ignored: every candidate in a batch is applied, redelivered or not. The row is still written (`ON CONFLICT DO NOTHING`), so a conflict never aborts the batch. |
| A2 | `libs/outbox` `OutboxWriter` (§0.3 C10 exemption), wired by order-service | No outbox row. After the caller's transaction commits, one direct `KafkaTemplate.send`; a failed or lost send is logged and never persisted or retried. Producer settings unchanged (D04-2). |
| A3 | instrument-service `AttemptSubmitter` | A charge whose submit result is `Unknown` is resubmitted at once, same attempt, under a fresh provider idempotency key (§0.3 E7), up to 3 times. The client reference stays the attempt id, so a duplicate is attributable. |
| A4 | order-service `MoneyOrderController` + `ZeroSumTriggers`; ledger-service `OrderDecoder` | The API's zero-sum rule, both deferred zero-sum triggers (disabled at startup by the owner role Flyway uses, re-enabled at startup whenever A4 is off; append-only triggers untouched), and the ledger re-check (§0.3 E8). Only `ZERO_SUM_VIOLATED` is dropped; every other rule stays. |
| A5 | instrument-service `WebhookReceiver` | A duplicate delivery is applied as if new. The `provider_events` primary key still refuses a second row, so "recorded twice" is not possible; the state machine and the order-level `event_id` key are the layers left. |
| F2 hook | ledger-service `MoneyOrderListener` | Not an ablation: the F2 crash point. When armed (`/tmp/zs-chaos-f2` in the container) it halts the JVM after a batch's transaction commits and before the offset is acknowledged. |
| F3 hook | instrument-service `AttemptSubmitter` | Not an ablation: the F3 crash point (§0.3 E9). When armed it halts the JVM after the provider answered and before the attempt's state is written. |

B0 is a configuration (A1+A2+A3+A4), not a code path.

### 3.2 Faults

Every container fault fires on its own seeded schedule, one action every 20–40 s from the start of the load, until the
load has an answer for every trip; then injection stops, every container is restarted if down and must be healthy, and
FakeCard's profile is reset to `{"seed":0}`.

| Id here | Master | Injection in this experiment |
|---|---|---|
| F1 | F1 | `docker kill -s KILL order-service`, then `docker start` at once. |
| F2 | F2 | Arm the F2 hook; wait ≤ 60 s for the process to halt at the crash point (exit 86); `docker start`. A hook that does not fire is disarmed, the container killed, and the record says `hook_fired: false`. A random `kill -9` is **not** used: each poll batch commits in one transaction, so the commit-to-ack window is a few ms and a random kill almost never lands in it (step doc: "prefer the crash-point seam for F2"). |
| F4 | F4 | `docker restart kafka`. |
| F7 | F7 | FakeCard `timeout_after_commit_rate=0.2`, `http_500_rate=0.05` for the whole load. |
| F11v | F11 variant + A4 bugs | The load resends 20% of trips with the same key and body (expects 200 replay) and 2% with the same key and a different, balanced body (expects 422), and 1% of trips carry a ±1 minor-unit fare-split bug on the rider leg. |
| F8c | F8, card side | FakeCard `webhook_duplicate_rate=0.3`, `webhook_reorder_rate=0.3`, `webhook_drop_rate=0.1`. **Deviation:** master F8 is FakeBank's webhook chaos plus `return_rate`; this experiment runs no payout runs, so F8's knobs are applied to FakeCard's charge webhooks and returns are not exercised. |
| F12b | F12 + A4 bugs (§0.3 E6) | F1 + F3 (hook) on independent schedules, FakeCard F7 + F8c knobs together, and 1% fare-split bugs. |

**Not run: F6** (Toxiproxy latency/resets on the provider link), targeted by A3. The Compose topology has no route
from instrument-service to fake-providers through Toxiproxy; adding one is a D00-3 change outside this task. A3 runs
all of its runs under F7.

### 3.3 Workloads (seeded, through the real order API)

| Name | Trips | Rate | Riders / drivers | Cards | Bugs | Used by |
|---|---|---|---|---|---|---|
| plain-90 | 9,000 | 100/s | 200 / 50 | no | — | F1 cells |
| plain-60 | 6,000 | 100/s | 200 / 50 | no | — | F2, F4 cells |
| f11-bugs | 6,000 | 100/s | 200 / 50 | no | 1% ±1; F11 resend 20%, key reuse 2% | F11v cells |
| cards | 200 | 4/s | 50 / 20 | yes (`tok_card_ok`) | — | F7, F8c cells |
| cards-bugs | 300 | 5/s | 50 / 20 | yes | 1% ±1 | F12b cells |

Each trip is one COMMERCE order: rider receivable +fare, driver payable −(fare − fee), platform revenue −fee, fare
uniform in 500–5,000 minor units, fee = fare / 5. The client retries a trip with its own idempotency key and the
identical body on transport errors, 409 and 5xx, backing off 200 ms → 2 s, until a definitive answer or 4 min
(abandoned, and counted). The card rate is low on purpose: instrument-service has 4 submit threads and a lost response
holds one for 7 s (FakeCard's withhold), so a faster rate would only queue work for the sweeper.

**Scale-down (deviation).** Master §8.8: 20 runs per ablation of 10,000 trips each, and 20 A0 runs per fault. Here:
6 runs per ablation (split evenly across its targeted faults, §0.3 E4), 3 A0 control runs per fault cell, 200–9,000
trips per run. The confidence statements in §9 use these counts.

### 3.4 Cells and run allocation

| Cell | Switches | Workload | Runs | Predicted class (§8.5) | Threshold |
|---|---|---|---|---|---|
| A1/F2 | A1, F2 hook | plain-60 | 3 | I6b violated | ≥ 50% (2 of 3) |
| A1/F4 | A1 | plain-60 | 3 | I6b violated | **crash-timing-dependent: ≥ 1 of 3** |
| A2/F1 | A2 | plain-90 | 3 | I6 violated with `missing_orders` > 0 | **crash-timing-dependent: ≥ 1 of 3** |
| A2/F4 | A2 | plain-60 | 3 | I6 violated with `missing_orders` > 0 | **crash-timing-dependent: ≥ 1 of 3** |
| A3/F7 | A3 | cards | 6 | I7 violated with `duplicate_charges` > 0 | ≥ 50% (3 of 6) |
| A4/F11v | A4 | f11-bugs | 6 | I1 or I2 violated | ≥ 50% (3 of 6) |
| B0/F12b | A1–A4, F3 hook | cards-bugs | 6 | ≥ 2 classes among zero-sum (I1/I2), missing (I6), duplicate charges (I7), drift (I6b, counted only if I6 passed) | ≥ 50% (3 of 6) |
| A5/F8c | A5 | cards | 3 | **no violation** (layered defence) | reported, not a validity cell |
| A0/F1, A0/F2, A0/F4, A0/F7, A0/F11v, A0/F12b | none (F2/F3 hooks where the fault needs them) | as the paired cell | 3 each | none | every run PASS |

Why the three cells are named crash-timing-dependent, before any evidence exists:

- **A2/F1.** A dual write loses a message only if the kill lands while a committed order's record is still inside the
  producer (≈ `linger.ms`, 5 ms by default in Kafka clients 4.x, plus one round trip). At 100 trips/s that is well
  under one record per kill on average.
- **A2/F4.** An idempotent producer buffers through a broker restart and delivers when it returns, unless the outage
  exceeds `delivery.timeout.ms` (120 s default). `docker restart kafka` is expected to be far shorter, so this cell may
  show nothing, which would mean F4 does not exercise the outbox's durability.
- **A1/F4.** Redelivery under a broker restart needs either a relay batch re-sent by application code after its 10 s
  `send-timeout` (outage must outlast it) or a consumer offset commit that fails after an apply commit.

A variant is **valid** when every one of its cells meets its threshold. A0 cells are valid when every run is `PASS`.

### 3.5 Run classification and quiesce

- Quiesce (master §8.3, applied to the checks run): load finished; both outboxes (orders, instruments) have 0
  unpublished rows; the sum of lag over every consumer group (`kafka-consumer-groups.sh --describe --all-groups`) is 0,
  with an unreadable lag counted as not zero; 0 attempts in `CREATED`, `SUBMITTING` or `UNKNOWN`; 0 undelivered
  FakeCard/FakeBank webhook events; and the orders and `applied_orders` counts unchanged across two consecutive probes
  3 s apart. **Maximum wait: 300 s.** Settlement cycles are not awaited: I9 and I12 are not in the checked set.
- Verifier: `tools/verifier` with `--checks I1,I2,I3,I4,I6,I6b,I7` against all four databases as the read-only
  `verifier` role. Exit 0 → `PASS`, 1 → `VIOLATION`, 2 → `HARNESS_ERROR`.
- A run that did not quiesce is `NOT_QUIESCED` whatever the verifier said, and is never a pass. The predicted class is
  judged only on quiesced runs.
- `HARNESS_ERROR` runs (verifier exit 2, a failed Docker action, a service whose `ZS-CHAOS` line differs from the
  requested set, a dirty working tree) are rerun with the same seed and never counted. Violations and non-quiesced runs
  are findings and are never rerun.
- Seeds: run r of every cell with the same fault set uses the same seed (the paired design): same trips, same fault
  schedule; the switch is the only difference. Base seed 20260918.
- Order: interleaved — run 1 of every cell in a batch, then run 2, and so on.
- After every run: FakeCard profile back to `{"seed":0}`, the stack's data containers and volumes removed; after every
  batch: `docker compose ... down --volumes`.

### 3.6 Comparison metrics recorded per run (master §8.5)

Invariant violations by id (verifier), duplicate charges count and minor units (I7 metrics), missing orders (I6
`missing_orders`), unexplained drift (I6b `drift_abs_minor`), recovery time to zero lag (from the later of the end of
generation and the last fault action to the first probe with zero lag and empty outboxes), stuck attempts (non-terminal
attempts at the last probe).


### 3.7 Pre-evidence amendment, 2026-09-19: observability only

Made after the pilots and before the counted evidence series, so that every run can be replayed step by step
afterwards. **No service behaviour, workload, fault, quiesce rule or classification changed.**

- Each service's full log (`docker logs --timestamps`, all restarts of the container) is saved, gzipped, before the
  run's containers are removed: `logs/<label>/<service>.log.gz`.
- Grafana annotations (tag `zs-run`) mark each run's start, every fault action with its exit code, the end of the load,
  and the verdict as a region over the run. Both provisioned dashboards (`zs-flow`, `zs-money-invariants`) gained an
  annotation layer that shows them.
- Each run JSON records its UTC window and a Grafana URL for it.
- At the end the data services and volumes are removed, but `otel-lgtm` is left running with every run's metrics,
  traces and annotations. `make down` removes it.

Two evidence runs made before this amendment (`A0-F2-r1`, `A1-F2-r1`, at `eb9da11`) lacked the capture and were
discarded. The series restarted from run 1 at the amendment's commit.


### 3.8 Second amendment, 2026-09-19, after the 51-run series: a broker fault that exercises A1 and A2

Recorded **before** any F4h run. The 51-run series (3.4) finished with A1/F4 and A2/F4 at 0 of 3: a graceful
`docker restart kafka` (F4) neither lost a buffered producer send nor dropped the ledger's offset commit, so F4
exercised neither protection. §8.5 names the remedy: when an ablation does not show its failure class, the harness is
not exercising that protection, and A0's "0 violations" claim for it is invalid until the harness is fixed. The F4
results stay in this report exactly as measured. This amendment adds a harder broker fault and does not replace them
silently.

- **F4h:** `docker kill -s KILL kafka` once per run, at a seeded 20–40 s into the load, with the broker **kept down
  150 s** before `docker start`. 150 s exceeds the producer's `delivery.timeout.ms` (Kafka's default 120 s;
  order-service does not override it), so without an outbox a send made early in the outage expires in the producer's
  buffer. A hard kill also takes the broker down without completing in-flight offset commits.
- **Cells, 3 runs each, workload plain-60:** A0/F4h (control), A1/F4h, A2/F4h.
- **Thresholds, fixed now:** A0/F4h every run PASS. A2/F4h ≥ 50 % (a send made early in the outage should always
  expire, so this is not timing-dependent). **A1/F4h is named crash-timing-dependent: ≥ 1 of 3**, because redelivery
  needs the kill to land between a batch's database commit and its offset commit.
- **Variant validity:** A1 and A2 are judged on their crash cell (F2, F1) and F4h. F4 is reported alongside, as the
  finding that a graceful broker restart does not exercise them.

## 4. Provenance

| Item | Value |
|---|---|
| Git commit SHA | 51-run series: `530037d`; F4h series: `41b0960`. **Every one of the 60 run JSONs records a clean working tree** (the harness refuses an evidence run otherwise) |
| Images | built by the harness from that checkout at the start of each invocation (`docker compose build`), then run under `docker-compose.chaos.yml` |
| Hardware | Apple M4, 10 cores, 24 GiB, on mains power; Docker Desktop VM 10 CPUs / 7.75 GiB. Unrelated applications were closed; nothing else used Docker during the runs |
| Raw data | [raw/ablation/](raw/ablation/): `runs/` (60 run JSONs), `verifier/`, `logs/` (every service, every run), `summary.json`; `pilots/` and `pre-amendment/` kept and labelled, never counted |

## 5. Results by cell

Generated from the raw run JSONs and `summary.json`. Damage columns are the verifier's own metrics, one value per run.

| Cell | Runs | Predicted class observed | Threshold | Valid | Invariants violated (runs) | Damage per run |
|---|---|---|---|---|---|---|
| A0/F1 | 3 | 3 of 3 | all runs PASS | yes | — | 0 violations |
| A0/F2 | 3 | 3 of 3 | all runs PASS | yes | — | 0 violations |
| A0/F4 | 3 | 3 of 3 | all runs PASS | yes | — | 0 violations |
| A0/F4h | 3 | 3 of 3 | all runs PASS | yes | — | 0 violations |
| A0/F7 | 3 | 3 of 3 | all runs PASS | yes | — | 0 violations |
| A0/F11v | 3 | 3 of 3 | all runs PASS | yes | — | 0 violations |
| A0/F12b | 3 | 3 of 3 | all runs PASS | yes | — | 0 violations |
| A1/F2 | 3 | 3 of 3 | >= 50% of runs | yes | I6b×3 | drift $355/28,398/27,208 |
| A1/F4 | 3 | 0 of 3 | >= 1 run | **no** | — | 0 violations |
| A1/F4h | 3 | 0 of 3 | >= 1 run | **no** | — | 0 violations |
| A2/F1 | 3 | 3 of 3 | >= 1 run | yes | I6×3, I6b×3 | missing orders 2/2/2; drift $85/138/112 |
| A2/F4 | 3 | 0 of 3 | >= 1 run | **no** | — | 0 violations |
| A2/F4h | 3 | 3 of 3 | >= 50% of runs | yes | I6×3, I6b×3 | missing orders 21/21/32; drift $1,193/1,260/1,604 |
| A3/F7 | 6 | 6 of 6 | >= 50% of runs | yes | I7×6 | duplicate charges 28/28/27/28/28/26 ($672/719/844/786/874/598) |
| A4/F11v | 6 | 6 of 6 | >= 50% of runs | yes | I1×6, I2×5 | unbalanced orders 55/70/64/58/60/65 |
| B0/F12b | 6 | 5 of 6 | >= 50% of runs | yes | I1×5, I2×4, I7×6 | duplicate charges 22/20/27/28/19/21 ($565/503/690/683/569/597); unbalanced orders 2/1/4/5/4/0 |
| A5/F8c | 3 | 3 of 3 | reported only (predicted: no violation) | reported only | — | 0 violations |

60 evidence runs: {'PASS': 33, 'VIOLATION': 27}; trips created 246,696; wall 124 min

## 6. Verdict per variant

| Variant | Cells and result | Verdict |
|---|---|---|
| **A0** (full design) | 21 of 21 PASS under F1, F2, F4, F4h, F7, F11v, F12b | **valid**: 0 invariant violations, 0 duplicate charges |
| **A1** ledger de-duplication off | F2 **3/3** (I6b; up to $28,398 of drift in one run, up to 231 accounts drifting); F4 0/3; F4h 0/3 | **not valid** under the pre-registered rule (§3.8). The protection is real and the prediction held under consumer crashes, but the broker cells never exercised it (§8) |
| **A2** outbox off (dual write) | F1 **3/3** (2 orders lost per run); F4h **3/3** (21–32 lost per run); F4 0/3, reported | **valid** |
| **A3** fresh provider key on retry | F7 **6/6** (26–28 duplicate charges per 200 trips, $598–874 over-charged) | **valid** |
| **A4** zero-sum checks off | F11v **6/6** I1 (55–70 unbalanced orders); I2 in 5 of 6 | **valid**. In the sixth run the ±1 errors cancelled out globally, so only the per-order check (I1) could see them |
| **B0** all four off | F12b **5/6** with two or more classes (duplicate charges in 6/6, zero-sum in 5/6) | **valid** |
| **A5** webhook de-duplication off | F8c 3/3 PASS | as predicted: the attempt state machine refuses stale events (`ignored_stale_event`), a second layer |

## 7. What the broker-fault results mean

- A **graceful** Kafka restart (F4) exercised neither the ledger's de-duplication (A1) nor the outbox (A2): 0 of 6.
  The producer and consumer rode through it. A0's clean result under F4 therefore says little about either protection.
- A **hard** kill held past the producer's metadata wait (F4h) exercises the outbox decisively: A2 lost orders in
  3 of 3, while the control lost none and kept the API available.
- **No broker fault** exercised A1 (0 of 6). Only a consumer crash between database commit and offset commit does
  (F2, 3 of 3).

## 8. Walkthroughs: one run per variant, step by step

Each walkthrough is built from the run's own files: `raw/ablation/runs/<label>.json` (timeline, faults, recovery,
verifier findings), `raw/ablation/logs/<label>/<service>.log.gz` (every service's log across restarts), and
`raw/ablation/verifier/<label>.{json,md}`. Times are UTC, 2026-09-19. Every run is also in Grafana
(`otel-lgtm`, http://127.0.0.1:3000, left running): its metrics and traces, plus annotations tagged `zs-run` marking the
run start, each fault, the end of the load and the verdict on the `ZeroSum — Flow` and `Money invariants` dashboards.
Each run JSON's `grafana_url` opens its exact window. The container's own filesystem kept Grafana's and Prometheus's
data across a Docker Desktop restart between the two series; `make down` deletes it.

### A0 control under the storm: `A0-F12b-r2` (PASS)

FakeCard lost 20 % of responses and failed 5 %, duplicated, reordered and dropped webhooks, and 1 % of fares were
split wrong.
1. 02:06:35 load starts: 300 card trips at 5/s. The buggy trip is **rejected with 422** (`bug_orders_rejected_422: 1`),
   so it never enters the books.
2. 02:06:44 instrument-service logs `no CHARGE transition from SUCCEEDED to UNKNOWN`. A webhook had already settled
   the attempt when the submitter's timed-out answer arrived. The transition table refuses the stale, older result
   (see the note at the end of this section).
3. 02:06:55 and 02:07:19, **F3**: instrument-service halts between the provider call and recording the result
   (exit 86), then restarts. 02:07:03, **F1**: order-service is `kill -9`'d (exit 137) and restarts; 69 client calls
   retried with the same idempotency key.
4. Load ends at 02:07:35. The backlog reaches zero 55.2 s after the last fault. 598 orders are stored and 598
   applied.
5. Verifier: every check passes. 0 duplicate charges, and the books sum to zero.

### A1, ledger de-duplication off: `A1-F2-r2` (VIOLATION I6b)

1. 01:28:09 load starts: 6,000 trips at 100/s. ledger-service logs `ZS-CHAOS … active=[A1, F2]`.
2. 01:28:32.154 **F2**: `ZS-CHAOS F2 halting after committing a batch of 6 records, before acknowledging it`
   (exit 86). The batch is in the database; Kafka does not know it was consumed.
3. 01:28:36 ledger restarts. 01:29:17 the consumer rejoins: `Setting offset for partition … to the committed offset`.
   Kafka hands back records the ledger has already applied. With the `applied_orders` check skipped, they are
   **applied again**. F2 fires once more at 01:28:57.
4. 6,000 orders are stored and 6,000 appear in `applied_orders` (the row is written with `ON CONFLICT DO NOTHING`),
   so a count alone would look healthy.
5. Verifier **I6b**: 230 of 251 accounts drifted, 2,839,750 minor units (**$28,397.50**) in total. For example,
   `rider:a1f2r2r186/receivable ledger=71,757 orders=67,674`. Only the cross-store sum catches double-apply. I2–I4
   still hold, because every duplicate is internally balanced.

### A2, outbox off (dual write): `A2-F1-r1` (VIOLATION I6)

1. 01:09:11 load starts: 9,000 trips. order-service logs `active=[A2]`. Each order is committed, then sent straight
   to Kafka, with no outbox row.
2. 01:09:49 and 01:10:13 **F1**: order-service `kill -9` (exit 137). Orders committed in the moments before each
   kill never reached Kafka. The client got a 201 or retried, and nothing will ever re-send them.
3. Backlog zero after 38.5 s: 9,000 orders stored, **8,998 applied**.
4. Verifier **I6**: `missing_orders: 2`, e.g. `not applied: 01a0b736-5fb9-7169-92a3-cb0d5b196e37`. **I6b** follows:
   5 accounts are short by those orders' entries (8,472 minor units). The A0/F1 control with the same seed stored and
   applied 9,000 of 9,000.

### A3, fresh idempotency key on retry: `A3-F7-r1` (VIOLATION I7)

1. 01:53:28 load starts: 200 card trips at 4/s. FakeCard loses 20 % of responses after committing the charge.
2. Each lost response surfaces as `Unknown`. instrument-service logs
   `ZS-CHAOS A3 resubmitting attempt 01a0b75e-58b6-… under a fresh key`, and FakeCard sees a new key and **charges
   again**.
3. Verifier **I7**: 200 attempts but **228 provider successes**: 28 duplicate charges, 67,206 minor units ($672.06)
   over-charged. For example, `CHARGE 01a0b75e-58b6-… succeeded 2 times`. The A0/F7 control logged 260
   timeout/unknown lines under the same faults and made 0 duplicate charges: the same key makes the provider replay
   the original charge.

### A4, zero-sum checks off: `A4-F11v-r1` (VIOLATION I1, I2)

1. 01:56:07 order-service logs `zero-sum triggers are {…=true} but A4 is on`, then
   `zero-sum-triggers-enabled=false`. The owner role disabled the two zero-sum triggers only; the append-only triggers
   stay. The API rule and the ledger's re-check are off too.
2. 01:56:09 load starts: 6,000 trips, with 1 % carrying a ±1-minor-unit fare-split bug, 20 % resent with the same
   key (1,188 replays), and 2 % same key with a different body (116 rejected with 422: idempotency still works).
3. **All 55 buggy orders were accepted** (`bug_orders_accepted: 55`). In the A0 control, the same bugs were rejected
   at the API.
4. Verifier **I1**: 55 unbalanced orders, e.g. `01a0b761-…: USD sums to 1`. **I2**: the global USD total is no
   longer zero.

### B0, everything off, under the storm: `B0-F12b-r2` (VIOLATION I1, I2, I7)

Same faults and seed as `A0-F12b-r2` above, with A1–A4 switched off. F3 fired twice and F1 once. Result: **20
duplicate charges** ($503.26), and 1 unbalanced order (`sums to -1`) that broke the global zero. The control on the
identical schedule passed.

### A5, webhook de-duplication off: `A5-F8c-r1` (PASS, as predicted)

FakeCard duplicated 30 %, reordered 30 % and dropped 10 % of webhooks. With `provider_events` de-duplication off,
every duplicate reached the attempt state machine. The next layer stopped it:
instrument-service logs `ignored_stale_event: webhook evt_2718364c-… reported S…` for each late or repeated event.
Verifier: all checks pass. This is the predicted "no violation", and it shows the defence is layered.

### A2 under the hard broker kill: `A2-F4h-r1` (VIOLATION I6), against its control `A0-F4h-r1` (PASS)

Same seed and schedule; only A2 differs.
1. **Control, A0-F4h-r1.** Load 08:42:08–08:43:08. Kafka `kill -9` at 08:42:37 (exit 137), down for 150 s, started
   at 08:45:07. order-service never talks to Kafka at request time, so it **kept accepting orders at full rate
   throughout: 6,000 in 60 s**, each one committed with its outbox row. When the broker returned, the relay drained
   the backlog: zero lag 19.1 s later, 6,000 stored and 6,000 applied.
2. **A2-F4h-r1** (no outbox: commit, then send directly). Kafka `kill -9` at 08:50:05, back at 08:52:36. Each direct
   send now waited for topic metadata and failed: order-service logs
   `TimeoutException: Topic payments.money-orders.v1 not present in metadata after 60000 ms` 26 times. That is
   `max.block.ms`, a shorter wait than the 120 s delivery timeout §3.8 reasoned from. The wait happened **inside the
   HTTP request**, so request threads and connections stalled. The load that took 60 s in the control took **204 s,
   with 169,980 client retries**.
3. Verifier **I6**: 6,000 orders stored, **5,979 applied: 21 missing**, e.g. `not applied: 01a0b8db-c616-…`.
   These are orders the API committed and whose only send failed, and nothing will ever re-send them. **I6b**: 37
   accounts short by $1,193.

The outbox buys two things here: **no lost orders**, and an API that **stays available while the broker is down**.

### A1 under broker faults: why nothing broke

A1 broke under F2 (3 of 3) and under no broker fault (0 of 6). The likely reason, consistent with the logs but not
proven here: the ledger commits a batch to the database and acknowledges its Kafka offset straight away. A graceful
restart lets that acknowledgement finish, and in a hard kill the consumer is most likely waiting on the dead broker,
not holding an applied-but-unacknowledged batch. Redelivery of
an applied batch therefore needs the **consumer** to die in that few-millisecond gap, which is what the F2 hook does.
In this design the de-duplication guards against consumer crashes, not broker crashes. Under the rule fixed in
advance, A1 is still not valid: the F4h cell was pre-registered as part of its judgement, and it showed nothing.

### A note these logs surfaced

`A0-F12b-r2` logs the refused stale transition at **ERROR** (`could not record the outcome of attempt …`). The
behaviour is correct: an older timed-out answer must not overwrite a newer SUCCEEDED. But an expected race logged as
an error is noise that an on-call engineer would learn to ignore. It should be INFO, with a counter. Not changed
during the evidence series.

## 9. Confidence

With 0 violations in n independent runs, the 95 % upper bound on the per-run failure probability is about 3/n (rule
of three). For the full design that is **3/21 ≈ 14 % per run** across these fault sets. Per fault cell (n = 3) it is
wide, about 63 %. These runs show that each protection is exercised and matters; they do not bound rare failures
tightly. The master's plan of 20 runs of 10,000 trips per cell would.

## 10. Limitations

- **Scaled down**: 3–6 runs per cell and 200–9,000 trips per run, against the master's 20 × 10,000 (§3.3).
- **F6 not run** (no Toxiproxy route to the providers). F8 applied to FakeCard's webhooks only; FakeBank returns not
  exercised.
- The F2 and F3 crash points are **hooks inside the services**, armed per injection. They are deterministic, which is
  their point, but they are code paths only the `chaos` profile can enable (§3.1).
- One laptop, one PostgreSQL, one single-node Kafka (replication factor 1).
- **A1 is not valid** (§6). Making it valid would need a fault that kills the ledger consumer independently of any
  hook, at a rate that makes landing in the commit-to-acknowledge window likely.
