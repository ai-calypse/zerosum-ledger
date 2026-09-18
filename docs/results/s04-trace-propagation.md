# S04-T06 — Trace propagation across the pipeline

- **Date:** 2026-09-16 (first run), re-verified after the CR-S04-01 fix
- **Git SHA:** `de61328` (fix), first run at `ebc1110`
- **Stack:** `docker compose` per D00-3 — PostgreSQL 18.6, Kafka 4.3.1 KRaft, grafana/otel-lgtm 0.33.0, OTel agent 2.31.1
- **Method:** one money order posted through the live API with a writer token, traced end to end, spans read from
  Tempo's query API (`/api/search`, `/api/traces/<id>`) inside the `otel-lgtm` container

## Classification

**Connected (links).**

The request's trace now reaches Kafka, and the ledger's apply is reachable from it by an explicit span link. It is
deliberately **not** described as a single parent-child trace: batch consumption produces a linked processing span
rather than a parented one, which is the normal pattern and is what S07-T01 must account for when it builds M12 (a)
evidence.

## Evidence after the fix

| Trace | Spans | Relationship |
|---|---|---|
| `5a3c0d40536213f7c7171a3da765fb7e` | `POST /v1/money-orders` (SERVER, root) → `INSERT orders.money_orders`, `INSERT orders.money_order_entries`, `INSERT orders.outbox`, and **`payments.money-orders.v1 publish` (PRODUCER, child)** | The publish is now **inside the request's trace** |
| `dff706fd0e8a31a0e01aa35543cda6cb` | `payments.money-orders.v1 process` (CONSUMER, root, **1 link**) → the whole apply transaction, ending in `INSERT ledger.entity_changelog` | The link resolves to **`5a3c0d40…`**, the request trace |

Two traces joined by a link, where there had been three joined by nothing useful.

## What the first run found (kept, because the defect was real)

Before the fix there were **three** traces:

| Trace | Root | Problem |
|---|---|---|
| `adb50356bf69f536b62819dd700eab0c` | `POST /v1/money-orders` | Contained the DB work including `INSERT orders.outbox`, but **no producer span at all** |
| `6d95da62c5376119042c545083a5f9e4` | `payments.money-orders.v1 publish` | **An orphan root** — one span, parented by nothing, belonging to no request |
| `c35834254ea0c9c0428779a4146c2ca6` | `payments.money-orders.v1 process` | Linked to the **orphan**, not to any request |

So the chain read: request trace → *(break)* → orphan publish → *(link)* → apply. M12 (a) was not met.

## Why it broke, and what the fix actually changes

§0.3 C11 and D03-5 require the trace context captured in the outbox row at write time to be **restored** when the relay
sends. The relay already stored `traceparent` and copied it onto the `ProducerRecord` — and that was not enough. The
agent's producer instrumentation builds the publish span from the **current thread's** context, which on the relay
thread is empty, and then writes its own `traceparent` over the copied one.

**Storing a header is not restoring a context.** The relay now rebuilds the context from the row's own headers with
`W3CTraceContextPropagator` and makes it current around each send, per record rather than per batch — a batch mixes
rows written by different requests, and one context for the whole batch would file every publish under whichever
request happened to be first. A row with no stored `traceparent` still sends, untraced; refusing to publish would turn
a tracing gap into stuck money.

`RelayTraceContextTest` pins this without an SDK or agent, because `W3CTraceContextPropagator` builds a valid
`SpanContext` from the API alone: the trace id current at the moment of the send equals the stored one, each record in
a batch gets its own, an uncontexted row gets no fabricated parent, and the scope closes so the relay thread cannot
keep publishing under a stale trace.

## Checks made before concluding

- Both runs used a stack rebuilt from current sources. The first attempt at this check ran against images ~30 h stale,
  which is why `/v1/invariants` returned 404 before the rebuild; that result was discarded, not reported.
- Sampling was not a factor: every other span in each window is present, including all DB spans on both sides.
- The DLQ header case was not exercised in either run, so nothing is claimed about a dead-lettered record's context.
- fake-providers and instrument-service are not in the traced path (API → outbox → relay → Kafka → apply).

## Handoff

- **S07-T01** inherits a **links** topology at the consumer hop. Custom apply spans and M12 (a) evidence must follow
  links, not assume parent-child.
- The three defects this task exposed in deployment wiring — containers dialling `localhost:9092`, `NewTopic`s
  declared as a `List` and so never provisioned, and auth tokens never passed through compose — are recorded in the
  step register. All three were invisible to 132 green tests.

## Reproduce

```bash
docker compose up -d --build --wait
# post one order with a writer token, then:
docker compose exec -T otel-lgtm sh -lc \
  'curl -s --get "http://localhost:3200/api/search" --data-urlencode "q={ name = \"payments.money-orders.v1 publish\" }"'
# the publish span's trace id should equal the POST /v1/money-orders trace id
```
