# S09 — the operator dashboard (ZeroSum Explorer)

Date: 2026-09-18, 08:45–09:47 UTC. Built on `main`; the page is commit `c2e995c`, the read endpoints behind it are
`743ebbe`, `0565641`, `a2abc89` and `e0f3a6f`.

The Explorer was a set of look-up forms. It is now a dashboard of the whole running system, reached through the
one-origin proxy at **http://127.0.0.1:8080/explorer.html** (`docker-compose.demo.yml`). It is still one file,
`services/ledger-service/src/main/resources/static/explorer.html`, served by ledger-service, with no third-party script,
style, font or image: every chart is SVG built with `createElementNS`, and every value is set with `textContent` or an
SVG attribute. Design reference: the Sentinel operator dashboard in `ojuri-io/ojuri` (restrained warm-grey surfaces,
sidebar navigation, serif figures, three muted status colours); no code or asset was copied from it.

![Overview](../../images/dashboard-overview.png)

## What each tab shows, and what feeds it

All paths are through the proxy. "reader" and "admin" are the bearer token the call carries; "none" means the page
sends no `Authorization` header at all.

| Tab | Panel | Source | Token |
|---|---|---|---|
| Overview | KPI tiles: money orders and how many do not sum to zero, trip volume, global sum (I2), invariants, charges collected, money held in clearing, quarantined orders, consumer lag | `/orders/v1/money-orders/summary`, `/v1/accounts/summary`, `/v1/invariants`, `/instruments/v1/payment-attempts/summary`, `/v1/freshness` | reader |
| | Needs attention: failing invariants with their violation lists, every non-zero clearing balance, quarantine, paused listener, lagging partitions, UNKNOWN/NEEDS_REVIEW attempts, reconciliation breaks, a refused payout run, outbox backlog, pending provider webhooks, and the standing note that I5 is verified per entity | the same, plus `/instruments/v1/reconciliation-runs`, `/instruments/v1/payout-runs`, `/orders/v1/outbox/stats`, `/providers/admin/summary` | reader, admin |
| | Services: health and response time of ledger-service, order-service, instrument-service, fake-providers, Grafana (otel-lgtm) and the proxy itself | `/actuator/health`, `/orders/actuator/health`, `/instruments/actuator/health`, `/providers/actuator/health`, `/grafana/api/health`, `/healthz` | none |
| | Consumer lag by partition (12 bars), pipeline freshness (outbox → Kafka → apply) | `/v1/freshness`, `/orders/v1/outbox/stats` | reader |
| | Orders applied per minute, last hour | Prometheus `query_range` through `/grafana/api/datasources/proxy/uid/prometheus/` | none |
| Money flow | Flow diagram: account classes as boxes (ledger balance on the normal side), one arrow per order reason (trip.completed, charge.succeeded, settlement.received, payout.accepted/settled, and the reversals refund.succeeded, payout.failed, payout.returned), width ∝ amount | box: `/v1/accounts/summary`; arrow: `legs` of `/orders/v1/money-orders/summary` | reader |
| | Orders by type (count and gross volume); every reason's credits against its debits with order-service's recount of unbalanced orders; balances by account class with ledger-service's per-currency total | the same two endpoints | reader |
| Ledger explorer | Entity look-up (exact id, with suggestions from recent attempts), balances, changelog (paged, source and idempotency key per row), verify (rebuilds balances and rehashes the chain), and the money order behind any row with its entries summed per currency | `/v1/entities/{id}/balances`, `/changelog`, `POST /verify`, `/orders/v1/money-orders/{id}` | reader |
| Payments | KPIs, status donut, kind × provider bars stacked by status, the newest 100 attempts; an attempt's transition history with UNKNOWN and resolver steps highlighted, and what the provider itself recorded for it | `/instruments/v1/payment-attempts/summary`, `/instruments/v1/payment-attempts?limit=100`, `/instruments/v1/payment-attempts/{id}`, `/providers/admin/truth?client_reference=` | reader, admin |
| Reconciliation | Runs with matched lines vs breaks, breaks by type for the selected run, the breaks themselves (type/status filters), the money held in clearing, and **provider clearing reconciled across services**: collections − refunds − settlements summed from order-service, set against the balance ledger-service holds | `/instruments/v1/reconciliation-runs`, `/instruments/v1/reconciliation-runs/{id}/breaks`, `/v1/invariants`, `/orders/v1/money-orders/summary`, `/v1/accounts/summary` | reader |
| Payouts | Owed to drivers, paid by runs, in flight, settled, returned/failed; payout runs with drivers by outcome; payout attempts by status; recent payout attempts | `/instruments/v1/payout-runs`, `/instruments/v1/payment-attempts?kind=PAYOUT`, `/v1/accounts/summary` | reader |
| Providers | FakeCard charges and refunds, FakeBank payouts, faults injected, webhooks pending, the active fault profile of both providers (read-only), truth by client reference | `/providers/admin/summary`, `/providers/admin/truth` | admin |
| Live metrics | Orders applied/min, order → ledger latency p95, ledger apply p95, entity lock wait p95, oldest unpublished outbox row, orders seen by the collection policy, oldest UNKNOWN attempt, attempts needing review, HTTP requests by service; each card shows its PromQL | Prometheus through Grafana's datasource proxy | none |
| Measured results | The recorded figures, static and labelled "measured 2026-09-18, see docs" | none (static) | — |

