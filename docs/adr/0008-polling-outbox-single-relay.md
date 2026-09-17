# ADR-0008 — Polling transactional outbox with a single relay per service

- **Status:** Accepted
- **Date:** 2026-09-16
- **Decision owner:** S03 (D03-5) — [docs/step_03_order_service_outbox.md#decisions-and-outputs](../step_03_order_service_outbox.md#decisions-and-outputs)

## Context

A money order is only useful once it reaches the ledger, and it must reach it exactly as it was committed. Writing to
PostgreSQL and publishing to Kafka are two systems, so any attempt to do both in one step has a window where one
succeeded and the other did not: publish-then-commit can publish an order that never existed, and commit-then-publish
can commit an order that never publishes. Neither is acceptable for money.

The ordering guarantee matters as much as delivery. A trip's orders — the original and its adjustments — must reach the
ledger in the order they were created, or a balance passes through states that never existed.

## Decision

1. **Transactional outbox.** The order row and its outbox row are written in the same transaction, so they commit
   together or not at all. The API path never touches Kafka: an order is accepted while the broker is down.
2. **Polling relay, not CDC.** A background loop selects unpublished rows in id order, sends them, waits for every
   acknowledgement, then marks them published and commits. Debezium is the documented upgrade path if polling cannot
   meet the publish-lag target, and is deliberately not adopted now.
3. **Mark only after acknowledgement.** Marking first would convert a broker failure into silent loss. A crash between
   send and commit therefore produces duplicates, never loss — the trade the pattern makes, and why consumers must be
   idempotent.
4. **One relay per service, with a guard.** Compose runs a single replica, which is the primary control. The backstop is
   a transaction-scoped PostgreSQL advisory lock taken at the start of each batch: a second relay skips its batch and
   increments a counter, so the misconfiguration is visible rather than silent. The lock is transaction-scoped, so a
   crashed relay never leaves it held.
5. **`SKIP LOCKED` for liveness, the advisory lock for order.** Skipping locked rows stops a second relay blocking
   behind the first, but on its own it does **not** preserve per-key order: a second relay could publish a later batch
   while the first still held earlier rows. The advisory lock is what serialises batches.
6. **The order group is the message key** (ADR-0007), so a trip's records share a partition and stay ordered.
7. **Publish timestamps use `clock_timestamp()`**, not `now()`. `now()` is the transaction start time, which would
   understate publish lag by the whole duration of the send.
8. **Trace context is captured at append time** into the row's headers. The relay sends from a different thread, where
   the originating trace is gone, so a trace spanning HTTP through Kafka to the ledger depends on it being written with
   the row.
9. **Bounded cleanup.** A scheduled job deletes published rows past their retention in bounded batches, in its own
   transactions, and never touches unpublished rows.
10. **One publish path.** Only `libs/outbox` may call the Kafka producer from application code, enforced by an ArchUnit
    rule shipped as a test fixture so every producing service reuses it.

## Alternatives considered

- **Publish inside the request, then commit.** Rejected: publishes orders that may never commit.
- **Commit, then publish from the same request thread.** Rejected: a crash between the two loses the order silently,
  and the caller has already been told it succeeded.
- **Debezium / logical decoding.** Rejected for now: it adds a connector, a schema registry question and operational
  surface, to solve a latency problem that has not been measured yet. Recorded as the upgrade path.
- **Kafka transactions spanning the database write.** Rejected: Kafka transactions cannot include a PostgreSQL write, so
  this solves nothing while adding exactly-once machinery.
- **Several relay instances with row-level locking only.** Rejected: `SKIP LOCKED` alone breaks per-key ordering across
  instances, which is the guarantee the key exists to provide.
- **Session-scoped advisory lock.** Rejected: a crashed relay would hold it until its connection was reaped.

## Consequences

- **Duplicates are expected and must be harmless.** Consumers are idempotent; the ledger's `applied_orders` table is
  what makes a redelivery a no-op.
- **Publish lag is a real metric, not a formality.** It is measured from the row's `created_at`, and the oldest
  unpublished age is a gauge read at scrape time, so it stays truthful while the relay is stuck — which is exactly when
  the number matters.
- **A poison row blocks everything behind it.** The relay will not skip money, so a row that can never be sent stops
  the queue by design. It is logged with its id and counted; an operator resolves it.
- **Cleanup limits rebuild-from-outbox.** Deleted rows are ones a full replay would have needed. Recorded as a
  limitation; the replay tool is conditional and unassigned.
- **Throughput is bounded by the poll interval and batch size**, both configuration. SP2 exists to tune them if the
  publish-lag target is missed.
