# S05-T09 — the collection policy: charges, refunds, and the first money this service actually moves

Date: 2026-09-17. Branch: `worktree-agent-a60223d2de0132ec8`.

## Measured

All counts are from the Gradle XML results (`build/test-results/*/TEST-*.xml`) of the runs described. This build sets
`failOnNoDiscoveredTests = false`, so BUILD SUCCESSFUL on its own would prove only that nothing ran.

| Suite | Layer | Tests | Failures | Skipped |
|---|---|---|---|---|
| `CollectionPolicyTest` (both nested groups) | unit | 15 | 0 | 0 |
| `PublishPathArchitectureTest` | unit | 2 | 0 | 0 |
| `CollectionPolicyIT` (including its nested groups) | integration | 9 | 0 | 0 |
| `CollectionKillSwitchIT` | integration | 1 | 0 | 0 |
| instrument-service, whole unit layer | unit | 108 | 0 | 4 |
| instrument-service, whole integration layer | integration | 29 | 0 | 0 |

The unit layer went from 91 to 108 and the integration layer from 19 to 29; the 4 unit skips are the adapter contract
suite's settlement-report and webhook-parsing assumptions, once per provider, unchanged since `providers.md`. The
additions reconcile exactly: 15 + 2 = 17 unit, and 4 + 2 + 3 + 1 = 10 integration, so no existing test was replaced or
quietly dropped.

**Suite naming.** The register names `PolicyQuarantineIT`; it is the nested `Quarantine` group of `CollectionPolicyIT`
here, so it shares that class's broker and database instead of starting a second pair of containers.

## The claims, and what they are asserted against

The assertions are made against **ground truth wherever our own records could differ from reality**, because this is
the task where a mistake charges a real payer twice or not at all:

- **One attempt, one provider call.** The golden O1 (D01-9) published three times produces one `CHARGE` attempt
  reaching `SUCCEEDED` — and the provider stub's own request count goes up by exactly **one**. "We only wrote down one
  charge" and "we only charged the payer once" are different claims, and only the second is what matters.
- **One payment event, on the topic.** Exactly one `CHARGE_SUCCEEDED` is read back off `payments.payment-events.v1`
  with a real consumer, and it validates against the D01-8 schema. Counting outbox *rows* would have passed just as
  happily with the relay unwired, which is the state this task found the service in.
- **The adjustment refunds the adjusted amount.** The golden O3 produces a `REFUND` of 300 — not of the 2500 fare —
  reaching `SUCCEEDED`.
- **Blocked on capture, both ways.** With the provider slower than the read timeout, the capture becomes `UNKNOWN`
  and its refund is created `blocked_on_capture = true` and stays in `CREATED`; once the capture resolves the refund
  is released, sized and submitted. A second case runs the same path with the provider slow but *inside* the read
  timeout, where the release happens unattended end to end.
- **The kill switch.** With collections frozen the attempt is still created and stays in `CREATED`, and the provider's
  request count does not move at all.

## Two negative controls

Both suites passed on their first run, which is not evidence of anything on its own. Each of the two claims that would
cost real money was therefore shown to fail when the mechanism behind it was removed.

**1. The refund cap.** `Math.min(wanted, group.refundable())` was changed to `wanted` and the unit layer re-run:
**108 tests, 2 failed**, naming exactly the two cases that bound a refund — `a refund never exceeds what is
refundable` produced a refund of **900** against a 100 remainder, and `refunds already in flight are subtracted`
produced a 300 refund where none was due. Without that one expression the service would return more money than it
ever took.

**2. The database uniqueness key.** `ON CONFLICT (kind, source_order_id, entity_id, currency) DO NOTHING` was removed
from the attempt insert and the integration layer re-run: **29 tests, 3 failed**, all three money-path cases of
`CollectionPolicyIT`. The golden test failed with *"the refund never reached SUCCEEDED; it is null"* — the redelivered
order hits a duplicate-key violation, the record is never acknowledged, the partition pauses, and the adjustment
queued behind it is never processed. So the idempotency claim rests on the constraint and not on a lucky ordering.

Both edits were reverted and both layers re-run green at the counts in the table above.

## Three defects this task surfaced

**1. Payment events reached Kafka never.** Only the outbox *writer* was wired (a deliberate, correct call in S05-T07,
when nothing produced events). Every attempt transition since S05-T07 appended a payment event to the table, and with
no relay they simply accumulated: order-service's mapper would have seen no charge, and the ledger would never have
learned that money moved. Silent at both ends — the service is healthy, the table is full, and the topic is empty.
The relay, its metrics, the cleanup job and the stats query are now wired, and the integration test reads the events
back **off the topic** rather than out of the table, so an unwired relay fails the build.

**2. instrument-service was never passed a broker address.** Its `docker-compose.yml` block had no
`ZS_KAFKA_BOOTSTRAP`, so `application.yml`'s `localhost:9092` default would have applied inside the container — where
localhost is the container itself. The consumer would have dialled itself and the relay published nothing, while the
container reported healthy. This is the same class of defect as the missing `ZS_*_TOKEN` variables S05-T08 found, and
it is invisible to every test, because tests set their own bootstrap address.

**3. A single malformed record would have stopped collections for good.** `ContractSchemas.validate` *throws* on bytes
that are not JSON at all rather than returning schema errors. The first version of the listener called it outside a
`try`, so a record of random bytes would have escaped as an exception, paused the partition, and been redelivered for
ever — every rider behind it never charged. Decoding is now classified before anything is written, and the quarantine
test publishes exactly such a record on the same partition as a good order to prove the good one still applies.

## What this does not do

- **Nothing resolves an `UNKNOWN` capture in production yet.** The sweeper and the `UNKNOWN` resolver are S05-T12. The
  attempt is left with `next_check_at` set to "due now" rather than to a D05-8 schedule, which that task owns. In the
  above-read-timeout test the single resolution step is driven by the test calling `AttemptOutcomes.apply` — the same
  production method the resolver will call — because no component calls it yet; everything after that point (the
  transition, the event, the release, the refund's own submission) is the service's own code on its own path.
- **Poison is quarantined but not dead-lettered.** D04-4 asks for both. Publishing to a DLQ needs a Kafka producer,
  and instrument-service is inside the package M4(b) guards — it publishes the payment events the whole ledger is
  derived from. Order-service deferred its DLQ for exactly this reason. The quarantine row holds the payload, so
  nothing is lost; `PublishPathArchitectureTest` now enforces the rule here, canary included.
- **The kill switch is read at startup and has no admin endpoint.** Freezing collections is a redeploy, matching the
  runbook freeze. The "switch back on and they complete" half is the old-`CREATED` sweep (§0.3 C24), which is S05-T12,
  so it is not asserted here — only that a frozen attempt is created, stays in `CREATED`, and reaches no provider.
- **No retry inside the consumer.** The master's provider-retry rule (idempotent providers, at most 3 tries) is not
  implemented: the S05-T09 edge case is explicit that a capture past the read timeout becomes `UNKNOWN` and the
  consumer does not retry inline.
- **No migration.** V2 already carries `blocked_on_capture`, the uniqueness key, `instrument_tokens` and
  `quarantined_records`, and the runtime grants they need. Nothing in this task required a V3, so none was added.
- **Payouts are untouched.** The payout run, its freshness check and the payouts kill switch are S05-T10.
- An adjustment for a group with **no charge at all** creates nothing rather than a blocked refund (recorded in
  D05-6): same-group ordering is guaranteed, so the absence means the charge was never created, and a blocked refund
  would wait for a capture that is never coming.