Nothing that takes a ledger snapshot polls: everything behind a token loads when a token is entered or Refresh is
pressed. The only timer is the Live metrics tab, which reads Grafana alone, every 15 s, and only while that tab is open
and visible.

## Endpoints added (all read-only GETs, reader role unless stated)

| Service | Endpoint | Tests |
|---|---|---|
| ledger-service | `GET /v1/accounts/summary`: stored balances summed by entity kind, account and currency in one statement, plus the per-currency totals summed server-side from the same rows (zero is I2) | `AccountsSummaryApiIT` (every class equals the accounts table; totals are zero; 401), `LedgerOpenApiContractIT` (schema) |
| order-service | `GET /v1/money-orders/summary`: orders per type, reason and currency with gross volume and `unbalanced_orders` recounted from the stored entries; and the legs, every entry summed per (type, reason, entity kind, account, currency); one REPEATABLE READ snapshot | `OrdersSummaryIT` (deltas around two created orders, legs net to zero, exact field set, 401), `OpenApiSpecConsistencyTest` (spec ⇔ handlers) |
| instrument-service | `GET /v1/payment-attempts` (newest first, optional `kind`), `GET /v1/payment-attempts/summary`, `GET /v1/reconciliation-runs` (with `breaks_by_type`), `GET /v1/payout-runs` (outcome counts and `paid_minor`, refusals included). A `limit` outside 1..500 or an unknown `kind` is refused with the new problem code `invalid_query` rather than clamped | `DashboardListsIT` (ordering, filter, counts, folding, 401, `invalid_query`, every body validated against the spec) |
| fake-providers | `GET /admin/summary` (admin): charges, refunds and payouts counted by status, fault counts, undelivered webhooks, both active fault profiles. Absent under `demo-public` like the rest of `/admin` | `AdminSecurityIT` (401, 403, counts, profile), `DemoPublicAdminIT` (404) |

The ledger, order and instrument endpoints are in their `openapi/*.yaml`; fake-providers has no OpenAPI file, as before.

`ExplorerHeaders` now also sends `Cache-Control: no-cache` on the page: during this work a browser kept serving the
previous build after a redeploy, which would show an old dashboard against a new API.

## The page's safety contract, unchanged

`ExplorerServingIT` still holds, unmodified: one inline `<script>` and one inline `<style>`, both hashed into the CSP
at startup; `default-src 'none'`, `connect-src 'self'`, `frame-ancestors 'none'`; no `<link>`, no `<script src`; no
`innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write` or `eval(` in the script; exactly two password inputs
(reader, admin); no embedded token; title "ZeroSum Explorer". Tokens are held in two JS variables only, dropped on a
401/403 from the service that holds them, and never sent to Grafana or to a health endpoint.

## Verified live

On the running Compose stack (8 containers) through the proxy, in the gstack `browse` headless Chromium at 1440 × 900
and 390 × 844:

- Every tab rendered real values; `browse console --errors` reported **no console errors and no CSP violation** on
  any tab after the final build.
- At 390 px no tab scrolls horizontally (`scrollWidth == innerWidth` on all nine); the flow diagram and wide tables
  scroll inside their own panel.
