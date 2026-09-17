# Step 05 — Instruments and fake providers

> **Pack:** ZeroSum Ledger implementation docs · **Master:** [docs/zerosum_ledger_mvp_plan.md#step-05](zerosum_ledger_mvp_plan.md#step-05) (v1.2)
> **Doc version:** 1.1 · **Date:** 2026-09-15 · **Step status:** Planned (no implementation exists)
> **Planned effort:** 24 h (master schedule; see [docs/zerosum_ledger_mvp_plan.md#schedule-overview](zerosum_ledger_mvp_plan.md#schedule-overview)), plus 2 h pre-allocated contingency for S05-T13 ([docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates)) · **Gate:** G2
> **Shared procedures:** [docs/README.md#change-detection](README.md#change-detection) · [docs/README.md#source-of-truth](README.md#source-of-truth) · [docs/README.md#status-legend](README.md#status-legend)

<a id="purpose-and-outcome"></a>
## A. Purpose and outcome

**Problem this step solves.** After S04, money orders flow effectively-once from the order API into the ledger, but no money actually moves. Nothing collects from riders, refunds adjustments, or pays drivers, and nothing models the part of payments that causes real incidents: providers that time out after committing, providers without idempotency keys, asynchronous outcomes, duplicated and reordered webhooks, and bank returns that arrive after a payout looked settled ([docs/zerosum_ledger_mvp_plan.md#workflows](zerosum_ledger_mvp_plan.md#workflows), W1–W4). This step builds the collection and disbursement stages ([docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components)) and the simulated external world they talk to.

**Concrete deliverable.**

1. A `fake-providers` service with two simulators whose semantics deliberately differ (FakeCard: synchronous, idempotent; FakeBank: asynchronous, non-idempotent, with late returns), persistent state, seeded fault knobs, a signed webhook sender with redelivery, and a ground-truth endpoint for tests.
2. In `instrument-service`: the provider-agnostic `PaymentInstrument` interface with capability flags, two adapters, a shared provider contract test suite and an ArchUnit boundary.
3. The instruments database: payment attempts, append-only transition history, provider-event records, instrument tokens, payout runs and the instrument outbox.
4. Charge, refund and payout attempt state machines with an optimistic transition guard, covered by a full state × event table test.
5. The collection policy consumer, the payout run (freshness check, in-flight uniqueness, kill switches), the HMAC webhook receiver, the sweepers and the `UNKNOWN` resolver, plus ADR-0010 (FakeBank quiet period).
6. A scenario catalog format, W1–W4 scenario files with expected final state, and a minimal runner (contingency-funded S05-T13).

**Contribution to the MVP.** This step delivers must-haves M7–M10 ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)) and closes decision gate G2 ([docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates)). It also produces the fault knobs, ground truth and kill switches that S08's fault matrix and ablations depend on ([docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix)), and the third résumé bullet about uncertain outcomes ([docs/zerosum_ledger_mvp_plan.md#portfolio-value](zerosum_ledger_mvp_plan.md#portfolio-value)) can only be filled from evidence that starts here.

**In scope.**

- FakeCard charges, refunds, lookups and charge/refund webhooks; FakeBank payouts, lookups and payout webhooks with a simulated banking-day lifecycle.
- Provider fault knobs for latency, failures, uncertain commits, webhook chaos, returns and processing delay, all seeded.
- `PaymentInstrument`, capabilities, result types, both adapters, the contract suite and the module boundary rule.
- Attempts, transitions, the provider-event record, the instrument outbox (via `libs/outbox`), and the payment events that feed the S03 mapper.
- Collection policy, payout run, webhook receiver, sweepers, `UNKNOWN` resolution, quiet-period resubmission, and money-movement kill switches.
- The instrument-service OpenAPI file for the endpoints this step implements.
- W1–W4 scenario files and a minimal runner with verifier-lite checks (I7, I8, I10).

**Explicitly excluded (owner in parentheses).**

- FakeCard settlement report generation, report discrepancy knobs, the reconciler, reconciliation tables and endpoints, and `SETTLEMENT_RECEIVED` emission (S06: [docs/step_06_reconciliation_verifier.md#s06-t01](step_06_reconciliation_verifier.md#s06-t01), [docs/step_06_reconciliation_verifier.md#s06-t02](step_06_reconciliation_verifier.md#s06-t02)). This step only declares the settlement-report operation, its capability and the knob-schema extension point ([docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications), C23).
- The full cross-store verifier I1–I12 ([docs/step_06_reconciliation_verifier.md#s06-t04](step_06_reconciliation_verifier.md#s06-t04)) and W5–W6 scenarios ([docs/step_06_reconciliation_verifier.md#s06-t05](step_06_reconciliation_verifier.md#s06-t05)).
- The metric name registry, the providers dashboard and alert rules ([docs/step_07_observability_performance.md#s07-t01](step_07_observability_performance.md#s07-t01), [docs/step_07_observability_performance.md#s07-t02](step_07_observability_performance.md#s07-t02), [docs/step_07_observability_performance.md#s07-t03](step_07_observability_performance.md#s07-t03)), and the P3 collection-overhead measurement ([docs/step_07_observability_performance.md#s07-t05](step_07_observability_performance.md#s07-t05)).
- Ablation seams A3 and A5 and the F3 breakpoint hook (added by S08 through a change request on D05-5; §0.3 E7, E9), chaos scripts, toxiproxy toxics and the simulator workloads ([docs/step_08_fault_injection_ablation.md#s08-t01](step_08_fault_injection_ablation.md#s08-t01), [docs/step_08_fault_injection_ablation.md#s08-t02](step_08_fault_injection_ablation.md#s08-t02), [docs/step_08_fault_injection_ablation.md#s08-t03](step_08_fault_injection_ablation.md#s08-t03)).
- The `FakeWallet` developer integration test ([docs/step_09_demo_docs_release.md#s09-t05](step_09_demo_docs_release.md#s09-t05)).
- Authorization holds, chargebacks and disputes, dunning, instant payouts, the Stripe test-mode adapter and synchronous in-session payments ([docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred)); real money, PCI DSS and KYC ([docs/zerosum_ledger_mvp_plan.md#non-goals](zerosum_ledger_mvp_plan.md#non-goals)).

<a id="agent-prompt"></a>
## B. Step-specific agent prompt

```text
You are the implementation agent for Step 05 (Instruments and fake providers) of the ZeroSum Ledger project.

Repository root: zerosum-ledger/
Step document: docs/step_05_instruments_fake_providers.md

1. Read docs/README.md (source-of-truth, conflict-resolution, change-detection, status-legend), then this
   document in full, then every source in section C at the linked anchors: master (v1.2) §0.3 items C5, C8,
   C9, C12, C20–C25, E2, E3, E7, E9, O3 and O9, then §5.4, §5.5, §5.6, §5.7, §5.9, §5.10, §5.11, §4.4,
   §4.5 (TB2/TB4), §2.4, §3.1 (M7–M10), §6.4, §8.1, §8.3, §8.4 and §11.2 (G2).
2. Inspect the current repository and the registers (section H) and execution records (section I) of S00,
   S01, S02, S03 and S04. Resolve current definitions from those registers and the artifacts they reference
   (version catalog, compose file, DB roles, libs/money, libs/contracts, libs/outbox, libs/auth, topic
   config, freshness and outbox-stats endpoints). Do not rely on copies or examples in this document.
3. Run the change-detection procedure (docs/README.md#change-detection) before changing code. Record every
   consumed document and artifact with its git revision or hash in section I.2.
4. Complete only this step's remaining authorized tasks (S05-T01 … S05-T13), in the dependency order given
   by each task's "Depends on" field. S05-T13 is funded from pre-allocated contingency. S05-C01 may start
   only when its evidence trigger is recorded. Do not implement settlement reports, reconciliation, the
   verifier, dashboards, alert rules, ablation flags, chaos scripts or anything else owned by S06–S09.
5. Verify each task exactly as its "Verification and definition of done" field says. Record evidence paths
   in H.4 and I.1.
6. Record every decision D05-1 … D05-14 with rationale and alternatives in H.1, actual implementation and
   configuration paths in H.2, and produced artifacts in H.3. Put a trace comment next to every
   authoritative config value, e.g. "# decision: D05-8 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs".
7. Never invent results. Never mark blocked or unexecuted evidence as passed: use "Not run" or "Blocked"
   with the exact missing dependency. Never implement future-step scope.
8. Re-run change detection at every phase boundary (end of Phases 1–6) and before handoff. Mark affected
   completed tasks "Needs review" when an upstream source changed.
9. If you need to change a decision owned by the master or an upstream step (for example the payment-event
   schema D01-8, the mapper D03-6, the outbox D03-5, topics D04-1 or the error policy D04-4), stop and follow
   docs/README.md#conflict-resolution. Never weaken an acceptance gate.

Finish by completing section G, section J handoff conditions and H.6, then report: tasks done, evidence,
open blockers and change requests raised.
```

<a id="required-reading"></a>
## C. Required reading and prerequisites

### C.1 Master sections

| Link | Why |
|---|---|
| [docs/zerosum_ledger_mvp_plan.md#step-05](zerosum_ledger_mvp_plan.md#step-05) | Step objective, task list, exit criteria, largest-step risk and the ADR-0010 decision. |
| [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) | v1.2 resolutions this step implements: C5, C8, C9, C12, C20–C25, E2, E3, E7, E9, O3, O9. |
| [docs/zerosum_ledger_mvp_plan.md#instrument-interface](zerosum_ledger_mvp_plan.md#instrument-interface) | PROPOSED `PaymentInstrument`, capabilities, result types, FakeCard vs FakeBank behavior, magic tokens and fault knobs. |
| [docs/zerosum_ledger_mvp_plan.md#state-machines](zerosum_ledger_mvp_plan.md#state-machines) | PROPOSED charge, refund and payout state machines, stale-event rule, `UNKNOWN` resolution schedule, quiet-period rule. |
| [docs/zerosum_ledger_mvp_plan.md#policies](zerosum_ledger_mvp_plan.md#policies) | Collection policy (charge, refund, blocked-on-capture) and payout-run eligibility and known limitation. |
| [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order) | Which payment events become which orders; defines what each attempt transition must emit. |
| [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts) | Payment-event shape, deterministic event ID, topics and consumer groups (proposal; selected in D01-8 and D04-1). |
| [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis) | PROPOSED instrument-service endpoints and fake-provider internal APIs. |
| [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas) | PROPOSED instruments DDL, optimistic transition statement, outbox table shape. |
| [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) | Auth, validation, timeouts, retry rules, idempotency, stale state and recovery contracts. |
| [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards) | Stripe idempotency and webhook semantics, uncertain outcomes, payout/ACH return behavior, adapter-owned minor-unit mapping. |
| [docs/zerosum_ledger_mvp_plan.md#industry-designs](zerosum_ledger_mvp_plan.md#industry-designs) | Airbnb pre-RPC / RPC / post-RPC phasing: no network calls inside local transactions. |
| [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) | TB2 webhook controls and TB4 fault-injection and admin endpoint controls. |
| [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows) | W1 and W4 sequences and the crash-point recovery table. |
| [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) | M7–M10 acceptance criteria (and M12(c) for the alert signals this step must expose). |
| [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) | G2 proceed, narrow-scope, change-architecture and stop conditions. |
| [docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app) | Scenario catalog intent: files with expected final state. |
| [docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix) | Faults F3, F6, F7, F8 that later use this step's knobs; knobs must make them injectable. |
| [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation) | A3 and A5 target this step's code; seams are added later by S08. |
| [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants) | I7, I8, I9, I10, I11: the checks this step's data must make possible; the v1.2 quiesce definition (§0.3 E3). |
| [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded) | Expected behavior when providers are slow or webhooks are dropped. |
| [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring) | Runbook's money-movement freeze (proposed kill-switch names) and the unknown-attempts and pending-payouts alerts (§0.3 O9). |
| [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation) | Proposed names for the alert-signal gauges this step exposes (§0.3 O9); D07-1 owns the registry. |
| [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets) | Webhook secret handling and rotation proposal. |
| [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut) | The S05 cut line (webhook reorder simulation). |
| [docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates) | Why S05-T13 exists and how it is funded; cost is not a decision driver. |

### C.2 Earlier step documents and their registers

| Register | Decision IDs consumed |
|---|---|
| [docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs) | D00-1 pinned versions; D00-2 module layout (where `services/instrument-service` and `services/fake-providers` live); D00-3 compose topology and profiles (fake-providers container, internal network, `chaos` profile guard); D00-4 databases and roles (`instruments`, `fakeproviders`; an owner and an application role per service plus the read-only verifier role, §0.3 O3); D00-5 CI job split and test tags; D00-6 observability wiring; D00-7 SP3 outcome (metrics export path); D00-8 environment and secret conventions (tokens, webhook secrets, flags, log redaction); D00-9 ADR process and results template; D00-10 build conventions and test tags. |
| [docs/step_01_domain_contracts.md#decisions-and-outputs](step_01_domain_contracts.md#decisions-and-outputs) | D01-1 `Money` API; D01-2 ISO minor-unit table; D01-3 fee rounding (FakeCard fee simulation); D01-5 ZeroSumValidator rule set (only if S05-C01 triggers); D01-6 chart of accounts and ADR-0003; D01-7 currency allow-list; D01-8 payment-event JSON Schema and versioning rule; D01-9 golden payloads O1–O8 (source for scenario expected balances); D01-10 seeded generators and seed-reporting convention; D01-11 ArchUnit money rules. |
| [docs/step_02_ledger_core.md#decisions-and-outputs](step_02_ledger_core.md#decisions-and-outputs) | D02-2 append-only enforcement pattern (transition history, provider-event records); D02-7 ledger read API (payable balances for payouts, clearing accounts for I9 checks); D02-8 invariant queries (used by scenario assertions where available). |
| [docs/step_03_order_service_outbox.md#decisions-and-outputs](step_03_order_service_outbox.md#decisions-and-outputs) | D03-1 orders schema (only if S05-C01 triggers); D03-2 money-order API (scenarios create COMMERCE orders through it); D03-3 idempotency semantics (pattern reused for payout-run keys); D03-4 `libs/auth` module and principal mapping (S05 wires it into instrument-service, §0.3 C9); D03-5 `libs/outbox` (ADR-0008); D03-6 payment-event mapper and consumer (ADR-0006); D03-7 outbox stats endpoint (freshness input). |
| [docs/step_04_kafka_pipeline.md#decisions-and-outputs](step_04_kafka_pipeline.md#decisions-and-outputs) | D04-1 topic definitions and partition key (ADR-0007); D04-2 client configuration; D04-3 listener design (manual ack after commit); D04-4 error-handling policy (transient vs poison, DLQ naming, pause and alert; each consumer quarantines in its own database, §0.3 C8); D04-5 freshness endpoint (Kafka record-timestamp basis, §0.3 C12); D04-6 pipeline e2e suite (harness the runner builds on); D04-7 relay lag and SP2 decision. |

### C.3 Artifacts that must already exist

These are the paths *as planned* by upstream registers. **Resolve the actual path from each register's H.2/H.3 table, not from this list.**

| Planned artifact | Owner |
|---|---|
| `gradle/libs.versions.toml`, Gradle modules for `services/instrument-service` and `services/fake-providers` | D00-1, D00-2, D00-10 |
| `docker-compose.yml` with a fake-providers service entry and profiles | D00-3 |
| `infra/postgres/init.sql` with the `instruments` and `fakeproviders` databases with owner and application roles; per-service Flyway baseline | D00-4 |
| `.github/workflows/ci.yml` with integration and e2e jobs keyed to test tags | D00-5 |
| `.env.example` and secret conventions | D00-8 |
| `docs/adr/` (ADR-0001 process) and `docs/results/TEMPLATE.md` | D00-9 |
| `libs/money` (`Money`, ISO table, `FeeCalculator`, `ChartOfAccounts`) | D01-1, D01-2, D01-3, D01-6 |
| `libs/contracts` payment-event schema and golden O1–O8 payloads | D01-8, D01-9 |
| Append-only trigger/grant pattern (reusable migration snippet) | D02-2 |
| `openapi/ledger-service.yaml` and the running balances endpoint | D02-7 |
| `libs/auth` | D03-4 |
| `libs/outbox` | D03-5 |
| Payment-event mapper and `order-mapper` consumer in order-service | D03-6 |
| Order-service outbox stats endpoint | D03-7 |
| Topic definitions config, client config, listener and error-handling components | D04-1 … D04-4 |
| Ledger freshness endpoint | D04-5 |
| Pipeline e2e test suite | D04-6 |

### C.4 Blocking vs independent work

| Missing input | Blocks | Independent preparation that can proceed |
|---|---|---|
| D00-3 fake-providers compose entry or D00-4 `fakeproviders` role | Container-based parts of S05-T06, S05-T13 | S05-T01–T03 against a Testcontainers PostgreSQL; S05-T04 (pure Java) |
| D01-8 payment-event schema | Outbox payload validation in S05-T07; S05-T13 | S05-T01–T06, S05-T08 table tests with an in-memory event sink |
| D01-3 fee rounding / D01-1 `Money` | Fee fields in S05-T01; adapter mapping in S05-T05 | FakeBank S05-T02, fault knobs S05-T03 |
| D02-7 balances API | Payout amount in S05-T10 | S05-T10 run bookkeeping, idempotency and uniqueness with a stubbed balance port (test only); all other tasks |
| D03-4 `libs/auth` | Role checks on S05-T08–T10 endpoints | Endpoint logic and tests behind a test principal; webhook HMAC (S05-T11) is independent of bearer auth |
| D03-5 `libs/outbox` | Event publication in S05-T07 and everything downstream of it | Fake providers, interface, adapters, contract suite (S05-T01–T06) |
| D03-6 mapper and consumer | Order creation in S05-T13 scenarios | Attempt-level assertions in S05-T09–T12 using the instrument outbox contents |
| D04-1/D04-2/D04-3/D04-4 topics, client config and error policy | S05-T09 consumer | S05-T09 policy decision logic as a pure function with unit tests |
| D04-5 freshness endpoint or D03-7 outbox stats | Freshness refusal in S05-T10 | Eligibility, in-flight uniqueness and kill switches in S05-T10 |
| D04-6 pipeline e2e suite | S05-T13 runner base | Scenario file format and W1–W4 expected states (S05-T13 steps 1–3) |

<a id="ownership-and-requirements"></a>
## D. Ownership and engineering requirements

### D.1 Decisions and contracts owned by this step

Every entry starts from a PROPOSED master contract. The selected value lives in H.1 and in the artifact H.2 points to.

| Decision ID | What is decided | Master's proposed starting point |
|---|---|---|
| D05-1 | `PaymentInstrument` interface, `Capabilities`, command and result types, `UnsupportedCapabilityException`, adapter registration by provider ID (actual signatures) | [docs/zerosum_ledger_mvp_plan.md#instrument-interface](zerosum_ledger_mvp_plan.md#instrument-interface) |
| D05-2 | fake-providers internal API, persistence schema, fault-knob schema (fields, validation, extension point for S06 discrepancy knobs, §0.3 C23), refund idempotency (§0.3 C23), seeding, magic-token set, simulated banking-day lifecycle, ground-truth response, injected-fault log (§0.3 E2), webhook redelivery-queue status (§0.3 E3) | [docs/zerosum_ledger_mvp_plan.md#instrument-interface](zerosum_ledger_mvp_plan.md#instrument-interface), [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis) |
| D05-3 | Webhook signature format (signed string, header names, secret rotation handling), timestamp tolerance source, verification component shared by sender tests and receiver | [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) (TB2), [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting), [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets) |
| D05-4 | Instruments DB schema and Flyway migrations: attempts, transition history, provider-event records, instrument tokens, payout runs, quarantine (§0.3 C8; Kafka topic, partition and offset unique together, §0.3 C25), outbox; uniqueness (attempt key per §0.3 C22) and partial indexes; append-only enforcement | [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas) |
| D05-5 | Attempt state machines and transition table, including the emitting transition for each payment event type (§0.3 C5, C20), the ahead-of-state rule (§0.3 C21), and the seam points S08 adds by change request (§0.3 E7, E9) | [docs/zerosum_ledger_mvp_plan.md#state-machines](zerosum_ledger_mvp_plan.md#state-machines), [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order) |
| D05-6 | Collection policy implementation: charge and refund creation, refundable-amount computation, blocked-on-capture release, handling of orders the policy cannot act on, poison-record quarantine (§0.3 C8) | [docs/zerosum_ledger_mvp_plan.md#policies](zerosum_ledger_mvp_plan.md#policies) |
| D05-7 | Payout run implementation: idempotency, eligibility parameters, freshness computation (timestamp basis per §0.3 C12) and threshold source, in-flight uniqueness, run record | [docs/zerosum_ledger_mvp_plan.md#policies](zerosum_ledger_mvp_plan.md#policies), [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) |
| D05-8 | Sweeper thresholds (including the old-`CREATED` resubmission sweep, §0.3 C24) and `UNKNOWN` resolution schedule configuration, `NEEDS_REVIEW` cut-off, pending-payout lookup polling | [docs/zerosum_ledger_mvp_plan.md#state-machines](zerosum_ledger_mvp_plan.md#state-machines), [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded) |
| D05-9 | ADR-0010: FakeBank quiet period, resubmission rule, residual duplicate-payout risk, and the constraint on FakeBank's maximum processing delay | [docs/zerosum_ledger_mvp_plan.md#state-machines](zerosum_ledger_mvp_plan.md#state-machines), [docs/zerosum_ledger_mvp_plan.md#step-05](zerosum_ledger_mvp_plan.md#step-05) |
| D05-10 | Provider contract test suite: structure, capability-skip mechanism, how a new adapter plugs in, ArchUnit provider-boundary rule | [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) (M7), [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) |
| D05-11 | Money-movement kill switches: flag names, default values, where they are checked, behavior when off | [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring) |
| D05-12 | Scenario catalog format, W1–W4 scenario files with expected final state, runner command and quiesce-lite definition | [docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app) |
| D05-13 | `openapi/instrument-service.yaml` for the endpoints implemented here, error codes and roles | [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis) |
| D05-14 | *(appended)* Provider adapter client configuration: connect and read timeouts, automatic-retry classification, transport-error → result mapping, per-provider minor-unit mapping | [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting), [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards) |

D05-14 is appended because adapter timeouts and retry classification are authoritative runtime values that S08's fault injection (F6, F7) depends on and that would otherwise have no owner.

### D.2 Inherited decisions

| Decision | Owner | How this step must use it |
|---|---|---|
| Pinned versions (Spring Boot, Testcontainers, ArchUnit, PostgreSQL image) | D00-1 ([docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs)) | Add no dependency outside the catalog. `RestClient` and core `RetryTemplate` need no new library ([docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack)); a new library requires a D00-1 change request. |
| Module layout | D00-2 | Place code in the planned service modules; package names `instrument.core` and `instrument.providers.<name>` follow M7(b). |
| Compose topology, profiles, memory limits | D00-3 | fake-providers stays on the internal network; the admin fault endpoints are disabled in `demo-public`; do not add containers. |
| Databases and roles | D00-4 | Migrations run as each service's owner role and runtime uses its application role (§0.3 O3); instrument-service uses only its own database, fake-providers only its own. No cross-database reads. |
| CI job split and test tags | D00-5, D00-10 | Tag unit, integration, contract and e2e tests per the recorded convention so they land in the right CI job. |
| Observability wiring and SP3 outcome | D00-6, D00-7 | Provider-call timers and attempt counters use the recorded export path (OTLP or the Prometheus fallback). |
| Metric name registry and required alert signals | D07-1 ([docs/step_07_observability_performance.md#decisions-and-outputs](step_07_observability_performance.md#decisions-and-outputs)) | Expose the oldest-`UNKNOWN`-attempt and oldest-`PENDING`-payout gauges required by §0.3 O9, using the names proposed in [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation) until D07-1 records the registry. |
| Environment and secret conventions | D00-8 | Admin token, reader/writer tokens, webhook secrets and kill-switch flags follow the `ZS_*` convention; secrets never appear in logs (log-redaction test). |
| ADR process | D00-9 | ADR-0010 follows ADR-0001's format and numbering. |
| `Money`, ISO table, fee rounding | D01-1, D01-2, D01-3 | All amounts are `Money`; FakeCard fees use `FeeCalculator`; adapters map ISO minor units explicitly (D05-14). |
| Chart of accounts and sign convention | D01-6 (ADR-0003) | Collection policy reads rider receivable deltas using the recorded sign convention; scenario expected balances use normal-side presentation from D02-7. |
| Currency allow-list | D01-7 | Attempts and fake providers reject currencies outside the allow-list. |
| Payment-event JSON Schema and versioning | D01-8 | Every outbox payload is validated against the schema in tests; event types and the event ID rule follow [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts) as selected in D01-8 (§0.3 C5); new fields require a D01-8 change request. |
| Golden payloads O1–O8 | D01-9 | W1–W3 expected balances are derived from (not copied from) the golden set; if D01-9 changes, scenario files are regenerated. |
| Seeded generators and seed reporting (ADR-0009) | D01-10 | Fault-knob randomness and generative tests print the seed using the same convention. |
| ArchUnit money rules | D01-11 | New code must pass them; the provider-boundary rule (D05-10) is added alongside. |
| Append-only enforcement pattern | D02-2 | Apply to transition history and provider-event records. |
| Ledger read API | D02-7 | Payout amount comes from the balances endpoint at run time; clearing-account checks in scenarios use it. |
| Money-order API and idempotency semantics | D03-2, D03-3 | Scenarios create COMMERCE orders through the API; payout-run idempotency follows the same replay/mismatch semantics unless D05-7 records a justified difference. |
| Auth module and principal mapping | D03-4 | This step wires `libs/auth` into instrument-service (writer/reader/admin checks, 401/403 on authenticated endpoints; §0.3 C9) and reuses it for fake-providers admin endpoints. |
| `libs/outbox` (ADR-0008) | D03-5 | Instrument outbox uses the library unchanged; one relay instance. If SP2 replaced the relay (D04-7 → D03-5), use whatever D03-5 now records. |
| Payment-event mapper and consumer (ADR-0006) | D03-6 | Instrument-service publishes facts only; it never creates money orders (unless S05-C01 triggers). |
| Outbox stats endpoint | D03-7 | One of the three freshness components for payout runs. |
| Topics, partition key, client config | D04-1, D04-2 | `instrument-policy` consumer group and payment-event producer keys come from the topic config; never hard-code topic names. |
| Listener design and error policy | D04-3, D04-4 | The collection policy consumer acks after commit, retries transient errors, and routes poison records per the recorded policy, quarantining them in the instruments database (§0.3 C8). |
| Freshness endpoint | D04-5 | Ledger component of the payout freshness check, measured from the Kafka record timestamp (§0.3 C12). |
| Pipeline e2e suite | D04-6 | S05-T13 runner extends it rather than creating a second harness. |

### D.3 Engineering requirements

**Package and module boundaries**

- `services/fake-providers`: packages for FakeCard, FakeBank, faults (knobs and seeded randomness), webhooks (event log and sender) and truth. It has no dependency on instrument-service code. It may depend on `libs/money` for fee arithmetic.
- `services/instrument-service`:
  - `instrument.core`: `PaymentInstrument`, capabilities, commands, results, provider events, and the provider registry. Must not import `instrument.providers.*` (ArchUnit, M7(b)).
  - `instrument.providers.fakecard`, `instrument.providers.fakebank`: adapters only. No class outside a provider's package imports it; wiring happens through Spring component scanning of `PaymentInstrument` beans keyed by provider ID (no factory class).
  - Attempts (schema access, transition service, state machines), policy (collection consumer), payouts (run endpoint), webhooks (receiver), recovery (sweepers, resolver). None of these contain provider-specific branches; they branch only on `Capabilities` ([docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components), design rule 4).
- Only `libs/outbox` sends to Kafka ([docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure)).

**Interfaces**

- Internal: `PaymentInstrument` (D05-1), transition service (single entry point for every status change, D05-5), provider registry.
- External to instrument-service: the endpoints in D05-13, the `instrument-policy` consumer (D04-1), payment events on the instrument outbox (D01-8).
- fake-providers: charge, refund, payout, lookup, admin fault, fault-log, redelivery-status and truth endpoints (D05-2; §0.3 E2, E3); outbound signed webhooks (D05-3).

**Data flows**

1. **Collection (W1, W2).** Money-order topic → collection policy consumer → attempt `CREATED` (unique per kind, source order and entity) → submit → provider → terminal transition with outbox payment event → S03 mapper → COLLECTION or REFUND order → ledger.
2. **Payout (W3).** Payout-run request → freshness, kill switch and eligibility checks → balances from D02-7 → payout attempts → FakeBank `Pending` → accepted event → webhook `settled`/`failed`/`returned` → events → orders.
3. **Uncertain outcome (W4).** Timeout or 5xx → `UNKNOWN` → retry with the same key (idempotent provider) or lookup and quiet period (non-idempotent) → terminal state. No payment event until the outcome is known ([docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows)).
4. **Webhooks.** Provider → receiver → signature and timestamp check → provider-event record and transition in one transaction → 2xx after commit.

**Transaction phasing (all provider calls).** Follow the pre-RPC / RPC / post-RPC split ([docs/zerosum_ledger_mvp_plan.md#industry-designs](zerosum_ledger_mvp_plan.md#industry-designs)): commit the move to `SUBMITTING` first, call the provider with no database transaction or row lock open, then record the result in a new transaction through the optimistic guard. A crash at any point leaves a durable state that the sweeper can resolve.

**Lifecycle behavior**

| Situation | Required behavior |
|---|---|
| Startup | Flyway migrations run; configuration is validated (unknown provider IDs, missing webhook secrets, invalid schedule values fail startup); active kill-switch values and any active fault knobs are logged once; sweepers start only after migrations. |
| Graceful shutdown | Listener stops polling, finishes the in-flight transaction and commits offsets within the shutdown timeout in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting); sweepers stop claiming work; in-flight provider calls either complete their post-RPC transaction or are left `SUBMITTING` for the sweeper. |
| Crash (F3) | Between provider call and state update the attempt stays `SUBMITTING`; the sweeper moves it to `UNKNOWN` and the resolver finishes it. No path requires manual repair. S08-T02 adds the F3 breakpoint hook at this boundary via change request on D05-5 (§0.3 E9). |
| fake-providers crash | Committed charges and payouts persist; undelivered webhooks persist and resume redelivery after restart; FakeBank lifecycle timers are derived from persisted timestamps, not in-memory timers. |
| Retry | Provider calls: automatic retry only when the provider has idempotency keys and the failure is transport-level or 5xx, bounded per D05-14; declines are never retried. Consumer: D04-4. Webhook sender: redelivery schedule per D05-2 until a 2xx. |
| Concurrency | Every transition uses the optimistic guard; zero updated rows means another actor won and the handler re-reads. One in-flight payout per driver and currency is a database constraint, never a check-then-insert. |

**Security and trust boundaries**

- TB2: webhook signature and timestamp are checked before parsing the body into domain objects; the raw body is what gets verified; comparison is constant-time; secret rotation accepts current and previous secrets (D05-3, D00-8).
- TB4: fault-knob and truth endpoints require the admin token and are disabled entirely in `demo-public` ([docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries)).
- TB1-style controls on instrument-service endpoints: bearer roles via `libs/auth` (D03-4) with 401/403 per [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis) (§0.3 C9), body cap and unknown-field rejection on money endpoints ([docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting)).
- Instrument tokens are fake magic strings, never card or bank numbers ([docs/zerosum_ledger_mvp_plan.md#non-goals](zerosum_ledger_mvp_plan.md#non-goals)). Tokens, HMAC signatures and `Authorization` headers never reach logs.

**Seams added later (§0.3 E7, E9).** S08 adds two seams through a change request on D05-5: A3 resubmits the same attempt with a fresh provider idempotency key, and F3 inserts a breakpoint hook between the provider call and the post-RPC state update. Keep resubmission and the post-RPC boundary each in one named code path so these seams stay small.

**Deployment constraints**

- One instance each of instrument-service and fake-providers (relay ordering per ADR-0008; sweepers assume a single claimant).
- fake-providers exposes no host port outside the internal network in any profile except where D00-3 records otherwise for local debugging.
- Memory limits come from D00-3; the scenario runner must work inside them on the CI runner described in [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers).

### D.4 Configuration ownership

| Value group | Planned location | Traced to |
|---|---|---|
| Fault-knob defaults, seed, FakeCard fee simulation parameters, simulated banking-day length, maximum processing delay, webhook redelivery schedule | `services/fake-providers/src/main/resources/application.yml` (fault-knob defaults also accepted via the admin API) | `# decision: D05-2 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs`; the processing-delay bound additionally cites D05-9 |
| Magic-token set | `services/fake-providers` source constants plus a test fixture listing | D05-2 |
| Webhook signing: header names, signed-string format, tolerance, secret variable names | `services/instrument-service/src/main/resources/application.yml`, `services/fake-providers/src/main/resources/application.yml`, `.env.example` placeholders | D05-3, D00-8 |
| Adapter base URLs, connect and read timeouts, retry classification, minor-unit mapping | `services/instrument-service/src/main/resources/application.yml` (per provider block) | D05-14 |
| Minimum payout, freshness threshold, payout-run defaults | `services/instrument-service/src/main/resources/application.yml` | D05-7 (threshold value must equal M10(c); cite [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)) |
| Sweeper threshold, `UNKNOWN` resolution schedule, `NEEDS_REVIEW` cut-off, pending-payout polling interval | `services/instrument-service/src/main/resources/application.yml` | D05-8 |
| FakeBank safe-resubmit quiet period | Adapter capability constant in `instrument.providers.fakebank`, value from configuration | D05-9 (ADR-0010) |
| Kill switches | Environment variables per D00-8, bound in `services/instrument-service/src/main/resources/application.yml` | D05-11 |
| Scenario files and runner settings | `scenarios/` (planned by [docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app); confirm against D00-2) | D05-12 |
| Instruments migrations | `services/instrument-service/src/main/resources/db/migration/` | D05-4 |
| fake-providers migrations | `services/fake-providers/src/main/resources/db/migration/` | D05-2 |
| Topic names and consumer group | Not owned here: read from D04-1 config | D04-1 |

**Permitted alternatives that downstream work must handle**

| Alternative | Decided in | What must stay possible |
|---|---|---|
| G2 architecture alternative: instrument-service writes orders through a shared validation library | S05-C01 (changes D03-6) | Payment events remain the internal fact record even if orders are written directly; S06 and S08 read D03-6 to learn which path is active. |
| SP2: Debezium replaces the polling relay | [docs/step_04_kafka_pipeline.md#s04-c01](step_04_kafka_pipeline.md#s04-c01) (D04-7 → D03-5) | Transition code only inserts outbox rows; it never assumes how rows are published or cleaned up. |
| SP3 fallback: Prometheus scraping | D00-7 | Provider metrics are plain Micrometer meters independent of export mode. |
| SP4 option (b): sharding hot entities, including provider accounts | [docs/step_07_observability_performance.md#s07-t06](step_07_observability_performance.md#s07-t06) (D07-6) | Scenario expected balances must be expressed so they can be checked against aggregated provider accounts; D05-12 records how. |
| FakeBank quiet period changed | D05-9 (ADR-0010) | Fake-provider processing-delay bound and S08 F8 profiles re-read ADR-0010. |
| Ablation seams A3 (fresh provider idempotency key on resubmission, §0.3 E7) and A5; F3 breakpoint hook (§0.3 E9) | [docs/step_08_fault_injection_ablation.md#s08-t03](step_08_fault_injection_ablation.md#s08-t03) and [docs/step_08_fault_injection_ablation.md#s08-t02](step_08_fault_injection_ablation.md#s08-t02) via change request on D05-5 | Keep `UNKNOWN` handling, provider-event dedupe and the post-RPC boundary each behind one clearly named code path so a single guarded flag or hook can target it. |
| Minimum cut: webhook reorder simulation dropped | [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut) | The reorder knob is isolated in S05-T03 so it can be omitted without touching duplicate delivery. |

<a id="phases-and-tasks"></a>
## E. Phases and tasks

| Phase | Tasks | Hours | Funding |
|---|---|---|---|
| Phase 1 — Fake providers | S05-T01, S05-T02, S05-T03 | 7 | Step work |
| Phase 2 — Interface and adapters | S05-T04, S05-T05, S05-T06 | 5 | Step work |
| Phase 3 — Attempts and state machines | S05-T07, S05-T08 | 4 | Step work |
| Phase 4 — Policies and payouts | S05-T09, S05-T10 | 4 | Step work |
| Phase 5 — Webhooks and recovery | S05-T11, S05-T12 | 4 | Step work |
| **Step total** | S05-T01 … S05-T12 | **24** | Master step hours |
| Phase 6 — G2 scenarios | S05-T13 | 2 | Pre-allocated contingency ([docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates)) |
| Conditional | S05-C01 | 0 (see task) | Unallocated contingency ([docs/README.md#effort](README.md#effort)) |

<a id="phase-1"></a>
### Phase 1 — Fake providers (7 h)

**Objective:** a deterministic, persistent simulated external world whose two providers differ in exactly the ways the master specifies, with every failure mode injectable by seed and every provider-side fact queryable as ground truth.

**Exit checkpoint:** fake-providers boots against its own database; FakeCard and FakeBank integration tests pass; a seeded fault profile produces the same provider-side outcomes on two runs with the same seed and the same request sequence; signed webhooks are delivered and redelivered; ground truth is queryable with the admin token and unavailable without it; D05-2 and D05-3 have draft entries in H.1; change detection re-run.

<a id="s05-t01"></a>
#### S05-T01 — FakeCard: charges, refunds, idempotency keys, lookup, magic tokens, persistence
- **Outcome:** FakeCard accepts charges and refunds synchronously, replays stored results for a repeated idempotency key, supports lookup by provider ID and by client reference, and persists every charge, refund and outgoing event in the `fakeproviders` database.
- **Estimate:** 3 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#instrument-interface](zerosum_ledger_mvp_plan.md#instrument-interface) (FakeCard column, magic tokens, fee simulation); [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis) (fake-provider APIs); [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards) (Stripe idempotency and refund semantics); D00-2, D00-4 (module, owner and application roles per §0.3 O3, Flyway baseline); D01-1 `Money`, D01-3 fee rounding, D01-7 currency allow-list; `services/fake-providers` module from D00-2.
- **Depends on:** none within S05 (requires S00 and S01 registers complete).
- **Instructions:**
  1. Create fake-providers Flyway migrations for FakeCard charges, refunds, an idempotency record keyed by (provider, idempotency key) that stores a request fingerprint and the full stored response, and a provider event log that the webhook sender (S05-T03) drains. Start from the fake-providers row in [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components); record the selected tables in D05-2.
  2. Implement the charge endpoint: validate amount (positive, allow-listed currency), instrument token and client reference; decide the outcome from the magic token (success, decline with a code, processing error); compute the simulated fee with `FeeCalculator` using the fee parameters in configuration; persist the charge and its outgoing event in one transaction; return the final result synchronously.
  3. Idempotency: a request with a known key and the same fingerprint returns the stored response, including stored error responses, following Stripe's "first result is saved" semantics. The same key with a different fingerprint returns a distinct error code (record it in D05-2). Concurrent requests with one key must produce one charge: use the unique constraint and re-read on conflict, never check-then-insert.
  4. Implement refunds: a refund references a charge; the sum of succeeded refunds can never exceed the captured amount (enforced inside the refund transaction with a row lock on the charge); an over-refund returns a failed outcome with a code, not a 500. Refunds honor idempotency keys the same way as charges ([docs/zerosum_ledger_mvp_plan.md#instrument-interface](zerosum_ledger_mvp_plan.md#instrument-interface); §0.3 C23).
  5. Implement lookup by provider charge ID and by client reference (the attempt ID the adapter sends). Lookup by client reference returns zero or more records; the adapter decides how to interpret multiples.
  6. Emit provider events for charge succeeded/declined and refund succeeded/failed into the event log with a stable provider event ID. Do not deliver them here.
  7. Record the selected magic-token set and decline/error codes in D05-2 and in a test fixture; tokens outside the set are treated as a valid card that succeeds (record this or the alternative choice).
- **Edge cases and failure behavior:**
  - Zero, negative or overflowing amounts, and currencies outside D01-7 are rejected with 4xx and create no charge.
  - A processing-error token produces a 5xx after nothing is committed, so the adapter sees a retryable failure with no provider-side effect.
  - Refund for an unknown or declined charge fails with a code; no refund row with a succeeded status is created.
  - Concurrent refunds on one charge cannot jointly exceed the captured amount.
  - Database unavailable: return 503 without committing anything.
- **Outputs:** planned `services/fake-providers` FakeCard package, migrations under `services/fake-providers/src/main/resources/db/migration/`, magic-token fixture, H.1 draft for D05-2.
- **Verification and definition of done:**
  - `FakeCardChargeIT` (Testcontainers PostgreSQL): success, decline and processing-error tokens produce the recorded outcomes and persisted rows.
  - `FakeCardIdempotencyIT`: same key and body replays byte-identical response; same key and different body returns the recorded mismatch code; a burst of concurrent requests with one key (count recorded in H.4) yields exactly one charge row.
  - `FakeCardRefundIT`: a repeated refund key replays the stored result; partial refunds up to the captured amount succeed; the next one fails; concurrent refunds never exceed the captured amount.
  - Lookup by ID and by client reference returns the persisted records.
  - Fee on the worked-example capture equals the fee produced by D01-3 for the configured schedule (assert against `FeeCalculator`, not a literal).
  - All tests tagged per D00-10 and green in the CI integration job.

<a id="s05-t02"></a>
#### S05-T02 — FakeBank: asynchronous payouts, no idempotency, lookup by client reference, settle/fail/return lifecycle
- **Outcome:** FakeBank accepts payouts with a pending response, deliberately accepts duplicate submissions, exposes lookup by client reference, and advances each payout through settle, fail and return outcomes on a simulated banking-day clock driven by persisted timestamps.
- **Estimate:** 2 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#instrument-interface](zerosum_ledger_mvp_plan.md#instrument-interface) (FakeBank column); [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards) (payout "paid then failed" and ACH return windows, as simulation inspiration only); [docs/zerosum_ledger_mvp_plan.md#state-machines](zerosum_ledger_mvp_plan.md#state-machines) (residual-risk note on processing delay); D00-4; D01-1; D01-7; event log table from S05-T01.
- **Depends on:** S05-T01
- **Instructions:**
  1. Add migrations for FakeBank payouts: provider payout ID, client reference (not unique), amount, destination token, status, return code, and timestamps for acceptance, scheduled processing, settlement and return.
  2. Implement the payout endpoint: validate, persist a pending payout, emit no terminal event yet, and return the pending response. Do not deduplicate by client reference; a second submission creates a second payout. This asymmetry is the point of FakeBank (M7, W4).
  3. Implement lookup by client reference returning all payouts with that reference and their current status.
  4. Implement a lifecycle scheduler that, on each tick, selects due payouts from the database and advances them: pending → settled or failed; settled → returned with a return code. Outcome selection comes from the magic token first, then from the seeded return-rate knob (S05-T03). Processing delay is drawn from the seeded generator and bounded by the maximum-processing-delay setting, whose value must respect ADR-0010 (D05-9).
  5. Simulated banking days: all lifecycle delays are expressed in simulated banking days converted by the configured banking-day length, so CI and scenarios can compress time. Record the conversion rule in D05-2.
  6. Emit settled, failed and returned events into the shared event log with stable provider event IDs. A return is emitted only after settlement.
- **Edge cases and failure behavior:**
  - Duplicate payouts with one client reference are both processed independently; ground truth shows both (this is how A3-style duplication becomes observable in S08).
  - Scheduler crash or restart: due payouts are recomputed from persisted timestamps; no payout is advanced twice (guard with a conditional status update).
  - A return token on a payout whose settlement has not happened yet waits for settlement first.
  - Clock jumps backward: the scheduler never reverts a status.
- **Outputs:** planned FakeBank package and migrations in `services/fake-providers`; lifecycle scheduler; D05-2 draft entries for lifecycle and banking-day rule.
- **Verification and definition of done:**
  - `FakeBankPayoutIT`: submission returns pending; two submissions with one client reference create two payouts; lookup returns both.
  - `FakeBankLifecycleIT` with a compressed banking day: ok token reaches settled; fail token reaches failed; return token reaches settled then returned with the token's return code; event log contains each event exactly once.
  - Restart test: stop the application context mid-lifecycle, restart, and assert every payout reaches its expected terminal status with no duplicate events.
  - A configuration test fails if the maximum processing delay is not below the quiet period recorded in D05-9 (the test reads both configuration sources; see S05-T12 for the ADR).

<a id="s05-t03"></a>
#### S05-T03 — Fault knobs, seeded randomness, signed webhook sender with redelivery, ground-truth endpoint
- **Outcome:** every failure mode in the master's knob list is configurable per provider at runtime through an admin-only endpoint, all random choices are reproducible from a seed, provider events are delivered as signed webhooks with duplicate, reorder and drop simulation plus scheduled redelivery, and tests can read provider-side ground truth, the injected-fault log and the webhook redelivery-queue status.
- **Estimate:** 2 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#instrument-interface](zerosum_ledger_mvp_plan.md#instrument-interface) (fault knobs); [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) (TB2, TB4); [docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix) (F6–F8 need these knobs); [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants) (I7 reads ground truth); [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (C23 extension point, E2 fault log, E3 redelivery status); D00-3 (`demo-public` profile), D00-8 (admin token, webhook secrets), D01-10 (seed reporting), D03-4 (admin auth); event log from S05-T01/T02.
- **Depends on:** S05-T01, S05-T02
- **Instructions:**
  1. Define the fault-knob schema per provider (D05-2): latency distribution parameters, HTTP 500 rate, reset-before-commit rate, timeout-after-commit rate, webhook duplicate/reorder/drop rates, return rate, banking-day length, maximum processing delay, and `seed`. Validate ranges (rates within [0, 1], delays non-negative). Reject unknown fields, but record the **extension point** (§0.3 C23) through which S06-T01 adds settlement-report discrepancy knobs additively ([docs/step_06_reconciliation_verifier.md#s06-t01](step_06_reconciliation_verifier.md#s06-t01)).
  2. Implement the admin fault endpoint: replace the knob set for a provider atomically, persist it, and log the active profile. Require the admin token (D03-4). Do not register the endpoint at all in `demo-public` (TB4).
  3. Seeded randomness: one `RandomGenerator` stream per provider and decision type, derived from the seed, so adding a knob does not shift other decisions. Log the seed at profile activation using the D01-10 convention. Record in D05-2 the reproducibility boundary: identical seed plus identical request order gives identical outcomes; concurrent request interleaving is not controlled.
  4. Apply knobs in request handling:
     - Latency: sleep a sampled delay before handling.
     - HTTP 500 and reset-before-commit: fail without committing (reset closes the connection).
     - Timeout-after-commit: commit, then withhold the response past the client's configured read timeout (D05-14) before closing. Record the withhold duration rule in D05-2 so it always exceeds the adapter read timeout.
  5. Webhook sender: a poller claims undelivered events from the event log, signs each delivery per D05-3 with a fresh timestamp, and POSTs it to the configured receiver URL. Apply the duplicate knob (extra delivery), the reorder knob (hold an event so a later event for the same object is delivered first), and the drop knob (skip an attempt). Undelivered or non-2xx deliveries follow the redelivery schedule in configuration until acknowledged. Persist delivery attempts so redelivery survives a restart. Keep the reorder logic in one isolated component: it is the minimum-cut line.
  6. Ground-truth endpoint: admin-only, returns per entity (and unfiltered for tests) every charge, refund and payout with status, amounts, client reference and provider IDs, plus counts of injected faults by type. Record the response shape in D05-2; S06-T04 consumes it. Also persist every injected fault, with the attributes listed in §0.3 E2, in a fault log exposed through the admin API, and expose the webhook redelivery-queue status (undelivered and scheduled deliveries) through the admin API (§0.3 E3). Record both shapes in D05-2.
  7. Write D05-3 draft: signed string composition (timestamp and raw body), header names, algorithm, and how multiple secrets are expressed during rotation.
- **Edge cases and failure behavior:**
  - Receiver down: deliveries back off along the schedule; events are never marked delivered without a 2xx.
  - Knob update during load: requests already in flight use the old profile; no partial profile is ever visible.
  - All rates zero: behavior is identical to no profile (a test asserts this).
  - Invalid knob payload: 400 with the offending field; the previous profile stays active.
  - Admin endpoints without a token: 401/403, never a silent no-op.
- **Outputs:** planned faults (including the fault log), webhooks (including redelivery-queue status) and truth packages in `services/fake-providers`; fault-knob schema documentation in the D05-2 artifact; webhook signing helper; H.1 drafts for D05-2 and D05-3.
- **Verification and definition of done:**
  - `FaultKnobDeterminismIT`: two runs with the same seed and request sequence produce identical outcome sequences; a different seed produces a different sequence.
  - `TimeoutAfterCommitIT`: with the rate set to 1, a client using the D05-14 read timeout times out, and ground truth shows the committed charge.
  - `WebhookSenderIT` with a test receiver: signature verifies with the D05-3 verifier; duplicate rate 1 yields two deliveries of one event ID; drop rate 1 on the first attempt yields redelivery; reorder produces a later event first for the same payout; restart mid-schedule still delivers.
  - `AdminSecurityIT`: fault and truth endpoints reject missing or non-admin tokens, and are absent under the `demo-public` profile.
  - `FaultLogIT`: every fault injected in a seeded run appears exactly once in the fault log, and the count matches the ground-truth fault counters (§0.3 E2).
  - `RedeliveryStatusIT`: the status reports pending redeliveries while the receiver is down and is empty after they drain (§0.3 E3).
  - Change detection re-run and recorded in I.2 (Phase 1 boundary).

<a id="phase-2"></a>
### Phase 2 — Interface and adapters (5 h)

**Objective:** a provider-agnostic core contract, two adapters that translate provider behavior faithfully into that contract, and a reusable contract suite that proves both adapters honor it.

**Exit checkpoint:** `instrument.core` compiles with no provider imports; both adapters pass the shared contract suite against the real fake-providers container; the ArchUnit provider-boundary rule is in the build and fails on a deliberate violation; D05-1, D05-10 and D05-14 have entries in H.1; change detection re-run.

<a id="s05-t04"></a>
#### S05-T04 — `PaymentInstrument` interface, capabilities and result types
- **Outcome:** the core contract that every stage codes against exists as pure Java types, with capability flags that let core code decide between idempotent retry and lookup-plus-quiet-period without naming a provider.
- **Estimate:** 1.5 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#instrument-interface](zerosum_ledger_mvp_plan.md#instrument-interface) (PROPOSED interface and records); [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components) (design rule 4); [docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval) (H4: a third adapter must be addable without core changes); D01-1 `Money`; D00-2 module layout.
- **Depends on:** none within S05 (can run in parallel with Phase 1).
- **Instructions:**
  1. Start from the PROPOSED interface and records in the master and create them in `instrument.core`. Record the actual signatures in D05-1; any deviation (renamed type, extra field) gets a rationale in H.1.
  2. Keep results sealed so `switch` over them is exhaustive: submit results (succeeded, pending, declined, unknown), lookup results (found, not found, unavailable) and a normalized provider event (provider event ID, provider reference, client reference, normalized status, amount, occurred-at, optional return or failure code).
  3. Define `ProviderStatus` as the normalized status vocabulary that core state machines consume; adapters map provider-native statuses onto it.
  4. Add `UnsupportedCapabilityException`. Core code must check `capabilities()` before calling an operation; calling an unsupported operation is a programming error, not a runtime branch.
  5. Declare the settlement-report operation and capability exactly as D05-1 selects (§0.3 C23), but implement no report types beyond what the method signature needs; report content is D06-1 ([docs/step_06_reconciliation_verifier.md#s06-t01](step_06_reconciliation_verifier.md#s06-t01)).
  6. Commands carry the attempt ID (used as the idempotency key and as the client reference), the instrument token, `Money`, and a deadline. Refund commands also carry the provider reference of the original charge.
  7. Provide a provider registry that collects `PaymentInstrument` beans into a map keyed by provider ID and fails startup on duplicate IDs. No factory class.
- **Edge cases and failure behavior:**
  - `Capabilities` with the quiet period set while idempotency keys are true is allowed but ignored; a missing quiet period while idempotency keys are false fails construction (validation in the record constructor).
  - Unknown provider ID lookups in the registry throw a typed exception that callers translate to a 4xx or a quarantined policy record, never an NPE.
- **Outputs:** planned `instrument.core` package in `services/instrument-service`; H.1 entry for D05-1.
- **Verification and definition of done:**
  - Unit tests for `Capabilities` validation and registry duplicate detection.
  - A compile-time exhaustiveness check: a test `switch` over each sealed result compiles without a default branch.
  - `instrument.core` has no Spring web or HTTP client imports (checked by the ArchUnit rules added in S05-T06).

<a id="s05-t05"></a>
#### S05-T05 — FakeCard and FakeBank adapters: timeouts, minor-unit mapping, error classification
- **Outcome:** two adapters translate core commands into fake-provider HTTP calls with explicit timeouts, map amounts through an adapter-owned minor-unit table, and classify every response and transport failure into exactly one result type.
- **Estimate:** 2 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) (timeouts and retry rules); [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards) (uncertain outcomes; providers can differ from ISO minor units); [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack) (`RestClient`, core `RetryTemplate`); D05-1 (S05-T04), D05-2 and D05-3 drafts (S05-T01–T03); D01-1, D01-2; D00-6 (provider call metrics wiring).
- **Depends on:** S05-T03, S05-T04
- **Instructions:**
  1. Create `instrument.providers.fakecard` and `instrument.providers.fakebank`, each with a `RestClient` built from its own configuration block (base URL, connect timeout, read timeout) per D05-14. Never rely on library default timeouts.
  2. FakeCard adapter: charge and refund send the attempt ID as idempotency key and client reference; lookup queries by client reference; capabilities declare idempotency keys and no quiet period.
  3. FakeBank adapter: disburse sends the attempt ID as client reference only; lookup by client reference; capabilities declare no idempotency keys and the quiet period from D05-9 configuration. Calling charge or refund throws `UnsupportedCapabilityException`.
  4. Classification table (record the final table in D05-14):
     - 2xx with a final status → succeeded or declined; FakeBank's pending response → pending.
     - 4xx with a decline or failure code → declined (definitive, never retried).
     - 4xx that indicates our own bug (validation, auth) → throw a typed non-retryable exception; the attempt handler moves the attempt to `UNKNOWN` only if the request may have been processed, otherwise surfaces the error for review. Record which codes fall where.
     - 5xx, read timeout, connection reset after the request was written → unknown, with the reason.
     - Connect timeout or connection refused before any byte was sent → unknown as well (conservative), unless D05-14 records a justified exception.
  5. Automatic retry: only when the adapter's capabilities declare idempotency keys and the classification is transport-level or 5xx, bounded by D05-14, with the same idempotency key on every try. A final failure still returns unknown.
  6. Minor-unit mapping: each adapter owns a mapping from ISO minor units (D01-2) to provider units. For the fake providers the mapping is identity, but it must be explicit and tested with zero-decimal and three-decimal currencies so a real adapter can differ ([docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards)).
  7. Webhook parsing: `parseWebhook` verifies the signature with the D05-3 verifier over the raw body and header timestamp before deserializing, then maps provider event types to normalized statuses. Invalid signature or stale timestamp throws the typed signature exception.
  8. Emit a provider-call timer tagged by provider, operation and outcome using the metric name proposed in [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation). Record it in H.2 as provisional; S07-T01 owns the registry ([docs/step_07_observability_performance.md#s07-t01](step_07_observability_performance.md#s07-t01)).
- **Edge cases and failure behavior:**
  - Lookup returning several payouts for one client reference (FakeBank duplicates): return found with a status that reflects "at least one exists"; D05-5 records how duplicates are surfaced for I7 (never silently pick one without logging).
  - Malformed JSON in a 2xx response: unknown (the provider may have committed).
  - Response amount or currency different from the command: unknown plus an error log; never treat as succeeded.
  - Deadline in the command already passed before sending: do not send; return a typed "not submitted" failure so the attempt can stay `CREATED`.
- **Outputs:** planned adapter packages, adapter configuration blocks in `services/instrument-service/src/main/resources/application.yml`, classification table in D05-14, provisional metric entry in H.2.
- **Verification and definition of done:**
  - Adapter unit tests (WireMock only for HTTP edge cases, as allowed by [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers)): each row of the classification table has a test; read timeout returns unknown; 5xx on FakeCard retries with the same key and stops at the D05-14 bound; 5xx on FakeBank never retries.
  - Minor-unit mapping tests for a zero-decimal and a three-decimal currency.
  - `parseWebhook` rejects a tampered body, a wrong secret and a stale timestamp; accepts current and previous secrets.
  - A log-redaction assertion (D00-8 test utility) shows no token or signature in adapter logs.

<a id="s05-t06"></a>
#### S05-T06 — Shared provider contract test suite and ArchUnit provider boundary
- **Outcome:** one contract suite runs unchanged against every adapter, skips unsupported capabilities explicitly and visibly, and an ArchUnit rule prevents provider code from leaking into the core, satisfying M7.
- **Estimate:** 1.5 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) (M7(a), M7(b)); [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) (contract layer against the real fake-providers container); [docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure) (module boundaries); [docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval) (FakeWallet integration relies on this suite); D00-3 (container image), D00-5 and D00-10 (contract test tag), D01-11 (existing ArchUnit rules); S05-T05 adapters.
- **Depends on:** S05-T05
- **Instructions:**
  1. Write an abstract JUnit contract class parameterized by: the adapter under test, a token set per outcome (success, decline, uncertain), and a hook to set fault knobs. Each adapter gets a concrete subclass of a few lines. Record this plug-in shape in D05-10 so S09-T05's FakeWallet can follow it ([docs/step_09_demo_docs_release.md#s09-t05](step_09_demo_docs_release.md#s09-t05)).
  2. Contract cases: charge success, charge decline, refund success and failure, disburse pending then final outcome via webhook, lookup found and not found, lookup after an uncertain commit (timeout-after-commit knob), webhook parse valid and invalid, unsupported operation throws, capabilities are internally consistent, idempotent replay for adapters that declare idempotency keys, duplicate acceptance for adapters that do not.
  3. Skip mechanism: each case declares the capability it needs; unsupported capabilities are skipped with JUnit assumptions whose message names the capability, so the report lists them as skipped, not passed. The settlement-report case is present and skipped with a message pointing at S06-T01 until that task implements it (§0.3 C23).
  4. Run the suite against the real fake-providers application (Testcontainers from the image built per D00-3, or the Spring Boot app started in the test JVM if D00-10 records that as the contract convention). Reset provider state and knobs between cases.
  5. Add ArchUnit rules next to D01-11's: (a) no class outside `instrument.providers.<name>` depends on that package; (b) `instrument.core` depends on no provider package and no HTTP client type; (c) policy, payout, webhook and recovery packages depend only on `instrument.core` for provider access.
- **Edge cases and failure behavior:**
  - Asynchronous cases wait with a bounded poll on ground truth, never a fixed sleep; the bound comes from the compressed banking-day setting.
  - A skipped case that the adapter claims to support is a failure (capability declared true but assumption triggered).
  - Contract tests must not share mutable provider state across adapters running in parallel.
- **Outputs:** planned contract test sources in `services/instrument-service/src/test/`, ArchUnit rules, D05-10 entry.
- **Verification and definition of done:**
  - Both adapter subclasses pass; the test report lists skipped cases with capability names (M7(a)).
  - A deliberate violation (a core class importing a FakeCard class, on a throwaway branch or in an ArchUnit self-test fixture) fails the build (M7(b)); evidence path in H.4.
  - Contract tests run in the CI job selected by D00-5.
  - Change detection re-run and recorded (Phase 2 boundary).

<a id="phase-3"></a>
### Phase 3 — Attempts and state machines (4 h)

**Objective:** durable payment attempts whose every status change goes through one guarded transition path that writes history and, where money moves, a payment event, all in one transaction.

**Exit checkpoint:** instruments migrations apply on a clean database and on the S00 baseline; the transition service passes its concurrency test; the full state × event table test is green for charge, refund and payout; attempt read and cancel endpoints exist in code and in `openapi/instrument-service.yaml`; D05-4 and D05-5 have entries in H.1; change detection re-run.

<a id="s05-t07"></a>
#### S05-T07 — Instruments schema, transition persistence with optimistic guard, and outbox
- **Outcome:** the instruments database holds attempts, append-only transition history, provider-event records, instrument tokens, payout runs and an outbox, and a single transition service applies status changes atomically with history and payment events.
- **Estimate:** 2 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas) (PROPOSED instruments DDL and the guarded transition statement); [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts) (payment event, deterministic event ID, partition key); [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order); [docs/zerosum_ledger_mvp_plan.md#migrations](zerosum_ledger_mvp_plan.md#migrations) (additive only); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (C5, C8, C20, C22); D00-4 role and Flyway baseline; D01-8 payment-event schema; D02-2 append-only pattern; D03-5 `libs/outbox`; D04-1 topic config.
- **Depends on:** S05-T04
- **Instructions:**
  1. Write Flyway migrations starting from the PROPOSED instruments DDL, limited to: attempts, transition history, provider-event records, instrument tokens (entity → provider and token), payout runs (unique idempotency key and request hash, per-run results), a quarantine table for poison records under a surrogate key (§0.3 C8; policy per D04-4), and the `libs/outbox` table. Reconciliation runs and breaks are **not** created here (S06). Record the selected schema in D05-4.
  2. Keep the constraints that carry correctness: the attempt uniqueness key for charges and refunds as revised in [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas) (§0.3 C22: it includes currency); the unique partial index for one in-flight payout per (driver entity, currency) (M10(a)); the due-work index for sweepers; positive amounts; the optimistic `version` column.
  3. Apply the D02-2 append-only pattern to transition history and provider-event records (triggers plus revoked grants for the instrument role).
  4. Implement the transition service as the only code that changes attempt status: in one transaction, run the guarded update (matching attempt ID, expected version and expected from-status), insert the next transition-history row with a cause, and, for every transition that D05-5 marks as emitting a payment event (terminal or not, §0.3 C20), insert an outbox row through `libs/outbox` keyed by the attempt's order group (per D04-1). If the guarded update touches zero rows, roll back and return a "lost race" result so the caller re-reads.
  5. Build payment-event payloads from attempt data only, with the event types and deterministic event ID rule in [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts) as selected in D01-8 (§0.3 C5); validate them against the D01-8 schema in tests.
  6. Draft D05-5's event-emission table from the v1.2 event types in [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts) and [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order) (§0.3 C5, C20). Each event type has exactly one emitting transition and occurs at most once per attempt. In particular:
     - `PAYOUT_ACCEPTED` is emitted on the non-terminal transition into `PENDING`.
     - A decline at submission (`SUBMITTING` → `FAILED`) emits `PAYOUT_REJECTED`, which creates no order; `PAYOUT_FAILED` is emitted only from `PENDING`, after acceptance.
     - `CHARGE_DECLINED` and `REFUND_FAILED` are emitted although they create no order.
- **Edge cases and failure behavior:**
  - Duplicate attempt creation (redelivered order) hits the unique constraint; the caller treats the conflict as "already created" and loads the existing attempt.
  - Outbox insert failure rolls back the whole transition; there is never a status change without its event.
  - A transition requested from a status that does not match the current row returns "lost race" or "illegal" (distinguished by re-reading), never a partial write.
  - Migration re-run on an existing database is a no-op (Flyway checksums).
  - A payout resubmitted after `UNKNOWN` → `CREATED` never emits an event type twice; the transition table forbids it.
- **Outputs:** planned migrations under `services/instrument-service/src/main/resources/db/migration/`, transition service, payment-event builder, H.1 entries for D05-4 and the D05-5 draft.
- **Verification and definition of done:**
  - `InstrumentsSchemaIT`: update, delete and truncate on transition history and provider-event records fail for the application role and the owner (D02-2 test pattern).
  - `OneInflightPayoutIT`: a second in-flight payout for the same driver and currency violates the index; one in a different currency, or after the first reaches a terminal state, succeeds (M10(a)).
  - `TransitionConcurrencyIT`: many threads race the same transition on one attempt; exactly one history row and at most one outbox row exist afterwards, and every loser gets "lost race".
  - `PaymentEventSchemaTest`: every emitted event type validates against D01-8, and event IDs follow the §0.3 C5 rule.
  - `PayoutEventEmissionTest`: a decline at submission emits only `PAYOUT_REJECTED`; a failure after acceptance emits `PAYOUT_ACCEPTED` then `PAYOUT_FAILED`; a resubmitted payout emits no event type twice.
  - `AttemptUniquenessIT`: one charge per currency for a rider in one order is allowed; a duplicate with the same key columns conflicts (§0.3 C22).

<a id="s05-t08"></a>
#### S05-T08 — State × event transition table tests, attempt read and cancel endpoints
- **Outcome:** the charge, refund and payout state machines are defined as data, every state × event pair is tested (legal transitions applied, illegal ones rejected and logged, non-advancing ones ignored as stale), and operators can read an attempt with its history or cancel a `CREATED` attempt.
- **Estimate:** 2 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#state-machines](zerosum_ledger_mvp_plan.md#state-machines) (diagrams and transition rules); [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) (M8(a)); [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis) (attempt read and cancel); [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) (cancellation only from `CREATED`); D03-4 `libs/auth` (§0.3 C9); D05-4 and D05-5 drafts from S05-T07.
- **Depends on:** S05-T07
- **Instructions:**
  1. Express each state machine as an immutable transition table (current state, event) → (next state, emitted event or none, outcome class: applied, ignored-stale, illegal). Events include submit, each submit result, sweeper timeout, retry result, lookup found/not-found/unavailable, quiet period elapsed, each webhook status, cancel and the review cut-off. Start from the master diagrams; record the final tables in D05-5.
  2. Define "non-advancing" precisely: an event whose target is the current state or an earlier state on the same path is ignored and logged as a stale event (for example a settled webhook after a return). An event with no row for the current state is illegal: rejected, logged with attempt ID, state and event, and counted.
  3. Encode the **ahead-of-state** rule from [docs/zerosum_ledger_mvp_plan.md#state-machines](zerosum_ledger_mvp_plan.md#state-machines) (§0.3 C21) as a distinct table outcome: the event is recorded, the attempt's true state is resolved by provider lookup, and the intermediate transitions are applied in order so every payment event (for example settled, then returned) is emitted exactly once. Such an event is never dropped. Record the implementation in D05-5.
  4. Wire the tables into the transition service so the service refuses anything the table does not allow.
  5. Implement the attempt read endpoint (attempt plus ordered transition history) and the cancel endpoint (allowed only from `CREATED`; otherwise the not-cancellable problem code), using the reader and admin roles from D03-4. These are the first authenticated instrument-service endpoints: wire `libs/auth` into the service here, returning 401 without a valid token and 403 for the wrong role (§0.3 C9).
  6. Create `openapi/instrument-service.yaml` with these two operations, RFC 9457 problem responses and role annotations (D05-13). Later tasks add their operations to the same file.
- **Edge cases and failure behavior:**
  - Cancel racing a submit: the optimistic guard lets exactly one win; the loser returns the conflict code with the current state.
  - A payout moved from `UNKNOWN` back to `CREATED` for resubmission can be cancelled only while it is `CREATED`.
  - Terminal states accept no events other than those the table marks stale (logged, no change).
  - Unknown attempt ID on read or cancel: 404.
- **Outputs:** planned state-machine table classes, `TransitionTableTest`, attempt endpoints, initial `openapi/instrument-service.yaml`, H.1 D05-5 and D05-13 entries.
- **Verification and definition of done:**
  - `TransitionTableTest` enumerates the Cartesian product of states and events for each machine (generated, not hand-listed) and asserts the table outcome for every pair, including that ahead-of-state pairs map to lookup resolution rather than rejection (§0.3 C21); the test fails if a new state or event is added without a table decision (M8(a)).
  - Illegal transitions produce a log line and a counter increment (asserted with a captured appender and meter registry).
  - `AttemptEndpointsIT`: read returns history in sequence order; cancel from `CREATED` succeeds and from `SUBMITTING` returns the conflict code; a missing token returns 401 and a wrong-role token 403 (§0.3 C9).
  - An OpenAPI validation test checks that implemented responses for these operations conform to the spec.
  - Change detection re-run and recorded (Phase 3 boundary).

<a id="phase-4"></a>
### Phase 4 — Policies and payouts (4 h)

**Objective:** money actually moves: COMMERCE orders turn into charge and refund attempts, payout runs turn eligible payable balances into payout attempts, and both paths can be frozen with kill switches.

**Exit checkpoint:** a COMMERCE order published to the money-order topic yields exactly one charge attempt that reaches a terminal state and emits its payment event; an adjustment yields a correctly sized refund, including the blocked-on-capture path; a payout run creates payouts only for eligible drivers and refuses when the pipeline is stale or payouts are disabled; D05-6, D05-7 and D05-11 have entries in H.1; change detection re-run.

<a id="s05-t09"></a>
#### S05-T09 — Collection policy consumer (charges and refunds, including blocked-on-capture)
- **Outcome:** the `instrument-policy` consumer turns each COMMERCE order's rider receivable change into at most one charge or refund attempt, submits it through the correct adapter using pre-RPC / RPC / post-RPC phasing, and releases blocked refunds when the group's capture resolves.
- **Estimate:** 2 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#policies](zerosum_ledger_mvp_plan.md#policies) (collection policy); [docs/zerosum_ledger_mvp_plan.md#industry-designs](zerosum_ledger_mvp_plan.md#industry-designs) (no network calls inside transactions); [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows) (W1); [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis) (instrument token registration); D01-6 chart of accounts and sign convention; D01-8 money-order schema; D03-4 auth; D04-1, D04-2, D04-3, D04-4 topic, client, listener and error policy; D05-1 registry, D05-5 tables, D05-4 schema.
- **Depends on:** S05-T05, S05-T08
- **Instructions:**
  1. Implement the instrument token registration endpoint (writer role): associates an entity with a provider and a fake token; re-registration replaces the token for future attempts only. Add it to D05-13, with 401/403 per §0.3 C9.
  2. Configure the `instrument-policy` consumer from D04-1 and D04-2 and reuse the D04-3 listener pattern: one transaction per record or batch, manual acknowledgment only after commit. Poison records (undecodable or schema-invalid) follow D04-4 and are quarantined in the instruments database under a surrogate key (§0.3 C8).
  3. Write the policy decision as a pure function from a money order (plus the group's current charge/refund state) to zero or more attempt intents. Ignore every order type except COMMERCE. Net the rider receivable delta per (rider entity, currency) using the D01-6 sign convention; each currency yields its own intent, matching the attempt uniqueness key (§0.3 C22):
     - Positive delta → one charge intent for that amount.
     - Negative delta → one refund intent for the smaller of the absolute delta and the group's refundable amount (captured minus refunded minus refunds in flight). If the group's charge is still in flight, create the refund in `CREATED` with the blocked-on-capture flag. If the group's charge was declined, create nothing.
     - Any unrefundable remainder creates nothing; it stays visible as rider credit in the ledger.
  4. In the consumer transaction, insert attempts (conflict on the uniqueness key means redelivery: load and continue) and commit. Only then submit: move `CREATED` → `SUBMITTING` through the transition service, call the adapter outside any transaction, and apply the submit result in a new transaction. Submission runs on a bounded executor so the listener thread is not blocked by provider latency; a crash after commit and before submission is picked up by the S05-T12 sweeper (attempts left in `CREATED` past a threshold recorded in D05-8 are resubmitted).
  5. When a charge in a group reaches a terminal state, release blocked refunds in that group in the same transaction: recompute the refundable amount, clear the flag, and submit; if the charge was declined, cancel the blocked refund with a recorded cause.
  6. Check the collections kill switch (D05-11) before any submission. When off, attempts are still created (so no order is lost) but stay `CREATED` and are logged; the S05-T12 old-`CREATED` sweep submits them once the switch is on (§0.3 C24).
  7. Record in D05-6 the policy for orders the policy cannot act on: rider without a registered token, unknown provider. Recommended: record a policy-error row or log with an alert-worthy counter and create no attempt, rather than blocking the partition; confirm against D04-4.
- **Edge cases and failure behavior:**
  - Same order delivered three times: one attempt, one provider call per submission path (asserted with ground truth).
  - Adjustment arrives before the original COMMERCE order's charge attempt exists (cross-group ordering is not guaranteed, but same-group ordering is per ADR-0007): the refundable amount is zero with nothing in flight; record in D05-6 whether this creates a blocked refund or nothing, and cover it with a test.
  - Provider latency above the adapter read timeout: the attempt goes to `UNKNOWN`; the consumer does not retry inline.
  - Database unavailable during the consumer transaction: transient path per D04-4 (retry, then pause); no acknowledgment.
- **Outputs:** planned policy package and consumer, token registration endpoint, OpenAPI additions, H.1 entries for D05-6 and D05-11 (collections flag).
- **Verification and definition of done:**
  - `CollectionPolicyTest` (pure): positive, negative, zero net delta; declined charge; refund capped at refundable; in-flight charge → blocked refund; non-COMMERCE ignored; multi-entry netting; rider entries in two currencies yield one intent per currency (§0.3 C22).
  - `CollectionPolicyIT` (Testcontainers Kafka + PostgreSQL + fake-providers): publish the golden O1 payload (D01-9) three times → one charge attempt reaching `SUCCEEDED` and one charge-succeeded outbox event; publish the O3 adjustment → one refund of the adjusted amount reaching `SUCCEEDED`; with FakeCard latency set above the read timeout during the charge, the refund is created blocked and is submitted after the charge resolves.
  - Kill switch off: attempts exist in `CREATED`, ground truth shows no provider calls; switch on: they complete.
  - Payment-event payloads validate against D01-8.
  - `PolicyQuarantineIT`: an undecodable record and a schema-invalid record are each quarantined in the instruments database and routed per D04-4, and the partition keeps moving (§0.3 C8). Redelivering the same record after a simulated crash leaves exactly one quarantine row (§0.3 C25).

<a id="s05-t10"></a>
#### S05-T10 — Payout run: freshness check, in-flight uniqueness, kill switches
- **Outcome:** an idempotent payout-run endpoint pays every eligible driver the payable balance read from the ledger at run time, never creates a second in-flight payout for a driver and currency, and refuses to run when the pipeline is stale or payouts are disabled (M10(a), M10(c)).
- **Estimate:** 2 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#policies](zerosum_ledger_mvp_plan.md#policies) (payout run eligibility, amount, known limitation); [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) (freshness composition, stale state); [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) (M10); [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) (ADR-0004 balance-dependent policy outside the ledger); [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded) (Kafka unavailable → payout runs refuse); D02-7 balances API ([docs/step_02_ledger_core.md#decisions-and-outputs](step_02_ledger_core.md#decisions-and-outputs)); D03-3 idempotency semantics; D03-7 outbox stats; D04-5 freshness endpoint; D05-4 schema; D05-5 payout table.
- **Depends on:** S05-T05, S05-T08
- **Instructions:**
  1. Implement the payout-run endpoint (writer role, idempotency key required). Store the key, a canonical request hash and the response in the payout-runs table; a replay returns the stored response, a key with a different body returns the mismatch code, following D03-3 semantics. Add the operation and its problem codes to D05-13, including 401/403 (§0.3 C9).
  2. Order of checks, each producing a recorded problem code: payouts kill switch (D05-11) → freshness → eligibility.
  3. Freshness: compute pipeline staleness as the sum of order-service outbox oldest age (D03-7), ledger oldest unapplied age (D04-5) and the instrument outbox oldest age (local, via D03-5), per [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting). Use the timestamp basis in §0.3 C12 and D04-5 so no stage is counted twice. Refuse with the stale-ledger code when it exceeds the M10(c) threshold configured under D05-7. If any component is unavailable, refuse the run with the same code (fail closed) and log which component failed.
  4. Candidate drivers: drivers with a registered bank token. For each, read the payable balance and `as_of_seq` from D02-7 at that moment. Eligible when the credit balance meets the configured minimum payout (D05-7) and there is no in-flight payout. Payout amount = that credit balance.
  5. Insert payout attempts in `CREATED` with the run ID. A unique partial index violation means another payout is in flight: record the driver as skipped with a reason, do not fail the run.
  6. Commit the run and attempts, then submit each attempt through the FakeBank adapter outside the transaction (same phasing as S05-T09). Pending → `PENDING` with the payout-accepted event (D05-5).
  7. Record per-driver outcomes (paid, skipped-ineligible, skipped-in-flight, skipped-no-token) in the run record; the response lists them.
  8. Document the known limitation (a downward adjustment still in the pipeline can produce driver debt after a payout) in H.5 as inherited from [docs/zerosum_ledger_mvp_plan.md#policies](zerosum_ledger_mvp_plan.md#policies); it is measured later as R1.
- **Edge cases and failure behavior:**
  - Two concurrent runs with different keys: the unique partial index guarantees at most one in-flight payout per driver and currency; the second run reports skipped-in-flight.
  - Ledger balance endpoint returns 404 for a driver with no entries: skipped-ineligible.
  - Balance changes between freshness check and read: accepted by design (ADR-0004); the `as_of_seq` is stored on the attempt metadata or run record for audit.
  - Kill switch flipped off during a run: attempts not yet submitted stay `CREATED` and are reported; they are not submitted until the switch is on.
  - Multi-currency drivers: one payout per currency with a positive credit balance.
- **Outputs:** planned payouts package, payout-run endpoint, freshness client for D03-7 and D04-5, OpenAPI additions, H.1 entries for D05-7 and D05-11 (payouts flag).
- **Verification and definition of done:**
  - `PayoutRunIT` (Testcontainers with stubbed D03-7/D04-5 responses where the real services are not in the test): eligible driver gets one payout of the ledger balance; driver below minimum and driver with an in-flight payout are skipped with reasons.
  - `PayoutRunIdempotencyIT`: replay returns the stored response and creates no new attempts; same key with a different body returns the mismatch code.
  - `PayoutFreshnessIT`: staleness above the threshold, or any freshness component unavailable, returns the stale-ledger problem code and creates no attempts (M10(c)); a unit test of the freshness sum with fixed component timestamps asserts the §0.3 C12 basis.
  - `ConcurrentPayoutRunIT`: two runs racing for one driver produce exactly one in-flight payout (M10(a)).
  - Payouts kill switch off: the run is refused with the recorded code; ground truth shows no FakeBank payouts.
  - Change detection re-run and recorded (Phase 4 boundary).

<a id="phase-5"></a>
### Phase 5 — Webhooks and recovery (4 h)

**Objective:** asynchronous and uncertain outcomes always converge: webhooks are authenticated, deduplicated and applied exactly once, and every attempt stuck in `SUBMITTING`, `CREATED` or `UNKNOWN` is resolved on schedule without ever double-submitting to a non-idempotent provider.

**Exit checkpoint:** M8(b), M8(c), M9(a) and the attempt-level part of M9(b) have passing evidence; ADR-0010 is committed; the OpenAPI file covers every endpoint implemented in S05-T08–T11 and its validation test is green; D05-3, D05-8 and D05-9 are filled in H.1; change detection re-run.

<a id="s05-t11"></a>
#### S05-T11 — Webhook receiver: HMAC, timestamp tolerance, dedupe, record-then-apply
- **Outcome:** provider webhooks are rejected unless correctly signed and fresh, each provider event is recorded exactly once, and the resulting transition is applied in the same transaction before the endpoint returns 2xx (M9(a)).
- **Estimate:** 2 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) (TB2); [docs/zerosum_ledger_mvp_plan.md#state-machines](zerosum_ledger_mvp_plan.md#state-machines) (record before apply, stale events); [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards) (duplicate and out-of-order delivery); [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) (handler time budget, body cap, idempotency); [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets) (rotation); D00-8 secret conventions and log redaction; D05-3 draft (S05-T03); D05-4 schema; D05-5 tables including the ahead-of-state rule (S05-T08); adapters' `parseWebhook` (S05-T05).
- **Depends on:** S05-T03, S05-T05, S05-T08
- **Instructions:**
  1. Implement the webhook endpoint per provider path. Read the raw body bytes (respecting the body cap) before any JSON binding; resolve the adapter from the registry by provider path; call `parseWebhook`. Invalid signature or a timestamp outside the TB2 tolerance returns 400 with the invalid-signature problem code and writes nothing. Unknown provider path returns 404.
  2. Finalize D05-3: signed string, header names, algorithm, tolerance (value source: TB2), constant-time comparison, and acceptance of current and previous secrets. Fake-providers' sender and this receiver both use the same verification component or a shared test vector, so format drift fails a test.
  3. In one transaction: insert the provider-event record keyed by (provider, provider event ID). On conflict, the event was already recorded: commit nothing new and return 200 (duplicate). Otherwise locate the attempt by client reference (attempt ID) or provider reference, then ask the transition table for the outcome: applied → transition service (history + payment event); ignored-stale → log the stale event and keep the record; ahead-of-state → keep the record and schedule an immediate lookup resolution for the attempt via the S05-T12 resolver, which applies the intermediate transitions in order (§0.3 C21; never dropped); illegal → log and count.
  4. Return 2xx only after commit. Keep the handler inside the time budget in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting): no provider calls inside the handler; lookups are scheduled, not performed inline.
  5. Events for unknown attempts are recorded with an unmatched marker and counted; they return 2xx so the provider stops redelivering, and they remain visible for S06 and the verifier.
  6. Add the webhook operation to D05-13 and complete the OpenAPI validation test for every operation implemented in this step.
- **Edge cases and failure behavior:**
  - Same event delivered concurrently twice: the primary-key conflict lets exactly one transaction apply; the other returns 200 with no effect.
  - Database unavailable: return 503 so the provider redelivers; nothing is acknowledged without a durable record.
  - Valid signature but a body that fails schema binding: record nothing, return 400, log with provider event ID if extractable (no secrets or full payload in logs).
  - Webhook arrives while a sweeper or resolver is transitioning the same attempt: the optimistic guard picks one; the loser re-reads and re-evaluates the table.
  - Webhook reorder simulation dropped under the minimum cut: duplicate handling and the ahead-of-state rule stay in place.
- **Outputs:** planned webhooks package, receiver endpoint, OpenAPI completion for D05-13, final D05-3 entry.
- **Verification and definition of done:**
  - `WebhookSignatureTest`: tampered body, wrong secret, missing header, timestamp older than the TB2 tolerance and timestamp too far in the future are all rejected with 400; previous secret accepted during rotation (M9(a)).
  - `WebhookDedupeIT`: one event delivered many times, sequentially and concurrently, yields one provider-event record, one transition and one outbox event.
  - `WebhookChaosIT` (fake-providers with duplicate and reorder rates from M9(b), plus returns): over a seeded batch of payouts, every attempt's final state equals ground truth, and the instrument outbox contains exactly one payment event per money-moving transition (attempt-level M9(b)). The money-order-level half of M9(b) (0 duplicate money orders) is asserted in S05-T13's W3 scenario through the S03 mapper.
  - Log-redaction assertion: no signature header or secret in captured logs.

<a id="s05-t12"></a>
#### S05-T12 — Sweepers, `UNKNOWN` resolver and quiet-period resubmission (ADR-0010)
- **Outcome:** stuck attempts are found and resolved on a configurable schedule, idempotent providers are retried with the same key, non-idempotent providers are resubmitted only after the quiet period and a not-found lookup, unresolved attempts escalate to `NEEDS_REVIEW`, and ADR-0010 records the quiet period and its residual risk.
- **Estimate:** 2 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#state-machines](zerosum_ledger_mvp_plan.md#state-machines) (resolution schedule, quiet-period rule, residual risk); [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows) (W4 sequence, crash points); [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) (sweeper threshold rule, retry rules); [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded) (pending-payout lookup polling); [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) (M8(b), M8(c)); [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring) (unknown-attempts alert signal); D00-9 ADR process; D05-1 capabilities; D05-5 tables; D05-14 read timeouts; S05-T02 processing-delay configuration test.
- **Depends on:** S05-T09, S05-T10, S05-T11
- **Instructions:**
  1. Sweeper (scheduled, single instance): select attempts in `SUBMITTING` whose last transition is older than the sweeper threshold (read timeout plus margin, rule from [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting)) and move them to `UNKNOWN` with the first check time set. Select attempts left in `CREATED` past the D05-8 threshold (crash before submission, or kill switch re-enabled; §0.3 C24) and submit them if the relevant kill switch is on and they are not blocked on capture. Use the due-work index; claim through the optimistic guard, not row locks held across provider calls.
  2. Resolver: select `UNKNOWN` attempts whose next check time has passed. For each:
     - Idempotent provider (per `Capabilities`): resubmit with the same attempt ID as key; apply the result; an unknown result schedules the next check.
     - Non-idempotent provider: call lookup by client reference. Found → map the normalized status through the table (for payouts, `PENDING` or beyond, stepping through intermediate transitions so each event is emitted once). Not found and the quiet period since the original submission has elapsed → transition to `CREATED` and resubmit. Not found before the quiet period → schedule the next check no earlier than quiet-period expiry. Unavailable → schedule the next check.
     - After the review cut-off → `NEEDS_REVIEW`.
  3. Schedule: next check times follow the D05-8 configured sequence (start from the master's schedule); store the next check time on the attempt so restarts resume correctly.
  4. Pending-payout polling: `PENDING` payouts older than the D05-8 polling threshold are looked up, so dropped webhooks still converge ([docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded)).
  5. Ahead-of-state resolution requests scheduled by S05-T11 are processed by the same resolver path.
  6. Expose the alert signals required by §0.3 O9, the oldest-`UNKNOWN`-attempt age and oldest-`PENDING`-payout age gauges, plus a `NEEDS_REVIEW` count and attempt state counters, using the names proposed in [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation). D07-1 owns the names, so record them as provisional in H.2 for S07-T01 and S07-T03.
  7. Write ADR-0010 (`docs/adr/0010-*.md` per D00-9): chosen quiet period and why, the two-condition resubmission rule, the residual risk that a provider processing a request after the quiet period can duplicate a payout, the requirement that FakeBank's maximum processing delay stays below the quiet period (enforced by the S05-T02 configuration test), and how S08 F8 and A3 relate (A3 resubmits the same attempt with a fresh provider idempotency key, §0.3 E7; the F3 breakpoint hook is added by change request on D05-5, §0.3 E9). Link it from D05-9.
- **Edge cases and failure behavior:**
  - Webhook and resolver race on one attempt: the optimistic guard lets one win; the other re-reads and finds a terminal or later state.
  - FakeBank lookup returns more than one payout for a client reference (a duplicate already happened): do not resubmit; transition to the found state, count a duplicate-detected metric and log for I7 investigation.
  - Provider down for longer than the review cut-off: attempts move to `NEEDS_REVIEW`, never to a guessed terminal state.
  - Service restart during a resubmission: the attempt is `SUBMITTING` again and follows the sweeper path; the quiet-period clock is measured from the most recent submission, recorded in D05-9.
  - Kill switch off: the resolver still performs lookups (read-only) but does not resubmit.
- **Outputs:** planned recovery package (sweeper, resolver), D05-8 configuration block, `docs/adr/0010-*.md`, provisional metric entries in H.2, H.1 entries for D05-8 and D05-9.
- **Verification and definition of done:**
  - `ResolverFakeCardIT`: with the timeout-after-commit knob at 1 for the first attempt only, the charge ends `SUCCEEDED` via idempotent retry and ground truth shows one charge.
  - `ResolverFakeBankIT`: (a) commit-then-timeout → lookup finds the payout → `PENDING`, no second payout; (b) reset-before-commit → lookup not found → no resubmission before the quiet period (asserted with a controllable clock) → resubmission after it → exactly one payout in ground truth.
  - `UnknownOutcomeScaleIT` (tagged for the CI job D00-5 designates for long integration tests): runs the M8(b) volume and timeout-after-commit rate against FakeCard; ground truth shows exactly one successful charge per attempt (M8(b)); run seed recorded in H.4.
  - `QuiesceNoStuckAttemptsIT`: after load stops and the resolver drains, no attempt remains in `SUBMITTING` or `UNKNOWN` beyond the M8(c) age, and none is in `NEEDS_REVIEW` (M8(c)).
  - Restart test: kill the instrument-service context with attempts in `SUBMITTING` and `UNKNOWN`; after restart all resolve with no duplicate provider effects.
  - `AlertSignalGaugesIT`: the oldest-`UNKNOWN` and oldest-`PENDING`-payout gauges report the age of a deliberately stuck attempt and drop after resolution (§0.3 O9).
  - ADR-0010 committed and linked from H.1 D05-9.
  - Change detection re-run and recorded (Phase 5 boundary).

<a id="phase-6"></a>
### Phase 6 — G2 scenarios (contingency-funded, 2 h)

**Objective:** turn W1–W4 into executable, repeatable scenario files with expected final state, and a runner that proves the G2 condition end to end.

**Exit checkpoint:** W1–W4 pass locally and in the CI e2e job selected by D00-5, asserting balances, provider truth, attempt states and verifier-lite checks I7, I8 and I10; D05-12 is filled in H.1; G2 is evaluated in H.6; change detection re-run before handoff.

<a id="s05-t13"></a>
#### S05-T13 — Scenario catalog format, W1–W4 scenario files with expected final state, minimal runner
- **Outcome:** a documented scenario file format, four scenario files for W1–W4 each carrying an expected final state, and a single-command runner that executes them against the stack and fails on any mismatch. Funded from pre-allocated contingency (master #constraint-updates).
- **Estimate:** 2 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app) (scenario catalog intent); [docs/zerosum_ledger_mvp_plan.md#workflows](zerosum_ledger_mvp_plan.md#workflows) (W1–W4); [docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example) and D01-9 golden payloads (expected balances derive from these); [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants) (I7, I8, I10 and the quiesce definition); [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) (G2); [docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates) (funding); D02-7 balances API; D03-2 money-order API; D03-6 mapper; D04-5 freshness; D04-6 pipeline e2e suite; D00-3 compose; D00-5 e2e job; D05-2 ground truth, D05-7 payout run, D05-11 kill switches.
- **Depends on:** S05-T09, S05-T10, S05-T11, S05-T12
- **Instructions:**
  1. Define the scenario format (D05-12): scenario ID, seed, fault-knob profile per provider, instrument registrations, an ordered list of steps (create money order via D03-2, start payout run, advance simulated time, wait for quiesce-lite), and an expected final state with three parts: ledger balances per account (normal-side presentation from D02-7), provider ground truth (counts and statuses per client reference), and attempt states. Leave room for settlement cycles and reconciliation-break expectations so S06-T05 can add W5–W6 without a format change ([docs/step_06_reconciliation_verifier.md#s06-t05](step_06_reconciliation_verifier.md#s06-t05)). Record how expectations are checked if provider accounts are sharded under SP4 option (b).
  2. Write W1 (trip completed, charged), W2 (fare adjusted after capture, partial refund), W3 (payout accepted, settled, then returned, with webhook duplicates and reordering enabled to exercise the money-order half of M9(b)) and W4 (FakeCard commit-then-timeout resolved by idempotent retry, and FakeBank uncertain submission resolved by lookup and quiet period). Derive expected balances from the D01-9 golden payloads and record the derivation in the file header; do not hand-type numbers that disagree with D01-9. Settlement orders are S06 scope, so W1–W4 expected states stop before settlement, and the card clearing account is expected to hold the captured-minus-refunded amount.
  3. Build the runner on the D04-6 e2e suite: a Gradle task or tagged JUnit e2e test that takes a scenario ID (or all), starts or attaches to the compose stack, applies knobs and registrations, executes steps, waits for quiesce-lite, then asserts. Record the exact command in D05-12 and make it callable by S08 orchestration and the S09 demo.
  4. Quiesce-lite (recorded in D05-12, aligned with the v1.2 quiesce definition in [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants), §0.3 E3): order and instrument outboxes empty, consumer lag zero (D04-5), no attempts in `CREATED`, `SUBMITTING` or `UNKNOWN`, and no pending webhook redeliveries according to the fake-providers redelivery-queue status (D05-2), bounded by a timeout that fails the scenario (never a pass).
  5. Verifier-lite assertions:
     - I7: each provider-side success in ground truth maps to exactly one attempt, and no attempt claims success without a provider record.
     - I8: each terminal money-moving attempt has exactly one money order, found through the D03-2 group listing by the event-derived idempotency key.
     - I10: no stuck or `NEEDS_REVIEW` attempts, per the M8(c) age.
     - Additionally assert zero duplicate money orders per event ID (M9(b)) and the returned-payout re-credit order (M10(b)).
  6. Write the scenario result (pass/fail per assertion, seed, git SHA) to a JSON file under the build directory; do not write to `docs/results/` (evidence publication is S08/S09).
- **Edge cases and failure behavior:**
  - Quiesce timeout: fail with a diagnostic dump (outbox counts, lag, non-terminal attempts), never pass on partial state.
  - Scenario rerun on a dirty stack: the runner namespaces entity IDs by run (for example a run suffix) or resets state per D00-3; record which in D05-12.
  - Upstream golden payload change (D01-9): the scenario files are marked Needs review and regenerated.
  - CI runtime: simulated banking days are compressed through the fault profile so the W3 return completes inside the CI e2e budget in [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd).
- **Outputs:** planned `scenarios/` directory with W1–W4 files and a format description, runner task or test class, CI e2e wiring, H.1 entry for D05-12.
- **Verification and definition of done:**
  - The runner command passes all four scenarios locally on a fresh stack; evidence (run JSON, seed, SHA) recorded in H.4.
  - The CI e2e job selected by D00-5 runs W1–W4 and is green on the commit that closes the step.
  - Negative check: corrupting one expected balance in a scratch copy of W1 makes the runner fail with a clear mismatch message.
  - G2 "proceed" conditions evaluated and recorded in H.6 (see section G).

<a id="conditional-work"></a>
### Conditional and deferred work

<a id="s05-c01"></a>
#### S05-C01 — G2 architecture alternative: instrument-service writes orders through a shared validation library
- **Outcome:** if the event-to-order mapping model (ADR-0006) causes ordering issues that cannot be resolved, instrument-service creates COLLECTION, REFUND and DISBURSEMENT orders directly through a shared validation library, with the rationale and downstream impact recorded.
- **Estimate:** 0 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) (G2 change-architecture condition); [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) (ADR-0006); [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components) (design rule 3); [docs/README.md#conflict-resolution](README.md#conflict-resolution); [docs/README.md#effort](README.md#effort); D03-6 mapper and consumer ([docs/step_03_order_service_outbox.md#decisions-and-outputs](step_03_order_service_outbox.md#decisions-and-outputs)); D03-1 and D03-3 (order store and idempotency); D01-5 validator; evidence from S05-T09, S05-T11 and S05-T13.
- **Depends on:** S05-T13 (evidence), plus a recorded trigger
- **Instructions:**
  1. **Trigger (evidence required):** an e2e scenario or integration test shows an ordering failure attributable to the mapping hop (for example orders for one group applied in an order that breaks policy decisions or invariants) that cannot be fixed within the current design after root-cause analysis. Record the failing seed, scenario and analysis in I.2.
  2. **Funding:** unallocated contingency ([docs/README.md#effort](README.md#effort)). **Conditional hours:** the master gives no estimate; estimate from the evidence before starting, record the estimate and the remaining contingency in the change request, and do not start if it cannot be funded.
  3. Raise a change request against D03-6 (owner S03) per [docs/README.md#conflict-resolution](README.md#conflict-resolution): new decision, rationale, impact on S03 mapper, S06 settlement emission and S08 ablations (A2 and A4 seams), and the tasks to mark Needs review.
  4. Implement only what the approved change request specifies: extract the validation rules into a shared library used by both services, write orders from instrument-service with the same idempotency key rule, keep payment events as the fact record, and keep the single-writer-per-table rule for each database.
  5. Re-run S05-T13 scenarios and the affected upstream tests.
- **Edge cases and failure behavior:**
  - Both paths active at once (mapper and direct writes) would double-book: the switch must be atomic per deployment and covered by a test that fails if both are enabled.
  - Ordering issues caused by a bug rather than the model do not trigger this task; they are fixed in place.
- **Outputs:** planned change request record, updated D03-6 register entry and ADR-0006 revision, shared validation library path recorded by the change request.
- **Verification and definition of done:** W1–W4 pass on the new path; the failing seed from the trigger now passes as a regression test; D03-6 and this register record the decision; affected downstream tasks are marked Needs review.

**Deferred improvements** (not implemented in this step; owners are the master's deferred and should-have lists):

- Authorization holds and pending/posted balances; chargebacks and disputes; dunning for declined charges; instant payouts; the Stripe test-mode adapter; synchronous in-session payments ([docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred)).
- S4 hot-entity sharding of provider accounts, conditional on SP4 ([docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have)); scenario expectations must stay checkable if it lands.
- A circuit breaker library for provider calls: add only if needed ([docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack)).
- Leader election for sweepers and relays when more than one instance is required ([docs/zerosum_ledger_mvp_plan.md#scaling-triggers](zerosum_ledger_mvp_plan.md#scaling-triggers)).
- Minimum-cut removal of the webhook reorder simulation, if G2's narrow-scope condition applies ([docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut)).

<a id="risks-and-recovery"></a>
## F. Risks and recovery

| Risk | Detection (signal or test) | Recovery / fallback |
|---|---|---|
| Largest step overruns the schedule (R11 in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register)) | Actual vs planned hours recorded at each phase boundary; the G2 narrow-scope condition in [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) | Apply the S05 minimum cut (drop the webhook reorder simulation, keep duplicate delivery) per [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut); fund fixes from unallocated contingency; never cut `UNKNOWN` handling or the contract suite. |
| Fake providers too idealized, so later evidence isn't credible (R5 in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register)) | H.4 lists every D05-2 knob with at least one test that exercises it; ground-truth fault counters are non-zero in knob tests | Add or sharpen the missing failure mode in S05-T03 before G2; record the gap in H.5 if it cannot be closed. |
| Timeout-after-commit knob withholds the response for less than the adapter read timeout, so `UNKNOWN` is never exercised | `TimeoutAfterCommitIT`; ground truth shows injected timeouts while no attempt entered `UNKNOWN` | Derive the withhold duration from the D05-14 read timeout, not a separate constant; add a configuration test that fails if it is shorter. |
| Duplicate FakeBank payout despite the quiet period (residual risk in ADR-0010) | Duplicate client references in ground truth; duplicate-detected counter in the resolver; I7 assertion in S05-T13 | Stop resubmission on multiple lookup hits; revise D05-9/ADR-0010 (longer quiet period or tighter processing-delay bound) via the owner's own register; never weaken I7. |
| Reordered webhooks arrive ahead of state and a return is lost (breaks M9(b), M10(b)) | `WebhookChaosIT`; W3 scenario expects the re-credit order | Apply the ahead-of-state rule (§0.3 C21, implemented in D05-5); if lookups cannot provide the needed status, raise it as a D05-2 change inside this step. |
| Implementation drifts from the v1.2 event types (§0.3 C5, C20), for example emitting `PAYOUT_FAILED` for a decline at submission or skipping the outbox row on the non-terminal acceptance | `PayoutEventEmissionTest`; W3 clearing-account expectations; mapper DLQ or quarantine records | Correct the implementation to the D05-5 table; if a new case needs a new event type, raise a change request against D01-8 and D03-6 ([docs/README.md#conflict-resolution](README.md#conflict-resolution)). |
| Races between webhook handler, resolver, sweeper and cancel corrupt attempt state (R2 in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register)) | `TransitionConcurrencyIT`; restart tests in S05-T12; illegal-transition counter | Keep the single transition service and optimistic guard; treat any intermittent failure on these tests as a product bug per [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling). |
| Provider calls accidentally made inside a database transaction, holding connections and locks during provider latency | Hikari pool saturation under high provider latency in `CollectionPolicyIT`; code review against the phasing rule in D.3 | Move the call to the post-commit executor path; add a test that runs with provider latency above the read timeout and asserts the pool is not exhausted. |
| Freshness components unavailable, so payout runs are permanently refused | Stale-ledger refusals with the failing component logged (S05-T10) | Fail-closed is intended; fix the upstream component (D03-7, D04-5) rather than relaxing the check. |
| G2 architecture risk: the ADR-0006 mapping hop causes unresolvable ordering issues | S05-T13 scenario failures analysed to the mapping hop | Trigger S05-C01 with evidence and a funded estimate; otherwise fix in place. |
| Seeded reproducibility is weaker than expected under concurrency | `FaultKnobDeterminismIT` passes only for serial request order | Document the reproducibility boundary in D05-2 and H.5; S08 records seeds plus schedules rather than claiming bit-exact replays. |
| Injected faults missing from the fault log, so S06 cannot attribute breaks for I12 (§0.3 E2) | `FaultLogIT` comparison with ground-truth fault counters | Fix the logging path before handoff; S06 must not infer injected faults from outcomes. |
| Scenarios asserted before webhook redeliveries drain, so a late event changes state afterwards (§0.3 E3) | Redelivery-queue status non-empty at assertion time; intermittent W3 failures | Quiesce-lite waits on the redelivery-queue status; a timeout fails the scenario, never passes it. |
| Scenario e2e runtime or flakiness exceeds the CI budget in [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd) | CI job duration and failure history on the e2e job | Compress simulated banking days in scenario profiles; run nightly per [docs/zerosum_ledger_mvp_plan.md#waiting-time](zerosum_ledger_mvp_plan.md#waiting-time); fix flaky money-path scenarios as bugs, never retry to green. |
| Upstream contract changes during the step (payment-event schema, topics, outbox library, auth module) | Change detection at phase boundaries ([docs/README.md#change-detection](README.md#change-detection)) | Mark affected tasks Needs review in I.1, revalidate only those, record the outcome in I.2. |
| Webhook signature bypass or secret leakage (TB2) | `WebhookSignatureTest`; log-redaction assertions in S05-T05 and S05-T11 | Fix before G2; rotate secrets per [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets). |
| Driver debt from a payout racing an adjustment (known limitation in [docs/zerosum_ledger_mvp_plan.md#policies](zerosum_ledger_mvp_plan.md#policies)) | Not detected here; reported later as the R1 metric in [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants) | Documented in H.5; measured in S08. Not a reason to move balance-dependent policy into the ledger (ADR-0004). |

<a id="acceptance-checklist"></a>
## G. Acceptance checklist

Thresholds and rates are those in the linked master sections; they are not restated here.

**Must-have acceptance ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have))**

- [ ] M7(a): both adapters pass the shared provider contract suite, and unsupported capabilities appear as named skips in the report (S05-T06).
- [ ] M7(b): the ArchUnit provider-boundary rule is in the build and a deliberate violation fails it (S05-T06).
- [ ] M8(a): the generated state × event table test covers every pair for charge, refund and payout; illegal transitions are rejected and logged (S05-T08).
- [ ] M8(b): the unknown-outcome scale test at the M8(b) volume and knob rate shows exactly one successful provider charge per attempt, with the seed recorded (S05-T12).
- [ ] M8(c): after quiesce, no attempt remains in `SUBMITTING` or `UNKNOWN` beyond the M8(c) age (S05-T12, S05-T13).
- [ ] M9(a): bad signatures and timestamps outside the M9(a)/TB2 tolerance are rejected with 400 (S05-T11).
- [ ] M9(b): at the M9(b) duplicate and reorder rates, attempt final states match provider truth (S05-T11) and there are zero duplicate money orders (S05-T13 W3).
- [ ] M10(a): the unique partial index enforces at most one in-flight payout per driver and currency, including under concurrent runs (S05-T07, S05-T10).
- [ ] M10(b): a returned payout re-credits the driver's payable through a new order (S05-T13 W3).
- [ ] M10(c): the payout run refuses with the stale-ledger code when freshness exceeds the M10(c) threshold or a freshness component is unavailable (S05-T10).

**Gate G2 ([docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates))**

- [ ] Proceed condition: W1–W4 e2e scenarios pass in the CI e2e job, with verifier-lite checks I7, I8 and I10 green ([docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants)), and M7–M10 above are all checked.
- [ ] Narrow-scope condition evaluated: cumulative project hours compared with the G2 threshold; the minimum-cut decision is recorded in H.1 and H.6.
- [ ] Change-architecture condition evaluated: S05-C01 recorded as triggered (with evidence) or not triggered.
- [ ] Stop/re-plan condition checked against actual hours and capacity, result recorded in H.6.

**Security, operations and contracts**

- [ ] TB2: webhook verification tests pass for tampering, wrong secret, stale timestamp and secret rotation ([docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries)).
- [ ] TB4: fault-knob and ground-truth endpoints require the admin token and are absent under `demo-public` (test evidence in H.4).
- [ ] Authenticated instrument-service endpoints return 401 without a valid token and 403 for the wrong role ([docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis), §0.3 C9).
- [ ] Log-redaction assertions show no tokens, `Authorization` headers or signatures in instrument-service and fake-providers logs.
- [ ] Money-movement kill switches freeze collections and payouts and release them when re-enabled, matching the runbook freeze in [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring).
- [ ] ADR-0010 is committed; the configuration test keeps FakeBank's maximum processing delay below the quiet period.
- [ ] Every fault knob in D05-2 is exercised by at least one named test (knob → test list in H.4), so F6, F7 and F8 can be injected in S08 ([docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix)).
- [ ] fake-providers exposes the injected-fault log (§0.3 E2) and the webhook redelivery-queue status (§0.3 E3) through its admin API, with tests.
- [ ] Poison records on the `instrument-policy` consumer are quarantined in the instruments database (§0.3 C8), at most once per Kafka position (§0.3 C25).
- [ ] The oldest-`UNKNOWN` and oldest-`PENDING`-payout alert-signal gauges are exposed (§0.3 O9).
- [ ] Every payment event emitted by instrument-service validates against D01-8 and follows the v1.2 event types and event ID rule, including rejection before acceptance vs failure after acceptance ([docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts), §0.3 C5, C20); the S03 rule that only `libs/outbox` sends to Kafka still passes.
- [ ] `openapi/instrument-service.yaml` matches every implemented endpoint (validation test green).
- [ ] No money-path test is retried until it passes ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)).

**Records**

- [ ] H.1 has a selected value, rationale, alternatives, status and date for D05-1 … D05-14.
- [ ] Every authoritative configuration value in D.4 carries a trace comment to its decision ID.
- [ ] H.2–H.5 list actual paths, artifacts with revisions, evidence and limitations; nothing marked passed without evidence.
- [ ] I.1 status is current for every task including S05-C01; I.2 change detection was re-run before handoff.

<a id="decisions-and-outputs"></a>
## H. Decisions and outputs register

### H.1 Decisions and rationale

| ID | Decision | Rationale | Alternatives considered | Status | Date |
|---|---|---|---|---|---|
| D05-1 | — | — | — | Pending | — |
| D05-2 | Fault-knob schema per provider (`latency_p50_ms`, `latency_p95_ms`, `http_500_rate`, `reset_before_commit_rate`, `timeout_after_commit_rate`, `webhook_duplicate_rate`, `webhook_reorder_rate`, `webhook_drop_rate`, `return_rate`, `simulated_banking_day_seconds`, `max_processing_delay_ms`, `seed`), replaced whole through `PUT /admin/faults/{provider}` and persisted in `fault_profiles`; one seeded `L64X128MixRandom` stream per provider and decision (`Decision` enum); injected faults in `fault_log` (§0.3 E2); redelivery-queue status and ground truth in `GET /admin/truth` (§0.3 E3) | Absent knobs take a no-fault default and unknown knobs are refused by name, so a typo cannot become a chaos run that injects nothing; one stream per decision means S06-T01 can add the `report_*` knobs (§0.3 C23) without shifting any existing outcome; the fault log is written by the same call that draws the decision, so a fault cannot be injected invisibly | Merging partial profiles (rejected: a knob would survive a replacement); a single RNG stream (rejected: adding a knob would invalidate every recorded run); in-memory-only profiles (rejected: a restart would silently stop the faults a run was measuring) | Accepted | 2026-09-17 |
| D05-3 | `ZS-Signature: t=<unix seconds>,v1=<hex>`, HMAC-SHA256 over `<t>.<raw body>`, 300 s tolerance (master §5.11); `ZS_WEBHOOK_SECRETS=current,previous`, sender signs with the first and a verifier accepts any; delivery state on `provider_events` (`delivery_attempts`, `next_attempt_at`, `reorder_held`), redelivery 1s/5s/30s/2min/10min with the last interval repeating, `delivered_at` set only after a 2xx. **Receiver (S05-T11):** `POST /v1/webhooks/{provider}`, signature-authenticated rather than bearer-authenticated, raw bytes verified before any binding, 400 `invalid_signature` writing nothing, 404 on an unknown provider path, 64 KB body cap. The sender is given the receiver's **base** URL (`ZS_WEBHOOK_RECEIVER_URL`) and appends the provider. The two sides keep separate verification code — instrument-service must not depend on the simulator — held together by a shared test vector asserted on both sides | The timestamp is inside the signed string, so a captured delivery cannot be replayed by rewriting the header; the raw bytes are signed because a re-serialised body would reject honest deliveries; the schedule lives in the row so a restart resumes it rather than stranding the event. The refusal says only "did not verify", because distinguishing "stale" from "wrong secret" tells a forger which half to fix. One base URL rather than a URL per provider means two variables cannot disagree | A separate `webhook_deliveries` table (rejected: one delivery per event, so the join bought nothing); giving up after the last interval (rejected: it would mark an outcome undeliverable when the receiver is merely down longer than ten minutes); sharing `WebhookSigner` between the two services (rejected: an adapter implements someone else's format, and sharing the type would let our own refactor change what we believe the provider signed — the shared vector catches drift without the coupling); one receiver URL with the provider read from the body (rejected: the path is what selects the adapter, and a body-driven choice would parse before it routed) | Accepted | 2026-09-17 |
| D05-4 | — | — | — | Pending | — |
| D05-5 | The charge, refund and payout machines are immutable data in `AttemptStateMachines`: per machine a progression (how far along each status is), the diagram's arrows and a claim alias (`DECLINED` → `FAILED` for refunds and payouts); per event the status it claims and whether it is a command or a provider observation. Outcomes are applied, ignored-stale, illegal and ahead-of-state; `AttemptTransitions` refuses any move the tables do not draw, logging and counting it. Ahead-of-state (§0.3 C21) returns the claimed status plus `resolutionPath`, the intermediate statuses in order, so a payout that returns while still `PENDING` emits `PAYOUT_SETTLED` then `PAYOUT_RETURNED` | The 891 (kind, state, event) decisions are derived from four explicit inputs that can each be read against the master diagrams; 891 hand-written rows would be 891 chances to mistype one and find out in production. There is deliberately no `UNKNOWN` → `FAILED` arrow on the payout machine, so a failure found by lookup must pass through `PENDING` and emit acceptance first — otherwise the mapper reverses an order that was never created | A literal (state, event) → outcome map per machine (rejected: unreviewable and drift-prone); applying an ahead-of-state event directly (rejected: skips the intermediate payment events); treating an unreachable cancel as ahead-of-state (rejected: a command has nothing to look up, and `not_cancellable` is the documented answer) | Accepted | 2026-09-17 |
| D05-6 | The `instrument-policy` consumer turns each COMMERCE order's rider receivable delta, netted per (rider, currency), into at most one attempt: positive → CHARGE of the delta; negative → REFUND of `min(\|delta\|, captured − refunded − refunds in flight)`, created `blocked_on_capture` when the group's charge is unresolved and released, sized and submitted when that charge reaches a terminal state, or cancelled if it refused. The decision is a pure function (`CollectionPolicy`); the phases are separate objects — create and commit (`CollectionPolicyService`), call the provider outside any transaction (`AttemptSubmitter`, bounded executor), apply the result and release blocked refunds in one new transaction (`AttemptOutcomes`). Idempotency is the `payment_attempts_one_per_order` key plus the `CREATED → SUBMITTING` optimistic guard. An adjustment for a group with no charge at all creates nothing; `NEEDS_REVIEW` counts as in flight. Orders the policy cannot act on (no registered token, unknown provider) are counted on `instrument_policy_errors_total` and create nothing; poison is quarantined in this service's own database and acknowledged | Two deliveries must charge a payer once, and only a database constraint can promise that — a check-then-insert lets both deliveries pass the check. The phase split is the "no network calls inside transactions" rule made structural: a provider call inside the creating transaction would let a rollback erase the record of money that has already moved. A blocked refund is sized at release rather than at creation because refundable is zero while the capture is unresolved, so capping early would silently turn a real adjustment into nothing. A group with no charge gets nothing rather than a blocked refund, because same-group ordering is guaranteed (ADR-0007), so the absence means the charge was never created — and a blocked refund would wait for a capture that is never coming, until the old-`CREATED` sweep submitted a refund with no charge to reverse | Submitting inside the consumer transaction (rejected: no network calls in transactions, and a slow provider would hold the partition); a check-then-insert for idempotency (rejected: duplicates under concurrency, which is the failure the key exists to prevent); capping a blocked refund at creation (rejected: refundable is zero then, so nothing would ever be refunded); treating `NEEDS_REVIEW` as a refusal (rejected: it would refund money the provider may well have taken); dead-lettering poison (deferred: instrument-service publishes payment events, so naming a Kafka producer here is what M4(b) forbids — order-service deferred its DLQ for the same reason, and the quarantine row holds the payload) | Accepted | 2026-09-17 |
| D05-7 | `POST /v1/payout-runs` (writer, `Idempotency-Key` required), one run per currency. The `payout_runs` row **is** the idempotency record (D03-3): request hash is SHA-256 over the canonical `v1\|currency=<CUR>` with `request_hash_version=1`, a matching key replays the stored run and a key stored against a different request is 422. Order of checks is kill switch → freshness → eligibility. **Freshness** is the sum of order-service's outbox oldest age (D03-7, HTTP), the ledger's oldest unapplied age (D04-5, HTTP) and this service's own outbox oldest age (D03-5, local `OutboxStatsQuery`), each on its §0.3 C12 basis, refused above `zs.payouts.freshness-threshold` (5 s = M10(c)); any component that cannot be read refuses with the same code. **Candidates** are drivers holding a token at a provider whose `Capabilities.disburse()` is true; the amount is the `payable` balance presented on its normal side, read from D02-7 at that moment, with `as_of_seq` recorded per driver. **M10(a)** is `ON CONFLICT DO NOTHING` against the `one_inflight_payout` partial index, never a query: the loser records `SKIPPED_IN_FLIGHT` and the run still succeeds. Phasing follows D05-6 — every HTTP read before the transaction, inserts only inside it, submission through `PayoutSubmitter` after it commits, outcome applied by the shared `AttemptOutcomes`. A refusal is stored as a `REFUSED` run with its `refusal_code` and answered 409. Per-driver outcomes live in `payout_runs.results` (V5) | Only the index can promise "one in-flight payout per driver and currency" under load; a check-then-insert lets two concurrent runs both pass the check and pay the driver twice, which on this path is money gone rather than a duplicate row. The stages are **summed, not maxed**, because they are sequential — an order still in order-service's outbox has not reached the ledger, whose own lag starts only once it has — so three individually fresh stages can be nine seconds of staleness. An unreadable component is treated as stale rather than healthy because the alternative is paying against balances nobody checked. Refusals are recorded so "why did nobody get paid at 09:00" is answered by a row rather than a rotated log line, and the results are stored because a skipped driver leaves no attempt to re-derive the reason from. Reusing `AttemptOutcomes` keeps one place in the service that turns a submit result into a status change | A check-then-insert for in-flight uniqueness (rejected: both runs pass it); taking the worst stage instead of the sum (rejected: sequential stages compose, and it would call a nine-second pipeline fresh); treating an unreadable freshness component as fresh (rejected: fail-open on the one path that sends money out); not recording refusals (rejected: leaves no evidence of why a run paid nobody); recomputing the per-driver results on replay (rejected: skips leave no attempt row, so the reasons would silently vanish); one run covering every currency (rejected: the in-flight rule is per (driver, currency) and a single amount per driver would be ambiguous); extending `AttemptSubmitter` with payout branches (rejected: a different kill switch and a different operation, and it would route the money-out path through the tested collection path) | Accepted | 2026-09-17 |
| D05-8 | A scheduled recovery package (`instrument.recovery`) with four passes on one tick: `SUBMITTING` older than the master §5.11 threshold (read timeout plus a configured 10 s margin, derived rather than configured twice) becomes `UNKNOWN` with its first check armed in the same transition; `CREATED` older than 30 s and not blocked on capture is submitted (§0.3 C24), dispatched by kind to `AttemptSubmitter` or the payout path; `UNKNOWN` whose `next_check_at` is due is resolved on the master §5.10 schedule (1 s, 5 s, 30 s, 2 min, 10 min, then every 30 min to a 24 h cut-off, then `NEEDS_REVIEW`); `PENDING` payouts older than 10 min are looked up (master §6.6). The schedule is a pure function of when the attempt entered `UNKNOWN`, read from the append-only `attempt_transitions` history, so no schema change was needed and a restart resumes exactly where it left off. Work is claimed through the optimistic guard and handed to the bounded policy executor; `next_check_at` is re-armed before the provider call. Config block `zs.sweeper` | A counter column would have to be incremented by every actor that looks at the attempt, and a resolver that crashed between the provider call and the increment would silently skip or repeat a delay with no way to tell afterwards; the entry time is already recorded exactly once. Measuring the next check from entry rather than from the last check also stops a busy sweeper from stretching the 24 h cut-off indefinitely, which is how an attempt would never reach review at all. Re-arming before the call is what stops one attempt having two resolutions in flight. `blocked_on_capture` refunds are excluded because such a refund is waiting for the thing that will size it, not stuck | A `checks_so_far` column on the attempt (rejected: needs a migration and is wrong after a crash); scheduling from the last check (rejected: the cut-off becomes unbounded under load); row locks held across the provider call (rejected: holds a transaction open for an HTTP timeout, which D05-6 already refused); four separate `@Scheduled` methods (rejected: they interleave on the one scheduler thread); doing the provider calls on the scheduler thread (rejected: one slow provider stops every sweep, including the one that would have noticed) | Accepted | 2026-09-17 |
| D05-9 | ADR-0010 extended in place by a dated amendment rather than a new ADR. Quiet period 60 s (master §5.10), configured at `zs.instruments.quiet-period` and reaching the resolver only as `Capabilities.safeResubmitQuietPeriod`. Resubmission requires BOTH conditions, folded into the single `QUIET_PERIOD_ELAPSED` event: at least the quiet period since the most recent submission, AND a lookup returning `NotFound`. The quiet-period clock is read from the append-only history, so a restart mid-resubmission measures from the submission actually outstanding. `Unavailable` never counts as `NotFound`. Residual risk: a bank processing a request after the quiet period can still duplicate a payout, bounded only by FakeBank's `max_processing_delay_ms` staying below the quiet period, which `FaultKnobs` enforces at the configuration boundary | Folding both conditions into one event is what keeps a timer from ever moving money on its own: the payout machine then has one backward arrow, reachable one way. Extending ADR-0010 rather than writing ADR-0011 avoids two documents that must agree about how long a bank is left alone, the failure mode being that someone changes one of them; the rule was already in ADR-0010's decision list, and only its implementation was missing | A new ADR taking the next free number (rejected: restates the rule, so two copies to keep true — recorded in `docs/scope-decisions.md`, which had anticipated the opposite); an event per condition (rejected: a later caller could take the timer half alone); measuring the quiet period from `updated_at` (rejected: it answers when anything last changed, not when we last sent money); shrinking the quiet period so tests run fast (rejected: it would exercise a configuration no deployment uses, so the tests move a controllable clock instead) | Accepted | 2026-09-17 |
| D05-10 | — | — | — | Pending | — |
| D05-11 | Money-movement kill switches live in their own configuration block, `zs.kill-switches.*`, bound from `ZS_*` environment variables and defaulting to enabled. S05-T09 adds `collections-enabled` (`ZS_COLLECTIONS_ENABLED`, default `true`), passed to the container in the instrument-service Compose block and listed in `.env.example` as a commented default rather than a generated secret. It is checked in `AttemptSubmitter` immediately **before** a submission and never before creation: with collections frozen the attempt is still created and waits in `CREATED`, counted on `instrument_policy_submissions_withheld_total{reason="collections_disabled"}`, until the switch is on and the S05-T12 old-`CREATED` sweep submits it (§0.3 C24). The value is read at startup. S05-T10 adds `payouts-enabled` (`ZS_PAYOUTS_ENABLED`, default `true`) to the same block, checked at the **start of a payout run** rather than before a submission: a frozen run is refused outright with `payouts_disabled` and creates no attempt at all. That is the opposite of the collections rule, deliberately — a money order cannot be replayed, so a frozen collection must keep its attempt, whereas a payout run can simply be run again once the switch is on, and leaving payouts staged in `CREATED` would mean a freeze that still had money queued to leave the building | Checking before submission rather than before creation is what makes a freeze reversible without losing orders: an order dropped while frozen is a rider who is never charged, and no sweep can recover what was never written down. A separate block rather than a flag under `zs.instruments` keeps the switches from binding into the adapter's configuration record, which no class outside `..instrument.adapter..` may name (ADR-0010). Defaulting to enabled because a stack that came up frozen would report healthy and move no money, which is the harder failure to notice. Read at startup because a flag that could change under a submission already on the wire would make "was it on when we called the provider?" unanswerable | Refusing the order outright while frozen (rejected: loses money orders, and nothing could replay them); a database-backed switch flippable at runtime (rejected for the MVP: it adds a read on every submission and a second source of truth, and the runbook freeze is a redeploy); one flag for all money movement (rejected: collections and payouts fail and are frozen independently, which is the whole point of having a switch) | Accepted | 2026-09-17 |
| D05-12 | — | — | — | Pending | — |
| D05-13 | `openapi/instrument-service.yaml` opens with `GET /v1/payment-attempts/{attempt_id}` (reader) and `POST /v1/payment-attempts/{attempt_id}/cancel` (admin), RFC 9457 problems with the codes `invalid_attempt_id`, `attempt_not_found`, `not_cancellable`, `unauthorized`, `forbidden` and `database_unavailable`, and 401/403 on both operations (§0.3 C9). S05-T11 adds `POST /v1/webhooks/{provider}` with `security: []` — it is authenticated by HMAC, not by a token nobody would give a provider — the codes `invalid_signature`, `invalid_webhook` and `payload_too_large`, and a `WebhookAck` whose `disposition` names what the transition table did (`applied`, `duplicate`, `ignored_stale`, `ahead_of_state`, `illegal`, `unmatched`), several of which are 200 deliberately. Responses are snake_case through one mapper setting and are validated against the file by `AttemptEndpointsIT`. S05-T10 adds `POST /v1/payout-runs` (writer, `Idempotency-Key` required) with the `PayoutRunRequest`, `PayoutRun` and `PayoutRunResult` schemas and the codes `ledger_stale` and `payouts_disabled`, both 409; they were added to the single existing `Problem` enum — which a merge had corrupted once before — and the file was re-parsed to confirm all 21 codes and all 7 paths survive, with `PayoutRunIT` validating real 201, 200 and problem responses against it. The instrument token is never returned | Role annotations and problem codes belong in the published contract, not only in code, and `additionalProperties: false` plus a real-response test is what keeps the two from drifting. Nullable fields are documented but not `required`, because whether a null is serialized or omitted is a mapper default rather than a contract | Returning the attempt's instrument token for convenience (rejected: D00-8 — it would reach every client log); a separate response schema per operation (rejected: cancel returns the same document, so one schema is one thing to keep true) | Accepted | 2026-09-17 |
| D05-14 | — | — | — | Pending | — |

### H.2 Implementation and configuration locations

| Item | Planned path | Actual path | Traced to |
|---|---|---|---|
| fake-providers FakeCard, FakeBank, faults (with fault log), webhooks (with redelivery-queue status), truth packages | `services/fake-providers/src/main/java/` | `services/fake-providers/src/main/java/dev/zerosum/fakeproviders/{card,bank,shared,faults,webhooks,admin}/` | D05-2, D05-3 |
| fake-providers migrations | `services/fake-providers/src/main/resources/db/migration/` | `V1__baseline.sql`, `V2__fake_providers.sql`, `V3__fault_knobs_and_webhooks.sql` | D05-2 |
| Fault-knob defaults, fee simulation parameters, banking-day length, processing-delay bound, redelivery schedule | `services/fake-providers/src/main/resources/application.yml` | `services/fake-providers/src/main/resources/application.yml` (`zs.fakebank`, `zs.faults`, `zs.webhooks`) | D05-2, D05-9 |
| Webhook signing configuration (both services) and secret placeholders | `services/fake-providers/src/main/resources/application.yml`, `services/instrument-service/src/main/resources/application.yml`, `.env.example` | `services/fake-providers/src/main/resources/application.yml` (`zs.webhooks`), `services/instrument-service/src/main/resources/application.yml` (`zs.webhooks.secrets`), `.env.example` (`ZS_WEBHOOK_SECRETS`, `ZS_WEBHOOK_RECEIVER_URL`), `docker-compose.yml` (both services: the secret on each side, the receiver URL on fake-providers) | D05-3, D00-8 |
| `instrument.core` (interface, capabilities, results, registry) | `services/instrument-service/src/main/java/` | — | D05-1 |
| FakeCard and FakeBank adapters | `services/instrument-service/src/main/java/` (`instrument.providers.*`) | — | D05-1, D05-14 |
| Adapter client configuration (timeouts, retry, minor-unit mapping) | `services/instrument-service/src/main/resources/application.yml` | — | D05-14 |
| Instruments migrations | `services/instrument-service/src/main/resources/db/migration/` | — | D05-4 |
| Transition service and state-machine tables | `services/instrument-service/src/main/java/` | `services/instrument-service/src/main/java/dev/zerosum/instrument/store/` (`AttemptTransitions`, `AttemptStateMachines`, `IllegalTransitions`, `PaymentEvents`, `AttemptQueries`) | D05-5 |
| Collection policy consumer and token registration | `services/instrument-service/src/main/java/` | `services/instrument-service/src/main/java/dev/zerosum/instrument/policy/` (`CollectionPolicy`, `CollectionPolicyService`, `AttemptSubmitter`, `AttemptOutcomes`, `AttemptStore`, `GroupLedger`, `InstrumentTokens`, `MoneyOrderPolicyListener`, `PolicyQuarantine`, `PolicyMetrics`, `PolicyConfiguration`, `PauseOnFailureErrorHandler`, `ListenerResumeProbe`, `KafkaTopicsConfig`) and `.../instrument/api/InstrumentTokenController.java`; consumer settings in `application.yml` (`spring.kafka`, `zs.policy`) | D05-6 |
| Payout run and freshness client; payout policy configuration | `services/instrument-service/src/main/java/`, `services/instrument-service/src/main/resources/application.yml` | `services/instrument-service/src/main/java/dev/zerosum/instrument/payouts/` (`PayoutRunService`, `PayoutSubmitter`, `PayoutClient`, `PipelineFreshness`) and `.../instrument/api/PayoutRunController.java`; settings in `application.yml` (`zs.payouts`, `zs.kill-switches.payouts-enabled`); per-driver outcomes added by `db/migration/V5__payout_run_results.sql` | D05-7 |
| Webhook receiver | `services/instrument-service/src/main/java/` | `services/instrument-service/src/main/java/dev/zerosum/instrument/webhooks/` (`WebhookReceiver`; `AheadOfStateResolver` was absorbed by S05-T12 into `recovery.AttemptResolver`, so webhook-driven and sweeper-driven resolution are one implementation rather than two walks of the same resolution path), `.../instrument/api/WebhookController.java` (the endpoint, beside the other controllers so one exception handler covers them all) and `.../instrument/adapter/ProviderWebhooks.java` (verification and normalization, inside the adapter boundary) | D05-3, D05-5 |
| Sweeper and resolver; schedule configuration | `services/instrument-service/src/main/java/`, `services/instrument-service/src/main/resources/application.yml` | `services/instrument-service/src/main/java/dev/zerosum/instrument/recovery/` (`AttemptSweeper`, `AttemptResolver`, `ResolutionSchedule`, `RecoveryQueries`, `PayoutResubmission`, `AlertSignals`, `SweeperProperties`, `RecoveryConfiguration`) and the `zs.sweeper` block in `application.yml`. No migration: `next_check_at` and the `attempts_due` index already existed from `V2` | D05-8, D05-9 |
| Kill switches | `services/instrument-service/src/main/resources/application.yml`, `.env.example` | `services/instrument-service/src/main/resources/application.yml` (`zs.kill-switches.collections-enabled`), the `ZS_COLLECTIONS_ENABLED` line in the instrument-service block of `docker-compose.yml`, and the S05 section of `.env.example`; checked in `.../instrument/policy/AttemptSubmitter.java`. The payouts switch is S05-T10 | D05-11, D00-8 |
| `libs/auth` wiring in instrument-service | `services/instrument-service/src/main/java/`, `services/instrument-service/src/main/resources/application.yml` | `services/instrument-service/src/main/java/dev/zerosum/instrument/api/` (`AuthConfiguration`, `InstrumentAuthorization`), `application.yml` (`zs.auth`), and the `ZS_*_TOKEN` variables in the instrument-service block of `docker-compose.yml` | D03-4 |
| Provider contract suite and ArchUnit provider-boundary rules | `services/instrument-service/src/test/java/` | — | D05-10 |
| Provisional provider and attempt metrics, including the §0.3 O9 alert-signal gauges (names owned by D07-1) | `services/instrument-service/src/main/java/` | `.../instrument/recovery/AlertSignals.java` (`unknown_attempts_oldest_seconds`, `pending_payouts_oldest_seconds`, `attempts_needs_review`, `attempts_state_total{kind,status}`) and `.../instrument/recovery/AttemptResolver.java` (`instrument_attempt_resolutions_total`, which absorbed `instrument_ahead_of_state_resolutions_total`); registered in `infra/otel/registry.yaml`, where the first four were previously listed as blocked on S05. `provider_call_seconds` remains blocked and belongs to the adapter path | D05-14, D05-8; D07-1 later |
| Scenario runner command | build script or e2e test class per D04-6 | — | D05-12 |

### H.3 Produced artifacts

| Artifact | Planned path | Actual path | Revision/hash |
|---|---|---|---|
| Instrument-service OpenAPI | `openapi/instrument-service.yaml` | `openapi/instrument-service.yaml` (attempt read and cancel from S05-T08; `POST /v1/instrument-tokens` and the `invalid_registration` and `unknown_provider` problem codes from S05-T09; `POST /v1/payout-runs` with `ledger_stale` and `payouts_disabled` from S05-T10; `POST /v1/webhooks/{provider}` from S05-T11) | 1.0.0 |
| ADR-0010 FakeBank quiet period | `docs/adr/0010-*.md` | `docs/adr/0010-payment-instrument-abstraction.md`, extended in place by the dated amendment "The quiet period, as implemented (S05-T12, D05-9)" rather than a duplicate ADR; deviation recorded in `docs/scope-decisions.md` | 1.1 (amended) |
| Scenario catalog format description | `scenarios/` (format file per D05-12) | — | — |
| W1–W4 scenario files | `scenarios/` | — | — |
| fake-providers container image | per D00-3 build convention | — | — |
| Magic-token fixture | `services/fake-providers/src/test/` | — | — |

### H.4 Validation results and evidence

| Check | Method | Result | Evidence path | Date |
|---|---|---|---|---|
| FakeCard charges, idempotency, refunds | `FakeCardChargeIT`, `FakeCardIdempotencyIT`, `FakeCardRefundIT` (S05-T01) | Not run | — | — |
| FakeBank payouts and lifecycle, restart | `FakeBankPayoutIT`, `FakeBankLifecycleIT` (S05-T02) | Not run | — | — |
| Processing delay below quiet period | Configuration test (S05-T02, ADR-0010) | Not run | — | — |
| Fault determinism, timeout-after-commit, webhook sender, admin security | `FaultKnobDeterminismIT` (6), `WebhookSenderIT` (5), `AdminSecurityIT` (8), `DemoPublicAdminIT` (2), `WebhookSignerTest` (6, unit) — the timeout-after-commit case is inside `FaultKnobDeterminismIT` | Pass: 37 integration tests, 0 failures, 0 skipped (the 16 from S05-T01/T02 included and still green); 6 unit tests, 0 failures | [docs/results/s05/providers.md](results/s05/providers.md#s05-t03) | 2026-09-17 |
| Fault log and redelivery-queue status | Covered by `AdminSecurityIT.faultLogMatchesTheCounters` and `WebhookSenderIT.dropIsRetriedUntilItLands` / `receiverDownDrainsAfterRecovery`, not by separate `FaultLogIT` / `RedeliveryStatusIT` classes | Pass (inside the counts above) | [docs/results/s05/providers.md](results/s05/providers.md#s05-t03) | 2026-09-17 |
| Knob coverage list (knob → test) | Review of D05-2 knob list against test sources | Not run | — | — |
| Adapter classification, retry bound, minor units, webhook parsing, redaction | Adapter unit tests (S05-T05) | Not run | — | — |
| M7(a) contract suite | Contract test report (S05-T06) | Not run | — | — |
| M7(b) provider boundary | ArchUnit rules plus deliberate violation (S05-T06) | Not run | — | — |
| Append-only, one in-flight payout, transition concurrency, event schema | `InstrumentsSchemaIT`, `OneInflightPayoutIT`, `TransitionConcurrencyIT`, `PaymentEventSchemaTest` (S05-T07) | Not run | — | — |
| Payout event emission and attempt uniqueness | `PayoutEventEmissionTest`, `AttemptUniquenessIT` (S05-T07) | Not run | — | — |
| M8(a) transition table | `TransitionTableTest` (S05-T08) | Pass — 46 tests, 0 failures: 33 generated cases covering the 891 (kind, state, event) pairs, plus the product and constant-coverage checks and 11 nested rule cases. A deliberately altered diagram arrow failed 3 of them | `docs/results/s05/transitions.md` | 2026-09-17 |
| Attempt endpoints, 401/403 and OpenAPI conformance | `AttemptEndpointsIT`, OpenAPI validation test (S05-T08, S05-T11) | Pass for the two S05-T08 operations — 9 tests, 0 failures (6 endpoint cases, 2 table-enforcement cases, 1 OpenAPI conformance case). The webhook operation is S05-T11 | `docs/results/s05/transitions.md` | 2026-09-17 |
| Collection policy and kill switch | `CollectionPolicyTest`, `CollectionPolicyIT` (S05-T09) | Pass — `CollectionPolicyTest` 15 unit cases (7 netting, 8 decision), `CollectionPolicyIT` 4 integration cases plus its 3 registration cases, `CollectionKillSwitchIT` 1 case; 0 failures across all. The golden O1 published three times yielded **one** CHARGE attempt reaching `SUCCEEDED`, **one** provider call (the stub's own request count, not our records) and **one** `CHARGE_SUCCEEDED` event read back off `payments.payment-events.v1`; the golden O3 adjustment yielded a REFUND of 300 reaching `SUCCEEDED`. A capture slower than the read timeout became `UNKNOWN` with its refund created blocked, released and submitted once the capture resolved. With collections frozen the attempt stayed in `CREATED` and the provider was not called at all | `docs/results/s05/policy.md` | 2026-09-17 |
| Policy quarantine | `PolicyQuarantineIT` (S05-T09) | Pass — 2 cases, 0 failures, written as the nested `Quarantine` group of `CollectionPolicyIT` rather than a separate class so they share its broker and database. Undecodable bytes and a schema-invalid order were each quarantined in the instruments database, and a good order published behind them **on the same partition** still produced its attempt; the same Kafka coordinates quarantined twice leave one row (§0.3 C25). Partial against D04-4: the records are quarantined and acknowledged but **not** dead-lettered | `docs/results/s05/policy.md` | 2026-09-17 |
| M10(a), M10(c) payout run | `PayoutRunIT` (with the nested `OneInFlightPayout`, `Freshness`, `Idempotency`, `Access`, `ReturnedPayout` and `OpenApiConformance` groups), `PayoutKillSwitchIT`, `PipelineFreshnessTest` (S05-T10) | Pass — 15 integration cases and 4 unit cases, 0 failures. The register names `PayoutRunIdempotencyIT`, `PayoutFreshnessIT` and `ConcurrentPayoutRunIT`; they are nested groups of `PayoutRunIT` here, so they share its database and stubs instead of starting three more containers. **M10(a)**: four runs started together against one driver produced exactly **one** attempt — one response said `PAID`, three said `SKIPPED_IN_FLIGHT`, and the table held one in-flight payout and one `PAYOUT_ACCEPTED`. **M10(c)**: a 9 s ledger age, and separately three individually-fresh stages summing to 9 s, were each refused 409 `ledger_stale` with no attempt created; the ledger reporting `status: error` and order-service answering 503 were refused the same way. Kill switch off: 409 `payouts_disabled`, no attempt, and the bank's own request count unchanged. **M10(b) evidence**: a payout driven to `RETURNED` emitted `PAYOUT_ACCEPTED`, `PAYOUT_SETTLED`, `PAYOUT_RETURNED` — each exactly once, in that order, each validating against D01-8 | [docs/results/s05/payouts.md](results/s05/payouts.md) | 2026-09-17 |
| M9(a) webhook verification | `WebhookSignatureTest` (S05-T11) | Not run | — | — |
| M9(b) attempt level | `WebhookDedupeIT`, `WebhookChaosIT` (S05-T11) | Not run | — | — |
| Uncertain-outcome resolution and restart | `ResolverFakeCardIT`, `ResolverFakeBankIT`, restart test (S05-T12) | Pass for the resolution cases — `ResolverFakeCardIT` 4 cases and `ResolverFakeBankIT` 4 plus its 2 nested gauge cases, 0 failures. FakeCard commit-then-timeout ends `SUCCEEDED` by idempotent retry with **one** charge in provider ground truth; FakeBank commit-then-timeout is found by lookup and becomes `PENDING` with **no second payout**; FakeBank reset-before-commit is **not** resubmitted until the real 60 s quiet period has passed on a controllable clock, then is resubmitted to **exactly one** payout; an `Unavailable` lookup never resubmits even with the clock ten quiet periods past. **The restart test was not written**, so that half is Not run | [docs/results/s05/resolver.md](results/s05/resolver.md) | 2026-09-17 |
| M8(b) scale | `UnknownOutcomeScaleIT` with recorded seed (S05-T12) | **Not run.** The class was not written and no 10,000-charge run at `timeout_after_commit_rate=0.2` was made, so M8(b) is unevaluated. `QuiesceNoStuckAttemptsIT` asserts the same per-attempt property (exactly one charge in ground truth per attempt) over 12 attempts with the fault applied deterministically by count; that is 1/800th of the volume and must not be read as M8(b) evidence | [docs/results/s05/resolver.md](results/s05/resolver.md) | 2026-09-17 |
| M8(c) no stuck attempts | `QuiesceNoStuckAttemptsIT` (S05-T12) | Pass — 1 case, 0 failures. Twelve charges, half of them committing and then going silent, are created and then left alone; nothing in the test drives the sweeper. After the scheduled sweeper drains them: **0** attempts in `SUBMITTING` or `UNKNOWN`, **0** in `NEEDS_REVIEW`, all 12 `SUCCEEDED`, and **exactly one** charge in provider ground truth and one payment event per attempt | [docs/results/s05/resolver.md](results/s05/resolver.md) | 2026-09-17 |
| Alert-signal gauges | `AlertSignalGaugesIT` (S05-T12) | Pass — 2 cases, 0 failures, written as the nested `the §0.3 O9 alert signals` group of `ResolverFakeBankIT` rather than a separate class, so they share its database and context. A deliberately stuck attempt raises `unknown_attempts_oldest_seconds` and the gauge drops once it resolves, at which point `pending_payouts_oldest_seconds` reports the payout now waiting on the bank; a second case drives an attempt past the cut-off and asserts `attempts_needs_review` rises. `NEEDS_REVIEW` is separately asserted to have no automatic exit | [docs/results/s05/resolver.md](results/s05/resolver.md) | 2026-09-17 |
| G2: W1–W4 with I7, I8, I10, M9(b) order level, M10(b) | Scenario runner locally and in CI e2e job (S05-T13) | Not run | — | — |
| Runner negative check | Corrupted expected state in a scratch copy (S05-T13) | Not run | — | — |

### H.5 Known limitations and blockers

| Item | Type (limitation/blocker) | Impact | Owner/next action |
|---|---|---|---|
| FakeBank quiet-period residual risk: a provider that processes a request after the quiet period can duplicate a payout | limitation | Resubmission safety depends on the processing-delay bound being respected | D05-9 / ADR-0010; listed in release honesty items ([docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist)) |
| Payout racing a downward adjustment can create driver debt | limitation | Driver balance may go negative after a payout | Inherited from [docs/zerosum_ledger_mvp_plan.md#policies](zerosum_ledger_mvp_plan.md#policies); measured by S08 as the R1 metric |
| Settlement reports and discrepancy knobs are not implemented in this step | limitation | Contract suite settlement case is a named skip; card clearing stays non-zero in W1–W4 expectations | S06-T01, S06-T02; ownership resolved in master v1.2 (§0.3 C23) |
| Seeded reproducibility holds for request order, not for concurrent interleavings | limitation | Chaos runs are reproducible by seed and schedule, not bit-exact | D05-2; S08 run records |
| Kafka without authentication, static bearer tokens | limitation | MVP-only security posture | Inherited from [docs/zerosum_ledger_mvp_plan.md#mvp-vs-production](zerosum_ledger_mvp_plan.md#mvp-vs-production) |
| v1.1 gap: payout event types and outbox rows for non-terminal emitting transitions | limitation | None remaining; implemented through D05-5 | Resolved in master v1.2 (§0.3 C5, C20) |
| v1.1 gap: webhooks ahead of the current state | limitation | None remaining; implemented through D05-5 | Resolved in master v1.2 (§0.3 C21) |
| v1.1 gap: attempt uniqueness key without currency | limitation | None remaining; implemented through D05-4 | Resolved in master v1.2 (§0.3 C22) |
| v1.1 gap: FakeCard refund idempotency and settlement-report ownership | limitation | None remaining; refunds idempotent (D05-2), reports built by S06-T01 | Resolved in master v1.2 (§0.3 C23) |
| v1.1 gap: no recovery path for attempts left in `CREATED` | limitation | None remaining; old-`CREATED` sweep in D05-8 | Resolved in master v1.2 (§0.3 C24) |
| Two payout submission paths: `payouts.PayoutSubmitter` and `recovery.PayoutResubmission` | limitation | Maintenance only, not a duplicate-payout risk: the once-only guarantee is the shared guarded `CREATED` to `SUBMITTING` transition, which is not duplicated. Both also read the same `payouts-enabled` switch | S05-T12 could not widen `..instrument.payouts` (out of its scope). Next action: make `PayoutSubmitter#submit` public, or extract one submission port, and delete `PayoutResubmission` |
| FakeBank duplicate-detected metric not emitted | limitation | A lookup returning several payouts is logged by the adapter but not counted, so the D05-5 duplicate counter and the I7 signal it feeds are missing. The safety half is implemented and tested: any lookup that finds something forbids resubmission outright | Needs either a `LookupResult.Found` contract change in `instrument.core` or a `MeterRegistry` in the adapters, both of which ripple past S05-T12. Next action: raise against D05-5 |
| M8(b) not evaluated at its stated volume | blocker (for M8(b) only) | M8(b) is 10,000 charges at `timeout_after_commit_rate=0.2`; `UnknownOutcomeScaleIT` was not written and no such run was made, so M8(b) is Not run rather than met. M8(c) is evaluated at a 12-attempt volume | S05-T12 follow-up; belongs to the long-running CI job D00-5 designates |

### H.6 Completion status

| Field | Value |
|---|---|
| Step status | Planned |
| Gate result | Not evaluated |
| Completed on | — |
| Completed by | — |
| Handoff accepted by next step | — |

<a id="execution-record"></a>
## I. Execution and change record

### I.1 Task execution record

| Task ID | Status | Output paths | Evidence | Blockers |
|---|---|---|---|---|
| S05-T01 | Planned | — | — | — |
| S05-T02 | Planned | — | — | — |
| S05-T03 | Done | `services/fake-providers/src/main/java/dev/zerosum/fakeproviders/{faults,webhooks,admin}/`, `V3__fault_knobs_and_webhooks.sql`, `application.yml`, `docker-compose.yml`, `.env.example` | 37 integration + 6 unit tests, 0 failures ([docs/results/s05/providers.md](results/s05/providers.md#s05-t03)) | Restart-mid-schedule redelivery is not covered by a test; the webhook receiver (S05-T11) does not exist, so delivery is unwired in Compose |
| S05-T04 | Planned | — | — | — |
| S05-T05 | Planned | — | — | — |
| S05-T06 | Planned | — | — | — |
| S05-T07 | Planned | — | — | — |
| S05-T08 | Complete | `services/instrument-service/src/main/java/dev/zerosum/instrument/store/` (`AttemptStateMachines`, `IllegalTransitions`, `AttemptQueries`), `.../instrument/api/`, `openapi/instrument-service.yaml`, `docker-compose.yml`, `services/instrument-service/src/main/resources/application.yml` | `docs/results/s05/transitions.md` | — |
| S05-T09 | Complete | `services/instrument-service/src/main/java/dev/zerosum/instrument/policy/` (collection policy, consumer, submitter, outcomes, quarantine, topics), `.../instrument/api/InstrumentTokenController.java`, `.../instrument/store/InstrumentOutboxConfiguration.java` (relay, metrics, cleanup), `services/instrument-service/src/main/resources/application.yml`, `services/instrument-service/build.gradle.kts`, `openapi/instrument-service.yaml`, `docker-compose.yml`, `.env.example` | `docs/results/s05/policy.md` | Sweeper and `UNKNOWN` resolver are S05-T12, so a capture left `UNKNOWN` is resolved by nothing in production yet; DLQ routing deferred per M4(b) |
| S05-T10 | Planned | — | — | — |
| S05-T11 | Planned | — | — | — |
| S05-T12 | Planned | — | — | — |
| S05-T13 | Planned | — | — | — |
| S05-C01 | Planned | — | — | — |

<a id="change-record"></a>
### I.2 Consumed sources and change record

| Source/artifact path | Revision or hash | Recorded at | Affected tasks | Review outcome |
|---|---|---|---|---|
| `docs/zerosum_ledger_mvp_plan.md` (v1.2) | — | 2026-09-15 (doc 1.1) | All | Revalidated: document updated to master v1.2 |
| Master §5.4, §5.5, §5.7, §5.10: event types, outbox rows, ahead-of-state rule, uniqueness key | — | 2026-09-15 (doc 1.1) | S05-T07, S05-T08, S05-T09, S05-T11 | Resolved in master v1.2 (§0.3 C5, C20, C21, C22) |
| Master §5.9: refund idempotency, settlement extension point | — | 2026-09-15 (doc 1.1) | S05-T01, S05-T03, S05-T04, S05-T06 | Resolved in master v1.2 (§0.3 C23) |
| Master §5.10: old-`CREATED` sweep | — | 2026-09-15 (doc 1.1) | S05-T09, S05-T12 | Resolved in master v1.2 (§0.3 C24) |
| Master §0.3: quarantine, `libs/auth`, freshness basis | — | 2026-09-15 (doc 1.1) | S05-T07, S05-T08, S05-T09, S05-T10 | Resolved in master v1.2 (§0.3 C8, C9, C12) |
| Master §0.3 C25 and §5.7 quarantine DDL | — | 2026-09-15 (doc 1.1) | S05-T07, S05-T09 | Resolved in master v1.2 (§0.3 C25): quarantine rows are unique per Kafka topic, partition and offset |
| Master §0.3: fault log, redelivery status, quiesce | — | 2026-09-15 (doc 1.1) | S05-T03, S05-T13 | Resolved in master v1.2 (§0.3 E2, E3) |
| Master §0.3: S08 seams on D05-5 | — | 2026-09-15 (doc 1.1) | S05-T12 | Resolved in master v1.2 (§0.3 E7, E9) |
| Master §0.3: owner/application roles, alert-signal gauges | — | 2026-09-15 (doc 1.1) | S05-T01, S05-T07, S05-T12 | Resolved in master v1.2 (§0.3 O3, O9) |
| `docs/README.md` | — | — | All | — |
| `docs/step_00_foundations.md` (register) | — | — | S05-T01–T03, S05-T06, S05-T13 | — |
| `docs/step_01_domain_contracts.md` (register) | — | — | S05-T01, S05-T05, S05-T07, S05-T09, S05-T13 | — |
| `docs/step_02_ledger_core.md` (register) | — | — | S05-T07, S05-T10, S05-T13 | — |
| `docs/step_03_order_service_outbox.md` (register) | — | — | S05-T07–T10, S05-T13, S05-C01 | — |
| `docs/step_04_kafka_pipeline.md` (register) | — | — | S05-T07, S05-T09, S05-T10, S05-T13 | — |
| `gradle/libs.versions.toml` | — | — | S05-T01, S05-T05, S05-T06 | — |
| `docker-compose.yml` | — | — | S05-T03, S05-T06, S05-T13 | — |
| `infra/postgres/init.sql` | — | — | S05-T01, S05-T07 | — |
| `.github/workflows/ci.yml` | — | — | S05-T06, S05-T13 | — |
| `.env.example` | — | — | S05-T03, S05-T11 | — |
| `libs/money/` (directory hash) | — | — | S05-T01, S05-T05 | — |
| `libs/contracts/` (directory hash: payment-event schema, golden payloads) | — | — | S05-T07, S05-T09, S05-T13 | — |
| `libs/outbox/` (directory hash) | — | — | S05-T07 | — |
| `libs/auth` (path from D03-4) | — | — | S05-T03, S05-T08–T10 | — |
| `openapi/ledger-service.yaml` | — | — | S05-T10, S05-T13 | — |
| `openapi/order-service.yaml` | — | — | S05-T10, S05-T13 | — |
| Topic definitions config (path from D04-1) | — | — | S05-T07, S05-T09 | — |
| Listener and error-handling components (paths from D04-3, D04-4) | — | — | S05-T09 | — |
| Pipeline e2e suite (path from D04-6) | — | — | S05-T13 | — |
| `docs/adr/` (ADR-0006, ADR-0007, ADR-0008 and the ADR template) | — | — | S05-T07, S05-T12, S05-C01 | — |

<a id="handoff"></a>
## J. Handoff

**What downstream steps consume**

| Consumer | Consumes | Resolve current definition from |
|---|---|---|
| S06 [docs/step_06_reconciliation_verifier.md#s06-t01](step_06_reconciliation_verifier.md#s06-t01) | FakeCard service, event log and fee data; the fault-knob schema extension point for discrepancy knobs and the settlement-report interface method and capability (§0.3 C23); the injected-fault log for I12 (§0.3 E2) | D05-1, D05-2 |
| S06 [docs/step_06_reconciliation_verifier.md#s06-t02](step_06_reconciliation_verifier.md#s06-t02) | Attempts (charges and refunds with provider references), transition service and instrument outbox for `SETTLEMENT_RECEIVED`; additive instruments migrations for reconciliation tables | D05-4, D05-5 |
| S06 [docs/step_06_reconciliation_verifier.md#s06-t04](step_06_reconciliation_verifier.md#s06-t04) | Ground-truth endpoint (I7), instruments schema (I8, I10, I11), redelivery-queue status for quiesce (§0.3 E3) | D05-2, D05-4 |
| S06 [docs/step_06_reconciliation_verifier.md#s06-t05](step_06_reconciliation_verifier.md#s06-t05) | Scenario catalog format and runner for W5–W6 | D05-12 |
| S07 [docs/step_07_observability_performance.md#s07-t01](step_07_observability_performance.md#s07-t01), [docs/step_07_observability_performance.md#s07-t02](step_07_observability_performance.md#s07-t02), [docs/step_07_observability_performance.md#s07-t03](step_07_observability_performance.md#s07-t03) | Provisional provider-call and attempt-state meters, and the oldest-`UNKNOWN` and oldest-`PENDING`-payout alert-signal gauges (§0.3 O9), for the registry, providers dashboard and alerts | H.2 provisional metric rows; D05-8, D05-14 |
| S07 [docs/step_07_observability_performance.md#s07-t05](step_07_observability_performance.md#s07-t05) | Provider latency knobs for the P3 collection-overhead runs | D05-2 |
| S08 [docs/step_08_fault_injection_ablation.md#s08-t01](step_08_fault_injection_ablation.md#s08-t01) | Magic tokens and instrument registration for simulator workloads | D05-2, D05-13 |
| S08 [docs/step_08_fault_injection_ablation.md#s08-t02](step_08_fault_injection_ablation.md#s08-t02) | Fault-knob profiles for F6–F8 and the storm F12; kill switches; the F3 breakpoint hook seam in instrument-service, added via change request on D05-5 (§0.3 E9) | D05-2, D05-5, D05-11, D05-14 |
| S08 [docs/step_08_fault_injection_ablation.md#s08-t03](step_08_fault_injection_ablation.md#s08-t03) | Seams for A3 (resubmits the same attempt with a fresh provider idempotency key, §0.3 E7) and A5 (provider-event dedupe), added via change request on D05-5 | D05-5, D05-8, D05-3 |
| S08 [docs/step_08_fault_injection_ablation.md#s08-t04](step_08_fault_injection_ablation.md#s08-t04) | Scenario runner, quiesce-lite definition and redelivery-queue status (§0.3 E3) | D05-12, D05-2 |
| S09 [docs/step_09_demo_docs_release.md#s09-t01](step_09_demo_docs_release.md#s09-t01), [docs/step_09_demo_docs_release.md#s09-t06](step_09_demo_docs_release.md#s09-t06) | Scenario runner for the W1 quickstart and the demo | D05-12 |
| S09 [docs/step_09_demo_docs_release.md#s09-t02](step_09_demo_docs_release.md#s09-t02) | M7–M10 evidence for the traceability table; ADR-0010 | H.4; D05-9 |
| S09 [docs/step_09_demo_docs_release.md#s09-t05](step_09_demo_docs_release.md#s09-t05) | `PaymentInstrument`, contract suite plug-in shape and instrument OpenAPI for the FakeWallet integration test | D05-1, D05-10, D05-13 |

**Where to resolve definitions.** Consumers read this step's register ([docs/step_05_instruments_fake_providers.md#decisions-and-outputs](step_05_instruments_fake_providers.md#decisions-and-outputs)) for the decision ID, then the artifact path in H.2/H.3, and only then the master proposal for context. Nothing in this document's task text is a contract.

**Handoff conditions**

1. Gate G2 is evaluated and recorded in H.6 with evidence links, or the gate's narrow-scope or change-architecture outcome is recorded with its follow-up.
2. H.1 has selected values for D05-1 … D05-14; H.2 and H.3 have actual paths and revisions; H.4 has results and evidence for every check listed.
3. I.1 statuses are current; S05-C01 is recorded as triggered or not triggered with the evidence reviewed.
4. Change detection was re-run immediately before handoff and I.2 is current.
5. Any change requests raised against upstream owners (D01-8, D03-5, D03-6, D04-1, D04-4) are recorded with their impact assessments.
