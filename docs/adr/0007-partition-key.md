# ADR-0007 — The partition key is the order group

- **Status:** Accepted
- **Date:** 2026-09-16
- **Decision owner:** S04 (D04-1) — [docs/step_04_kafka_pipeline.md#decisions-and-outputs](../step_04_kafka_pipeline.md#decisions-and-outputs)
- **Starting point:** decision 0007 in [docs/zerosum_ledger_mvp_plan.md#decisions](../zerosum_ledger_mvp_plan.md#decisions)

## Context

Kafka guarantees order within a partition, not across a topic. So the key decides what "in order" means for this
system, and the question is which orders must not overtake one another.

A trip's money orders are the case that matters: the original COMMERCE order and its later adjustments belong to the
same story, and an adjustment that lands before the order it adjusts would put a balance through a state that never
existed. A payout run is the same shape.

## Decision

1. **The record key is `order_group_id`** — a trip id, a payout id, or for settlement events the composite
   `settlement:<provider>:<report_id>:<currency>`, which is both the event id and the group.
2. **One group therefore lives on one partition**, and its records are consumed in the order they were published.
3. **Cross-group ordering is not guaranteed and not needed.** Balance arithmetic is commutative and each order is
   independently zero-sum, so two unrelated trips may interleave freely without affecting any balance.
4. **The partition count is part of the contract** (12 for both pipeline topics, recorded in `TopicDefinitions`), not
   a tuning knob. Kafka maps a key to a partition by hashing it modulo the partition count, so changing the count
   remaps existing groups and lets a later record for a group land on a different partition from its predecessors.
   Startup fails rather than silently adding partitions.
5. **Dead-letter records keep their original key**, so a record re-published from the DLQ rejoins its group in order
   instead of racing the rest of it.

## Alternatives considered

- **Key by `order_id`.** Rejected: every order gets its own key, so an adjustment could be processed before the order
  it adjusts. It would spread load perfectly and lose the only ordering guarantee that matters.
- **Key by entity (rider or driver).** Rejected: an order touches several entities, so there is no single entity to
  key on, and a popular platform entity would make one partition hot.
- **No key (round-robin).** Rejected: no ordering at all, and the ledger would have to reconstruct sequence itself.
- **A single partition for total ordering.** Rejected: it buys an ordering guarantee the system does not need and caps
  throughput at one consumer.

## Consequences

- **A hot group is a hot partition.** One very busy trip is served by one consumer thread. Acceptable: trips are small
  and numerous, and the ledger's own per-entity locking is the tighter constraint (SP1).
- **Repartitioning is a migration, not a config change.** Raising the partition count requires draining the topic or
  accepting reordering, so the startup check treats a mismatch as a failure with a documented reset procedure.
- **Consumers must still be idempotent.** Ordering does not prevent redelivery; the ledger's `applied_orders` table is
  what makes a repeat a no-op (M5(a)).
- **The DLQ has fewer partitions than its source**, so a recoverer that copies the source partition number would try
  to write to a partition that does not exist. Partition selection is set explicitly by key (S04-T03).