- The dark theme (system preference or the Theme button) was checked on Money flow and Payments.
- Figures on the screenshots, all read at 09:33 UTC: 413 money orders, every one summing to zero; trip volume
  $31,511.30 over 229 trips; global sum $0.00 across 130 accounts; invariants consistent; 161 of 165 charges succeeded
  ($4,063.50); consumer lag 0 on all 12 partitions; 40.4 orders applied per minute. Provider clearing: order-service's
  collections $4,088.50 − refunds $12.00 − settlements $588.54 = **$3,487.96, equal to the balance ledger-service
  holds** ("services agree"). Attempt `01a0b3d3-2f33-7e5a-aff8-92f011054c3b` shows the lost-response path:
  SUBMITTING → UNKNOWN ("submission outcome unknown: read timeout") → SUCCEEDED ("idempotent retry: provider
  replayed"), and FakeCard's own record of the same charge. One payout run: 8 payouts, 6 settled, 1 returned (R01),
  1 failed (account closed), $1,128.40 paid.

Screenshots (tokens appear only as password-field bullets):

- [`docs/images/dashboard-overview.png`](../../images/dashboard-overview.png)
- [`docs/images/dashboard-money-flow.png`](../../images/dashboard-money-flow.png)
- [`docs/images/dashboard-payments.png`](../../images/dashboard-payments.png)
- [`docs/images/dashboard-reconciliation.png`](../../images/dashboard-reconciliation.png)
- [`docs/images/dashboard-payouts.png`](../../images/dashboard-payouts.png)

### How the stack was populated

Through the public APIs only, with the scripts in [raw/dashboard/](raw/dashboard/) (they read tokens from `.env` at
run time and print none):

1. `populate.py`: 23 rider cards (20 `tok_card_ok`, 2 insufficient funds, 1 processing error) and 8 driver bank
   accounts (6 ok, 1 `tok_bank_return_R01`, 1 `tok_bank_fail_account_closed`); 36 trips; 15 trips with FakeCard's
   `timeout_after_commit_rate` at 0.4 (seed 918), whose lost responses were resolved by webhooks; 3 fare adjustments,
   which became refunds; one payout run; 12 more trips. FakeCard restored to `{"seed":0}`.
2. `populate_unknown.py`: 8 trips with `timeout_after_commit_rate` 0.6 **and** `webhook_drop_rate` 1.0 (seed 919), so
   the only way out of UNKNOWN was the resolver; 5 attempts took it. FakeCard restored to `{"seed":0}` (checked in
   `fault_profiles`: every rate 0, seed 0).
3. `trickle.py`: one trip every 3 s for 4 minutes (80 trips), so the live charts had a current reading for the
   screenshots.

### How the tokens were typed without being written anywhere

gstack `browse` appends every command's arguments to `.gstack/browse-audit.jsonl`, so a `fill` with the token would
have put it in a file. Instead `raw/dashboard/token_relay.py` held the two tokens in memory and served them on
127.0.0.1 to a helper tab on the proxy's origin, which handed them to the Explorer tab over a `BroadcastChannel`. A
`grep -F` of every file under `.gstack/`, `docs/images/` and `raw/dashboard/` for each token found none.

## Incident during this work: one test order reached the live ledger

Running the existing `AttemptEndpointsIT` (instrument-service) with the stack up, its Spring context connected to the
**live** Kafka broker on `localhost:9092` (the default `ZS_KAFKA_BOOTSTRAP`) and published a `CHARGE_SUCCEEDED` payment
event for a test attempt. The running order-service booked it as a real `COLLECTION` order at 08:54:25 UTC:

- order `01a0b3b9-5f71-7f7a-9e4b-ebdc11a68c5e`, idempotency key
  `627de3a4-9b97-48e2-b8fa-851be7440a2d:CHARGE_SUCCEEDED`, group `trip_627de3a4`;
- `provider:fakecard/clearing +2,500`, `rider:R_627de3a4-9b97-48e2-b8fa-851be7440a2d/receivable −2,500`.

It is zero-sum and the ledger is consistent, but it is not real activity: `provider:fakecard` clearing went from the
146 recorded in [reconciliation-live.md](../s06/reconciliation-live.md) to 2,646 **before** any of the population
above, and the live system now has one more `charge.succeeded` order (162) than succeeded charge attempts (161).
Nothing was deleted or adjusted to hide it; the ledger is append-only and a compensating entry would be one more
fabricated order.

Fixed for the tests I touched: `AttemptEndpointsIT`, `DashboardListsIT` and `OrdersSummaryIT` pin
`spring.kafka.bootstrap-servers` to `127.0.0.1:1`, as `LedgerApiTestBase` already did. **Not fixed:** these Spring tests
assume no broker is listening and do not pin it: `PayoutTestBase`, `RecoveryTestBase`, `ReconciliationRunIT`,
`TransitionConcurrencyIT`, `WebhookReceiverIT` (instrument-service) and `MoneyOrderApiIT`, `OutboxStatsIT`,
`OpenApiSpecConsistencyTest` (order-service). Run while the stack is up, any of them that emits an event can publish it
into the running system. The full integration suites below were run with `ZS_KAFKA_BOOTSTRAP=127.0.0.1:1` and
`ZS_OTLP_METRICS_URL=http://127.0.0.1:1/v1/metrics` for that reason. The earlier, targeted IT runs (08:49–09:22 UTC)
did not override `ZS_OTLP_METRICS_URL`, so their metrics may have been pushed into the live Prometheus under the same
job names; the Live metrics charts for that window can include test traffic.

**Since fixed at the root** (`ee4be74`): the shared build convention now gives every test task
`ZS_KAFKA_BOOTSTRAP=127.0.0.1:1` and `ZS_OTLP_METRICS_URL=http://127.0.0.1:1/v1/metrics`, so none of the contexts listed
above can reach a running stack any more, whether pinned individually or not.

## Test results

Full `integrationTest` suites of the four services touched, run 09:32–09:47 UTC with `--rerun`, counts summed from
`build/test-results/integrationTest/TEST-*.xml` (every file timestamped inside that window):

```sh
export ZS_KAFKA_BOOTSTRAP=127.0.0.1:1 ZS_OTLP_METRICS_URL=http://127.0.0.1:1/v1/metrics
./gradlew :services:ledger-service:integrationTest --rerun      # then order-service, instrument-service, fake-providers
```

| Service | Test classes (incl. nested) | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|
| ledger-service (includes `ExplorerServingIT` 4/4, `AccountsSummaryApiIT` 2, `LedgerOpenApiContractIT` 7) | 30 | 96 | 0 | 0 | 0 |
| order-service (includes `OrdersSummaryIT` 2, `OpenApiSpecConsistencyTest` 5) | 7 | 57 | 0 | 0 | 0 |
| instrument-service (includes `DashboardListsIT` 5, `AttemptEndpointsIT` 6 + 3 nested) | 26 | 75 | 0 | 0 | 0 |
| fake-providers (includes `AdminSecurityIT` 10, `DemoPublicAdminIT` 2) | 10 | 49 | 0 | 0 | 0 |
| **Total** | 73 | **277** | 0 | 0 | 0 |

Afterwards the live stack was checked for leakage: order-service holds 465 orders and ledger-service has applied 465,
none created after 09:32 UTC outside the `trip_dash0219_*` groups the population scripts use; FakeCard's profile is
`{"seed":0}` with every rate 0.

## Not done, and limits

- **Hash values are not displayed.** The changelog API deliberately does not expose `prev_hash`/`row_hash`; the chain
  is shown through the verify action (rows rehashed, first bad sequence if any), not as hashes.
- **Entity search is exact-id.** There is no search endpoint (§0.3 C15); the input suggests ids seen in recent
  attempts, clearing accounts and `platform:main`.
- **Money held in clearing is not split** into "collected, not yet settled" and "residual of settled reports". The
  panel reconciles the total across both services instead; splitting it would need the per-day settlement join the
  S06 verifier does.
- **Live metrics depend on Grafana's anonymous access** in otel-lgtm and on the proxy. Metrics are pushed every 60 s,
  so the newest minute lags, and a p95 over an idle window is NaN: the card then shows the last sample and its age
  rather than a zero.
- **The Explorer shares its origin with Grafana** because of the one-origin proxy. Tokens are never stored, but a
  script on a Grafana page holding a reference to the Explorer's window could read the two password fields. This
  predates the dashboard (S09 proxy decision) and is noted, not changed.
- Run lists are the newest 30 reconciliation and payout runs, and the newest 100 attempts.
