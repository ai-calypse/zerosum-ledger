# S08-M8b — 10,000 charges at FakeCard `timeout_after_commit_rate=0.2`

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | S08-M8b (master §3.1 M8 (b); fault F7 without its `http_500_rate` term) |
| Type | fault injection |
| Owning step and task | S08 |
| Date (UTC) | 2026-09-18 |

## 2. Status

- **Measured** — 10,000 charges, as the master specifies, run as 5 independent seeded runs of 2,000 against the same
  stack and the same fault profile. Not one run of 10,000: each run was verified before the next started (a hang or
  a defect would have cost one run, not the whole budget). The sum is exactly 10,000; nothing is extrapolated.

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | Runs 2–5: `b3610c69494432abae2ffeaddb0397e6ded50c37`, working tree clean. Run 1: `b422c86a2822f7ee08e596a7375375fd67dff464`, working tree DIRTY with 2 untracked paths — `docs/results/s08/raw/aggregate.py` and the not-yet-run `infra/tests/.../ablation/` sources, both committed unchanged in `b3610c6`. `CardTimeoutVolumeE2ETest` is byte-identical at both SHAs. Branched from `main` at `08f9c7f`. |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at the SHA above. Runtime-reported: instrument-service image `sha256:ce22a063fb8096deef5ae0515a5674a41fc5e0e8330f00217e6b258456279322` (contains `recovery/AttemptSweeper` and `AttemptResolver`, i.e. S05-T12; built 2026-09-17T21:19Z, after T12's commit, and no instrument-service change since), fake-providers image `sha256:72b4878b18e83366073a73eaa45838dbbee81ec480d1787cfe59ff9c007d102a`, test JVM OpenJDK 25.0.4.1 |
| Seeds | 8180001, 8180002, 8180003, 8180004, 8180005 (one per run; FakeCard's `seed` knob) |
| Hardware | Apple M4 (Mac16,12), 10 cores, 24.0 GiB RAM, macOS 26.5.2, aarch64 |

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | Docker 29.8.0 (Desktop), Compose v5.5.1 |
| Docker VM CPUs / memory | 10 CPUs, 7.75 GiB |
| Emulated images (non-native architecture) | none |
| Other load on the host during the run | not controlled; other agent sessions may have been active. No other traffic reached FakeCard in any window: 0 stray charges. |

## 5. Scenario, workload and seeds

Test: [`infra/tests/src/test/java/dev/zerosum/infra/volume/CardTimeoutVolumeE2ETest.java`](../../../infra/tests/src/test/java/dev/zerosum/infra/volume/CardTimeoutVolumeE2ETest.java).

- **Fault profile**, set through `PUT /admin/faults/fakecard` and restored in a `finally` block after each run:
  `{"timeout_after_commit_rate":0.2,"webhook_drop_rate":0.0,"seed":<seed>}`. FakeCard commits the charge, withholds
  the response for 7 s (`ZS_FAULT_TIMEOUT_WITHHOLD`), then breaks the connection.
- **Workload.** Each charge is a COMMERCE trip order (fare 1,000, fee 200) for one of 50 riders registered with
  `tok_card_ok`, posted to order-service. It crosses outbox → Kafka → instrument-service's collection policy →
  a CHARGE attempt → FakeCard. Nothing calls FakeCard directly. Orders are paced so no more than 120 are in flight,
  keeping the policy's bounded queue (200) from overflowing.
- **Quiesce.** All 2,000 attempts terminal; then each rider's ledger receivable must return to 0 (the collection
  order trails the charge).

**How each claim is measured.**

| Claim | Source |
|---|---|
| Exactly one successful charge per attempt | `card_charges` in the fakeproviders DB (the table `/admin/truth` reads), grouped by `client_reference`, which is the attempt id |
| No charge outside the attempts | `card_charges` created between profile activation and restore whose `client_reference` is not one of the run's attempts |
| How each outcome was learned | the attempt's own `attempt_transitions` (`response` = "provider accepted the submission"; `webhook` = settled by a webhook while still `SUBMITTING`; `resolver` = went `UNKNOWN`) |
| Per-row fault attribution | each lost response claims, one-to-one, a `fault_log` row with `fault_type = 'timeout_after_commit'` (lowercase, as `Decision.faultType()` stores it) between its `SUBMITTING` transition and its charge's `created_at`. Both tables live on one PostgreSQL server, so one clock. Rows left over must fall inside some `UNKNOWN` attempt's interval (a lost idempotent retry); any other row is "unexplained". |
| Collected once in the ledger | ledger `GET /v1/entities/{rider}/balances`: receivable 0 for all 50 riders |

## 6. Exact commands

> These runs used `e2eTest`. The failure-mode tests were retagged `chaos` afterwards, so that `make demo` (which
> runs `e2eTest`) can never kill a service or delete outbox rows; re-run them with `chaosTest`.

```sh
# stack up: docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --wait; .env symlinked into the worktree
for k in 1 2 3 4 5; do
  ./gradlew :infra:tests:chaosTest --tests '*CardTimeoutVolumeE2ETest' --rerun \
    -Dzs.volume.charges=2000 -Dzs.volume.seed=818000$k -Dzs.volume.label=m8b-chunk$k
done
python3 docs/results/s08/raw/aggregate.py     # regenerates every table below
```

## 7. Raw data

For each run *k* = 1…5 in [raw/](raw/):

- `m8b-chunk<k>.json` — provenance block and summary
- `m8b-chunk<k>-attempts.csv.gz` — one row per attempt: attempt id, order id, rider, final status, outcome path,
  provider charge counts, the four timestamps, the fault row it claimed, and its settling cause
- `m8b-chunk<k>-TEST.xml` — JUnit XML; each is `tests="1" skipped="0" failures="0" errors="0"`

## 8. Results

Generated by `raw/aggregate.py` from the run JSON.

| Run | Seed | Charges | Attempts | Exactly one successful charge | Charges per attempt | Outcome paths | `timeout_after_commit` rows | Lost responses | Matched 1:1 | Retry rows (ambiguous) | Unexplained | Stray charges | Riders receivable ≠ 0 | Wall s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 8180001 | 2000 | 2000 | 2000 | 1: 2000 | response 1604, webhook 396 | 396 | 396 | 396 | 0 (0) | 0 | 0 | 0 | 696 |
| 2 | 8180002 | 2000 | 2000 | 2000 | 1: 2000 | response 1624, webhook 376 | 376 | 376 | 376 | 0 (0) | 0 | 0 | 0 | 661 |
| 3 | 8180003 | 2000 | 2000 | 2000 | 1: 2000 | response 1641, webhook 359 | 359 | 359 | 359 | 0 (0) | 0 | 0 | 0 | 636 |
| 4 | 8180004 | 2000 | 2000 | 2000 | 1: 2000 | response 1586, webhook 414 | 414 | 414 | 414 | 0 (0) | 0 | 0 | 0 | 727 |
| 5 | 8180005 | 2000 | 2000 | 2000 | 1: 2000 | response 1599, webhook 401 | 401 | 401 | 401 | 0 (0) | 0 | 0 | 0 | 703 |
| **Total** | | **10,000** | **10,000** | **10,000** | 1: 10,000 | response 8,054, webhook 1,946 | **1,946** | **1,946** | **1,946** | 0 (0) | **0** | **0** | **0** | 3,423 |
| **Median of runs** | | | | | | webhook 396 | 396 | 396 | 396 | | 0 | 0 | 0 | 696 |
| **Range (min–max)** | | | | | | webhook 359–414 | 359–414 | 359–414 | 359–414 | | 0–0 | 0–0 | 0–0 | 636–727 |

Every attempt ended `SUCCEEDED`. The injected rate was 1,946 / 10,000 = 19.46 % of original submissions (target 0.2).

**Seconds from `SUBMITTING` to settled** (from the per-attempt CSVs):

| Path | n | min | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| response | 8,054 | 0.001 | 0.003 | 0.015 | 0.023 | 0.067 |
| webhook | 1,946 | 0.011 | 0.556 | 0.978 | 1.055 | 1.744 |

### Reconciling the fault count with the outcomes (lesson from the previous attempt)

The previous attempt saw 20 fault rows, 40 attempts all `SUCCEEDED` and none `UNKNOWN`, and could not reconcile them.
Here the reconciliation is per row and exact: each of the 1,946 `timeout_after_commit` rows is claimed by exactly one
attempt whose POST never answered, and each of those 1,946 attempts claims exactly one row. No attempt went `UNKNOWN`
because **the provider's webhook settled every one of them while it was still `SUBMITTING`** — at most 1.744 s after
submission, well before the adapter gave up on the POST. "Timed out, yet SUCCEEDED and never UNKNOWN" is therefore
the correct outcome of this profile, and it is the webhook path working, not the resolver.

### Supplementary run — the same fault with every webhook dropped (forces the S05-T12 resolver)

Not part of the 10,000: an extra 1,000-charge run with `{"timeout_after_commit_rate":0.2,"webhook_drop_rate":1.0,
"seed":8180101}`, so that no webhook can settle a lost response and every one must go `UNKNOWN` and be resolved by
`AttemptResolver`. A dropped webhook is rescheduled rather than lost (`WebhookSender`), so the dropped events drain
once the profile is restored. Provenance: SHA `b993b49` with the working tree DIRTY by two files that the test does
not read (this document, untracked, and `raw/aggregate.py`, modified); seed 8180101; same images and hardware.
Raw: `raw/m8b-resolver1.json`, `raw/m8b-resolver1-attempts.csv.gz`, `raw/m8b-resolver1-TEST.xml` (`tests="1"
failures="0" errors="0"`).

| Run | Seed | Charges | Attempts | Exactly one successful charge | Outcome paths | `timeout_after_commit` rows | Lost original responses | Matched 1:1 | Rows in UNKNOWN intervals (ambiguous) | Unexplained | Stray charges | Riders receivable ≠ 0 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| resolver1 | 8180101 | 1000 | 1000 | **1000** | response 771, **resolver 229** | 283 | 229 | 229 | 54 (54) | **0** | 0 | 0 |

- **All 229 lost responses went `UNKNOWN` and were settled by "idempotent retry: provider replayed"**: the resolver
  re-sent the charge under the attempt id as idempotency key, and FakeCard replayed the stored charge instead of
  creating a second one. Every one of the 229 has exactly one provider charge.
- **The 54 rows beyond the 229** are timeouts injected into the retries themselves (a retry is a POST and draws the
  fault like any other). Each falls inside at least one `UNKNOWN` attempt's interval, so none is unexplained; none
  can be pinned to a single attempt, because many intervals overlap (they are entered in bursts, 7 s after bursts of
  submissions). They created no charge: the charge count per attempt is 1 for all 1,000.
- `webhook_drop` rows: 4,637, the redelivery attempts of the dropped events.

| Path | n | min | p50 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| response | 771 | 0.001 | 0.003 | 0.006 | 0.008 | 0.014 |
| submit → `UNKNOWN` | 229 | 7.002 | 7.005 | 7.010 | 7.012 | 7.014 |
| resolver (submit → `SUCCEEDED`) | 229 | 7.783 | 56.218 | 98.270 | 175.584 | 196.646 |

The slowest resolution took 196.6 s, inside M8 (c)'s 5-minute bound. Resolutions share the policy's 4-thread
executor with new submissions, which is why the median is far above the first scheduled check at +1 s.

## 9. Gate or threshold compared against

[docs/zerosum_ledger_mvp_plan.md#must-have](../../zerosum_ledger_mvp_plan.md#must-have), M8 (b): "provider ground truth
shows exactly one successful charge per attempt". **Pass** — 10,000 / 10,000, over 10,000 charges.

Also observed, not claimed as a separate gate: M8 (c) — 0 attempts of any kind in the stack were in `CREATED`,
`SUBMITTING`, `UNKNOWN` or `NEEDS_REVIEW` (instruments DB) after run 5 (~07:53Z) and again after the supplementary
run (08:02Z); FakeCard's profile was back to all-zero with seed 0 both times.

## 10. Deviations and limitations

- **The S05-T12 resolver is not what this profile exercises.** With webhook delivery healthy, 0 of 10,000 attempts
  entered `UNKNOWN`. M8 (b)'s wording is still met, but it does not by itself show the resolver's idempotent retry
  under load. The supplementary run below (webhooks dropped) is what does; see its section.
- **5 × 2,000, not 1 × 10,000.** Each run activates the profile with its own seed, so the per-decision random streams
  restart; the fault sequence is reproducible per seed and request order, not bit-exact under concurrency (FakeCard's
  own stated boundary, `FaultProfiles`).
- **F7 as the fault matrix states it also sets `http_500_rate=0.05`.** M8 (b) specifies only the timeout rate, and
  that is what ran. A 500 commits nothing, so it adds a different path (a clean failure), not a harder version of this one.
- **Observation, not investigated:** in the supplementary run, all 229 attempts entered `UNKNOWN` 7.002–7.014 s after
  `SUBMITTING` — FakeCard's withhold — not at the 5 s adapter read timeout configured in
  `services/instrument-service/src/main/resources/application.yml`. The transition cause still reads "read timeout".
  Whatever bounds the call, it is not the configured 5 s. It also sets throughput: each lost response holds one of
  the 4 policy threads for 7 s, which is why 2,000 charges took 636–727 s. Recorded for the owner of D05-14;
  `services/**` was out of scope here.
- Single host, simulated provider; results are environment baselines, not production claims.
