# S04-T06 — Trace propagation across the pipeline

- **Date:** 2026-09-16
- **Git SHA:** `032acd4`
- **Stack:** `docker compose` per D00-3 — PostgreSQL 18.6, Kafka 4.3.1 KRaft, grafana/otel-lgtm 0.33.0, OTel agent 2.31.1
- **Method:** one money order posted through the live API with a writer token, traced end to end, spans read from
  Tempo's query API (`/api/search`, `/api/traces/<id>`) inside the `otel-lgtm` container

## Classification

**Broken at the outbox hop.**

The Kafka hop itself is fine — the ledger's consume span is linked to the relay's publish span. What breaks is one
step earlier: the relay's publish starts its **own root trace** instead of continuing the trace of the HTTP request
that wrote the row.

## Evidence

Three traces, where M12 (a) requires one.

| Trace | Root span | Service | Relationship |
|---|---|---|---|
| `adb50356bf69f536b62819dd700eab0c` | `POST /v1/money-orders` (SERVER) | order-service | Root. Children: `INSERT orders.money_orders`, `INSERT orders.money_order_entries`, **`INSERT orders.outbox`**. **No producer span.** |
| `6d95da62c5376119042c545083a5f9e4` | `payments.money-orders.v1 publish` (PRODUCER) | order-service | **Root, and an orphan** — a single span, parented by nothing, in no request's trace |
| `c35834254ea0c9c0428779a4146c2ca6` | `payments.money-orders.v1 process` (CONSUMER) | ledger-service | Root with **1 link → trace `6d95da62…`**. Children: the whole apply transaction, ending in `INSERT ledger.entity_changelog` |

So the chain reads: request trace → *(break)* → publish trace → *(link)* → apply trace.

The consumer link was recorded base64-encoded in Tempo's protobuf JSON (`bZXaYsU3YRkELFRQg6X55A==`); decoded it is
`6d95da62c5376119042c545083a5f9e4`, the publish trace — **not** either of the two `POST /v1/money-orders` traces
(`adb50356…`, `45784ab8…`).

## Why it breaks, and why it is not fixed here

§0.3 C11 and D03-5 require the W3C trace context captured in the outbox row at write time to be **restored** when the
relay sends. `OutboxWriter` does capture it — `traceparent` is written into the row's headers from the active span —
and `OutboxRelay.send()` copies every stored header onto the `ProducerRecord`. That is not enough: the OTel agent's
producer instrumentation builds the publish span from the **current thread's** context, which on the relay thread is
empty, and then writes its own `traceparent` over the copied one. Storing the header is not the same as making it the
active context for the send.

Per the task, a break at the outbox is a defect against D03-5 and is **not patched from S04** — the relay belongs to
S03. Change request **CR-S04-01** is raised instead (see the step register, I.2).

## What this does and does not mean

- **Not a data-path fault.** The order flowed correctly: outbox drained in 1 s, the ledger applied in 1 s, freshness
  reported `ok` with zero lag. Only the *trace* is severed.
- **The Kafka hop already behaves as expected.** Batch consumption produces a linked processing span rather than a
  parented one, which is the normal pattern and is what S07-T01 must account for when it builds M12 (a) evidence.
  Even once the outbox hop is fixed, this pipeline will be **connected (links)**, not parent-child, at the consumer.
- **M12 (a) is not met today**, and no claim is made that it is.

## Checks made before concluding

- Sampling was not the cause: every other span in the same window is present, including all DB spans on both sides.
- The DLQ header case was not exercised in this run, so nothing is claimed about it.
- fake-providers and instrument-service images are still ~30 h old. Neither is in the traced path
  (API → outbox → relay → Kafka → apply), so the result stands, but the stack was not uniformly rebuilt.

## Reproduce

```bash
docker compose up -d --build --wait
# post one order with a writer token, then:
docker compose exec -T otel-lgtm sh -lc \
  'curl -s --get "http://localhost:3200/api/search" --data-urlencode "q={ name = \"POST /v1/money-orders\" }"'
docker compose exec -T otel-lgtm sh -lc 'curl -s "http://localhost:3200/api/traces/<id>"'
```
