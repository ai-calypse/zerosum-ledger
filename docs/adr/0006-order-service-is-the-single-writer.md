# ADR-0006 — Order-service is the single writer of money orders

- **Status:** Accepted
- **Date:** 2026-09-16
- **Decision owner:** S03 (D03-6) — [docs/step_03_order_service_outbox.md#decisions-and-outputs](../step_03_order_service_outbox.md#decisions-and-outputs)

## Context

Money orders are the ledger's only input, and every one of them must be validated, zero-sum and idempotent. Payment
facts originate elsewhere: instrument-service learns that a card was charged, a payout was accepted, a settlement
report arrived. Something has to turn those facts into orders.

If more than one service could write orders, every one of them would need its own copy of the validation rules, the
chart of accounts, the sign convention and the idempotency semantics — and the copies would drift. Drift in this
particular code does not produce a clear error; it produces a ledger that is quietly wrong.

## Decision

1. **Order-service is the only writer of money orders.** Every order, whether it arrives through the HTTP API or from
   a payment event, goes through the same store, the same validation and the same idempotency check.
2. **Other stages publish facts, not orders.** Instrument-service emits `zerosum.payment_event.v1`. It never decides
   what the entries should be.
3. **The mapper is a pure function.** Payment event in, `OrderDraft` or `NoOrder` out. No clock, no I/O, no Spring:
   the effective time comes from the event's `occurred_at`, never from `Instant.now()`, so replaying an event a week
   later produces the identical order.
4. **`event_id` is the idempotency key**, with the internal mapper principal as the source system
   (`instrument-service`, §0.3 C7). Repeated delivery of one event therefore produces exactly one order (I8), enforced
   by the same unique constraint the API path relies on.
5. **The consumer writes the order and the outbox row in one transaction**, then acknowledges. A crash after commit
   and before acknowledgement redelivers the event, which replays and is acknowledged — the duplicate costs nothing.
6. **Poison is quarantined, never skipped.** An event that cannot become a valid order is dead-lettered and written to
   order-service's own quarantine table. The partition keeps moving; the evidence is kept.

## Alternatives considered

- **Each service writes its own orders.** Rejected: every writer would need the validation rules, the chart of
  accounts and the sign convention, and a drifted copy corrupts the ledger silently rather than failing loudly.
- **The ledger validates instead.** Rejected: the ledger would have to reject already-committed orders, leaving the
  writer holding a fact it cannot express. Validation belongs where the order is created.
- **A clock-based effective time.** Rejected: an event reprocessed later would produce a different order, so the
  mapper would not be a pure function of the event and replay would not be safe.
- **Skipping unmappable events.** Rejected: silently dropping a payment fact loses money with no trace.

## Consequences

- **Order-service is on the critical path** for both the API and the event pipeline. It is also the only place that
  needs to be correct about entries, which is the point.
- **Mapper-only order types exist.** `SETTLEMENT` and the payout rows are produced only by the mapper and cannot be
  created through the API, so the API's type allow-list is narrower than the ledger's.
- **The G2 alternative stays open** (S05-C01). Instrument-service could write orders directly through a shared
  validation library, retiring the mapper consumer. Validation, hashing and the mapper are deliberately free of HTTP
  and Kafka types so they can move without a rewrite; this ADR would then be superseded rather than worked around.
- **An event the schema does not define is poison**, not a silently ignored record, so a producer shipping a new event
  type is discovered immediately instead of losing orders quietly.
