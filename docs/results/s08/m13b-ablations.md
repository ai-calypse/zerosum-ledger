# S08-M13b — ablations A1–A4

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | S08-M13b (master §3.1 M13 (b); ablation table [§8.5](../../zerosum_ledger_mvp_plan.md#ablation)) |
| Type | ablation |
| Owning step and task | S08 (S08-T03 seams, S08-T05 runs) |
| Date (UTC) | 2026-09-18 |

## 2. Status

- **Not run as specified — M13 (b) is NOT MET.** A1–A4 are defined as chaos-profile flags that disable one
  protection each in ledger-service, `libs/outbox`, instrument-service and order-service (S08-T03). None of those
  seams exists (`grep -ri ablation services libs` finds nothing), and this task was not permitted to edit
  `services/**` or `libs/**`. A variant that cannot be switched on cannot produce its failure class, so the validity
  rule (predicted class in ≥ 50 % of runs) cannot be evaluated for any of A1–A4 or B0.
- **Measured instead, and labelled as such:** two variants that disable the protection *from outside the code*, where
  that is possible without leaving the shared stack corrupted:
  - **A2, emulated by state** — the dual-write crash state, produced exactly. Predicted class occurred in 5 / 5 runs.
  - **A4, database layer only** — the zero-sum triggers disabled inside a rolled-back transaction. Predicted class
    (a stored order that does not sum to zero) occurred in 5 / 5 runs; the application and ledger layers stayed on.

These show that the protections they touch are load-bearing and that the checks used by M4 (a) and M8 (b) would see
the failure. They are not the A2 and A4 variants of the master, and they do not satisfy M13 (b).

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | `680c72a076a8e6cbd022a5a0f26cb07f6a996587` for both. A4 ran on a clean tree; A2 ran with 2 untracked files, A4's raw output (`raw/a4-*.{txt,xml}`), which the A2 test does not read. |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at the SHA above. order-service image `sha256:6049b4bf815a8bdadefcb9f4a050723e877d7e63dedb4cd08ac4d7d31b899fd7`; PostgreSQL `postgres:18.6`, Kafka `apache/kafka:4.3.1` (digests in `docker-compose.yml`); test JVM OpenJDK 25.0.4.1 |
| Seeds | none (non-generative: fixed bodies, random run ids) |
| Hardware | Apple M4 (Mac16,12), 10 cores, 24.0 GiB RAM, macOS 26.5.2, aarch64; Docker Desktop 29.8.0, VM 10 CPUs / 7.75 GiB |

## 4. Host and Docker allocation

Same host and stack as [m4a-crash-recovery.md](m4a-crash-recovery.md) §4. Other host load not controlled.

## 5–8. Per variant: method, commands, raw data, results

### A2 — outbox → dual write — **emulated by state; predicted failure in 5 / 5**

**Why this is a faithful emulation, and where it stops.** A2 predicts "committed orders never applied (I6)", and that is
a statement about the state a dual-writing service is left in when it dies between commit and send: the order is
committed and nothing durable says it still has to be published. The test builds that state exactly and changes nothing
else. It runs the M4 (a) scenario — Kafka paused, 20 orders committed, `docker kill -s KILL` order-service — then
deletes those 20 orders' outbox rows (the one thing the outbox adds; `orders_app` may delete outbox rows, the cleanup
job does). Kafka resumes, order-service restarts, and the test waits 20 s, four times M4 (a)'s bound. It does **not**
exercise a real direct-send code path, so producer behaviour under A2 (retries, `acks`) is not tested.

**Restore.** The deleted rows are re-inserted unchanged. That is the protection's effect coming back, and it also
repairs the shared stack. The un-ablated control is M4 (a): the same scenario without the deletion lost 0 of 100.

Test: [`infra/tests/src/test/java/dev/zerosum/infra/ablation/OutboxAblationE2ETest.java`](../../../infra/tests/src/test/java/dev/zerosum/infra/ablation/OutboxAblationE2ETest.java)

> These runs used `e2eTest`. The failure-mode tests were retagged `chaos` afterwards, so that `make demo` (which
> runs `e2eTest`) can never kill a service or delete outbox rows; re-run them with `chaosTest`.

```sh
./gradlew :infra:tests:chaosTest --tests '*OutboxAblationE2ETest' --rerun -Dzs.ablation.repetitions=5 -Dzs.ablation.orders=20
```

Raw: [raw/a2-outbox-ablation.txt](raw/a2-outbox-ablation.txt), [raw/a2-TEST.xml](raw/a2-TEST.xml)
(`tests="1" skipped="0" failures="0" errors="0"`, 131.5 s).

| Rep | Orders committed, unpublished at kill | Outbox rows deleted (ablation) | Applied after 20 s | **Lost (I6)** | Ledger's own `/v1/invariants` while lost | Applied after restore | Rider receivable after 20 s → after restore | Invariants after restore |
|---|---|---|---|---|---|---|---|---|
| 1 | 20 | 20 | 18 | **2** | consistent | 20 | 18,000 → 20,000 | consistent |
| 2 | 20 | 20 | 1 | **19** | consistent | 20 | 1,000 → 20,000 | consistent |
| 3 | 20 | 20 | 6 | **14** | consistent | 20 | 6,000 → 20,000 | consistent |
| 4 | 20 | 20 | 1 | **19** | consistent | 20 | 1,000 → 20,000 | consistent |
| 5 | 20 | 20 | 7 | **13** | consistent | 20 | 7,000 → 20,000 | consistent |
| **Median** | 20 | 20 | 6 | **14** | | 20 | | |
| **Range** | 20–20 | 20–20 | 1–18 | **2–19** | | 20–20 | | |

- **Predicted class in 5 / 5 runs** (67 of 100 committed orders lost). Meets the ≥ 50 % validity threshold *for this
  emulation*.
- **Not all were lost, and that is the realistic part.** 1–18 orders per run still reached the ledger. These are the
  produce requests the killed process had already written into the paused broker's socket, which the broker appended
  when it resumed — exactly what a dual-writer's in-flight sends would do. The loss is partial and varies run to run,
  so a check that expected "all or nothing" would misjudge it.
- **The ledger cannot see this failure.** Its own `/v1/invariants` said `consistent` in every run while orders were
  missing: an order that never arrives breaks no ledger-internal invariant. Only the cross-store comparison (orders DB
  vs `applied_orders`, the verifier's I6) detects it.
- **Restore:** in every run all 20 were applied once, the receivable came to exactly 20 × 1,000 (the orders that got
  through early were deduplicated, not doubled), and the ledger stayed consistent.

### A4 — zero-sum validation — **database layer only; predicted failure in 5 / 5**

A4 disables three layers: the application rule, the deferred DB triggers, and the ledger's re-check (§0.3 E8). The
first and third are code with no seam, so **they stayed on**. The test shows the application layer refusing the same
imbalance (`POST` → **422** `not_zero_sum`) and then bypasses it by writing SQL as `orders_owner`. It only reaches the
database layer.

Each repetition, in one transaction as `orders_owner`, rolled back at the end:

1. **Control, triggers ON.** Insert a COMMERCE order whose rider line is 1 minor unit too large (1,001 / −800 / −200,
   A4's ±1 fare-split bug), then `SET CONSTRAINTS ALL IMMEDIATE`, which runs the deferred check COMMIT would run.
2. **Ablated, triggers OFF.** `ALTER TABLE ... DISABLE TRIGGER` on `money_order_entries_zero_sum` and
   `money_orders_have_entries`, the same insert, the same forced check, then read back the stored entries' sum.
3. **Restore.** Roll back. PostgreSQL DDL is transactional, so the triggers come back with the rollback. Confirm
   `pg_trigger.tgenabled = 'O'` and that neither order exists.

Why rolled back: `money_orders` is append-only. A committed unbalanced order could never be removed from the shared
stack and would violate I1 there permanently.

Test: [`infra/tests/src/test/java/dev/zerosum/infra/ablation/ZeroSumTriggerAblationE2ETest.java`](../../../infra/tests/src/test/java/dev/zerosum/infra/ablation/ZeroSumTriggerAblationE2ETest.java)

```sh
./gradlew :infra:tests:chaosTest --tests '*ZeroSumTriggerAblationE2ETest' --rerun -Dzs.ablation.repetitions=5
```

Raw: [raw/a4-zero-sum-trigger-ablation.txt](raw/a4-zero-sum-trigger-ablation.txt), [raw/a4-TEST.xml](raw/a4-TEST.xml)
(`tests="1" skipped="0" failures="0" errors="0"`).

| Rep | Triggers ON: forced commit check | Triggers OFF: forced commit check | Stored entries sum, OFF | Triggers after rollback | Orders left after rollback |
|---|---|---|---|---|---|
| 1 | rejected, SQLSTATE 23514 | accepted | **+1** | O, O | 0 |
| 2 | rejected, SQLSTATE 23514 | accepted | **+1** | O, O | 0 |
| 3 | rejected, SQLSTATE 23514 | accepted | **+1** | O, O | 0 |
| 4 | rejected, SQLSTATE 23514 | accepted | **+1** | O, O | 0 |
| 5 | rejected, SQLSTATE 23514 | accepted | **+1** | O, O | 0 |

- **Predicted class (I1, an order that does not sum to zero) in 5 / 5** at the storage layer with the triggers off;
  0 / 5 with them on.
- **Not shown:** that such an order would go on to break I2 in the ledger. That needs the application layer off (so
  the order is accepted and written to the outbox) and the ledger re-check off (so it is applied instead of
  quarantined). Both are code.

### A1 — ledger consumer dedupe — **Not run**

**Reason: the seam does not exist, and no outside lever disables this protection.** The dedupe is one statement in
`services/ledger-service/src/main/java/dev/zerosum/ledger/store/LedgerStore.java`: `INSERT INTO applied_orders ... ON
CONFLICT (order_id) DO NOTHING RETURNING order_id`, and only the ids it returns are applied. Skipping it needs the
S08-T03 A1 seam in the apply engine. The two outside levers both fail to produce A1:

- dropping the `applied_orders` primary key makes that statement *error* ("there is no unique or exclusion constraint
  matching the ON CONFLICT specification"), so the consumer fails or quarantines instead of double-applying. That is a
  different failure class and says nothing about A1;
- deleting an `applied_orders` row and redelivering would double-apply, but only after the owner disables the
  append-only trigger. The inflated balance could never be removed from the append-only changelog, leaving the shared
  stack permanently I6b-violated.

M4 (a) does show the dedupe *working* against real duplicates: 21–24 copies of 20 orders on the topic, 20 applied.

### A3 — `UNKNOWN` handling (fresh provider key on timeout) — **Not run**

**Reason: the seam does not exist.** The resolver retries a charge through `FakeCardInstrument.charge`, which always
sends the attempt id as the idempotency key (`AttemptResolver.retry` → `reissue`). No configuration changes the key.
Calling FakeCard directly with a second key would exercise FakeCard, not instrument-service. It would show only that a
provider given two keys charges twice, and it would leave undeletable duplicate charges (`card_charges` has no DELETE
grant) in the shared stack's ground truth and settlement reports.

M8 (b)'s supplementary run shows the protection *working*: 229 lost responses went `UNKNOWN`, and each was retried
under its original key and replayed by FakeCard, with one charge per attempt.

### B0 — composite — **Not run**

Needs A1–A4 together. A1 and A3 do not exist, and A4 exists here only for its database layer.

### A5 (optional) — webhook `provider_events` dedupe — **Not run**

The dedupe is `ON CONFLICT (provider, provider_event_id) DO NOTHING` in
`services/instrument-service/.../webhooks/WebhookReceiver.java`. There is no seam.

## 9. Gate or threshold compared against

[docs/zerosum_ledger_mvp_plan.md#must-have](../../zerosum_ledger_mvp_plan.md#must-have) M13 (b), and the validity rule
of [§8.5](../../zerosum_ledger_mvp_plan.md#ablation): **not met** (A1, A3, B0 not run; A2 and A4 not run as defined).

What this leaves for the "0 violations proves nothing" risk (R6):

| Protection | Can this evidence's checks see it fail? |
|---|---|
| Outbox (A2) | Yes. The cross-store I6 count caught 67 lost orders the ledger's own check did not. |
| DB zero-sum triggers (A4, DB layer) | Yes, at the storage layer. |
| Ledger dedupe (A1) | Not shown. |
| Idempotent retry / `UNKNOWN` handling (A3) | Not shown by ablation. M8 (b)'s per-attempt check counts provider charges per attempt, so a second charge would show as `charges = 2`, but no run produced one. |

## 10. Deviations and limitations

- The two measured variants are **emulations outside the code**, not the S08-T03 seams, and are labelled that way
  everywhere they are cited. They must not be quoted as "A2 / A4 valid".
- A2's loss depends on how many sends were already in flight when the process died (1–18 of 20 got through here).
  A different broker pause or kill timing would change the count, not the class.
- 5 repetitions each. The master's sample sizes for ablation cells are not met.
- **Path to M13 (b):** implement S08-T03 (seams and startup guard, through change requests against S02, S03 and S05),
  then rerun A1–A4 and B0 through the same checks. The A2 test's cross-store count and M8 (b)'s per-attempt provider
  check are ready to be pointed at the real variants.
