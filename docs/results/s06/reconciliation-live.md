# S06 — reconciliation against the running FakeCard, followed into the ledger

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | S06-LIVE (M11(a) end to end; M11(b) against the real provider) |
| Type | deployment check |
| Owning step | S06 (closes the "evidence stops at the outbox row" and "the provider is a stub" gaps in [reconciliation.md](reconciliation.md)) |
| Date (UTC) | 2026-09-18, 08:41–08:43 |

## 2. Status

- **Measured**, on the running Compose stack (8 containers, fresh volumes created 08:28 UTC).

## 3. Provenance

| Item | Value |
|---|---|
| Git commit SHA | `de9dacf`. Images built at 08:28 UTC from `b91c0bc`; only documentation and evidence files changed between the two |
| Versions | [ADR-0002](../../adr/0002-stack-and-pinned-versions.md). PostgreSQL 18.6, Kafka 4.3.1, nginx 1.29 (digests in `docker-compose.yml`) |
| Seeds | fault profile seed `9180002` for the faulted run; the clean run has no generative input |
| Hardware | Apple M4, 10 cores, 24 GiB, macOS 26.5.2, Docker Desktop VM 10 CPUs / 7.75 GiB |

## 4. Scenario

Script: [raw/recon_live.py](raw/recon_live.py). It reads tokens from `.env` at run time and writes none.

1. Register a card (`tok_card_ok`) for 5 fresh riders: `POST /v1/instrument-tokens`.
2. Post 20 `COMMERCE` orders (fares 1,000 to 1,950, total 29,500) to order-service. From there the path is the
   production one: outbox → Kafka → instrument-service's collection policy → attempt → **the running FakeCard** →
   `CHARGE_SUCCEEDED` → order-service's `COLLECTION` order → ledger. Wait until all 20 attempts are `SUCCEEDED`.
3. **Close the day.** FakeCard answers 409 for a day that has not closed, and it runs on the wall clock. So the run
   moves **both sides** of its own 20 charges back by whole days: FakeCard's `card_charges.created_at` and
   instrument-service's `payment_attempts.created_at`. This is the method `SettlementReportIT` and
   `RecoveryTestBase` already use. **It is a simulated day close, not a wait until midnight.** Only this run's rows
   are moved.
4. `POST /v1/reconciliation-runs {"provider":"fakecard","report_date":…}` as admin, then read the breaks. Then post
   again with a **different** idempotency key, as a second caller would.
5. Follow the settlement: the `SETTLEMENT` order in order-service, its application in the ledger, the clearing
   balance, and `/v1/invariants`.

Run twice: a clean day (2026-09-17), then a second day (2026-09-16) with FakeCard's three report-corruption knobs at
0.1 each. Those knobs were set just before FakeCard generated that day's report and restored to `{"seed":0}` straight
afterwards; the report is frozen once generated.

## 5. Results

### 5.1 Clean day, 2026-09-17 ([raw log](raw/recon-live-clean.log))

| Step | Observed |
|---|---|
| Attempts | 20 / 20 `SUCCEEDED`; FakeCard holds 20 `SUCCEEDED` charges summing to 29,500 |
| FakeCard report | HTTP 200, `rpt_2026_09_17`, 20 lines |
| Reconciliation run | **201 `COMPLETED`, 20 lines matched, 0 breaks, settled** |
| Second caller, different key | 200 with the **same `run_id`**, `replayed: true`. The day was settled once |
| Settlement order (order-service) | `SETTLEMENT settlement.received`: `platform:main/cash +28,044` · `platform:main/processing_fees +1,456` · `provider:fakecard/clearing −29,500` (sums to 0; 1,456 is the report's fee total) |
| Ledger | the settlement order is in `applied_orders`; `provider:fakecard/clearing` = **0**; invariants consistent, 0 quarantined |

The money went round the full loop: 20 collections put 29,500 into the provider's clearing account, and the
settlement moved it out to cash and fees.

### 5.2 Faulted day, 2026-09-16 ([raw log](raw/recon-live-faults.log))

| Step | Observed |
|---|---|
| Attempts | 20 / 20 `SUCCEEDED`, 29,500 |
| Reconciliation run | **201 `COMPLETED`, 14 lines matched, 8 breaks, settled** |
| Breaks, by type | `DUPLICATE_LINE` 2 (report 3,200) · `AMOUNT_MISMATCH` 4 (report 6,604 vs ours 6,600) · `MISSING_IN_REPORT` 2 (ours 3,350) |
| Settlement order | `cash +27,902` · `processing_fees +1,452` · `clearing −29,354`: the report's totals **as reported** |
| Ledger | `provider:fakecard/clearing` = **146**, and `/v1/invariants` lists it under `non_zero_clearing_balances` |

The residual reconciles exactly: 29,500 ours − 29,354 settled = 146, which is 3,350 missing − 3,200 duplicated −
4 off-by-one. The difference is **left on the clearing account and flagged**, never adjusted away (D06-4).

After both runs the ledger holds 143 applied orders; invariants I2–I4 consistent, 0 quarantined.

## 6. Gate compared against

- **M11(a)**, a matching settlement report produces a `SETTLEMENT` order: **met end to end**, on the real
  provider, through order-service, into the ledger.
- **M11(b)**, injected discrepancies produce typed breaks: already met by `ReconciliationRunIT`; **now also observed
  against the running FakeCard**. Each knob produced its own break type.
- **M11(c)**, reconciliation under chaos, 0 unexplained breaks after 2 cycles: **not run**. These are two separate
  days, not a chaos run, and the 8 breaks above are deliberately injected and remain `UNEXPLAINED`.

## 7. Limitations

- **The day close is simulated by backdating** both sides of this run's rows. A real cycle would need the stack
  running across a UTC midnight.
- **One provider.** FakeBank has no settlement reports (a capability it genuinely lacks), so it cannot be
  reconciled this way.
- **Two runs, 40 charges.** This shows the path works; it says nothing about volume.
