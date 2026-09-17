# S05-T07 — attempts schema, the transition guard, and payment events

Date: 2026-09-17. Branch: `step/05-attempts`.

## Measured

All counts are from the Gradle XML results of the runs described.

| Suite | Layer | Tests | Failures | Skipped |
|---|---|---|---|---|
| `InstrumentsSchemaIT` | integration | 7 | 0 | 0 |
| `TransitionConcurrencyIT` | integration | 3 | 0 | 0 |
| `PaymentEventEmissionTest` | unit | 11 | 0 | 0 |
| instrument-service, whole unit layer | unit | 44 | 0 | 4 |
| instrument-service, whole integration layer | integration | 10 | 0 | 0 |

The 4 unit skips are the adapter contract suite's settlement-report and webhook-parsing assumptions, once per provider,
as recorded in `providers.md`.

**Concurrency: 16 racers** (`TransitionConcurrencyIT.RACERS`). Sixteen threads applied the same transition to one
attempt: exactly one returned `Applied`, fifteen returned `LostRace`, and afterwards there was **one** history row,
**one** outbox event, and the version had moved exactly once. A read-then-write would pass a single-threaded test and
then let a webhook and a sweeper both move the attempt and both emit an event — two money orders for one movement of
money.

**Suite naming.** The register names `InstrumentsSchemaIT`, `OneInflightPayoutIT` and `AttemptUniquenessIT`; they are
the three nested groups of `InstrumentsSchemaIT` here. It also names `PaymentEventSchemaTest` and
`PayoutEventEmissionTest`; they are the two nested groups of `PaymentEventEmissionTest`.

**The migration was verified against a live database before any test existed**: applied to the running stack by the
service's own Flyway, then interrogated through `pg_catalog`. Flyway reported `2 instruments schema success=true`; all
seven tables, the `attempts_due` / `one_inflight_payout` / `payment_attempts_one_per_order` indexes and all four
append-only triggers exist; and `instruments_app` holds only `INSERT` and `SELECT` on `attempt_transitions`.

## Two defects this task surfaced

**1. The payment event omitted `failure_code` entirely.** `CHARGE_DECLINED` failed D01-8 validation on its first run.
The deeper problem was that the payload was serialized from a record with every field always present: the schema
expresses several rules as *absence* — `"source_order_id": false` for payouts, `"failure_code": false` for successes —
and an explicit `null` violates those exactly as a value would. The payload is now assembled key by key, and
`PaymentEvents.toJson` **throws** when a required field is missing rather than emitting an event the consumer will
reject. By the time a bad payload reaches the outbox it is already committed beside a real status change, and the
money it describes has already moved.

**2. The service could not start with the outbox wired.** `zs.outbox.batch-size must be >= 1: 0`. This is
`libs/outbox` working as designed — it ships no defaults "so a deployment cannot silently inherit one" — and
instrument-service simply never declared the block. Fixed by stating all eight values, and **verified by parsing the
YAML** for `zs.outbox.batch-size` rather than by eyeballing indentation, because config that binds to nothing has been
this repository's recurring silent failure.

## CR-S05-01 discharged

The Testcontainers fixture was extracted to `libs/testsupport` (`ZsTestDatabase`), as CR-S05-01 required once a fourth
service needed it. fake-providers was migrated onto it and its suite re-run — **16 tests, 0 failures** — so the
extraction is proven on two services rather than being a fourth implementation.

Two deliberate scoping choices: `pooledDataSource` is omitted (only ledger-service uses it, and ledger-service keeps
its own copy for now), and the driver's `PGSimpleDataSource` is used instead of Spring's, so the shared module needs
no Spring dependency. **order-service and ledger-service still hold their own copies**; migrating them is follow-up
work, and until then there are two implementations rather than one.

## What this does not do

- **M8(a) is not met.** The state × event transition table test is S05-T08 and is not written; this task covers the
  guard and the emission table, not the legality of every pair.
- **Nothing publishes these events.** Only the outbox *writer* is wired. The relay and cleanup job start with their
  first producer in S05-T09, so payment events accumulate in the table and reach Kafka only once that lands.
- **Nothing calls the transition service yet.** It is exercised by its tests alone until the collection policy
  (S05-T09) creates attempts from COMMERCE orders.
- The `next_check_at` column and the `attempts_due` index exist for sweepers that do not exist yet (S05-T12).
