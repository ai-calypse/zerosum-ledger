# Scope decisions — minimum cut invoked

> **Decided:** 2026-09-16 by the builder. **Reason:** the project is a résumé portfolio piece. What it must
> demonstrate is end-to-end production practice — scalability, observability, monitoring, maintainability and
> CI/CD — not exhaustive coverage of every scenario the master plan imagines.

This is recorded rather than applied silently, because the pack forbids weakening acceptance gates quietly
([docs/README.md#conflict-resolution](README.md#conflict-resolution)). The master already anticipates this:
[§9.5 minimum cut](zerosum_ledger_mvp_plan.md#minimum-cut) defines what each step drops.

## 1. Minimum cut, invoked as written

| Step | Cut |
|---|---|
| S01 | ArchUnit module rules deferred — already built, so no change |
| S02 | Hash chain S1, smaller stress test — **already built and measured, so nothing is removed** |
| S03 | OpenAPI generated only |
| S04 | No SP2 spike |
| S05 | Webhook reorder simulation dropped |
| S06 | Reconciliation API polish dropped; **verifier kept** — it generates the invariant evidence |
| S07 | One dashboard; perf at 3 rates; SP4 option (a) only |
| S08 | Faults F1, F3, F7, F8, F12 only; ablations A1–A4 kept |
| S09 | README, architecture doc and demo video only; no explorer, study or integration test |

## 2. Deferred beyond the cut

| Item | Why | Where it was |
|---|---|---|
| Human evaluation study | Needs 5 real participants. Not feasible for a solo portfolio build, and it evidences documentation quality rather than engineering | master §8.7 |
| Hosted demo VM (S5) | Costs money and adds deployment surface without showing anything the Compose stack does not | master §3.2 |
| SP4 hot-entity sharding spike | SP1 measured a 606.8 orders/s ceiling, above the gate, so the sharding question is not on the critical path | master §4.8 |
| Ledger Explorer page (S2) | A UI is not what this project is demonstrating | master §3.2 |
| Per-task evidence transcripts | Replaced by short evidence notes plus the test output itself. The measured studies (SP1) keep their full reports | step docs H.4 |

## 3. What this does not change

- Every acceptance criterion still recorded as met is genuinely met, with tests behind it.
- Nothing already built is removed: S02's hash chain and full-size stress test stay.
- Honest evidence rules are unchanged. Unexecuted work is still "Not run"; no result is estimated.
- CI stays green on every push, since CI/CD is itself one of the things being demonstrated.

## 4. Priority order for the remaining work

1. **S03** order service and outbox — the write path.
2. **S04** Kafka pipeline — effectively-once apply, consumer lag, the scalability story.
3. **S07** observability: OTel traces, Micrometer metrics, Grafana dashboards, the performance run. **The centrepiece.**
4. **S05** instruments and fake providers, trimmed to the charge and payout paths.
5. **S06** verifier only.
6. **S09** README, architecture doc, demo.
7. **S08** a few faults and the ablations, only if the budget allows.

## S04 Kafka pipeline — scope cut (2026-09-16)

The user's direction stands: "do not follow the steps verbatim, just make it workable and resume presentable", and
defer anything complex that does not serve an end-to-end system with observability, monitoring and CI/CD. S04 as
written is the largest step in the pack (T03 alone carries 14 instructions and 7 integration tests), so it is cut to
its spine.

**Kept**

- **T01 topic provisioning and explicit client configuration.** One topic-definition source, `NewTopic` beans per
  service, and producer/consumer properties set explicitly rather than inherited. This is what makes the pipeline
  reproducible, and it closes the "misspelled topic auto-created with broker defaults" hole.
- **T02 ledger batch listener with manual ack after the database commit.** The M5 end-to-end spine: without it the
  ledger has an apply engine that nothing feeds.
- **T03 error handling, trimmed.** Transient-versus-poison classification, DLQ publishing with error headers, the
  quarantine row, and a pause on exhausted or unclassified failure. This also discharges the DLQ deferral recorded in
  S03-T07.
- **T04 freshness endpoint.** Small, and S05's payout eligibility depends on it.
- **T05 pipeline e2e.** Kept deliberately: it proves the API → outbox → relay → broker → ledger path end to end, and
  it establishes the first e2e harness, which is also what the deferred M4(a) SIGKILL test needs.
- **T06 trace propagation.** Directly serves the observability story that S07 builds on.

**Deferred**

- **T07 relay-lag measurement and the SP2 trigger decision**, and **S04-C01 (SP2 tuning and the Debezium Outbox Event
  Router spike).** Both are tuning exercises for a latency problem that has not been observed. Recorded as the upgrade
  path in ADR-0008 already.
- **D04-8 crash-point seam** (an interface for tests to halt the JVM between engine return and acknowledgement).
  Needed by S08's fault injection, not by a working pipeline; S08 is itself deferred.
- **The long tail of T03's edge-case integration tests** (`DlqPartitioningIT`, `QuarantineIdempotencyIT`,
  `UnclassifiedErrorIT` as separate suites) and **T01's `TopicMismatchIT`**. The behaviours they cover are implemented;
  what is dropped is a dedicated container test per edge case.
- **The quarantine re-publish runbook dry run.** The runbook step is written; executing it as a test is not.

Anything deferred here is recorded as deferred in the step register, never reported as passing.

### Amendment after reading the T05 and T06 specs (2026-09-16)

The S04 cut above kept T05 and T06 whole. Reading their instructions in full changes that, and the reasons are worth
stating rather than quietly narrowing the work.

**T05 pipeline e2e — reduced, with two cases Blocked rather than deferred.**

- **Case C (crash before ack)** requires the **D04-8 crash-point seam** to halt the ledger JVM mid-batch. That seam is
  deferred by the cut above, so Case C cannot be written without reversing that decision. It is **Blocked on D04-8**.
- **Case D (relay crash after send)** requires an injection point inside `libs/outbox` to halt order-service between
  broker confirmation and the outbox rows being marked published. **No such injection point exists in D03-5.** The
  task anticipates exactly this and instructs: raise a change request to S03, mark Case D **Blocked in I.1 naming that
  dependency, and do not fork `libs/outbox`.** That is what happens here — no seam is added to the library from S04.
- **The preferred harness** runs order-service and ledger-service as containers built from project images on a shared
  network with Testcontainers PostgreSQL and Kafka. That harness does not exist, and building it is the same lift that
  the deferred M4(a) SIGKILL test needs. It stays deferred under the résumé-scope direction.
- **What remains achievable and is kept:** Case E, the M6(c) audit walk, which uses only the public read APIs and
  needs no crash machinery. Cases A and B are already covered in substance by `LedgerListenerIT` and
  `DuplicateDeliveryIT` at listener level, and by S03's outbox evidence for the API-to-relay half; that coverage is
  cited rather than re-run through a harness that does not exist. **This is narrower than the task's definition of
  done, and is recorded as such — Cases A–E are not claimed to pass.**

**T06 trace propagation — attempt only with the stack up, otherwise `Not run`.**

The check requires the Compose stack running with the OTel agent attached, a real order posted, and its spans located
in the tracing backend, classified as parent-child, links, or broken at a named hop, with trace IDs and a screenshot.
None of that can be inferred from code. The task is explicit: "Never record an assumed result", and an agent that
cannot attach is recorded as `Not run` against the D00-7 reference.

So T06 is attempted only if the stack comes up on this machine; otherwise it is recorded `Not run` with the reason.
**Under no circumstances is a classification written from reading the code** — the entire value of that task is that
someone actually looked at a trace.

**Consequence for the step's exit criteria.** S04 will close with T05 partial (Case E only, C and D Blocked) and T06
either executed or `Not run`. The step register records both honestly instead of reporting a green step.

## S07 Observability and performance — scope cut (2026-09-16)

S07 is the user's stated centrepiece ("observability, monitoring"), so it is cut *less* aggressively than S04. But it
has a hard ordering conflict that has to be stated rather than worked around.

**The ordering conflict.** S07's inherited decisions include **D05-2/5/8/11/12/13** (instrument-service fault knobs,
attempt state machines, sweeper schedule, kill switches, scenario runner) and **D06-4/D06-5** (reconciliation metrics,
verifier). The agreed priority order runs S07 *before* S05 and S06, so none of those exist. Consequently:

- **Unbuildable now, blocked on S05:** `provider_call_seconds`, `attempts_state_total`, `unknown_attempts_oldest_seconds`,
  `pending_payouts_oldest_seconds`, `NEEDS_REVIEW` count — and therefore the whole **Providers dashboard** and the
  **Unknown attempts** and **Pending payouts** alert rows.
- **Unbuildable now, blocked on S06:** reconciliation-break signals — the **Reconciliation breaks** alert row and the
  reconciliation panel of the Money-invariants dashboard.
- **T01 instruction 1** also requires the D05-12 scenario runner to generate smoke traffic. It does not exist, so
  traffic is driven with the real HTTP API instead, and the substitution is recorded.

**Kept, because it exists and carries the step's value**

- **T01 registry and missing instrumentation**, scoped to order-service, ledger-service and `libs/outbox`: the
  order-to-apply and apply-duration histograms, lock wait, retry counters by class, the invariant gauge from the D02-8
  queries, and the scheduled consumer-lag gauges S04 left computed on-request only.
- **T02 dashboards**, reduced to the two that can be populated: **Flow** and **Money invariants** (minus its
  reconciliation panel). Provisioned from the repository, not hand-imported.
- **T03 alert rules** for the signals that exist — invariant violation, quarantine, outbox backlog, consumer lag or
  paused, DLQ messages — with offline rule tests and runbook stubs. The blocked rows are listed as absent, not quietly
  dropped.

**Deferred**

- **T04 k6 scripts, perf runner and lock-wait sampler**, and **T05 perf runs and analysis.** k6 is not installed, the
  runs need a quiet reference machine, and the honest-evidence rule makes a hurried single-repetition run worse than
  none. This leaves the **G3 gate and the performance hard gates unevaluated**, which is recorded as such.
- **T06 SP4 hot-entity mitigation**, which depends on T05 evidence, and **S07-C01**.

**Consequence.** S07 will close with M12(a) and M12(b) addressed for the services that exist, M12(c) partial, and **no
performance evidence at all**. The register says so plainly instead of reporting a green step.

**One discrepancy to carry into D07-1.** D00-7 records that the "Kafka consumer span is a child of the producer span,
not a link". S04-T06 measured the opposite on the live stack: the consumer span is a **root with a link** to the
producer. The registry records what the backend actually holds, and the discrepancy is raised against D00-7 rather
than silently followed.

## S05 Instruments and fake providers — scope cut (2026-09-17)

Thirteen tasks at roughly 26 hours, covering two simulated providers, an attempt state machine, a collection policy
consumer, a payout run, an HMAC webhook receiver, sweepers and a scenario catalog. It is the largest step in the pack,
and under the résumé-scope direction it gets cut to the spine that makes the system demonstrably end-to-end.

**Why this step is worth doing at all rather than skipping.** It is the only source of the signals S07 had to record
as absent — provider latency, attempt states, `UNKNOWN` age, pending payouts — so it converts four "blocked" register
rows into real telemetry, and it is what makes the money loop close: an order becomes a real charge, a webhook
returns, and the ledger reflects it.

**Kept**

- **T04 `PaymentInstrument` interface, capabilities and result types.** The abstraction the whole step exists to show.
- **T01 FakeCard** (charges, refunds, idempotency keys, lookup) and **T02 FakeBank** (asynchronous payouts, lookup by
  client reference, settle/fail/return). Both are needed for M7's shared contract suite to mean anything.
- **T05 adapters** and **T06 the shared contract test suite plus the ArchUnit provider-boundary rule.** M7 in full:
  one suite both providers pass, and a rule that stops provider packages leaking.
- **T07 instruments schema, transition persistence with the optimistic guard, and the outbox** — reusing
  `libs/outbox`, which already carries the trace-context fix from CR-S04-01.
- **T08 the state × event transition table test.** M8(a). A state machine without an exhaustive table test is a
  state machine nobody can trust.

**Deferred**

- **T03 fault knobs, seeded randomness, signed webhook sender with redelivery, ground-truth endpoint**, and
  **T11 the webhook receiver**. This is the painful one: deferring them means **M8(b), M8(c) and M9 cannot be
  claimed**, because uncertain-outcome and webhook behaviour is exactly what those knobs produce. Recorded as
  unmet rather than approximated.
- **T09 collection policy consumer**, **T10 payout run** (so **M10 is unmet**, including the freshness check that
  S04-T04's endpoint was built to serve), **T12 sweepers and the `UNKNOWN` resolver**, **T13 scenario catalog and
  runner**, and **S05-C01** (the G2 alternative, which also leaves **gate G2 unevaluated**).

**Consequence, stated plainly.** S05 will deliver **M7 and M8(a)** and leave **M8(b), M8(c), M9 and M10 unmet**. Four
of S07's blocked signals stay blocked, because the components that emit them are deferred. The register will say so
rather than reporting a green step.

## CR-S05-01 — the test-database fixture is now duplicated three times

**Raised:** 2026-09-16, during S05-T01/T02.
**Against:** D00-2 (module layout).

`LedgerTestDatabase` and `OrderTestDatabase` are near-identical Testcontainers fixtures that differ only in database
name, role names and migration path. S02 recorded the rule explicitly: *a third service needing it triggers a change
request to D00-2 to extract a shared fixture.* fake-providers is that third service, so the rule has fired and this is
the request.

**What was done instead:** a third copy, `FakeProvidersTestDatabase`, was written.

**Why the extraction is deferred:** extracting it means changing the test infrastructure of two services whose suites
are currently green, for no behavioural gain, in a step whose remaining budget is going to the provider abstraction
itself. The duplication is cheap to carry and expensive to get wrong right now.

**Consequence, stated plainly:** there are now three copies of the container-and-init-script wiring. A change to
`infra/postgres/init.sh` handling, the pinned image lookup, or the role list must be applied in three places, and
nothing enforces that. If a fourth service needs it, extract first and add the service second.

**Discharged, 2026-09-17 (S05-T07).** instrument-service was the fourth service to need it, so the extraction was
done first, as this entry required. `libs/testsupport` now holds `ZsTestDatabase`, parameterised by database name,
migration path and durability; instrument-service uses it, and **fake-providers was migrated onto it and its suite
re-run green (16 tests, 0 failures)**, so the shared class is proven on two services rather than being a fourth copy.

Two deliberate omissions: `pooledDataSource` is not included (only ledger-service uses it), and the driver's
`PGSimpleDataSource` is used rather than Spring's, so the module needs no Spring dependency.

**Still true:** order-service and ledger-service keep their own copies. That is two implementations, not one, and a
change to the init-script wiring or the image lookup still has to be made in both places. Migrating them is follow-up
work and is not done.

## ADR-0010 was taken by the provider abstraction, not the quiet period

**Noted:** 2026-09-16, during S05-T04.

`docs/step_05_instruments_fake_providers.md` S05-T09 instructs that ADR-0010 record the resubmission quiet period,
the two-condition resubmission rule and its residual risk. **S05-T09 is deferred** in this cut, so that ADR is not
being written, and the number was used for the decision that S05 actually made: the `PaymentInstrument` abstraction
(`docs/adr/0010-payment-instrument-abstraction.md`).

The quiet-period rule itself is not lost — it is stated in ADR-0010's decision list and consequences, because the
abstraction is what forces it (a provider without idempotency keys cannot be constructed without a quiet period).
What is missing is the *implementation*: nothing resubmits anything yet, so the residual risk that S05-T09 was meant
to document has no code to attach to.

**If S05-T09 is ever built**, it takes the next free ADR number and links back to ADR-0010, rather than renumbering.

## S05 scope revised mid-step: T07 and T08 are deferred too

**Revised:** 2026-09-16, after S05-T06.

The original S05 cut kept **T07** (payment-attempt schema, optimistic concurrency guard, outbox) and **T08** (the
state x event transition table), and deferred T09 (the resolver that drives attempts through those states).

That combination does not hold together. T07 and T08 build the tables and the transition rules for a state machine,
and T09 is the thing that would actually move an attempt through them. Building the first two without the third
leaves schema nothing writes to and a transition table nothing consults — the kind of half-built structure that looks
finished in a file listing and is dead code in practice.

**Delivered instead:** T04 (the abstraction), T01 and T02 (both simulated providers), T05 (both adapters) and T06
(the shared contract suite and the ArchUnit boundary rule). That is a complete, demonstrable story: two providers
that behave nothing alike, reached through one interface, with the boundary enforced by a rule that is shown to fire.

**Consequence, stated plainly:**

- **instrument-service has no persistence.** It is an adapter layer. No payment attempt is recorded anywhere, so
  nothing survives a restart and there is no attempt id to look up after a crash.
- **Nothing resolves an `Unknown` outcome.** The adapters produce `Unknown` correctly and `lookup` works, but no
  scheduler calls it. The quiet-period rule is specified in ADR-0010 and implemented by nobody.
- **M8(b), M8(c), M9 and M10 remain unmet**, as already recorded. This revision does not change that; it removes the
  impression that the schema half of the work was going to arrive separately.
- **Resuming S05 means starting at T07 and going through T09 together**, not picking T07 up alone.

## The CI e2e job proved nothing until S09 (resolved)

**Found:** 2026-09-16, while reviewing CI before S09.

`.github/workflows/ci.yml` has three jobs: build + unit, integration (Testcontainers), and e2e (Compose). The e2e job
generates a throwaway `.env`, builds every service jar and the OpenTelemetry agent, starts the full Compose stack,
runs `./gradlew e2eTest`, dumps logs and stops the stack.

**There is not a single `@Tag("e2e")` test in the repository.** `grep -rn '@Tag("e2e")'` returns nothing. The build
conventions set `failOnNoDiscoveredTests = false` deliberately, so a tag that selects nothing is reported rather than
failed — the task logs `0 tests selected by the tag filter` and the job goes green.

So the most expensive job in CI starts a seven-container stack and asserts nothing about it. It is not broken, and it
does verify that every image builds and every container reaches healthy — which is not nothing — but a reader
glancing at a green e2e badge would reasonably conclude that end-to-end behaviour is covered, and it is not.

**Why it is in this state:** S04's pipeline e2e suite (D04-6) was recorded as not delivered, and no later step picked
it up.

**Consequence:** the money path — order API to outbox to Kafka to ledger apply to balances — is covered by
integration tests against Testcontainers, but never once against the actual Compose stack the demo runs on. S04
already showed what that gap hides: three defects that 132 green tests could not see, all of them only reachable in a
real deployment.

**Resolved, 2026-09-16 (S09).** `infra/tests/src/test/java/dev/zerosum/infra/MoneyPathE2ETest.java` is the first
`@Tag("e2e")` test in the repository. It posts a money order to the running order-service, waits for it to arrive
through the outbox and Kafka, and asserts the applied balances, the zero-sum property of the order's entries, the
changelog link back to the order and its idempotency key, and that the ledger's own invariants hold with nothing
quarantined. Measured: 1 test, 0 failures, 0.699 s against the seven-container stack.

It reads credentials from `.env` rather than from the environment. CI generates `.env`, starts Compose and then runs
Gradle without exporting anything, so a test using `System.getenv` would have passed on a developer's shell and
failed in CI — the same hollowness in a new form.

**What is still not covered end to end:** the provider path (instrument-service has no API), crash and restart
(M4(a)), fault injection, and reconciliation. One test is not a suite, and the e2e job still runs only on a schedule
or a manual dispatch, never on a pull request.
