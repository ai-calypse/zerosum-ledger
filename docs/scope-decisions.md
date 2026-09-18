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

**Superseded:** 2026-09-17, during S05-T12. The resolver was built — as **S05-T12**, which is where the final plan
puts it — so the question above came due. It did **not** take the next free ADR number.

ADR-0010 already states the quiet-period rule in its decision list (decision 5) and its consequences, and it is
Accepted. A second ADR would have had to restate that rule to make sense on its own, leaving two documents that must
agree about how long a bank is left alone before money is sent again — the failure mode being that someone changes
one of them. So S05-T12 **extended ADR-0010 in place**, as a dated amendment recording the implementation: the
chosen 60 s and what bounds it, the two-condition rule and why neither condition is sufficient alone, the residual
duplicate-payout risk, and how S08 A3 and F8 relate.

The note above anticipated a separate ADR; this is the deviation from it, and the reason. `D05-9` in H.1 points at
ADR-0010 accordingly, which is what the step's own instruction ("link it from D05-9") asks for either way.

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

<a id="m13-evidence-harness"></a>
## M13 evidence harness — the simulator and verifier are real (CR-S09-01, 2026-09-17)

**The problem.** `tools/simulator` and `tools/verifier` were eight-line stubs that printed `not implemented yet`.
M13(a) was therefore recorded **NOT MET** in [docs/architecture.md](architecture.md), and every invariant claim in
this repository came from a test rather than from a tool anyone could point at a database. The minimum cut
([§1](#1-minimum-cut-invoked-as-written)) says the verifier is the one S06 deliverable that is *never* cut, precisely
because it is what generates invariant evidence — so leaving it a stub contradicted the cut this project already made.

**Decided.** Both tools are implemented as runnable Gradle applications, with tests that fail when the tools are
wrong. Three of the decisions behind them are worth recording, because none is what the step documents proposed.

**1. `libs/evidence` is a new module.** M13(c) requires the same provenance block — hardware, versions, git SHA and
whether the tree was clean, and seeds — from *both* tools. Two hand-maintained copies of that block are one change
away from drifting, and the provenance block is the single thing in a results file that must not. This is the
argument CR-S05-01 already accepted for `libs/testsupport`, applied again. The block is captured by the tool that
writes the result, never typed in afterwards, because a hand-copied SHA is the field that goes stale first.

**2. The verifier copies ledger-service's invariant SQL instead of importing it.** `InvariantQueries` (D02-8) is a
Spring `@Component`; importing it would put a whole service behind a command-line tool and make the verifier depend
on the service whose books it exists to audit independently. D02-8 anticipated exactly this and wrote each check as a
standalone `SELECT` needing only `SELECT` privileges, "so the S06 verifier can reuse them unchanged". **The cost is
real and is not hidden: the two copies must be changed together.** What it buys is a verifier that can audit a
database whose service is not running, which is the situation it exists for.

**3. The simulator reuses `TripSequenceGenerator` from `libs/money`'s test fixtures**, which means a main source set
depending on test fixtures. That is unusual and deliberate: S08-T01 is explicit that the simulator reuses the D01-10
generators and that two generators are never maintained, and the W1 trip shape is exactly what that generator already
produces. The price is that the fixtures' test-scoped dependencies land on the tool's runtime classpath. The
alternative was a second, silently diverging copy of the randomness every seeded claim in this repository rests on.

**Scope: three invariants and one scenario.** The verifier evaluates **I2, I3 and I4** only. I1 and I6–I12 are
cross-store checks against the orders and instruments databases, which S06 owns and which is not built; I5 is the
hash chain, which ledger-service's own `verify` endpoint evaluates. The simulator ships **one** scenario, W1
(trip completed), with no adjustments, refunds, payouts, provider path or injected faults — those are S08's fault
matrix and ablations, which remain deferred. M13(b) is therefore still NOT MET, and is not claimed.

**Not run: the live stack.** Neither tool has been executed against the running Compose stack. The evidence files in
[docs/results/m13/](results/m13/) record that as **Not run**, with the reason, rather than reporting a number from a
run that did not happen. What *was* executed is recorded there too: the verifier against a real PostgreSQL container
initialised by the real `infra/postgres` scripts, and the simulator CLI against a stub of the two service APIs. A
stub run is not evidence about the money path, and it is labelled as such wherever it appears.

## The outbox relay gap was recorded, not discovered

**Noted:** 2026-09-17, integrating S05-T09.

S05-T09's report listed as its first defect that "payment events reached Kafka never... silent at both ends". That
framing is wrong, and the correction matters more than the detail.

The gap was **deliberate and written down** when it was created. S05-T07 wired only the outbox *writer*, and
`docs/results/s05/attempts.md` said so plainly: *"Nothing publishes these events. Only the outbox writer is wired.
The relay and cleanup job start with their first producer in S05-T09, so payment events accumulate in the table and
reach Kafka only once that lands."* The configuration class carried the same note. S05-T09 implemented that deferred
work; it did not discover a hidden bug.

**Why bother correcting it:** this repository's claim is that its gaps are recorded rather than found later. An
implemented deferral re-labelled as a defect find inflates both the discovery and the risk that was actually carried,
and it would make the record of S05-T07 look dishonest when it was the opposite.

**S05-T09 did find two genuine defects**, neither recorded anywhere beforehand:

1. **instrument-service was never passed a broker address.** No `ZS_KAFKA_BOOTSTRAP` in its Compose block, so inside
   the container it would have dialled itself while reporting healthy — the same class as the missing `ZS_*_TOKEN`
   variables found in S05-T08, and the fourth instance of this shape in the project.
2. **One malformed record would have stopped collections permanently.** `ContractSchemas.validate` *throws* on
   non-JSON bytes rather than returning errors; called outside a `try`, a single poison record would have paused the
   partition and redelivered for ever. Decoding is now classified before anything is written.

## CR-S05-02 — provider ground truth cannot be queried by entity

**Raised:** 2026-09-17, verifying S05-T09 in a deployment.
**Against:** D05-3 (S05-T03 admin/ground-truth endpoint).

`GET /admin/truth?entity_id=` returns `"charges": []` for **every** entity, including one whose charge had just
succeeded seconds earlier.

**Confirmed against the database, not inferred.** After a successful charge:

```
fakeproviders.card_charges : charge_id=ch_30673ba9-…  client_ref=01a0ae99-e019-7da2-8e46-3c661b2e1ae5  SUCCEEDED
instruments.payment_attempts: attempt=01a0ae99-e019-7da2-8e46-3c661b2e1ae5  entity=rider:OKc401deb3df
```

The charge is stored against the **client reference**, which is the attempt id. The string `rider:OKc401deb3df`
appears nowhere in the fakeproviders database, because instrument-service never sends it: the adapter sends the
attempt id as client reference and instrument token, and nothing else. The endpoint is filtering charges by an
identifier the provider has never been given, so the filter can only ever match nothing.

**Why this is more than cosmetic.** Invariant **I7** compares the ledger against provider ground truth through this
endpoint. As it stands, an auditor querying by entity would conclude that no charge had occurred, for any entity, at
any time — a clean bill of health produced by a lookup that cannot match. The webhook-queue section of the same
response is populated correctly, which makes the empty `charges` array look like a real answer rather than a broken
query.

**The tension to resolve, not paper over.** Master §5.6 specifies the parameter as `entity_id`, but a real payment
provider does not know our entity ids, and teaching fake-providers about them would make the simulator less faithful
than the thing it simulates. Three ways out, in preference order:

1. **Query ground truth by client reference**, and have the caller resolve entity → attempt ids from the instruments
   database first. Keeps the provider ignorant of our domain, which is the property that makes it a useful
   simulation. Requires a change request against the master's §5.6 parameter.
2. Have instrument-service pass the entity id to the provider as opaque metadata. Faithful to §5.6, less faithful to
   reality.
3. Leave it and document that `entity_id` never matches. Rejected: a lookup that silently returns "nothing wrong" is
   the worst of the three.

**Not fixed here.** The endpoint belongs to S05-T03; fixing it inside a verification pass would put the change in a
commit whose subject is something else. Recorded, with I7 unusable through this endpoint until it is done.

### Resolved 2026-09-17 with S05-T11 — option 1, and a change request against master §5.6

**Change request.** Master [§5.6](zerosum_ledger_mvp_plan.md#rest-apis) specifies `GET /admin/truth?entity_id=`. The
parameter is now **`client_reference`**, and `entity_id` is **refused with 400** rather than accepted and ignored.

- **Why the master's parameter could not be kept honestly.** fake-providers is never told a ledger entity id: the
  adapter sends the attempt id as the client reference and instrument token, and nothing else. Any filter keyed on an
  entity id can only ever match nothing, so keeping the name working would have meant either teaching the simulator
  our domain (option 2 — faithful to §5.6, unfaithful to reality, and it would make the simulator a worse instrument
  than the thing it simulates) or leaving a lookup that answers "nothing wrong" by construction (option 3, already
  rejected).
- **Why refused rather than ignored.** An unknown parameter that is silently dropped is exactly how this defect
  produced a clean bill of health. A 400 naming `client_reference` cannot be misread as an answer.
- **What the caller does instead.** Resolve entity → attempt ids in the instruments database, then ask ground truth
  by reference. That keeps the provider ignorant of our domain, which is the property that makes it useful evidence
  for I7.
- **Cost.** `Truth.entity_id` in the response is now `Truth.client_reference`. Three tests moved with it. The verifier
  does not use this endpoint yet, so nothing outside fake-providers' own tests had to change.
- **Not changed.** The master document still reads `entity_id` at §5.6; this entry is the change request against it,
  and I7 is queryable through the endpoint again.

**Regression guard.** `AdminSecurityIT.groundTruthIsQueryableByClientReference` charges against an attempt-id-shaped
reference, finds it by `client_reference`, and asserts the `entity_id` form is a 400 — the exact query the deployment
check ran, now failing loudly instead of returning `[]`.

<a id="s06"></a>
## S06 Reconciliation — trimmed to its spine (2026-09-17)

S06 as written is five tasks: settlement reports, the reconciler, the reconciliation API with a scheduler and
metrics, the full I1–I12 verifier CLI, and the W5/W6 scenario files. The standing direction — résumé-presentable,
honest evidence, defer genuine complexity rather than fake it — cuts it to the part that demonstrates the actual
engineering idea: **money is checked against an independent account of itself, and disagreements are typed.**

**Kept**

- **T01 settlement reports in fake-providers**, with the three discrepancy knobs wired through the §0.3 C23
  extension point that S05 left for exactly this, and every injection recorded in the existing D05-2 fault log.
- **`settlementReport` implemented in the FakeCard adapter**, and `Capabilities.settlementReports` flipped to true
  for FakeCard only. This is what makes the shared contract suite **run** that case instead of skipping it: the
  project-wide unit skip count drops from 4 to 3, and the remaining 3 are the webhook-parsing assumptions plus
  FakeBank's settlement case, which is correctly not applicable.
- **T02 the reconciler**: a pure matcher, nine typed breaks, additive migration, and `SETTLEMENT_RECEIVED` emitted
  through the existing outbox path in the same transaction as the run.
- **T03 reduced to the two endpoints** the master names, with D03-3 idempotency and role checks.

**Deferred, and what each costs**

- **The scheduler and the settlement-cycle grace rule.** Without them a timing break is reported `OPEN` inside its
  own run and is never carried across cycles, so **M11(c) cannot be evaluated at all** — it is recorded "Not run",
  not "met". This is the honest cost of the cut and the reason the scheduler is the first thing to add back.
- **Reconciliation metrics.** S07's reconciliation alert row stays blocked, as S07 already recorded.
- **T04, the I1–I12 verifier CLI.** The minimum cut says the verifier is never cut, and that remains true: it is
  *unstarted*, not cancelled. `tools/verifier` still evaluates I2–I4 only, exactly as CR-S09-01 recorded. In
  particular **I9 (clearing residual = in-flight + open breaks) and I12 (every injected discrepancy has its mapped
  break) are not evaluated**, so the knob-to-break map in D06-2 is asserted by this step's own tests rather than by
  an independent tool.
- **T05, the W5/W6 scenario files.** Blocked rather than deferred: the D05-12 scenario catalog and runner do not
  exist, having been deferred in the S05 cut.

**Consequence, stated plainly.** S06 delivers **M11(a) and M11(b)** with tests behind them, and leaves **M11(c)
unevaluated**. The settlement event is asserted as far as the outbox row and compared field by field against golden
O6; the booking itself is order-service's mapper, which has its own golden test, and **no end-to-end run was
observed** — S04's pipeline harness was never delivered, and this step did not build one.

## Testcontainers runs leak into a running Compose stack

**Found:** 2026-09-17, verifying S06 reconciliation live.

`docker-compose.yml` publishes Kafka on `127.0.0.1:9092`, and test contexts that do not pin a bootstrap address fall
back to that default. So while the stack was up, **agents' Testcontainers runs published to the real broker**, and
order-service's mapper consumed their events into the real `orders` database.

The evidence was three `SETTLEMENT` money orders with idempotency keys `settlement:fakecard:rpt_test_*`, written by
`instrument-service` at 20:45–20:48. They looked exactly like proof that M11(a) was met — a reconciliation run
producing a SETTLEMENT order. They were not: `reconciliation_runs` held **0 rows** and the instruments outbox held
**0** `SETTLEMENT_RECEIVED` events. The orders came from test runs, through a shared broker.

**Two costs, one nearly paid.** `LedgerOpenApiContractIT.theFreshnessResponseValidatesEvenWhenItCannotBeComputed`
asserts the fail-closed path on the premise, stated in its own comment, that there is *"nothing at localhost:9092"* —
false for anyone with the stack running, which is how S05-T10's agent saw it fail. And an acceptance criterion was
one step from being promoted on contaminated rows.

**Fixed** by pinning `spring.kafka.bootstrap-servers` to `127.0.0.1:1` in `LedgerApiTestBase`, so the fail-closed
path is deterministic instead of contingent on a port being free.

**It did happen again, 2026-09-18 08:54 UTC.** `AttemptEndpointsIT` (instrument-service), run while the stack was
up, published a `CHARGE_SUCCEEDED` event into the live broker, and the running order-service booked it as a real
`COLLECTION` order (`provider:fakecard/clearing +2,500`). The ledger stayed consistent, and the order was left in
place because the ledger is append-only. The dashboard agent that found it pinned three tests and counted eleven
more that could do the same.

**Fixed at the root** instead of per test: `zs.java-conventions` sets `ZS_KAFKA_BOOTSTRAP=127.0.0.1:1` and
`ZS_OTLP_METRICS_URL=http://127.0.0.1:1/v1/metrics` for every `Test` task. Every service reads its broker and metrics
endpoint through those placeholders, so no test can reach a running stack's Kafka or Prometheus by default. A test
with its own Testcontainers broker is unaffected, because `@DynamicPropertySource` outranks the placeholder. The
e2e and chaos tests reach the stack over HTTP, JDBC and `docker exec`, never through these variables.

## CR-S05-03 — the payout submission path is restated rather than shared

**Raised:** 2026-09-17, integrating S05-T12.

`PayoutSubmitter` (S05-T10) is package-private, and its own javadoc anticipates the S05-T12 sweeper calling it. T12
was scoped away from `payouts/**` to avoid a merge collision, so it restated the submission call in
`recovery/PayoutResubmission` instead of sharing one.

**This is not a duplicate-pay risk, and the reason matters.** The once-only guarantee does not live in either class:
it is the guarded `CREATED → SUBMITTING` transition in `AttemptTransitions`, which is shared, plus the
`one_inflight_payout` partial unique index. Both paths also read the same `payouts-enabled` switch. What is
duplicated is the *call sequence*, not the safety property.

**The cost** is ordinary drift: a change to phasing or to the kill-switch read must now be made in two places, with
nothing enforcing it — the same shape as CR-S05-01's fixture duplication.

**Fix:** make `PayoutSubmitter`'s submission method public, or extract one port both callers use, and delete
`PayoutResubmission`. Not done here because it edits a module another agent owned during the same wave.

## CR-S05-04 — FakeCard lookup cannot resolve a refund

**Raised:** 2026-09-17, from S05-T12's review.

`FakeCardInstrument.lookup` queries only `/fakecard/v1/charges?client_reference=`. A **refund** attempt therefore
resolves as `NotFound`, because refunds live at a different resource.

**Harmless today, and precisely why it is worth recording.** FakeCard declares idempotency keys, so the resolver
retries with the same key rather than looking up — the broken branch is never taken. It becomes real the moment
anything resolves a refund by lookup: the ahead-of-state path would report a refund unresolvable, and `NotFound` is
the one answer that *permits a resubmission* (ADR-0010). A lookup that cannot see refunds must never be allowed to
say "it never happened" about one.

**Fix:** query refunds as well and merge the results, or return `Unavailable` for refund lookups until it does —
`Unavailable` is safe where `NotFound` is not.

## Agent worktrees are cut from a stale HEAD

**Found:** 2026-09-17, across the parallel-agent waves. **My process failure, not the agents'.**

Worktrees created with `isolation: "worktree"` did **not** branch from the `main` I had just merged. Every agent in
the final wave — performance, chaos, dashboard — branched from `98f33603`, while `main` was at `4d0406b`. Neither
S05-T10 (payout runs) nor S05-T12 (sweepers) existed in their trees.

**What it cost, concretely:**

- The dashboard agent skipped a payout-runs panel, reporting that "`payout-runs` appears nowhere in any Java source".
  That was true *of its tree* and false of `main`, where `PayoutRunController` and `POST /v1/payout-runs` exist and
  had already been driven live. A useful panel was dropped for a reason that looked like diligence.
- The S05-T12 agent noticed its own base was stale, fast-forwarded itself, and said so in its report. That it caught
  this and another agent did not is luck, not process.
- The chaos agent was measuring crash recovery, a volume run and ablations against a system **older than `main`**, so
  its numbers describe a tree missing two merged features.

**Why it is easy to miss:** the divergence looks identical to the ordinary phantom-deletion diff. `git diff
main..branch` shows everything merged after the base as deletions, which is normal and safe, so the genuinely
important signal — that the agent could not see a feature — is buried in noise that is usually benign.

**The rule:** check `git merge-base main <worktree-branch>` at launch, not at merge. If it is behind, either
fast-forward the worktree before the agent starts or state in the brief which commits it will not see. A brief that
says "S05-T10 is merged, use it" against a tree where it does not exist wastes the agent's judgement on a false
premise — which is exactly what happened here.

## CR-S07-01 — a statement timeout while queueing for entity locks quarantined valid money (fixed)

**Found:** 2026-09-18, by the S07 batch study ([batched-vs-per-order.md](results/perf/batched-vs-per-order.md)),
not by any test. **Fixed the same day**, against D02-4.

**What happened.** At 32 writers × 100-order batches, all on one hot entity, the entity-lock
`SELECT … FOR UPDATE` (`LedgerStore.lockEntities`) exceeded the 5 s `statement_timeout` 122 times. D02-4 classifies
`57014` as non-transient, so the engine isolated each failing batch record by record, and under the same contention
**3 valid orders were quarantined** ([engine-warnings.log](results/perf/2026-09-18-batch-b/engine-warnings.log)).
Nothing was lost and I2–I5 held, but quarantine means an operator must hand-replay money that was never wrong.

**Why the existing guard did not catch it.** `lock_timeout` (2 s) bounds *each* lock wait, not their sum. One
statement locking many entities can queue behind several holders in turn, each wait under 2 s, and cross 5 s in total.
The failure then arrives as `57014`, not `55P03`, and the classifier had been told that `57014` means a runaway query.

**Fix.** The engine wraps `lockEntities`: a `57014` raised there becomes `RetryClassifier.LockQueueTimeout`, which is
transient and counted as a lock-timeout retry. Everywhere else, `57014` stays non-transient: a slow query elsewhere
would only time out again. Exhausting the retry schedule still raises `RetriesExhaustedException`, so the record is
redelivered, never quarantined.

**Evidence.** `ApplyLockTimeoutRetryIT.aStatementTimeoutWhileQueueingForLocksIsRetriedNotQuarantined` sets the
statement timeout (500 ms) below the lock timeout (10 s) and holds one entity lock for 1.5 s. Without the fix it fails
with the order `QUARANTINED` / `UNEXPECTED_DATABASE_ERROR` (checked by reverting the fix and running it); with the fix
the order is applied once and nothing is quarantined.

**Exposure before the fix.** None in the deployed topology: the ledger listener is single-threaded, so there is no
queue of writers on one entity. It would have appeared as soon as listener concurrency was raised. The batch study was
not re-run after the fix, so its 32 × 100 figure stays marked invalid.

**Confirmed at load, 2026-09-18.** The quiet-machine re-run ([quiet-rerun.md](results/perf/quiet-rerun.md))
ran 32 writers × 100-order batches on one entity again, with the fix in place. The same lock-queue timeouts occurred
**12 times; every one was retried, 0 orders were quarantined**, and all three windows were valid.
