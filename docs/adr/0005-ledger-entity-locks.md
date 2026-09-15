# ADR-0005 — Pessimistic, sorted entity locks for ledger apply

- **Status:** Accepted
- **Date:** 2026-09-15
- **Decision owner:** S02 (D02-4) — [docs/step_02_ledger_core.md#decisions-and-outputs](../step_02_ledger_core.md#decisions-and-outputs)

## Context

Applying a money order updates balances and appends a changelog row per entry, and every entity's changelog must carry a **gapless** sequence number (M5 (b), I4) and a hash chain (S1). Orders arrive concurrently and touch overlapping entity sets, with a few very hot entities: every commerce order touches `platform:main`, and every collection touches a provider clearing account ([§6.5](../zerosum_ledger_mvp_plan.md#bottlenecks)).

Two failure modes have to be designed out:

- **Lost updates**, where two transactions read the same balance and each writes its own result;
- **Deadlocks**, where two transactions lock overlapping entity sets in different orders.

## Decision

1. **Serialize on the entity row.** In one `READ COMMITTED` transaction, apply locks the batch's entity rows with `SELECT … WHERE entity_id = ANY(?) ORDER BY entity_id FOR UPDATE`, then reads accounts, computes in memory and writes.
2. **One deterministic lock order.** Entities are locked in ascending `entity_id` order. The database column collation is `"C"` (D02-1), so the database's order matches Java's `String` order, and provisioning inserts use the same order. A collation mismatch would reintroduce deadlocks, so `LedgerSchemaMigrationIT` asserts the two orders agree.
3. **Sequence numbers come from the locked entity row** (`entities.last_seq`), never from a database sequence object, whose values are not rolled back and would leave gaps.
4. **Transaction-local timeouts**, from configuration: `lock_timeout` 2 s and `statement_timeout` 5 s (`ledger.apply.*`, master §5.11).
5. **Retry only classified transient failures**, by the SQLSTATE of the root `SQLException` rather than by the translated exception type: `40P01` deadlock, `40001` serialization failure, `55P03` lock not available, the `08xxx` connection class, and `57P01`/`57P03` operator-initiated disconnects. Retries use jittered exponential backoff from 100 ms to 5 s, at most 10 attempts, and each attempt re-runs the whole transaction from fresh reads.
6. **Never skip money.** When the attempts are exhausted, apply raises `RetriesExhaustedException` carrying the batch; S04 maps it to pausing the listener plus an alert (D04-4). A transient failure is never quarantined.
7. **Non-transient failures are isolated.** In a batch of more than one fresh order, the batch is re-run one record at a time, so only the offending record is quarantined and the rest still apply.
8. **Interruptible backoff.** A thread interrupted during backoff stops retrying and propagates; nothing was committed.

## Alternatives considered

- **Account-level optimistic locking** (`lock_version` per account, the Modern Treasury model, [§2.2](../zerosum_ledger_mvp_plan.md#industry-designs)). Rejected as the default: a gapless per-entity sequence and a per-entity hash chain still need a per-entity serialization point, so the contention would move rather than vanish. It stays the documented G1 alternative (S02-C01) if deadlocks or sequence gaps prove unfixable.
- **`SERIALIZABLE` isolation.** Rejected: it converts lock waits into serialization failures under exactly the hot-entity pattern we expect, and the retry storm would be worse than a short lock wait.
- **`SELECT … FOR UPDATE SKIP LOCKED`.** Rejected for balances: skipping is right for an outbox queue, never for money that must be applied.
- **Advisory locks keyed by entity.** Rejected: they add a second locking scheme with no benefit over the row lock already needed to read `last_seq`.
- **Lock ordering by insertion order or by hash.** Rejected: any order works only if *every* writer uses it, and the natural key order is the one the database can also express in `ORDER BY`.

## Consequences

- **Hot entities serialize.** The throughput ceiling is set by how long the lock is held (round trips plus commit fsync). SP1 measures it and decides whether batched apply becomes must-have (D02-10); the measured evidence is added here when the study runs (S02-T07).
- **Deadlocks should not occur** with a single deterministic order; any deadlock retry counted by the stress test (S02-T03) is treated as a lock-ordering or collation defect, not as noise.
- **Batching is compatible.** The same entrypoint takes an ordered batch: more orders per lock acquisition amortizes the cost, which is the S3 upgrade path.
- Tested by `ApplyLockTimeoutRetryIT` (a held lock causes a retry and the order still applies exactly once; a lock held past the schedule raises the typed exception and quarantines nothing), `ApplyConnectionLossIT` (a terminated backend is retried, applied once) and `ApplyRetryClassifierTest` (the SQLSTATE table, including a bounded walk over cyclic cause chains).
