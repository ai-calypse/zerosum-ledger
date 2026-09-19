# S08-M13b — ablation experiment A0–A5, B0

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | S08-M13b (master §3.1 M13 (b); ablation table [§8.5](../../zerosum_ledger_mvp_plan.md#ablation); faults [§8.4](../../zerosum_ledger_mvp_plan.md#fault-matrix)) |
| Type | ablation |
| Owning step and task | S08 (S08-T03 seams and guard, S08-T04/T05 runner and runs) |
| Date (UTC) | results pending — see §2 |

## 2. Status

- **Run plan committed; evidence runs not yet executed.** This section is replaced with the measured status once the
  runs below exist. Until then nothing in this document is a result.

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

## 4–10. Results

Pending the evidence runs.
