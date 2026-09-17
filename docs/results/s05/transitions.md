# S05-T08 — the state × event tables, and the first authenticated instrument endpoints

Date: 2026-09-17. Branch: `worktree-agent-a99e2f4f7a03f8929`.

## Measured

All counts are from the Gradle XML results of the runs described.

| Suite | Layer | Tests | Failures | Skipped |
|---|---|---|---|---|
| `TransitionTableTest` (including both nested groups) | unit | 46 | 0 | 0 |
| `AuthWiringTest` | unit | 1 | 0 | 0 |
| `AttemptEndpointsIT` (including both nested groups) | integration | 9 | 0 | 0 |
| instrument-service, whole unit layer | unit | 91 | 0 | 4 |
| instrument-service, whole integration layer | integration | 19 | 0 | 0 |
| every module's unit layer | unit | 281 | 0 | 4 |

The 4 unit skips are the adapter contract suite's settlement-report and webhook-parsing assumptions, once per
provider, unchanged from `providers.md`. The unit layer went from 44 to 91 and the integration layer from 10 to 19.

**M8(a): 891 decisions, 33 generated cases.** `TransitionTableTest` enumerates kinds × states (3 × 11) and asserts
all 27 events inside each case, so every (kind, state, event) pair of the Cartesian product is asserted — 891 in
total — with two further cases proving the product is covered and that every `State` and `Event` constant is
accounted for in the test's own transcription of the master diagrams.

## Two negative controls

Both tests passed on their first run, which is not evidence of anything on its own. Each was therefore shown to fail:

**The transition table.** One arrow was changed in `AttemptStateMachines` (`SETTLED → RETURNED` became
`PENDING → RETURNED`) and the suite was re-run: **3 failures**, naming exactly the affected pairs —
`PAYOUT in SETTLED: LOOKUP_FOUND_RETURNED expected APPLIED->RETURNED but the table said AHEAD_OF_STATE(RETURNED)`,
the same for `WEBHOOK_RETURNED`, and the master's own `payout.returned while PENDING` example. The file was restored
and the suite re-run green.

**The Compose token wiring.** The three `ZS_*_TOKEN` lines were deleted from the instrument-service block and
`AuthWiringTest` was re-run: **failed**, on the missing environment keys. The file was restored and re-run green.

## The defect this task found

**instrument-service would have authenticated nobody.** `ZS_WRITER_TOKENS`, `ZS_READER_TOKEN` and `ZS_ADMIN_TOKEN`
were absent from its `docker-compose.yml` block. Every endpoint test passes regardless, because tests set the
properties themselves and never read `docker-compose.yml` — which is exactly how order-service and ledger-service
shipped the same hole in S03. The variables are now in the block, and `AuthWiringTest` fails the build if any service
whose `application.yml` declares `zs.auth` is not passed all three, checking both ends: that the YAML reads the
`ZS_*` names and that Compose passes them. It is parsed with snakeyaml rather than grepped, because the block that
binds to nothing sits at the wrong indent and looks perfectly fine to a regular expression.

## How the tables are expressed, and why not as 891 rows

Each machine declares four things: its progression (how far along each status is), the diagram's arrows, a claim
alias (`DECLINED` → `FAILED` for refunds and payouts, per master §5.10), and — on each event — the status it claims
and whether it is a command or a provider observation. Every decision follows from those. Writing 891 rows by hand
would not be more honest; it would be 891 chances to mistype one and find out in production. The guard against drift
is the test's independent transcription of the diagrams, demonstrated above.

Three consequences worth stating:

- **There is deliberately no `UNKNOWN → FAILED` arrow on the payout machine.** A payout whose lookup reports failure
  is therefore ahead-of-state, and `resolutionPath` walks it `UNKNOWN → PENDING → FAILED`, emitting `PAYOUT_ACCEPTED`
  and then `PAYOUT_FAILED`. Applying the failure directly would emit `PAYOUT_FAILED` for a payout the ledger never
  saw accepted, and the mapper would reverse an order that was never created.
- **A command is never ahead of state.** Cancelling a `SUBMITTING` attempt is illegal, not a lookup: there is nothing
  at the provider to reconcile it against. That is what makes the endpoint's `not_cancellable` conflict the same
  decision the table makes.
- **A lookup that learned nothing is ignored, not counted as illegal.** `LOOKUP_NOT_FOUND` and `LOOKUP_UNAVAILABLE`
  claim no status, so a provider outage cannot drive an alert whose meaning is "our code has a bug".

`AttemptTransitions` consults the table after its optimistic guard, not before: a caller whose from-status is merely
out of date still gets `LostRace`, which is a normal race and not a defect, while a caller that really was in that
status and asked for a move no machine draws gets the refusal. The refusal throws inside the transaction, so the
guarded update rolls back and nothing is written — asserted, including the outbox.

## What this does not do

- **Nothing executes an ahead-of-state resolution yet.** The table returns the outcome and the ordered path; the
  component that records the event, calls the provider lookup and applies the hops is the webhook receiver (S05-T11)
  and the resolver (S05-T12). Until then `AheadOfState` is a decision no caller acts on.
- **Nothing calls the transition service in production paths.** Attempts are still created only by tests; the
  collection policy is S05-T09.
- **The cancel endpoint does not consult `decide`.** It checks the status and lets the guarded transition enforce the
  rest, so an operator's mistaken cancel returns 409 without incrementing the illegal-transition counter that alerts
  on code defects.
- **`NEEDS_REVIEW` has no exit.** An attempt an operator resolves is left to the operator; a lookup that arrives
  afterwards is ignored as stale rather than quietly moving money.
- **The OpenAPI file covers only this task's two operations.** S05-T09 to T11 add theirs to the same file.
