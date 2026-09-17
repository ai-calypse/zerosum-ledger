# S05-T09 in a real deployment — a COMMERCE order becomes a charge, and comes back as a COLLECTION

Date: 2026-09-17. Tree: `main` after merging S05-T08, S05-T03, the M13 harness and S05-T09.

Until this run, the collection policy and the outbox relay had only ever run under Testcontainers. This session has
produced five defects that only a running stack revealed, so "the suite is green" was not treated as "it works".

## The loop, observed end to end

Stack: all seven containers healthy, started with `docker-compose.yml` plus the demo overlay.

| Step | Observed |
|---|---|
| 1. Register the instrument | `POST /v1/instrument-tokens` → **200**, `{"entity_id":"rider:OK…","provider":"fakecard"}` |
| 2. Post a COMMERCE order | `POST /v1/money-orders` → **201** |
| 3. Policy creates and submits a charge | `CHARGE status=SUCCEEDED provider_ref=ch_30673ba9-684d-462b-8afb-9042a0295e93` |
| 4. Payment event written **and published** | `CHARGE_SUCCEEDED published=true topic=payments.payment-events.v1` |
| 5. Provider queued its webhook | `charge.succeeded` for the same `provider_ref`, `pending: 1` |
| 6. Mapper turns the event back into an order | `money_orders` for the group: `COMMERCE trip.completed`, then **`COLLECTION charge.succeeded`** |

Step 6 is the one worth naming. A trip completed, the rider was charged through a real provider, and the ledger side
learned about it as a new money order — the full round trip across order-service, Kafka, instrument-service,
fake-providers and back. `published=true` is the specific thing a table-only assertion cannot prove: a row written
but never delivered looks identical to success until you check the relay.

## The first attempt failed, and the failure was mine

The probe before this one posted an order for a rider with **no registered instrument** and found nothing: no
attempt, no outbox row, no charge. The consumer had in fact worked perfectly — it consumed the record, committed
offset 1 on partition 8 with lag 0, and declined to act, saying so plainly:

```
WARN d.z.instrument.policy.PolicyMetrics : collection policy cannot act on order 01a0ae93-…:
     no_usable_instrument (entity rider:DEP675efefac1)
```

It acked rather than quarantining, which is right — an order for an unregistered entity is not poison. Three empty
query results looked like a broken consumer and were a missing setup step. Recorded because the reflex to read
absence as failure is the same reflex that reads `BUILD SUCCESSFUL` as a pass.

The kill switch was also ruled out here: `collections-enabled: ${ZS_COLLECTIONS_ENABLED:true}` reads the variable by
explicit placeholder, so Spring's relaxed binding — which would map `ZS_COLLECTIONS_ENABLED` to `zs.collections.enabled`
and quietly miss `zs.kill-switches.collections-enabled` — is not in play.

## A defect this run found

`GET /admin/truth?entity_id=` returns **`"charges": []`** for the very entity whose charge had just succeeded, while
the same response's webhook queue carries a `charge.succeeded` event naming that charge's `provider_ref`.

The charge exists. fake-providers stores it against the **client reference** (the attempt id), and the ground-truth
endpoint is keying its charge lookup on the ledger entity id, which fake-providers never sees. An auditor querying by
entity would conclude no charge occurred.

This matters beyond tidiness: invariant **I7** compares the ledger against provider ground truth through this
endpoint. Raised against S05-T03 (D05-3); not fixed here, because the fix belongs with the task that owns the
endpoint.

## What this run does not show

- **No refund or adjustment path.** Only the charge half of the policy was exercised; blocked-on-capture is covered
  by `CollectionPolicyIT`, not by this run.
- **The webhook was queued, never delivered.** `ZS_WEBHOOK_RECEIVER_URL` is deliberately unset until the S05-T11
  receiver exists, so `pending: 1` is the expected terminal state today.
- **No duplicate delivery.** One order, published once; this says nothing about M5(a) end to end.
- **No fault injection.** Knobs were left at their defaults for this run.
