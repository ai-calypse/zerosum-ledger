# Step 03 — Order service and outbox

> **Pack:** ZeroSum Ledger implementation docs · **Master:** [docs/zerosum_ledger_mvp_plan.md#step-03](zerosum_ledger_mvp_plan.md#step-03) (v1.2)
> **Doc version:** 1.1 · **Date:** 2026-09-15 · **Step status:** Planned (no implementation exists)
> **Planned effort:** 22 h (master schedule; see [docs/zerosum_ledger_mvp_plan.md#schedule-overview](zerosum_ledger_mvp_plan.md#schedule-overview)) · **Gate:** —
> **Shared procedures:** [docs/README.md#change-detection](README.md#change-detection) · [docs/README.md#source-of-truth](README.md#source-of-truth) · [docs/README.md#status-legend](README.md#status-legend)

<a id="purpose-and-outcome"></a>
## A. Purpose and outcome

**The problem this step solves.** After S02 the ledger can apply money orders, but nothing trustworthy creates them. Four things are missing:

- A single entry point that validates money orders before they are written. Uber's "pre-commit validations" and design rule 3 in [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components) require it.
- An idempotent API. Without one, a caller's retry after a timeout creates a second order: the double-charge failure described in [docs/zerosum_ledger_mvp_plan.md#problem](zerosum_ledger_mvp_plan.md#problem).
- A store that can't be edited, so history can't be rewritten.
- A reliable way to get committed orders onto Kafka. A naive "commit, then send" loses orders when the process crashes between the two steps. That is exactly the failure ablation A2 predicts in [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation).

Later stages also publish *facts* (payment events). Something has to turn those facts into money orders at the same validation point (ADR-0006).

**Concrete deliverable** (all planned):

1. An immutable orders database with idempotency uniqueness, a stored request hash, a deferred zero-sum constraint trigger and append-only enforcement that reuses the S02 mechanism.
2. The `services/order-service` REST API: create, fetch and list money orders. It is protected by a shared bearer-token module, validated with the S01 libraries, and returns RFC 9457 problem details with stable codes.
3. `libs/outbox`: a transactional outbox writer and a single-instance polling relay with cleanup and metrics. It is reusable by instrument-service in S05.
4. The outbox stats endpoint that later freshness checks depend on.
5. A pure payment-event → money-order mapper covering every master mapping row, plus the order-mapper consumer with order-level idempotency.
6. `openapi/order-service.yaml`, ADR-0006 and ADR-0008.

**Contribution to the MVP.**

- Delivers must-haves M2 (immutable store), M3 (idempotency keys) and M4 (transactional outbox) from [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have).
- Makes order-service the only writer of money orders, so invariant I1 is enforced where orders are born.
- Uses `event_id` as the idempotency key for mapper-created orders. That convention is what makes I8 checkable ([docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants)).

**In scope**

- Orders schema and migrations, zero-sum and append-only enforcement, the idempotent store.
- The money-order API, validation, token authentication (shared module), problem details, OpenAPI.
- `libs/outbox` (writer, relay, single-instance guard, cleanup, metrics, the publish-path ArchUnit rule) and its use in order-service.
- The outbox stats endpoint.
- The payment-event mapper and its consumer, including order-service's own quarantine table (§0.3 C8, C25). The poison-message policy itself is S04's (D04-4).
- ADR-0006 and ADR-0008.

**Explicitly excluded** (owner in parentheses)

| Excluded work | Owner |
|---|---|
| Topic provisioning, final Kafka client configuration, ledger listener, DLQ naming and pause/alert policy, freshness endpoint, pipeline e2e, relay lag measurement | S04 ([docs/step_04_kafka_pipeline.md#phases-and-tasks](step_04_kafka_pipeline.md#phases-and-tasks)) |
| Debezium CDC replacing the relay | S04-C01; master [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred) |
| Payment-event emission, instrument-service, webhooks, payout-run freshness sum | S05 ([docs/step_05_instruments_fake_providers.md#phases-and-tasks](step_05_instruments_fake_providers.md#phases-and-tasks)) |
| Source of `SETTLEMENT_RECEIVED` events (S03 only maps them) | S06-T02 |
| Custom spans, metric name registry, dashboards, alert rules, P1 measurement | S07 ([docs/step_07_observability_performance.md#phases-and-tasks](step_07_observability_performance.md#phases-and-tasks)) |
| A2 dual-write and A4 validation-off ablation seams; duplicate-submission workloads | S08-T03, S08-T01 |
| `demo-public` profile with a single rate-limited demo token | S09-C01; master [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have) (S5) |
| Ledger replay tool that republishes orders | Unassigned conditional work ([docs/README.md#known-gaps](README.md#known-gaps); master [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery)) |
| OAuth2/OIDC, mTLS, secret rotation, audit logging | Not built ([docs/zerosum_ledger_mvp_plan.md#mvp-vs-production](zerosum_ledger_mvp_plan.md#mvp-vs-production)) |
| Synchronous in-session payments | Deferred ([docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred)) |

<a id="agent-prompt"></a>
## B. Step-specific agent prompt

```text
You are the implementation agent for Step 03 (Order service and outbox) of the ZeroSum Ledger project.

Repository root: zerosum-ledger/
Step document: docs/step_03_order_service_outbox.md

1. Read docs/README.md (source-of-truth, conflict-resolution, change-detection, status-legend), then this
   step document in full, then every source in its section C at the linked anchors: master sections
   (step-03, must-have M2-M4, money-order-contract, rest-apis, schemas, event-contracts, event-to-order,
   apply-algorithm relay pseudocode, standards, trust-boundaries TB1, cross-cutting, decisions ADR-0006/0007/0008,
   and decomposition-clarifications items C1-C3, C5-C12, C25, E1 and O3).
2. Inspect the current repository and the registers (section H) and execution records (section I) of
   S00, S01 and S02. Resolve every inherited value (versions, roles, module layout, env conventions,
   validator, chart of accounts, currency allow-list, JSON Schemas, golden payloads, append-only pattern)
   from those registers and the artifacts they reference, never from copies in documents.
3. Run the change-detection procedure in docs/README.md#change-detection before changing code. Record
   revisions or hashes of every consumed document and artifact in section I.2.
4. Complete only this step's remaining authorized tasks (S03-T01 ... S03-T07) in dependency order.
   Do not provision topics, build the ledger listener, add ablation flags, custom spans, dashboards,
   the demo-public profile, or any other later-step, deferred or non-goal scope.
5. Verify each task exactly as its "Verification and definition of done" field says. Record evidence
   paths in H.4 and I.1.
6. Record every decision D03-1 ... D03-7 (decision, rationale, alternatives, date), actual implementation
   and configuration paths, and produced artifacts in section H. Put a trace comment next to every
   authoritative configuration value, e.g. "# decision: D03-5 — docs/step_03_order_service_outbox.md#decisions-and-outputs".
7. Never invent results. Mark unexecuted evidence "Not run" and blocked work "Blocked" with the exact
   missing dependency. Never mark blocked evidence as passed. Never retry a flaky money-path test until
   it passes; treat it as a bug.
8. Re-run change detection at every phase boundary and before handoff; mark affected completed tasks
   "Needs review" and revalidate them.
9. If you need to change anything owned by the master or an upstream step (for example the module
   layout D00-2, the dependency catalog D00-1, the payment-event schema D01-8, the append-only pattern
   D02-2), stop and raise a change request per docs/README.md#conflict-resolution. Never override an
   owner silently and never weaken an acceptance criterion.
10. Before handoff, complete the section G checklist and section J conditions, then report: tasks done,
    evidence paths, open blockers, change requests raised.
```

<a id="required-reading"></a>
## C. Required reading and prerequisites

### C.1 Master sections

| Link | Why |
|---|---|
| [docs/zerosum_ledger_mvp_plan.md#step-03](zerosum_ledger_mvp_plan.md#step-03) | Objective, task list, exit criteria and the relay-ordering risk for this step |
| [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) | v1.2 resolutions binding this step: C1 (request vs stored order), C2 (same-group check), C3 (header trigger), C5–C7 (event types, settlement events, mapper source system), C8 and C25 (own quarantine table), C9 (`libs/auth`, wired into order-service and ledger-service, 401/403), C10 (ArchUnit exemptions), C11 (trace context), C12 (outbox age basis), E1 (settlement booking), O3 (owner and application roles) |
| [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) | M2, M3 and M4 acceptance criteria this step must satisfy |
| [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract) | Proposed payload and validation rules 1–9, including source derivation and the request hash |
| [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis) | Proposed order-service endpoints, roles, headers and problem codes |
| [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas) | Proposed orders DDL, constraint trigger, append-only triggers and outbox table |
| [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts) | Topics, message key, headers, producer and consumer settings, payment-event shape |
| [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order) | Mapping rows the mapper must implement, and the settlement consistency check |
| [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm) | Proposed relay loop and cleanup pseudocode |
| [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards) | Idempotency-key semantics (Stripe, IETF draft), outbox and idempotent-consumer patterns |
| [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits) | Kafka EOS limits, explicit producer idempotence, `SKIP LOCKED`, deferrable constraint triggers |
| [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) | TB1 controls for the API; TB3 database role and append-only controls |
| [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) | Authentication, authorization, validation, concurrency, cancellation, timeouts, retries, stale state |
| [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components) | Single-writer and no-shared-database design rules |
| [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows) | W1 sequence and the crash-point recovery table |
| [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) | ADR-0006 (single writer), ADR-0007 (partition key), ADR-0008 (polling outbox, one relay) |
| [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded) | Required behavior when Kafka or PostgreSQL is unavailable |
| [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation) | Proposed outbox metrics and the single-clock rule |
| [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) | Required unit, database, Kafka and contract test coverage |
| [docs/zerosum_ledger_mvp_plan.md#migrations](zerosum_ledger_mvp_plan.md#migrations) | Additive-only migrations; care with append-only tables |
| [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets) | Token handling and the log-redaction requirement |
| [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling) | Flaky money-path tests are bugs |
| [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery) | Why outbox cleanup limits ledger rebuilds |
| [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut) | What the S03 cut removes if the minimum cut is invoked |
| [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) | R2 correctness and R11 capacity risks relevant to this step |

### C.2 Earlier step documents and their registers

| Register | Decision IDs consumed |
|---|---|
| [docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs) | D00-1 pinned versions and dependency catalog; D00-2 repository/module layout (including the `libs/outbox` and order-service skeletons); D00-3 compose topology (single order-service instance, health checks); D00-4 orders database, order-service role and Flyway baseline; D00-5 CI job split and test tagging; D00-6 OTel agent and metrics wiring; D00-7 SP3 result (metrics export path); D00-8 environment and secret conventions (token variables, `.env.example`, redaction guideline); D00-9 ADR process; D00-10 build conventions (toolchain, source sets, test tags) |
| [docs/step_01_domain_contracts.md#decisions-and-outputs](step_01_domain_contracts.md#decisions-and-outputs) | D01-1 Money API and overflow policy; D01-2 ISO minor-unit table (used through the validator); D01-4 FareSplitter (test data only); D01-5 ZeroSumValidator rule set; D01-6 ChartOfAccounts and ADR-0003 sign convention; D01-7 currency allow-list location; D01-8 money-order and payment-event JSON Schemas; D01-9 golden payloads O1–O8; D01-10 seeded generators and seed reporting; D01-11 ArchUnit money rules |
| [docs/step_02_ledger_core.md#decisions-and-outputs](step_02_ledger_core.md#decisions-and-outputs) | D02-2 append-only enforcement mechanism (reusable pattern); D02-9 quarantine table shape (reused for order-service's own quarantine table, §0.3 C8, C25); D02-7 ledger read API contract (S03-T03 wires `libs/auth` into these endpoints, §0.3 C9) |

### C.3 Artifacts that must already exist

These are the paths *planned* by upstream registers. **Resolve the actual path from the upstream register (H.2/H.3), not from this list.**

| Planned artifact | Upstream owner |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml` | D00-1, D00-2, D00-10 |
| Empty `services/order-service` Boot application and empty `libs/outbox` module | D00-2 |
| `docker-compose.yml` with PostgreSQL and Kafka | D00-3 |
| `infra/postgres/init.sql` (orders database and order-service role) and the order-service Flyway baseline migration | D00-4 |
| `.env.example` and the log-redaction guideline | D00-8 |
| `.github/workflows/ci.yml` with tag-based jobs | D00-5 |
| `docs/adr/0001-*.md` (ADR template/process) | D00-9 |
| `libs/money` (Money, ZeroSumValidator, ChartOfAccounts, FareSplitter, currency allow-list) and `docs/adr/0003-*.md` | D01-1, D01-4 to D01-7 |
| `libs/contracts` (JSON Schemas, golden O1–O8 payloads, schema test helpers, seeded generators) | D01-8, D01-9, D01-10 |
| ArchUnit money rules | D01-11 |
| Ledger append-only migration and its role tests | D02-2 |

### C.4 Blocking vs independent work

| Missing input | Blocks | Independent preparation that can proceed |
|---|---|---|
| D00-4 orders database, role and Flyway baseline | S03-T01, S03-T02 and every integration test in S03-T03 to S03-T07 | Request-hash canonicalization unit tests (S03-T01); pure mapper unit tests (S03-T07); auth module unit tests (S03-T03); OpenAPI draft (S03-T04) |
| D01-5, D01-6, D01-7 validator, chart of accounts, allow-list | Validation in S03-T03; draft validation in S03-T07 | S03-T01, S03-T02, S03-T05 relay mechanics |
| D01-8 schemas and D01-9 golden payloads | Contract assertions in S03-T03, S03-T05, S03-T07; golden mapping tests | Store, trigger and relay work with synthetic balanced payloads |
| D02-2 append-only mechanism | Immutability part of S03-T02 | Zero-sum trigger in S03-T02; S03-T03 to S03-T07 |
| Kafka Testcontainers module pinned in D00-1 | Relay integration and crash tests (S03-T05); consumer tests (S03-T07) | Outbox writer, guard and cleanup DB tests; pure mapper |
| D00-8 token variable conventions | Token configuration binding in S03-T03 | Constant-time comparison and role checks with test-only configuration |
| D00-7 SP3 outcome | Nothing functional (meters are export-agnostic) | All tasks |
| D01-8 payment-event schema without the v1.2 event types (§0.3 C5) | The `PAYOUT_REJECTED`, `REFUND_FAILED` and post-acceptance `PAYOUT_FAILED` rows in S03-T07 | Every other mapping row and the consumer |

<a id="ownership-and-requirements"></a>
## D. Ownership and engineering requirements

### D.1 Decisions and contracts owned by this step

| Decision ID | What is decided | Master's proposed starting point |
|---|---|---|
| D03-1 | Orders DB schema and deferred zero-sum trigger. Tables, keys and constraints; the same-group adjustment check inside the insert transaction (§0.3 C2); the header-level trigger for orders without entries (§0.3 C3); order-service's own quarantine table with unique Kafka coordinates (§0.3 C8, C25); how append-only enforcement is applied; grants | [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas) |
| D03-2 | Money-order API contract and error codes (`openapi/order-service.yaml`). Endpoints, roles, headers, request and response bodies, problem `code` values and statuses, body cap, unknown and duplicate field handling, the request body without server-set fields and the `Idempotency-Key` header as the source key (§0.3 C1), 401/403 problems (§0.3 C9), and the OpenAPI production approach | [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis), [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract) |
| D03-3 | Idempotency semantics (request-hash canonicalization, replay, 409). Canonical form and its version tag; hash inputs; the order of validation vs key lookup; replay response; key-reuse rejection; in-progress conflict and its lock-wait bound | [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract) (rule 8), [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards) |
| D03-4 | Auth implementation, shared module location, principal → `source_system` mapping. Token parsing, constant-time comparison, role hierarchy, filter mechanism, the `libs/auth` module wired into order-service and ledger-service (§0.3 C9), and the internal mapper principal with source system `instrument-service` (§0.3 C7) | [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting), [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) |
| D03-5 | `libs/outbox` design (table, relay, cleanup, metrics) (ADR-0008). Writer API, who owns the table migration, relay loop parameters, single-instance guard, backoff, publish timestamp source, trace-context capture into the outbox headers (§0.3 C11), cleanup, metric names, shutdown, provisional producer settings, publish-path ArchUnit rule with the framework-DLQ and A2-seam exemptions (§0.3 C10) | [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm), [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas), [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) |
| D03-6 | Payment-event → order mapper implementation and consumer (ADR-0006). Mapping rows for the v1.2 event types (§0.3 C5, C6), events that create no order, unknown events, the consumer transaction, poison handling under D04-4 with the order-service quarantine table (§0.3 C8, C25), provisional consumer settings | [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order), [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts) |
| D03-7 | Outbox stats endpoint. Response fields and units, empty-outbox semantics, clock source (age measured from `created_at`, §0.3 C12), role, failure behavior | [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis), [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) (stale state) |

### D.2 Inherited decisions

| Decision | Owner | How this step must use it |
|---|---|---|
| D00-1 pinned versions and dependency catalog | [docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs) | Use catalog entries only. Any new dependency (canonical-JSON library, OpenAPI tooling, security framework, OTel API) needs a change request to D00-1 first. |
| D00-2 module layout | S00 register | Place code in the recorded modules, including `libs/auth` (§0.3 C9). |
| D00-3 compose topology | S00 register | Exactly one order-service container. Readiness must not depend on Kafka ([docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded)). If the selected health check does, raise a change request. |
| D00-4 databases and roles | S00 register | The app role runs requests, the owner role runs migrations, and grants stay inside the orders database (§0.3 O3). |
| D00-5, D00-10 CI and build conventions | S00 register | Tag every new test as unit or integration so it lands in the right CI job. Put reusable test helpers in the recorded test-fixture source set. |
| D00-6, D00-7 observability wiring and SP3 result | S00 register | Register plain Micrometer meters and don't add exporters. Metrics must work under either SP3 outcome. |
| D00-8 environment and secret conventions | S00 register | Token variables and `.env.example` placeholders follow the recorded conventions. Apply the log-redaction guideline. |
| D00-9 ADR process | S00 register | Write ADR-0006 and ADR-0008 using the recorded template. |
| D01-1 Money and overflow policy | [docs/step_01_domain_contracts.md#decisions-and-outputs](step_01_domain_contracts.md#decisions-and-outputs) | Do all per-currency sums through Money. An overflow is a validation failure, never a 500. |
| D01-5, D01-6, D01-7 validator, chart of accounts, allow-list | S01 register | Run one shared validation implementation for API and mapper orders. Never reimplement a rule or copy the allow-list. |
| D01-8 JSON Schemas | S01 register | Stored, published and fetched orders validate against the money-order schema. Consumed events validate against the payment-event schema. |
| D01-9, D01-10, D01-4 goldens, generators, FareSplitter | S01 register | Use goldens in API and mapper tests and seeded generators (with printed seeds) in concurrency and generative tests. FareSplitter builds test data only; the service never computes splits (rule 9). |
| D01-11 ArchUnit money rules | S01 register | Apply them to order-service and `libs/outbox` packages. |
| D02-2 append-only mechanism | [docs/step_02_ledger_core.md#decisions-and-outputs](step_02_ledger_core.md#decisions-and-outputs) | Apply it to the order header and entry tables, never to the outbox table. |
| D02-9 quarantine table shape | S02 register | Reuse the shape (surrogate key, nullable order id, unique Kafka topic, partition and offset) for order-service's own quarantine table (§0.3 C8, C25). |
| ADR-0007 partition key | Master [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions), selected in D04-1 | Relay message key is the order group identifier. Revisit through change detection after S04-T01. |
| Targets M2–M4, TB1, timeouts | Master [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have), [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) | Reference these in tests and configuration. Never restate or relax them. |

### D.3 Engineering requirements

**Package and module boundaries**

- **`services/order-service`** has five packages:
  - `api`: controllers, request DTOs, problem mapping.
  - `order`: validation orchestration, request hashing, the store.
  - `mapping`: the pure mapper. No Spring, no I/O, no clock.
  - `consumer`: the Kafka listener adapter.
  - `config`.
- **`libs/outbox`** knows nothing about money orders. It exposes a writer, a relay, a stats query, cleanup and metrics. Its writer depends only on JDBC and transactions, and only its relay touches the Kafka producer. It is the only module allowed to call the producer send API in a producing service's application code (M4(b)); Spring's dead-letter recoverer and the A2 seam inside `libs/outbox` are exempt (§0.3 C10).
- **The shared auth module** `libs/auth` (§0.3 C9; details in D03-4) holds token configuration, the principal type and role checks. It must not depend on order-service classes: S03-T03 also wires it into ledger-service's read APIs, and instrument-service reuses it in S05.
- **`libs/money` and `libs/contracts`** are consumed read-only. S03 never edits them; changes go to S01 through a change request.
- **Portability for the G2 alternative.** Validation orchestration, request hashing and the mapper must not depend on HTTP or Kafka types. If the G2 alternative (S05-C01) is taken, they must be extractable into a shared library.

**Interfaces** (illustrative only; the selected signatures are recorded in D03-3, D03-5 and D03-6)

```java
// Illustrative, not a contract.
sealed interface CreateOutcome permits Created, Replayed, KeyReused, InProgress {}
interface OutboxWriter { void append(String topic, String messageKey, byte[] payload, Map<String, String> headers); } // requires an active transaction
sealed interface MappingResult permits OrderDraft, NoOrder {}
```

**Data flows**

1. **API create.**
   - Authenticate, then resolve the principal and source system.
   - Enforce the body cap, parse (reject unknown and duplicate fields, and server-set fields such as `order_id`, `source` and `created_at`, §0.3 C1), run Bean Validation, then the S01 rules, then compute the canonical hash. The `Idempotency-Key` header supplies the source key.
   - In one transaction: idempotent header insert, entries, outbox row, `COMMIT`. The deferred trigger runs at `COMMIT`.
   - Map the outcome to a response: created, replayed, key reused, in progress, or a validation failure raised at commit.
2. **Relay.** Follow the pseudocode in [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm):
   - Take the guard.
   - Select unpublished rows in id order, skipping rows locked by others.
   - Send them with the order group as the key, with headers.
   - Wait for acknowledgements, mark the rows published, commit.
3. **Mapper consumer.**
   - Receive the event, validate it against the schema, map it.
   - For an order-producing event: create the order through the same store call with the internal principal (source system `instrument-service`, §0.3 C7) and `event_id` as the key, append the outbox row in the same transaction, commit, then acknowledge.
   - For a no-order event: acknowledge without writing.
4. **Stats.** Read the unpublished count and oldest unpublished age from the outbox table using the database clock.

**Lifecycle**

- **Startup.**
  - Flyway migrates with the owner role; startup fails if a migration fails.
  - HTTP readiness depends on the database only.
  - The relay, the cleanup job and the consumer start after the application context is ready.
  - A relay that can't take the guard stays idle and reports it. It does not crash the service.
- **Shutdown.** Graceful, within the timeout proposed in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting):
  - HTTP stops accepting new requests.
  - The relay finishes or rolls back its in-flight batch. It never marks rows published without broker acknowledgement.
  - The consumer stops polling, finishes its transaction and commits offsets.
- **Crash.** Recovery follows the table in [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows):
  - Committed orders with unpublished rows are published after restart (M4(a)).
  - A crash after send but before commit produces duplicates, which must be harmless (M4(c)).
  - A crash before commit stores nothing, so the client's retry creates the order.
- **Retry.**
  - The API doesn't retry database errors internally. It fails fast with a service-unavailable problem.
  - The relay retries indefinitely with capped backoff.
  - The consumer retries transient errors a bounded number of times, then pauses. Poison records go to the dead-letter path and to order-service's own quarantine table (§0.3 C8, C25). The policy is S04's (D04-4).

**Security and trust boundaries**

- **TB1 controls.**
  - Tokens are compared in constant time, and roles are checked per endpoint.
  - The body cap is enforced before parsing.
  - Unknown fields are rejected.
  - Source system is derived only from the principal.
  - COMMERCE orders come only from writer principals. No HTTP path can create other order types; only the internal mapper can.
- **Logging and errors.** Tokens, `Authorization` headers and raw bodies are never logged. Problem responses name field paths and codes, never echo credentials, and never include stack traces.
- **TB3 controls.**
  - The order-service role reaches only the orders database.
  - Order tables have `UPDATE`, `DELETE` and `TRUNCATE` revoked, plus triggers that also stop the owner.
  - The outbox table needs `UPDATE` and `DELETE` for the app role.
  - Kafka has no authentication; this is a documented MVP limitation.
- **Payment events are untrusted input.** Every event passes schema validation, the mapper rules and the zero-sum validator before an order is written, because a producer bug must not bypass the single validation point.

**Deployment constraints**

- One order-service instance (ADR-0008). Compose enforces it (D00-3), and the relay guard is the backstop.
- Configuration comes from environment variables and `application.yaml` per D00-8.
- Only the `local` and `ci` profiles are touched. The `chaos` and `demo-public` profiles belong to S08 and S09.

### D.4 Configuration ownership

Every authoritative value below gets a trace comment, for example `# decision: D03-5 — docs/step_03_order_service_outbox.md#decisions-and-outputs`. Values proposed by the master are referenced from [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting), [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis) and [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm). They are set once, in the file named here.

| Runtime value | Planned location | Traced to |
|---|---|---|
| Writer, reader and admin tokens; writer token → source-system mapping | Environment variables per D00-8, bound in `services/order-service/src/main/resources/application.yaml`; placeholders in `.env.example` | D03-4 (variable names: D00-8) |
| Internal mapper principal and its source-system name | `services/order-service/src/main/resources/application.yaml` | D03-4 |
| Body size cap, idempotency key maximum length, unknown and duplicate field rejection | `application.yaml` and request DTO constraints | D03-2 |
| Canonicalization version tag and lock-wait bound for idempotent create | Code constant (version tag); `application.yaml` (lock wait) | D03-3 |
| Database statement and lock timeouts, Hikari connection timeout and pool size | `application.yaml` | D03-2 (proposal in master cross-cutting) |
| Currency allow-list | Location recorded in D01-7 (not copied) | D01-7 |
| Relay poll interval, batch size, send timeout, backoff bounds, guard key, cleanup interval and retention | `application.yaml` of each producing service. `libs/outbox` declares these properties **without defaults**, so there is a single source. | D03-5 |
| Producer settings (idempotence, acks) used by the relay | `application.yaml` (provisional) | D03-5 until S04-T01 records D04-2 |
| Topic names for relay routing and consumer subscription; consumer group id | `application.yaml` (provisional) | Master event contracts until S04-T01 records D04-1 |
| Consumer settings and dead-letter topic; order-service quarantine table | `application.yaml` (provisional until S04-T03 records D04-4); orders migration | D03-6, D04-4; table shape D03-1 (§0.3 C8, C25) |
| Stats response semantics | Code and `openapi/order-service.yaml` | D03-7 |
| Flyway locations (including any outbox migration location) | `application.yaml` | D03-1, D03-5 |

**Permitted alternatives downstream work must handle**

- **SP2 / Debezium** (S04-C01, D04-7 → D03-5). The outbox table and the writer API are the stable integration point. Only the relay component may be replaced. D03-7's stats semantics depend on rows being marked published, so a Debezium adoption must revise D03-7 through the change procedure.
- **G2 alternative to ADR-0006** (S05-C01 → D03-6). Instrument-service may write orders through a shared validation library. The validation, hashing and mapper code stay free of transport types so they can move. The order-mapper consumer may then be retired.
- **SP3 fallback** (D00-7). Outbox and mapper metrics are export-agnostic Micrometer meters.
- **Ablation seams** (S08-T03, D08-3). A2 needs a dual-write path (inside `libs/outbox`, exempt from the ArchUnit rule, §0.3 C10), and A4 disables application validation, both database trigger attachments and the ledger re-check (§0.3 E8). S03 adds **no flags** (YAGNI). The outbox append and the validation call each live in exactly one identifiable place, so S08 can add a guarded seam through a change request to D03-1, D03-2 and D03-5.
- **Minimum cut** ([docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut)). The OpenAPI file may be generated only.

<a id="phases-and-tasks"></a>
## E. Phases and tasks

| Phase | Tasks | Hours |
|---|---|---|
| 1 Persistence | S03-T01 (4), S03-T02 (2) | 6 |
| 2 API | S03-T03 (5), S03-T04 (1) | 6 |
| 3 Outbox | S03-T05 (5), S03-T06 (1) | 6 |
| 4 Payment-event mapping | S03-T07 (4) | 4 |
| **Total** | | **22** |

<a id="phase-1"></a>
### Phase 1 — Persistence

**Objective:** an orders database that stores immutable, zero-sum money orders and makes idempotency a database property rather than a check-then-insert.

**Exit checkpoint:**
- Migrations apply cleanly on an empty orders database under the D00-4 roles.
- The M2 database tests and the idempotent-store tests pass in the integration CI job.
- Change detection has been re-run and I.2 updated.

<a id="s03-t01"></a>
#### S03-T01 — Orders schema, migrations and idempotent order store
- **Outcome:** Flyway migrations create the order header and entry tables, with the idempotency uniqueness constraint and a stored request hash. A `JdbcClient` store exposes one create operation that returns Created, Replayed, KeyReused or InProgress, computed from a versioned canonical request form.
- **Estimate:** 4 h
- **Inputs:**
  - Master:
    - proposed orders DDL in [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas);
    - rules 6 and 8 in [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract);
    - idempotency-key semantics in [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards);
    - concurrency, idempotency and timeout rows in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting);
    - [docs/zerosum_ledger_mvp_plan.md#migrations](zerosum_ledger_mvp_plan.md#migrations);
    - M2(c) and M3 in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have);
    - §0.3 C1, C2 and O3 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications).
  - Upstream decisions: D00-4, D00-10, D01-1, D01-8, D01-10.
  - Artifact: the order-service Flyway baseline migration (path from D00-4).
- **Depends on:** none (upstream S00-T04 and S01-T05 must be Done)
- **Instructions:**
  1. **Schema.** Start from the proposed DDL and write the selected schema as the next versioned migration after the D00-4 baseline. Store everything needed to return the original response on replay: header, source, metadata, effective and created timestamps, and entries keyed by order and line number. Record the schema in D03-1. The append-only and zero-sum triggers belong to S03-T02.
  2. **Idempotency uniqueness.** Enforce (source system, idempotency key) with a unique constraint. Never check-then-insert.
  3. **Adjustments (§0.3 C2).** The foreign key enforces that the adjusted order exists. Check that it belongs to the same order group in the application, inside the insert transaction, by reading the adjusted order's group before inserting the header. Record the check in D03-1.
  4. **Grants.** Per D00-4, the app role gets only `SELECT` and `INSERT` on the order tables.
  5. **Canonicalization (D03-3).** Hash the parsed and normalized request, not raw bytes:
     - Inputs: the principal-derived source system, the idempotency key (from the `Idempotency-Key` header, §0.3 C1) and every client-supplied request field. Server-assigned fields (`order_id`, `source`, `created_at`) are never part of the request.
     - Absent, `null` and default optional fields normalize to one representation.
     - Object keys are sorted. Integers are written without a fractional part.
     - Entry order is significant, because line numbers are part of the order.
     - Prefix the canonical bytes with a version tag. Keys never expire (M3(e)), so a later serializer or library change must never turn a legitimate retry into a key-reuse error.
     - Consider RFC 8785 (JCS) as the canonical form. If it needs a new library, raise a change request to D00-1 first.
  6. **Create operation.** One transaction:
     - Set a transaction-local lock-wait bound (D03-3; proposal in master cross-cutting).
     - Insert the header with `ON CONFLICT … DO NOTHING RETURNING` the order id.
     - **If a row comes back:** batch-insert the entries and return Created. S03-T05 adds the outbox append on this branch only.
     - **If nothing comes back:** read the existing header, entries and stored hash in the same transaction. Return Replayed if the hash matches, otherwise KeyReused.
     - A lock-timeout SQLSTATE while waiting on a concurrent same-key transaction returns InProgress.
     - Don't let a unique-violation exception abort the transaction and then try to read inside it.
  7. **Identifiers.** Decide whether the database or the application generates order ids and timestamps, and record it in D03-1. A replay must return the originally stored ids and timestamps, never new ones.
  8. **Reads.** Add fetch-by-id (with entries) and list-by-group ordered by creation time, for S03-T03.
  9. **Single write path.** This create operation is the only write path for money orders. S03-T03 and S03-T07 call it with a principal-derived source system.
- **Edge cases and failure behavior:**
  - **First writer rolls back.** Two same-key transactions run, and the first rolls back (for example, its deferred trigger fails at `COMMIT`). The waiting insert must then succeed and return Created.
  - **Concurrent same key, same body.** Exactly one Created. The rest are Replayed after waiting, or InProgress once the wait bound expires.
  - **Same key, two principals.** Two distinct orders, because uniqueness is scoped by source system. Document this in D03-3.
  - **Key length.** Keys at and just over the maximum length (D03-2) are handled identically by the database check and API validation.
  - **Serializer drift.** A JSON library upgrade that changes number or escape formatting must not change the hash; pinned test vectors catch it.
  - **Older hash version.** A replay of an order hashed under an older canonicalization version compares using the stored version.
  - **Adjustment in another group.** An adjustment referencing an existing order in a different group is rejected with the adjustment error, never a 500.
- **Outputs:** planned:
  - Migration under `services/order-service/src/main/resources/db/migration/` (numbering per D00-4).
  - `services/order-service/src/main/java/…/order/OrderStore.java` and `…/order/RequestHasher.java`.
  - Tests `RequestHasherTest` and `OrderStoreIT`.
- **Verification and definition of done:**
  - **`RequestHasherTest` (unit tag).** Pinned vectors cover four cases:
    - reordered JSON keys give the same hash;
    - absent and `null` metadata give the same hash;
    - reordered entries give a different hash;
    - the version tag is present in the hashed bytes.
  - **`OrderStoreIT` (integration tag, PostgreSQL Testcontainers per D00-1).**
    - Created followed by Replayed returns identical stored ids and timestamps.
    - A different body returns KeyReused.
    - The M3(c) concurrent-request scenario on one key leaves exactly one header row. Every other call returns Replayed or InProgress, and the seed is printed per D01-10.
    - The first-writer-rollback case passes.
    - The same key from two principals creates two rows.
    - An unknown adjusted order is rejected (M2(c)).
  - **Flyway.** `flyway validate` passes, and re-running migrations is a no-op.

<a id="s03-t02"></a>
#### S03-T02 — Deferred zero-sum trigger and immutability enforcement
- **Outcome:** the orders database rejects unbalanced orders at `COMMIT` even when application validation is bypassed. It also rejects `UPDATE`, `DELETE` and `TRUNCATE` on order tables for both the app role and the owner role, using the mechanism selected in D02-2.
- **Estimate:** 2 h
- **Inputs:**
  - Master:
    - proposed constraint trigger and append-only functions in [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas);
    - constraint-trigger rules in [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits);
    - [docs/zerosum_ledger_mvp_plan.md#migrations](zerosum_ledger_mvp_plan.md#migrations);
    - M2 in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have).
  - Upstream decisions: D02-2 (and its migration and role tests, from [docs/step_02_ledger_core.md#s02-t01](step_02_ledger_core.md#s02-t01)), D00-4, D01-9.
- **Depends on:** S03-T01
- **Instructions:**
  1. **Append-only enforcement.** Read D02-2 and the artifact it references. Apply the same mechanism (privileges plus triggers, in the form S02 selected) to the order header and entry tables. The orders database is separate from the ledger database, so the function must be created by an orders migration. Reuse the pattern; don't invent a new one.
  2. **Zero-sum trigger.** Add the deferred constraint trigger on the entries table, starting from the proposed DDL. Record the selected form in D03-1.
  3. **Orders without entries (§0.3 C3).** The master DDL attaches the same deferred check to the header table as well, so a header committed with no entries fails at `COMMIT`. Implement both trigger attachments.
  4. **Commit-time errors.** Surface the trigger's SQLSTATE at `COMMIT` from the store as a distinct not-zero-sum outcome. S03-T03 maps it to the not-zero-sum problem code (D03-2), never a 500.
  5. **Outbox exclusion.** Don't apply append-only triggers or revokes to the outbox table. S03-T05 needs `UPDATE` and `DELETE` on it.
  6. **Trigger cost.** Keep the proposal's per-row cost note as is. S07 measures P1, and optimization waits for that evidence.
- **Edge cases and failure behavior:**
  - **Per-currency imbalance.** An order that sums to zero overall but not per currency (for example, positive in one currency and negative in another) fails at `COMMIT`.
  - **Too few entries.** Headers with zero entries or a single entry fail at `COMMIT`.
  - **Several orders in one transaction.** Each order id is checked independently.
  - **Out of the MVP threat model.** A superuser, or the owner running `ALTER TABLE … DISABLE TRIGGER`, can bypass the triggers. Record this limitation in H.5; don't test it.
  - **Ablation A4.** A4 disables application validation, both trigger attachments and the ledger re-check (§0.3 E8). That seam is added later by S08-T03 through a change request, not now.
- **Outputs:** planned:
  - Migration under `services/order-service/src/main/resources/db/migration/`.
  - Test `OrdersImmutabilityIT`.
- **Verification and definition of done:** `OrdersImmutabilityIT` (integration tag) passes:
  - `UPDATE`, `DELETE` and `TRUNCATE` on both order tables fail for the app role and the owner role (M2(a)).
  - A raw JDBC insert of an unbalanced order that bypasses the store fails at `COMMIT` with the zero-sum SQLSTATE (M2(b)).
  - A header without entries fails at `COMMIT`.
  - A per-currency imbalance fails at `COMMIT`.
  - An unknown adjusted order is rejected (M2(c), re-asserted).
  - The COMMERCE golden payloads from D01-9 commit successfully.

<a id="phase-2"></a>
### Phase 2 — API

**Objective:** a TB1-compliant REST API over the order store, with stable problem-details errors and a published, drift-checked OpenAPI contract.

**Exit checkpoint:**
- M3(a) to M3(e) pass through HTTP.
- The OpenAPI consistency test passes.
- The log-redaction test covers order-service.
- Change detection has been re-run.

<a id="s03-t03"></a>
#### S03-T03 — REST API, validation, token authentication and problem details
- **Outcome:** order-service exposes the create, fetch and group-list endpoints behind the shared token-auth module. It enforces the money-order rules using the S01 libraries and returns RFC 9457 problem details with a stable `code` for every rejection.
- **Estimate:** 5 h
- **Inputs:**
  - Master:
    - order-service rows and conventions in [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis);
    - rules 1–9 in [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract);
    - authentication, authorization, validation and cancellation rows in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting);
    - TB1 in [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries);
    - [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards);
    - PostgreSQL row in [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded);
    - [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets);
    - M3 in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have);
    - §0.3 C1 and C9 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications).
  - Upstream decisions: D00-1, D00-2, D00-8, D01-1, D01-5, D01-6, D01-7, D01-8, D01-9, D01-11, D02-7 (ledger read endpoints to protect).
  - This step: D03-3 (store from S03-T01).
  - Artifacts: `libs/money`, `libs/contracts`, `.env.example`.
- **Depends on:** S03-T01, S03-T02
- **Instructions:**
  1. **Auth module (D03-4).**
     - Create the `libs/auth` module recorded in D00-2 (§0.3 C9). Wire it into order-service **and** into ledger-service's read APIs, with the reader and admin roles those endpoints need. instrument-service is wired in S05.
     - Parse role tokens from the environment per D00-8. Writer tokens carry their source-system name, as proposed in master cross-cutting.
     - Compare tokens with `MessageDigest.isEqual`, checking every configured token rather than stopping at the first match.
     - Produce a principal holding role and source system.
     - Prefer a plain servlet filter plus per-endpoint role checks. Adopt a security framework only if it's already in D00-1.
     - Record the role hierarchy (for example, whether writer and admin tokens also satisfy reader endpoints).
  2. **Authentication failures (§0.3 C9).** A missing or invalid token gets 401 `unauthorized`. A valid token with the wrong role gets 403 `forbidden`. Record both in D03-2.
  3. **Request parsing.**
     - Enforce the body cap before deserialization, including for chunked requests without a length.
     - Reject unknown JSON fields on money endpoints.
     - Reject duplicate JSON keys, so the hashed request can't be ambiguous.
     - Reject coercion of floats or strings into integer amounts.
  4. **Idempotency header (§0.3 C1).** A missing header gets the missing-key problem (M3(d)). The header supplies `source.idempotency_key`. A body carrying `order_id`, `source` or `created_at` is rejected with a validation problem. Record the request contract in D03-2.
  5. **Validation order.** Run validation before the idempotency lookup, and record this order in D03-3:
     1. Bean Validation (shape).
     2. Entity pattern and account-per-kind (D01-6).
     3. Currency allow-list (D01-7).
     4. ZeroSumValidator (D01-5), with sums through Money (D01-1).
     5. The API accepts COMMERCE only (rule 7).

     Validation failures are not stored, following the Stripe semantics in master standards, so a corrected retry with the same key succeeds.
  6. **Outcome mapping.**
     - Created → the created status with the stored order.
     - Replayed → the replay status and replay header, with the same body as the original (M3(a)).
     - KeyReused → the key-reuse problem (M3(b)).
     - InProgress → the in-progress problem.
     - Commit-time zero-sum failure → the not-zero-sum problem.
     - Unknown or cross-group adjustment → the adjustment problem.
  7. **Problem details.**
     - Use Spring Framework's built-in `ProblemDetail` support, with a `code` property and one `@RestControllerAdvice`.
     - A connection-acquisition timeout or a database outage gets the service-unavailable problem.
     - Anything unexpected gets a generic problem without a stack trace or request echo.
  8. **Read endpoints.** Fetch by id and list by group, both reader role, returning stored orders.
  9. **Client disconnects.** No special handling. A committed order stays, and a retry replays it ([docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) cancellation row).
  10. **Kafka independence.** Nothing in the request path touches Kafka, and the readiness check doesn't include Kafka.
  11. **Log redaction.** Extend the D00-8 redaction test to order-service.
  12. **ArchUnit.** Apply the D01-11 money rules to order-service packages.
  13. **Custom spans.** Don't add any now. S07-T01 owns them.
- **Edge cases and failure behavior:**
  - **Body size.** A body exactly at the cap is accepted; one byte over is rejected before parsing.
  - **Malformed JSON.** Duplicate keys, a float amount like `2500.0`, or an amount sent as a string are rejected, never coerced.
  - **Overflow.** A per-currency sum overflow gives a validation problem, not a 500 (D01-1).
  - **Retry after a validation failure.** The first request fails validation and the corrected second request with the same key returns Created.
  - **Formatting-only differences.** A body that differs only in whitespace or key order returns Replayed.
  - **Wrong order type.** A non-COMMERCE type submitted through the API gets a validation problem.
  - **Wrong token.** A reader token on create is forbidden. A writer token on reads follows the recorded role hierarchy.
  - **PostgreSQL down.** The service-unavailable problem is returned within the connection timeout, and nothing is stored.
  - **Kafka down.** Orders are still accepted with the created status.
- **Outputs:** planned:
  - Shared auth module sources (path per D03-4).
  - `services/order-service/src/main/java/…/api/` controllers, DTOs and problem advice.
  - Token entries in `application.yaml` and placeholder keys in `.env.example` per D00-8.
  - Tests `TokenAuthFilterTest`, `ProblemMappingTest`, `MoneyOrderApiIT` and `LedgerAuthWiringIT`; auth wiring configuration in ledger-service.
- **Verification and definition of done:**
  - **Unit.** `TokenAuthFilterTest` covers roles, source-system derivation, and missing, invalid and wrong-role tokens. `ProblemMappingTest` checks that every outcome maps to its recorded code.
  - **`MoneyOrderApiIT` (integration tag, PostgreSQL Testcontainers).**
    - M3(a): the replay has the identical body, the replay header and one row.
    - M3(b): the key-reuse code.
    - M3(c): the concurrent same-key scenario ends with exactly one row, and every other response is the replay or the in-progress conflict.
    - M3(d): the missing-key code.
    - M3(e): a replay still works for an order whose creation timestamp is far in the past (inserted by the owner role), and no order-deletion job exists.
  - **Goldens.** The COMMERCE golden payloads from D01-9 are accepted with a writer token, and the adjustment golden is accepted after its original.
  - **Schema.** Fetched orders validate against the D01-8 money-order schema.
  - **Ledger-service auth (§0.3 C9).** `LedgerAuthWiringIT`: ledger read endpoints return 401 without a token, 403 with a wrong-role token, and succeed with a reader token.
  - **Hygiene.** The log-redaction test passes with an `Authorization` header present, and the D01-11 ArchUnit rules pass.

<a id="s03-t04"></a>
#### S03-T04 — OpenAPI specification for order-service
- **Outcome:** `openapi/order-service.yaml` documents every order-service endpoint, role, header, response body and problem code as selected in D03-2, and a test fails whenever the specification and the implementation diverge.
- **Estimate:** 1 h
- **Inputs:**
  - Master:
    - [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis);
    - [docs/zerosum_ledger_mvp_plan.md#deliverables](zerosum_ledger_mvp_plan.md#deliverables);
    - the API design row in [docs/zerosum_ledger_mvp_plan.md#portfolio-value](zerosum_ledger_mvp_plan.md#portfolio-value);
    - the S03 row of [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut).
  - This step: D03-2, D03-3, D03-4.
  - Upstream decisions: D01-8 (referenced, not copied), D00-1.
- **Depends on:** S03-T03
- **Instructions:**
  1. **Choose an approach** and record it in D03-2:
     - (a) A hand-written specification that references the D01-8 money-order schema by `$ref`, plus a test comparing the specification's paths and methods to Spring's registered handler mappings.
     - (b) A specification generated from annotations and committed, plus a diff test. This needs a new dependency, so it requires a D00-1 change request first. It is also the master's minimum-cut form.
  2. **Document** everything a caller needs:
     - the bearer security scheme and the required role per operation;
     - the idempotency header (required, with its maximum length) and the replay header;
     - each problem `code` with its status;
     - stored-order response bodies;
     - the group-list query parameter.
  3. **Stats operation.** Leave it out of the specification. S03-T06 adds it, and the consistency test forces that.
  4. **Error codes.** Generate the list of problem codes checked by the test from the code constants used by the advice from S03-T03, so the specification can't silently drift.
- **Edge cases and failure behavior:**
  - **`$ref` resolution.** A `$ref` into `libs/contracts` must resolve from the repository root. If Swagger UI can't follow relative file references, bundle a copy during the build instead of committing a duplicate.
  - **Later drift.** Later changes, such as S08 seams added through a change request, must update the specification in the same commit, or the test fails.
- **Outputs:** planned:
  - `openapi/order-service.yaml`.
  - `services/order-service/src/test/java/…/OpenApiSpecConsistencyTest.java`.
- **Verification and definition of done:** `OpenApiSpecConsistencyTest` passes:
  - Every handler mapping has a matching operation, and every operation has a handler.
  - Every problem-code constant appears in the specification.
  - The file parses as valid OpenAPI with the parser available in D00-1.
  - All `$ref`s resolve.

<a id="phase-3"></a>
### Phase 3 — Outbox

**Objective:** every committed money order reaches Kafka through a reusable outbox library. No other publish path exists, and a crash can cause duplicates but never loss.

**Exit checkpoint:**
- M4(a) and M4(b) pass.
- Relay duplicate behavior after a failed mark has been demonstrated.
- The stats endpoint serves reader requests.
- ADR-0008 is committed.
- Change detection has been re-run.

<a id="s03-t05"></a>
#### S03-T05 — `libs/outbox`: writer, polling relay, single-instance guard, cleanup and metrics
- **Outcome:** a library that appends outbox rows inside the caller's transaction, plus a single-instance polling relay. The relay publishes rows in id order, skips rows locked by others, backs off on failure, deletes old published rows and exposes outbox metrics. Order-service appends exactly one outbox row per created order.
- **Estimate:** 5 h
- **Inputs:**
  - Master:
    - relay and cleanup pseudocode in [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm);
    - outbox table in [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas);
    - [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits);
    - topic, key, headers and producer settings in [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts);
    - ADR-0007 and ADR-0008 in [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions);
    - [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards);
    - crash points in [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows);
    - [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded);
    - [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation);
    - [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery);
    - M4 in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have).
  - Upstream decisions: D00-1, D00-3, D00-4, D00-6, D00-9, D00-10, D01-8.
  - This step: D03-3 (store).
  - Artifact: `libs/outbox` module skeleton.
- **Depends on:** S03-T01
- **Instructions:**
  1. **Table.** Start from the proposed outbox DDL. Decide who owns its migration and record it in D03-5:
     - **Candidate A:** each producing service keeps its own copy of the migration, and a `libs/outbox` test fixture checks the required columns and partial index.
     - **Candidate B:** the library ships a migration location. Watch for version-number collisions in the shared Flyway history table.

     Grant the app role `SELECT`, `INSERT`, `UPDATE` and `DELETE` on the outbox only.
  2. **Writer.** Append requires an active transaction; throw if there is none, for example using mandatory transaction propagation. Serialize the payload once. The writer has no Kafka dependency.
  3. **Trace context (§0.3 C11).** Capture the current W3C trace context into the row's `headers` column at append time, so the relay can send it later from a different thread. Without this, M12(a)'s single trace can't span the outbox. Record the mechanism in D03-5. If it needs the OTel API as a dependency, raise a D00-1 change request. S04-T06 verifies propagation.
  4. **Order-service wiring.** On the Created branch of the store only, append the stored order as its D01-8 payload, including server-assigned id and timestamps.
     - Message key: the order group (ADR-0007).
     - Headers: as proposed in master event contracts.
     - Replayed, KeyReused and InProgress never append.
  5. **Relay loop.** Follow the pseudocode:
     - Use one transaction per batch.
     - Select unpublished rows in id order, skipping locked rows.
     - Send all rows, then wait for every acknowledgement within the send timeout.
     - On any failure, roll back and back off with a cap.
     - On success, mark the rows published and commit.
     - Sleep only when the batch wasn't full.

     Take all parameters from the D03-5 properties (no library defaults). Set the published timestamp with `clock_timestamp()` rather than `now()`. `now()` is the transaction start time, which would understate publish lag by the send duration.
  6. **Single-instance guard (ADR-0008).** Candidate: take a transaction-scoped PostgreSQL advisory lock (`pg_try_advisory_xact_lock`) at the start of each batch transaction. If it isn't acquired, skip the batch and count a guard skip.
     - Row locks that skip locked rows don't preserve per-key order across two relays: a second relay can publish a later batch first. The advisory lock serializes batches.
     - The compose single replica (D00-3) is the primary control; the guard is the backstop.
     - Record the mechanism.
  7. **Producer settings.** Set idempotence and acks explicitly, as [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits) requires. They live in order-service `application.yaml`, traced to D03-5 until S04-T01 records D04-2.
  8. **Cleanup job.** A scheduled job deletes published rows older than the D03-5 retention, in bounded batches and its own transactions. It never deletes unpublished rows.
  9. **Metrics.** Register Micrometer meters:
     - publish lag histogram;
     - oldest unpublished age gauge;
     - batch size;
     - send failures;
     - guard skips.

     Use the names proposed in [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation) where they exist, and record all names in D03-5. S07-T01 may rename them through the change procedure.
  10. **Shutdown.** Stop the loop, and let the in-flight batch commit or roll back within the graceful timeout. Never mark rows without broker acknowledgement.
  11. **ArchUnit rule (M4(b)).** In application packages of producing services, only `libs/outbox` classes may call the Kafka producer send API. Ship the rule as a `libs/outbox` test fixture so S05 reuses it. It inspects application classes, so framework-internal dead-letter publishing (S03-T07) doesn't trip it, and the A2 seam that S08 later adds inside `libs/outbox` is exempt (§0.3 C10).
  12. **ADR-0008.** Write it per D00-9: polling outbox, one relay per service, the guard mechanism, and the upgrade path through SP2 and Debezium (S04-C01).
- **Edge cases and failure behavior:**
  - **Kafka unavailable.** The relay backs off indefinitely, the API keeps accepting orders, and the oldest-age gauge grows ([docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded)).
  - **Partial batch failure.** The whole batch rolls back and is resent. This creates duplicates, and consumers may see a later record for a key before the resend of an earlier one. ADR-0007's commutative arithmetic and consumer dedupe make both harmless. Duplicates must be byte-identical in key, payload and order-id header.
  - **Crash timing.** A crash after send but before commit creates duplicates (M4(c)). A crash before send loses nothing.
  - **Poison row.** A row that can never be sent (larger than the broker's message limit, say) blocks everything behind it. The relay must not skip money: log the row id, count the failure, and rely on the oldest-age alert (S07-T03). The fix is forward (split or repair through a new order), never a deletion.
  - **Transaction boundaries.** A writer called outside a transaction throws. An order transaction that rolls back removes its outbox row too.
  - **Misconfigured second relay.** The guard serializes batches, and the guard-skip counter increases.
  - **Deleted rows and ledger rebuilds.** Cleanup deletes rows a full ledger rebuild would need. This is known; the replay tool is conditional and unassigned ([docs/README.md#known-gaps](README.md#known-gaps)).
  - **Table bloat.** Update and delete churn bloats the table. Rely on autovacuum; the partial index on unpublished rows stays small.
- **Outputs:** planned:
  - `libs/outbox/src/main/java/…`: writer, relay, guard, cleanup, metrics.
  - `libs/outbox` test fixtures: ArchUnit rule and table-shape assertion.
  - Orders outbox migration.
  - Relay properties in `services/order-service/src/main/resources/application.yaml`.
  - `docs/adr/0008-*.md`.
  - Tests: `OutboxWriterIT`, `OutboxRelayIT`, `RelayDuplicateIT`, `RelayGuardIT`, `OutboxCleanupIT`, `OrderPublishAfterCrashIT`.
- **Verification and definition of done:**
  - **`OutboxWriterIT`.** Append without a transaction throws, and a rollback removes the row.
  - **`OutboxRelayIT`** (PostgreSQL and Kafka Testcontainers per D00-1).
    - Rows are consumed in id order per key, and rows are marked only after acknowledgement.
    - While the Kafka container is paused, nothing is marked and rows remain. After unpausing, the backlog drains.
  - **`RelayDuplicateIT`.** A test-scoped JDBC wrapper fails the mark statement once; it is test code, not a production flag. Rows are resent, and the duplicates are byte-identical.
  - **`RelayGuardIT`.** Two relay instances run against one database with seeded orders across several groups. Per-key order holds, and the guard-skip counter is positive.
  - **`OutboxCleanupIT`.** Old published rows are deleted, and unpublished rows never are.
  - **`OrderPublishAfterCrashIT`** (M4(a), end to end).
    1. Pause Kafka.
    2. Create orders through the API against order-service running as a separate process or container.
    3. Kill it with SIGKILL.
    4. Unpause Kafka and restart order-service.
    5. Consume the topic and assert that every committed order id is published within the M4(a) bound, measured from restart.
  - **ArchUnit M4(b).** The rule passes on order-service. A deliberately violating class in a test source set makes it fail (rule self-test).
  - **Schema.** Every consumed payload validates against the D01-8 money-order schema.
  - **Metrics.** The outbox meters appear through the actuator metrics endpoint in a test. Export is verified by S00 and S07, not here.

<a id="s03-t06"></a>
#### S03-T06 — Outbox stats endpoint
- **Outcome:** a reader-role endpoint returns the unpublished outbox row count and the age of the oldest unpublished row, computed by a `libs/outbox` query that instrument-service can reuse.
- **Estimate:** 1 h
- **Inputs:**
  - Master:
    - outbox stats row in [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis);
    - stale-state row in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting);
    - [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded).
  - This step: D03-2, D03-4, D03-5, D03-7.
- **Depends on:** S03-T04, S03-T05
- **Instructions:**
  1. **Query.** Add a stats query to `libs/outbox`. It counts unpublished rows and computes the oldest age from `created_at` with the database clock (§0.3 C12), using the unpublished partial index.
  2. **Controller.** Add an order-service controller behind the reader role. Define the response per D03-7 (field names and units), and add the operation to `openapi/order-service.yaml`; the S03-T04 test enforces this.
  3. **Empty outbox.** Define the empty-outbox value (zero age or absent) and record it in D03-7, so the payout-run freshness sum in S05-T10 is well defined.
  4. **Scope.** Don't compute freshness here. The ledger freshness endpoint is S04-T04's, and the payout-run composition is S05-T10's.
- **Edge cases and failure behavior:**
  - **Empty outbox.** Returns the recorded empty value.
  - **Large backlog.** The count must stay cheap. If it's slow, consider a capped count, and record the choice in D03-7.
  - **Database unavailable.** Returns the service-unavailable problem, never a cached or default value. Freshness consumers must fail closed, not treat an error as fresh.
  - **Clock skew.** Not a concern: age uses the database clock.
- **Outputs:** planned:
  - Stats query in `libs/outbox`.
  - `services/order-service/src/main/java/…/api/OutboxStatsController.java`.
  - The OpenAPI operation.
  - Test `OutboxStatsIT`.
- **Verification and definition of done:**
  - **`OutboxStatsIT` passes:**
    - an empty outbox returns the recorded empty value;
    - rows inserted with controlled creation times return the exact count and an age at least the inserted age;
    - after the relay drains, the empty value returns;
    - a reader token succeeds, and a missing token is rejected;
    - a stopped database gives the service-unavailable problem.
  - **`OpenApiSpecConsistencyTest`** passes.

<a id="phase-4"></a>
### Phase 4 — Payment-event mapping

**Objective:** payment facts become money orders at the single validation point, exactly once per event.

**Exit checkpoint:**
- Every mapping row in [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order) has a passing unit test, or a recorded blocker.
- The consumer redelivery and poison tests pass.
- ADR-0006 is committed.
- Change detection has been re-run before handoff.

<a id="s03-t07"></a>
#### S03-T07 — Payment-event mapper and order-mapper consumer
- **Outcome:** a pure mapping function covers every row of the master mapping table. A Kafka consumer turns payment events into money orders through the idempotent store, with `event_id` as the idempotency key, and publishes each order through the outbox.
- **Estimate:** 4 h
- **Inputs:**
  - Master:
    - [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order);
    - payment event, settlement fields and consumer settings in [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts);
    - design rule 3 in [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components);
    - ADR-0006 in [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions);
    - idempotency and retries rows in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting);
    - W1 in [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows);
    - [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers);
    - I8 in [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants).
  - Master v1.2: §0.3 C5–C8, C25 and E1 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications).
  - Upstream decisions: D01-5, D01-6, D01-8, D01-9, D01-10, D02-9, D00-9.
  - This step: D03-3, D03-4, D03-5.
- **Depends on:** S03-T01, S03-T02, S03-T05
- **Instructions:**
  1. **Mapper.** A pure function from a schema-valid payment event to either `OrderDraft` or `NoOrder(reason)`.
     - The effective time comes from the event's occurrence time, never a clock.
     - The order group comes from the event.
     - Type, reason and entries come from the matching master row, with account codes and entity kinds from D01-6 and the ADR-0003 sign convention.
  2. **Declined charges.** Map them to `NoOrder` and increment the declined-charges counter named in the master row.
  3. **Settlement events (§0.3 C6, E1).** There is one event per provider, report and currency, and its `event_id` is also the order group. Check net + fee = gross before mapping; a mismatch is poison. Book the report's totals as reported: residuals are reconciliation breaks, never mapper adjustments.
  4. **No-order and unknown events (§0.3 C5).** `CHARGE_DECLINED`, `REFUND_FAILED` and `PAYOUT_REJECTED` map to an explicit `NoOrder`. Types the D01-8 schema doesn't define are poison. Record the complete list in D03-6.
  5. **`PAYOUT_FAILED` (§0.3 C5).** Distinct event types resolve the former ambiguity. `PAYOUT_FAILED` is emitted only after `PAYOUT_ACCEPTED` and maps to the reversal row; a decline at submission arrives as `PAYOUT_REJECTED` and creates no order. The mapper stays a pure function of the event type.
  6. **Consumer (per record).**
     1. Validate against the D01-8 payment-event schema.
     2. Map.
     3. For `NoOrder`, acknowledge.
     4. Otherwise, call the S03-T01 store with the internal mapper principal (source system `instrument-service`, D03-4, §0.3 C7) and `event_id` as the key. In the same transaction, append the outbox row (S03-T05 path).
     5. Commit, then acknowledge manually.

     How to handle the store's outcomes:
     - **Replayed:** a duplicate event. Acknowledge.
     - **KeyReused:** the same event id with different content, which is an integrity breach. Treat it as poison and log it at error level.

     Topic, group id and consumer settings are provisional, traced to D03-6 until S04-T01 records D04-1 and D04-2.
  7. **Validation.** Mapped drafts pass through the same D01-5 and D01-6 validation as API orders. A failure means a mapper bug or a bad event, and is treated as poison.
  8. **Poison handling (policy per D04-4; §0.3 C8, C25).**
     - Classify poison as non-retryable in the Spring Kafka error handler.
     - Dead-letter it to the DLQ topic following D04-4 (the master naming convention is provisional until S04-T03 records it).
     - Write a row to order-service's own quarantine table (shape per D02-9: surrogate key, nullable order id, Kafka topic, partition and offset unique together). Insert with do-nothing-on-conflict, so a redelivered poison record is quarantined once.
     - Transient database errors retry a bounded number of times, then the listener container pauses. Never skip.
  9. **Listener mode.** Use a single-record listener, not batching. Mapper throughput isn't a gate.
  10. **Tests and topics.** Tests create their topics through the Testcontainers admin client; provisioning belongs to S04-T01.
  11. **ADR-0006.** Write it per D00-9: order-service is the single writer; other stages publish facts; the G2 alternative (S05-C01) is recorded as the fallback.
- **Edge cases and failure behavior:**
  - **Duplicate delivery.** An event delivered repeatedly produces exactly one order (I8).
  - **Same event id, different amount.** KeyReused, handled as poison.
  - **Out-of-order events** (for example, payout settled arriving before payout accepted). Each order is independently zero-sum, and balance arithmetic is commutative (ADR-0007), so the mapper doesn't enforce sequence.
  - **Settlement mismatch.** The event goes to the DLQ with a quarantine row, and the partition keeps processing.
  - **Currency outside the allow-list.** Validation fails and the event is poison.
  - **Crash after commit, before acknowledgement.** The event is redelivered, returns Replayed, and is acknowledged.
  - **Database down.** Bounded retries, then pause. Lag grows, and nothing is skipped.
  - **Mapper-only order types.** Order types only the mapper produces can't arrive through the API (S03-T03).
- **Outputs:** planned:
  - `services/order-service/src/main/java/…/mapping/PaymentEventMapper.java`.
  - `…/consumer/PaymentEventListener.java`.
  - Order-service quarantine table migration (D03-1).
  - `docs/adr/0006-*.md`.
  - Tests `PaymentEventMapperTest` and `PaymentEventConsumerIT`.
- **Verification and definition of done:**
  - **`PaymentEventMapperTest` (unit tag).**
    - One test per master mapping row. For every non-COMMERCE golden order in D01-9, a constructed event reproduces its type, reason and entries exactly.
    - `CHARGE_DECLINED`, `REFUND_FAILED` and `PAYOUT_REJECTED` give `NoOrder`; `PAYOUT_FAILED` gives the reversal order; a settlement mismatch and an unknown type give poison.
    - A seeded generative test (D01-10) over random valid events checks that every draft passes ZeroSumValidator, printing the seed.
  - **`PaymentEventConsumerIT`** (PostgreSQL and Kafka Testcontainers).
    - Each event is published three times, and exactly one order and one outbox row exist per event id.
    - After the consumer group's offsets are reset to the beginning and the context restarts, no new orders appear.
    - A poison event produces a DLQ record and one quarantine row, redelivering it adds no second row (§0.3 C25), and the next event on the same partition is still processed.
    - Produced outbox payloads validate against D01-8.
  - **ADR-0006** is committed.
  - **All rows implemented.** Every mapping row, including the v1.2 no-order rows, has a passing test; no row is Blocked (§0.3 C5).

<a id="conditional-work"></a>
### Conditional and deferred work

**Conditional tasks.** S03 has no conditional tasks of its own. Three alternatives owned by other steps can reopen S03 decisions through [docs/README.md#conflict-resolution](README.md#conflict-resolution). Each is triggered by evidence and funded from unallocated contingency by its owning task:

| Trigger (evidence) | Owning task | S03 decisions affected |
|---|---|---|
| Relay lag misses the SP2 criterion after tuning | [docs/step_04_kafka_pipeline.md#s04-c01](step_04_kafka_pipeline.md#s04-c01) | D03-5 (relay replaced), D03-7 (stats semantics) |
| Ordering problems with ADR-0006 in W1–W4 e2e at G2 | [docs/step_05_instruments_fake_providers.md#s05-c01](step_05_instruments_fake_providers.md#s05-c01) | D03-6 (consumer retired or narrowed); validation extracted into a shared library |
| Ablation A2 and A4 seams are required | [docs/step_08_fault_injection_ablation.md#s08-t03](step_08_fault_injection_ablation.md#s08-t03) | D03-1, D03-2, D03-5 |

If the minimum cut is invoked ([docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut)), S03-T04 switches to a generated specification. The step's hours then follow the master cut table, and the change is recorded in H.5 and I.2.

**Deferred improvements** (not built in the MVP)

- **Debezium CDC with the Outbox Event Router** in place of polling. Deferred per [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred); the upgrade trigger is in [docs/zerosum_ledger_mvp_plan.md#scaling-triggers](zerosum_ledger_mvp_plan.md#scaling-triggers).
- **Protobuf/Avro payloads with a schema registry** instead of JSON. See [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred).
- **Table partitioning and archival** for the order tables. See [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred).
- **Multi-instance relays with leader election, and Kubernetes.** See [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred) and the trigger in [docs/zerosum_ledger_mvp_plan.md#scaling-triggers](zerosum_ledger_mvp_plan.md#scaling-triggers).
- **A synchronous in-session payment path** next to the asynchronous API. See [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred).
- **The `demo-public` single rate-limited writer token.** Should-have S5 in [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have), executed only through S09-C01.
- **OAuth2/OIDC, mTLS and token rotation audit.** Production-only per [docs/zerosum_ledger_mvp_plan.md#mvp-vs-production](zerosum_ledger_mvp_plan.md#mvp-vs-production); related deferrals are in [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred).

<a id="risks-and-recovery"></a>
## F. Risks and recovery

| Risk | Detection (signal or test) | Recovery / fallback |
|---|---|---|
| **Concurrent same-key requests create two orders or deadlock.** Correctness risk R2 in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) | `OrderStoreIT` and `MoneyOrderApiIT` concurrency scenarios (M3(c)); first-writer-rollback test | Rely only on the unique constraint with a conflict-aware insert and a bounded lock wait. Never add an application-level pre-check. |
| **Canonicalization changes turn legitimate retries into key-reuse errors.** Keys never expire. | Pinned vectors in `RequestHasherTest` fail after a dependency upgrade | Keep the version tag. Compare using the stored version. Never rewrite stored hashes (append-only). |
| **An order without entries, or with a per-currency imbalance, commits** because the proposed trigger misses it | `OrdersImmutabilityIT` header-only and per-currency cases | Header-level deferred constraint trigger (S03-T02; in the master DDL since v1.2, §0.3 C3). |
| **Two relay instances reorder messages for the same key** (the master's S03 risk) | `RelayGuardIT`; guard-skip counter above zero in a running stack | Single replica in compose (D00-3) plus the advisory-lock guard. Upgrade path is SP2/Debezium (S04-C01). |
| **A poison outbox row blocks every later row** (head-of-line blocking) | Oldest-unpublished-age gauge grows while send failures repeat for one row id; outbox backlog alert (S07-T03) | Stop, diagnose the row, and fix forward (repair the configuration, or issue a compensating order). Never delete or skip an unpublished row. |
| **Partial batch failure resends records**, so a consumer sees a later record for a key before an earlier one | `RelayDuplicateIT`; S04-T05 relay-crash e2e | This is designed-in. Consumers dedupe, and ledger arithmetic is commutative (ADR-0007). |
| **Kafka is down for a long time**, so the outbox grows and freshness goes stale | Stats endpoint count and age; outbox backlog alert; payout runs refuse (S05-T10) | Relay backs off. The API keeps accepting orders ([docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded)). Watch database disk. |
| **The M4(a) crash test is timing-flaky in CI** | Intermittent failures of `OrderPublishAfterCrashIT` | Treat it as a product bug until root cause is known ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)). Make the crash deterministic by pausing Kafka before creating orders. |
| **Tokens leak through logs or problem responses** | Log-redaction test; `ProblemMappingTest` asserts no header echo | Fix the redaction filter before merge. Rotate local tokens per [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets). |
| **The mapper drifts from the v1.2 event types** (for example, treating a submission decline as `PAYOUT_FAILED`) | `PaymentEventMapperTest` rows for every event type | Follow §0.3 C5 and the D01-8 schema; fix the mapper, never the event. |
| **Mapper poison handling diverges from S04's error policy** | S04-T03 records D04-4 differently from the provisional D03-6 handling; change detection flags it | Mark S03-T07 Needs review, adopt D04-4, and revalidate `PaymentEventConsumerIT`. |
| **Ledger-service read APIs stay unauthenticated** (TB1) | `LedgerAuthWiringIT` (S03-T03) | S03-T03 wires `libs/auth` into ledger-service (§0.3 C9); fix the wiring before handoff. |
| **Extra dependencies** (canonical JSON, OpenAPI tooling, OTel API) conflict with pinned versions. Framework churn R8 | Build failure or a dependency not present in D00-1 | Prefer built-in Spring, Jackson and JDK facilities. Otherwise raise a D00-1 change request before adding anything. |
| **Scope creep pulls S04 or S08 work into this step.** Capacity risk R11 | Hours in I.1 exceed the task estimates | Stop at the task boundary. Record the overrun and leave later-step work to its owner. |

<a id="acceptance-checklist"></a>
## G. Acceptance checklist

- [ ] M2(a): `UPDATE`, `DELETE` and `TRUNCATE` on order tables fail for the app and owner roles ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)); evidence recorded in H.4.
- [ ] M2(b): an unbalanced insert that bypasses the application fails at `COMMIT`. Header-only and per-currency imbalance cases also fail.
- [ ] M2(c): an adjustment must reference an existing order, and the same-group check (§0.3 C2) is tested.
- [ ] M3(a)–(e) pass through the HTTP API exactly as worded in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have), including the concurrent same-key scenario at the M3(c) request count.
- [ ] M4(a): **deferred, not met.** `RelayRestartRecoveryIT` publishes every row committed during an outage from a freshly built relay in 148 ms (bound 5 s), which proves the mechanism but not a process SIGKILL. The container test is deferred under [docs/scope-decisions.md](scope-decisions.md).
- [x] M4(b): the ArchUnit publish-path rule passes, and its self-test proves it can fail. It failed against real wiring on its first run; the wiring moved rather than the rule weakening.
- [x] M4(c), relay side: duplicates after a failed mark are byte-identical in key, payload and order id (`RelayDuplicateIT`). The downstream harmlessness part is tracked by S04-T05 and noted in J.
- [ ] TB1 controls from [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) are tested: constant-time token check, role per endpoint, body cap, unknown and duplicate fields rejected, source system derived from the principal. `libs/auth` also protects ledger-service read APIs with 401/403 (§0.3 C9).
- [ ] Every master mapping row in [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order) (including the v1.2 no-order rows, §0.3 C5) has a unit test ([docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) unit layer).
- [ ] The consumer creates exactly one order per `event_id` under repeated delivery, preserving the I8 convention ([docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants)).
- [ ] Every stored, fetched and published money order, and every consumed payment event, validates against the D01-8 schemas (contract layer).
- [ ] The outbox stats endpoint returns the D03-7 semantics and fails closed when the database is unavailable.
- [ ] `openapi/order-service.yaml` passes the consistency test.
- [ ] ADR-0006 and ADR-0008 are committed per D00-9.
- [ ] The log-redaction test and the D01-11 ArchUnit money rules pass for order-service and `libs/outbox`.
- [ ] Unit and integration CI jobs (D00-5) are green on the handoff commit.
- [ ] No gate is claimed for S03. P1 compliance ([docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go)) is **not** asserted here; S07 measures it.
- [ ] H.1–H.6 filled; I.1 and I.2 current; change detection re-run before handoff.

<a id="decisions-and-outputs"></a>
## H. Decisions and outputs register

### H.1 Decisions and rationale

| ID | Decision | Rationale | Alternatives considered | Status | Date |
|---|---|---|---|---|---|
| D03-1 | **Schema** `services/order-service/src/main/resources/db/migration/V2__orders_schema.sql`, after the D00-4 empty baseline and additive only. `money_orders` holds the header, source, request hash, metadata and times; `money_order_entries` holds signed lines keyed `(order_id, line_no)`, so the same entity/account/currency may repeat across lines (§0.3 C6). **Identifiers: the database assigns them.** `order_id uuid DEFAULT uuidv7()` and `created_at DEFAULT now()`, returned by `RETURNING`. PostgreSQL 18 ships `uuidv7()`, so ids are time-ordered with good index locality, and — the deciding reason — the store never mints a candidate id it would have to discard when the insert conflicts, so a replay returns the **original** id and timestamp rather than freshly generated ones. **Deviation from the master's proposed DDL:** a `request_hash_version smallint NOT NULL` column is added beside `request_hash`. S03-T01 requires a replay to be compared using the version the row was written under, and a version tag that exists only inside the digest cannot be read back to choose the comparison. **Adjustments (§0.3 C2):** the foreign key proves existence; the same-group rule is checked in the application inside the insert transaction, which a foreign key cannot express. **Grants:** `orders_app` keeps only SELECT and INSERT; UPDATE, DELETE and TRUNCATE are revoked, and `ALTER DEFAULT PRIVILEGES` narrows future orders tables the same way, following the CR-S02-01 precedent accepted for the ledger database. Triggers that also stop the **owner** are S03-T02's, because privileges alone leave the owner able to mutate (M2 (a)) | A replay must be indistinguishable from the original response, which database-assigned identity gives for free; storing the hash version makes the comparison rule explicit rather than implied | Application-generated ids (the store would discard a minted id on conflict, and a replay would have to re-read to avoid returning the wrong one); a version tag only inside the hashed bytes (unreadable at comparison time); expressing the same-group rule as a foreign key (not expressible); leaving D00-4's default DML privileges in place (the runtime role could mutate immutable orders) | Accepted | 2026-09-16 |
| D03-2 | **Contract** `POST /v1/money-orders` (writer), `GET /v1/money-orders/{order_id}` and `GET /v1/money-orders?group_id=` (reader). Errors are RFC 9457 problem details with a stable `code`, produced by one `@RestControllerAdvice`: `unauthorized` (401), `forbidden` (403), `idempotency_key_missing` (400), `validation_failed` (422), `not_zero_sum` (422), `idempotency_key_reused` (422), `unknown_adjusted_order` (422), `idempotency_key_in_progress` (409), `order_not_found` (404), `database_unavailable` (503) and `internal_error` (500) — the last carrying no stack trace and no echo of the request. A replay returns 200 with `Idempotent-Replayed: true` and a body identical to the original; a creation returns 201. **Request parsing (TB1):** unknown fields are rejected rather than ignored, so a body carrying a server-assigned `order_id`, `source` or `created_at` fails instead of appearing to have set it; duplicate keys are rejected so the hashed request cannot be ambiguous; a float or string is never coerced into an integer amount. **Idempotency key** comes from the `Idempotency-Key` header (§0.3 C1), capped at 255 characters **Specification (S03-T04): approach (a)**, hand-written `openapi/order-service.yaml` with an `OpenApiSpecConsistencyTest` that compares it against Spring's registered handler map in both directions and against the `ApiException.Code` enum, which is now the single home for problem codes so the advice and the specification cannot drift apart. Approach (b), generation from annotations, needs a new dependency and therefore a D00-1 change request first. **This is a considered divergence from the minimum cut's "OpenAPI generated only" wording**: the cut exists to reduce effort, and generating would add a dependency and a change request to produce the same committed artefact, so the cheaper path is the hand-written file plus the drift test. Recorded rather than applied silently. The outbox stats operation is deliberately absent until S03-T06, and an assertion fails once that endpoint exists, which forces the specification to be updated in the same change | One advice and one code set means a caller branches on `code` alone; rejecting rather than ignoring unknown input is what stops a caller believing it set a field it did not | Ignoring unknown fields (Boot's default: a client could think it had chosen an order id); a 500 for a commit-time zero-sum rejection (it is a client error); returning 200 for a creation (a caller could not tell a create from a replay without the header) | Accepted | 2026-09-16 |
| D03-3 | **Canonical form version 1**, specified in `RequestHasher`: SHA-256 over, in fixed order, the form version as one byte; the source system; the idempotency key; the type; the reason; the order group; a presence byte for the adjusted order plus its 16 bytes; the effective time as 8 bytes of epoch seconds and 4 of nanos; the entry count, then each entry in order as entity id, account, currency and an 8-byte signed amount; and the metadata as canonical JSON with sorted keys, preserved array order and integral numbers written without a fraction. Every string is length-prefixed UTF-8, so no field boundary is ambiguous. The form is built from the **parsed** request, never raw bytes, so key order and whitespace are irrelevant; absent, `null` and `{}` metadata are one representation; entry order is significant because line numbers are part of the order; and server-assigned fields are never hashed. **No new dependency:** this is a binary form rather than RFC 8785 JCS, satisfying the same requirements with no JSON-escaping ambiguity, so D00-1's pins are untouched and no change request was needed — S03-T01 instruction 5 only requires one if a library is added. It follows the D02-6 hasher already proven here against an independent implementation. **Semantics:** CREATED on a fresh key; REPLAYED when the stored hash matches, returning the original order; KEY_REUSED when it does not; IN_PROGRESS when the transaction-local `orders.store.lock-timeout` (2 s) expires waiting on a concurrent holder of the same key. Uniqueness is scoped `(source_system, idempotency_key)`, so the same key from two principals is two distinct orders. Keys never expire (M3 (e)), so the form can never change in place: a new version gets a new number, and a row is always compared under the version it was written with | Hashing the parsed request is what makes a legitimate retry survive a serializer change; a stored version number makes that guarantee checkable rather than hoped for | RFC 8785 via a new library (a change request against D00-1 for no benefit the binary form lacks); hashing raw request bytes (whitespace or key order would turn a retry into a 422); omitting the version (a future form change would silently reinterpret stored hashes) | Accepted | 2026-09-16 |
| D03-4 | **Module** `libs/auth`, consumed by order-service **and** ledger-service (§0.3 C9). Static bearer tokens per role from the D00-8 environment variables `ZS_WRITER_TOKENS`, `ZS_READER_TOKEN` and `ZS_ADMIN_TOKEN`, with no defaults, so a service started without them authenticates nobody — it fails closed. Writer tokens are `system:token` pairs, so **`source.system` is derived from the token and never from a request body** (D01-5 rule 8); a malformed pair fails at startup rather than being skipped silently. Comparison uses `MessageDigest.isEqual` and **checks every configured token** instead of returning on the first match, so timing cannot reveal how far down the list a presented token matched. **Role hierarchy, deliberately narrow:** writer and admin both satisfy reader endpoints, since both are strictly more privileged; nothing else implies anything, so a writer cannot reach admin operations and an admin cannot create money. **Design:** a plain servlet filter that only *authenticates* and attaches a `Principal`; each endpoint checks the role it needs, so authorisation is visible at the endpoint rather than in distant configuration, and unauthenticated paths such as actuator health keep working without an exception list. This also makes real the 401 and 403 that `openapi/ledger-service.yaml` had only declared | A security framework is not pinned in D00-1, and static per-role tokens need nothing more; deriving the source system from the principal is what makes idempotency scoping trustworthy | Spring Security (a dependency and a change request to D00-1 for no capability this needs); rejecting in the filter (every unauthenticated path would need listing there); short-circuiting token comparison (leaks match position through timing); a flat role model (a writer token would reach admin operations) | Accepted | 2026-09-16 |
| D03-5 | **Table: candidate A ownership** — each producing service keeps its own copy of the outbox migration (`services/order-service/src/main/resources/db/migration/V4__outbox.sql`), and a `libs/outbox` test fixture asserts the required columns and the partial index. Candidate B (a library-shipped migration location) would put library-versioned migrations into every service's shared Flyway history, where versions collide as soon as two services adopt different library versions; a copied table is cheaper to keep honest than a shared version space is to keep collision-free. **The outbox is deliberately not append-only** (D02-2 is applied to the order tables only): the relay marks rows published and cleanup deletes them, so `orders_app` is granted UPDATE and DELETE on this table alone. **Writer:** `append(topic, messageKey, payload, headers)` returning the row id, `@Transactional(MANDATORY)` so a caller without a transaction gets an exception rather than a row that might commit alone — enforcement needs a Spring-managed bean, so the test builds one through a real context rather than `new`. Payloads arrive already serialized; the relay never re-serializes. **Relay:** one transaction per batch — advisory lock, select unpublished in id order with `FOR UPDATE SKIP LOCKED`, send, await every acknowledgement, mark, commit. **Guard:** `pg_try_advisory_xact_lock` on key `0x7A530B01`, transaction-scoped so a crashed relay never leaves it held; a skipped batch increments a counter. `SKIP LOCKED` is for liveness only and does **not** preserve per-key order across two relays. **Backoff:** jittered doubling from `backoff-min` to `backoff-max`, retried indefinitely — the relay never skips a row, because a skipped row is committed money that never arrives. The loop sleeps only when the last batch was not full, so a backlog drains at full speed. **Publish timestamp:** `clock_timestamp()`, not `now()`, which is the transaction start time and would understate publish lag by the whole send. **Trace context** (§0.3 C11) is captured into the row's headers at append time, because the relay sends from another thread where the originating trace is gone. **Cleanup:** scheduled, bounded by `cleanup-batch-size`, own transaction, published rows past retention only, never an unpublished row however old. **Metrics** (D00-6, plain Micrometer): `outbox_publish_lag_seconds`, `outbox_oldest_unpublished_seconds`, `outbox_batch_size`, `outbox_send_failures_total`, `outbox_guard_skips_total`. The oldest-age gauge reads the database at scrape time rather than being set by the relay, so it stays truthful while the relay is stuck — which is when it matters. **Shutdown:** the loop stops and the in-flight batch commits or rolls back within the graceful budget; rows are never marked without acknowledgement. **Properties carry no library defaults**, so a deployment cannot silently inherit one. **Publish-path rule (M4(b))** ships as a test fixture. On its first run it failed against my own `OrderOutboxConfiguration`, which took a `KafkaTemplate` parameter to build the relay bean. Rather than carve out "wiring doesn't count" — indistinguishable from a real publish path later — construction moved into `OutboxFactory` inside the library, so no order-service class names a Kafka producer type at all | The outbox's whole value is that the row and the order commit together; every choice here protects either that atomicity or the per-key ordering the ledger depends on | Marking before acknowledgement (turns a broker failure into silent loss); `now()` for the publish timestamp (understates lag by the send duration); session-scoped advisory lock (a crashed relay holds it until its connection is reaped); several relays with row locks alone (breaks per-key order); library-shipped migration (Flyway version collisions across services); reading trace context at send time (the originating trace is gone by then); relaxing the ArchUnit rule to permit wiring (the carve-out would cover a real send) | Accepted | 2026-09-16 |
| D03-6 | — | — | — | Pending | — |
| D03-7 | — | — | — | Pending | — |

### H.2 Implementation and configuration locations

| Item | Planned path | Actual path | Traced to |
|---|---|---|---|
| Orders schema migrations (tables, uniqueness, triggers, grants) | `services/order-service/src/main/resources/db/migration/` | `V2__orders_schema.sql` (tables, idempotency uniqueness, runtime grants). The deferred zero-sum and append-only triggers arrive with S03-T02 | D03-1 |
| Idempotent order store and request hasher | `services/order-service/src/main/java/…/order/` | — | D03-3 |
| REST controllers, DTOs, problem advice | `services/order-service/src/main/java/…/api/` | — | D03-2 |
| Shared token-auth module (also wired into ledger-service) | `libs/auth/` (§0.3 C9) | — | D03-4 |
| Token and principal configuration | `services/order-service/src/main/resources/application.yaml`, `.env.example` | — | D03-4 |
| Body cap, key length, timeouts, pool | `services/order-service/src/main/resources/application.yaml` | — | D03-2, D03-3 |
| Outbox writer, relay, guard, cleanup, metrics, stats query | `libs/outbox/src/main/java/…` | `libs/outbox/src/main/java/dev/zerosum/outbox/{OutboxWriter,OutboxRelay,OutboxRelayLoop,OutboxCleanupJob,OutboxMetrics,OutboxProperties,OutboxRecord,OutboxFactory}.java`. The stats query is S03-T06's | D03-5, D03-7 |
| Outbox table migration | Per D03-5 (service migration directory or library location) | `services/order-service/src/main/resources/db/migration/V4__outbox.sql` (candidate A: the service owns its copy) | D03-5 |
| Relay and provisional producer properties | `services/order-service/src/main/resources/application.yaml` | `services/order-service/src/main/resources/application.yml` (`zs.outbox.*`, `spring.kafka.producer.*`) | D03-5 |
| Publish-path ArchUnit rule and table-shape fixture | `libs/outbox` test-fixture source set (per D00-10) | `libs/outbox/src/testFixtures/java/dev/zerosum/outbox/arch/{OutboxArchRules,OutboxTableAssertions}.java`, applied by `services/order-service/src/test/java/dev/zerosum/order/arch/PublishPathArchitectureTest.java` | D03-5 |
| Outbox stats controller | `services/order-service/src/main/java/…/api/` | — | D03-7 |
| Payment-event mapper | `services/order-service/src/main/java/…/mapping/` | — | D03-6 |
| Order-mapper consumer, provisional consumer and poison settings | `services/order-service/src/main/java/…/consumer/`, `application.yaml` | — | D03-6 |
| Order-service quarantine table migration (§0.3 C8, C25) | `services/order-service/src/main/resources/db/migration/` | — | D03-1, D03-6 |

### H.3 Produced artifacts

| Artifact | Planned path | Actual path | Revision/hash |
|---|---|---|---|
| Order-service OpenAPI specification | `openapi/order-service.yaml` | — | — |
| ADR-0006 single writer of money orders | `docs/adr/0006-*.md` | — | — |
| ADR-0008 polling outbox, one relay per service | `docs/adr/0008-*.md` | [docs/adr/0008-polling-outbox-single-relay.md](adr/0008-polling-outbox-single-relay.md) | — |
| Outbox library | `libs/outbox/` | `libs/outbox/` (writer, relay, loop, cleanup, metrics, factory, fixtures) | — |
| Shared auth module | `libs/auth/` | — | — |
| Order-service application | `services/order-service/` | — | — |
| CI test reports for S03 tests | CI artifact per D00-5 | — | — |

### H.4 Validation results and evidence

| Check | Method | Result | Evidence path | Date |
|---|---|---|---|---|
| Request-hash canonicalization vectors | `RequestHasherTest` (unit) | **Passed** (6 tests): reordered metadata keys, whitespace and `2000` vs `2000.0` all give the same hash; absent, `null` and `{}` metadata are one representation; reordered entries give a **different** hash; the form version is asserted structurally — `canonicalBytes(order, version)[0]` is the version, versions 1 and 2 give different bytes of equal length, and `hash(order)` is exactly the SHA-256 of those bytes. A sixth vector asserts every client-supplied input changes the digest, which catches a field being dropped from the form | [docs/results/s03/s03-t01-order-store.txt](results/s03/s03-t01-order-store.txt) | 2026-09-16 |
| Idempotent store outcomes and concurrency | `OrderStoreIT` (integration) | **Passed** (9 tests). A replay returns the **original** `order_id`, `created_at` and entries; a different body returns KEY_REUSED with no order; the same key from two principals creates two orders. **M3 (c):** 50 concurrent callers on one key gave `created=1 replayed=49 inProgress=0`, exactly one row (seed `-21388374799318065`). **`inProgress=0` means the 2 s lock-wait bound was never reached, so the IN_PROGRESS path is implemented and reachable but not evidenced by this run** — S03-T03 can provoke it through the API. First-writer-rollback: a holder takes the key and rolls back, and the waiter then CREATES (the rollback is explicit, not via the S03-T02 trigger, which does not exist yet). An unknown adjusted order and one in another group each raise their own error and write nothing. Flyway validates and re-running executes 0 | [docs/results/s03/s03-t01-order-store.txt](results/s03/s03-t01-order-store.txt) | 2026-09-16 |
| Immutability and commit-time zero-sum | `OrdersImmutabilityIT` (integration) | **Passed** (19 tests). UPDATE and DELETE on both order tables, and TRUNCATE on the entries table, all fail with 42501 for **both** `orders_app` and `orders_owner`. Truncating both tables together — the only form the foreign key would otherwise permit — is rejected by the statement trigger for both roles; truncating the header alone is refused by the foreign key first, which the test attributes to that mechanism rather than to the trigger. Raw inserts behind the store fail at COMMIT with 23514 for an unbalanced order, a header with no entries (§0.3 C3) and a per-currency imbalance that nets to zero overall. The store reports a commit-time rejection as `NOT_ZERO_SUM`, and the D01-9 COMMERCE goldens commit | [docs/results/s03/s03-t02-immutability.txt](results/s03/s03-t02-immutability.txt) | 2026-09-16 |
| M2(a)–(c) immutability and zero-sum at `COMMIT` | `OrdersImmutabilityIT` | **Passed** (19 tests): UPDATE and DELETE on both order tables, and TRUNCATE on the entries table, fail with 42501 for **both** roles; truncating both tables together is stopped by the statement trigger, while truncating the header alone is refused by the foreign key first — attributed separately rather than both credited to the trigger. Raw inserts behind the store fail at COMMIT with 23514 for an unbalanced order, a header with no entries (§0.3 C3) and a per-currency imbalance that nets to zero overall. M2 (c) unknown adjusted order rejected | [docs/results/s03/s03-t02-immutability.txt](results/s03/s03-t02-immutability.txt) | 2026-09-16 |
| Token auth and problem mapping | `TokenAuthTest` (16 tests); problem mapping via `MoneyOrderApiIT` | **Passed.** Writer tokens carry their source system, reader and admin carry none, near-miss tokens authenticate nobody, and the role hierarchy is narrow: writer and admin satisfy reader, nothing else implies anything. Malformed `system:token` configuration fails fast. **Deviation:** the planned separate `ProblemMappingTest` is not written; the outcome-to-code mapping is asserted through HTTP instead, as seven parameterised malformed-body cases each naming their expected code, which exercises the advice as wired | [docs/results/s03/s03-t03-api-auth.txt](results/s03/s03-t03-api-auth.txt) | 2026-09-16 |
| M3(a)–(e) through HTTP; goldens accepted | `MoneyOrderApiIT` | **Passed** (15 tests): (a) replay is byte-identical with `Idempotent-Replayed: true`, and a body differing only in whitespace or key order also replays; (b) 422 `idempotency_key_reused`; (c) 20 concurrent callers leave exactly one row, the rest 200 or 409; (d) 400 `idempotency_key_missing`; (e) the money-order row is itself the idempotency record, so no expiry job exists. A 422 validation failure does not consume the key. The D01-9 COMMERCE goldens are accepted and each fetched order validates against the D01-8 schema | [docs/results/s03/s03-t03-api-auth.txt](results/s03/s03-t03-api-auth.txt) | 2026-09-16 |
| Ledger-service endpoints protected by `libs/auth` (§0.3 C9) | `LedgerAuthWiringIT` | **Passed** (4 tests): 401 without a token and with an unknown token; 200 with a reader, writer or admin token. Wiring the filter alone was not enough — neither ledger controller checked a principal, so explicit role checks were added at each endpoint. This closes the S02 H.5 limitation that the declared 401/403 were not enforced | [docs/results/s03/s03-t03-api-auth.txt](results/s03/s03-t03-api-auth.txt) | 2026-09-16 |
| Log redaction covers order-service | `LogRedactionTest` (written fresh) | **Passed** (2 tests): `TokenRegistry` and `Principal` keep tokens out of `toString()`, and a rejected token is not echoed into the logs. **The instruction says to *extend* the D00-8 redaction test, but no such test existed anywhere in the repository**, so this is new rather than an extension. It cannot prove future code will not log a secret | [docs/results/s03/s03-t03-api-auth.txt](results/s03/s03-t03-api-auth.txt) | 2026-09-16 |
| OpenAPI matches handlers and codes | `OpenApiSpecConsistencyTest` | **Passed** (5 tests): every registered handler under `/v1` has an operation and every operation has a handler, compared in **both** directions; the `Problem.code` enum matches `ApiException.Code` exactly, which is now the single home for problem codes so the advice and the specification cannot drift; the file parses as OpenAPI 3.1 and declares the bearer scheme, the `Idempotency-Key` request header and the `Idempotent-Replayed` response header; every `$ref` resolves internally, so the file stands alone. A deliberate assertion fails once `GET /v1/outbox/stats` exists, forcing S03-T06 to document it in the same change | [docs/results/s03/s03-t04-openapi.txt](results/s03/s03-t04-openapi.txt) | 2026-09-16 |
| Outbox writer transaction requirement | `OutboxWriterIT` | **Passed** (4 tests): append without a transaction throws, a rollback leaves no row, a commit leaves an unpublished row with its headers, and the table-shape fixture passes against order-service's real migration. Built through a real Spring context, since `@Transactional(MANDATORY)` is proxy-enforced | [docs/results/s03/s03-t05-outbox.txt](results/s03/s03-t05-outbox.txt) | 2026-09-16 |
| Relay ordering, pause and drain | `OutboxRelayIT` | **Passed** (3 tests): 30 rows across 3 keys publish in id order per key on a 3-partition topic, marked only after acknowledgement; with the broker paused nothing is marked, the failure counter rises and the oldest-age gauge grows; the backlog drains on return. **Found a real defect:** `send()` had its send loop outside the try, so a synchronous `KafkaProducer.send` failure left `outbox_send_failures_total` at zero — the signal an operator alerts on | [docs/results/s03/s03-t05-outbox.txt](results/s03/s03-t05-outbox.txt) | 2026-09-16 |
| Relay duplicates are identical | `RelayDuplicateIT` | **Passed** (1 test): a test-scoped `JdbcTemplate` fails the mark once; rows are re-sent and the copies are byte-identical in key, payload and order-id header. The injection is test code — the relay ships no failure switch | [docs/results/s03/s03-t05-outbox.txt](results/s03/s03-t05-outbox.txt) | 2026-09-16 |
| Single-instance guard | `RelayGuardIT` | **Passed** (2 tests): a batch is skipped and counted while another connection holds the same advisory lock, taken directly so the skip is deterministic rather than a race; two relays over one database still preserve per-key order | [docs/results/s03/s03-t05-outbox.txt](results/s03/s03-t05-outbox.txt) | 2026-09-16 |
| Cleanup never deletes unpublished rows | `OutboxCleanupIT` | **Passed** (3 tests): published rows past retention go, rows inside retention stay, deletion is bounded by the cleanup batch size, and unpublished rows survive being backdated 30 days | [docs/results/s03/s03-t05-outbox.txt](results/s03/s03-t05-outbox.txt) | 2026-09-16 |
| M4(a) publish after SIGKILL | `OrderPublishAfterCrashIT` | **Deferred — not claimed.** `RelayRestartRecoveryIT` proves the recovery *mechanism*: 20 rows committed while the broker was paused were published by a freshly built relay in **148 ms** against the 5 s bound. It destroys the relay object, **not the process**, so it does not prove a SIGKILL loses nothing. The container-level test needs the first e2e harness (`e2eTest` selects no tests today) plus the full Compose stack, and is deferred under [docs/scope-decisions.md](scope-decisions.md) | [docs/results/s03/s03-t05-outbox.txt](results/s03/s03-t05-outbox.txt) | 2026-09-16 |
| M4(b) publish-path rule and self-test | ArchUnit test | **Passed** (2 tests): the rule passes on order-service and a deliberate violator makes it fail. **It caught real code on its first run** — my own `OrderOutboxConfiguration` took a `KafkaTemplate` parameter to build the relay bean. Construction moved into `OutboxFactory` inside the library rather than weakening the rule | [docs/results/s03/s03-t05-outbox.txt](results/s03/s03-t05-outbox.txt) | 2026-09-16 |
| Outbox stats semantics and fail-closed | `OutboxStatsIT` | Not run | — | — |
| Mapping rows, goldens, generative drafts | `PaymentEventMapperTest` | Not run | — | — |
| Consumer idempotency, poison path | `PaymentEventConsumerIT` | Not run | — | — |
| Produced and consumed payloads match D01-8 | Schema assertions in the tests above | Not run | — | — |

### H.5 Known limitations and blockers

| Item | Type (limitation/blocker) | Impact | Owner/next action |
|---|---|---|---|
| Append-only triggers stop DML, but not DDL by the table owner or a superuser trigger bypass | limitation | The owner can run `ALTER TABLE … DISABLE TRIGGER` or drop a trigger, and a superuser bypasses triggers entirely. Immutability guards against application and operator mistakes, not a malicious owner. Deliberately not tested: a test would only demonstrate that superusers are superusers | Same limitation as the ledger's (S02 H.5). Cross-store verification in S06 and the S09 release limitations |
| The deferred trigger runs once per entry row | limitation | At most 50 rows per order, so the cost is bounded, but it is per-row rather than per-order. The master's own note says to leave it | S07 measures P1; optimise only on that evidence |
| Kafka has no authentication (TB3) | limitation | Any container on the compose network can publish | Documented MVP limitation ([docs/zerosum_ledger_mvp_plan.md#mvp-vs-production](zerosum_ledger_mvp_plan.md#mvp-vs-production)); none in MVP |
| Static bearer tokens, no rotation audit | limitation | Token compromise requires manual rotation and restart | Master [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets); none in MVP |
| One relay instance per service (ADR-0008) | limitation | Relay throughput bounded by one poller | SP2 measurement in S04-T07; Debezium via S04-C01 |
| Published outbox rows are deleted after retention | limitation | A full ledger rebuild needs the unassigned replay tool | [docs/README.md#known-gaps](README.md#known-gaps) |
| Superuser or owner can disable triggers | limitation | Immutability relies on role discipline outside the app role | Documented; out of the MVP threat model |
| Ledger-service read APIs auth wiring | Resolved in master v1.2 (§0.3 C9) | S03-T03 wires `libs/auth` into ledger-service | — |
| Accepted-before-failed payout distinction | Resolved in master v1.2 (§0.3 C5) | Distinct event types make every mapping row implementable | — |
| Mapper poison-policy details follow D04-4 | limitation | The quarantine location is resolved (§0.3 C8, C25); DLQ naming and retry bounds may change when D04-4 is recorded | S04-T03; revalidate S03-T07 through change detection |

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
| S03-T01 | Done | `services/order-service/src/main/resources/db/migration/V2__orders_schema.sql`, `…/src/main/java/dev/zerosum/order/order/{NewOrder,RequestHasher,OrderStore,OrderStoreProperties}.java`, `application.yml` (`orders.store.lock-timeout`), `OrderServiceApplication` (`@ConfigurationPropertiesScan`), `build.gradle.kts` (libs/money, libs/contracts, Testcontainers), `…/src/test/java/dev/zerosum/order/{order/{RequestHasherTest,OrderStoreIT},support/OrderTestDatabase}.java` | [docs/results/s03/s03-t01-order-store.txt](results/s03/s03-t01-order-store.txt): 15 tests green | None. The IN_PROGRESS outcome is implemented but not yet evidenced by a test; S03-T03 provokes it through the API. The owner-level immutability triggers are S03-T02's |
| S03-T02 | Done | `services/order-service/src/main/resources/db/migration/V3__orders_immutability.sql`, `…/order/OrderStore.java` (`NOT_ZERO_SUM` outcome), `…/src/test/java/dev/zerosum/order/{order/OrdersImmutabilityIT,support/AppendOnlyAssertions}.java` | [docs/results/s03/s03-t02-immutability.txt](results/s03/s03-t02-immutability.txt): 34 order-service tests green | None. Owner-level DDL and superuser bypass are recorded in H.5 as out of the MVP threat model |
| S03-T03 | Done | `libs/auth/src/main/java/dev/zerosum/auth/{Role,Principal,TokenRegistry,TokenAuthFilter}.java`, `services/order-service/src/main/java/dev/zerosum/order/api/**` (controller, DTOs, problem advice, auth and strict-JSON configuration), ledger-service `api/{AuthConfiguration,LedgerAuthorization}.java` and role checks on its read endpoints, `.env.example` S03 token section, `gradle/libs.versions.toml` (`jakarta-servlet-api`, added under D00-1's pin-update policy) | [docs/results/s03/s03-t03-api-auth.txt](results/s03/s03-t03-api-auth.txt): 37 tests across the API, auth, ledger wiring and redaction | Two deviations recorded there: `ProblemMappingTest` is covered through HTTP in `MoneyOrderApiIT` rather than as a separate unit test, and the D01-11 ArchUnit rules are not yet applied to order-service |
| S03-T04 | Done | `openapi/order-service.yaml`, `services/order-service/src/test/java/dev/zerosum/order/api/OpenApiSpecConsistencyTest.java`, `…/api/ApiException.java` (problem codes moved into one `Code` enum) and `…/api/ApiExceptionHandler.java` | [docs/results/s03/s03-t04-openapi.txt](results/s03/s03-t04-openapi.txt): 5 tests green | None. Approach (a) hand-written plus drift test was chosen over generation; recorded in D03-2 as a considered divergence from the minimum cut's "OpenAPI generated only" wording |
| S03-T05 | Done | `libs/outbox/src/main/java/dev/zerosum/outbox/{OutboxWriter,OutboxRelay,OutboxRelayLoop,OutboxCleanupJob,OutboxMetrics,OutboxProperties,OutboxRecord,OutboxFactory}.java`, `libs/outbox/src/testFixtures/…/arch/{OutboxArchRules,OutboxTableAssertions}.java`, `libs/outbox/src/test/…` (6 IT classes + harness), `services/order-service/src/main/resources/db/migration/V4__outbox.sql`, `…/order/outbox/{MoneyOrderPublisher,OrderOutboxConfiguration}.java`, `…/arch/PublishPathArchitectureTest.java`, `application.yml` (`zs.outbox.*`, `spring.kafka.producer.*`), [docs/adr/0008-polling-outbox-single-relay.md](adr/0008-polling-outbox-single-relay.md) | [docs/results/s03/s03-t05-outbox.txt](results/s03/s03-t05-outbox.txt): 14 outbox + 58 order-service tests green | **M4(a) is deferred, not passed** — the relay-restart test proves the mechanism (148 ms vs the 5 s bound) but not a process SIGKILL. Three defects of mine are recorded in the evidence note |
| S03-T06 | Planned | — | — | — |
| S03-T07 | Planned | — | — | — |

<a id="change-record"></a>
### I.2 Consumed sources and change record

| Source/artifact path | Revision or hash | Recorded at | Affected tasks | Review outcome |
|---|---|---|---|---|
| `docs/zerosum_ledger_mvp_plan.md` (v1.2) | `d65384c 2026-09-15` | 2026-09-15 (doc 1.1); recomputed 2026-09-16 at S03 start | All | Revalidated: unchanged since S02 consumed it, so every inherited master value still resolves to the same text |
| `docs/README.md` | `d65384c 2026-09-15` | 2026-09-16 (S03 start) | All | No impact: the shared procedures (source of truth, conflict resolution, change detection, status legend) are unchanged |
| `docs/step_00_foundations.md` (register) | `9234ff4 2026-09-15` | 2026-09-16 (S03 start) | All | Current. Last changed by **CR-S02-05**, which added the `study` test layer to D00-10; S03 inherits the three CI-run layers plus `studyTest`, which no CI job invokes |
| `docs/step_01_domain_contracts.md` (register) | `0d5b34e 2026-09-15` | 2026-09-16 (S03 start) | S03-T01, S03-T02, S03-T03, S03-T05, S03-T07 | Current. Last changed by **CR-S02-04**, which added the clearing classification to D01-6; the validator, chart of accounts and currency allow-list S03 consumes are otherwise as S01 accepted them |
| `docs/step_02_ledger_core.md` (register) | `a00eec1 2026-09-15` | 2026-09-16 (S03 start) | S03-T02, S03-T07 | Complete and merged to `main` in `74b8e85`, G1 proceed. S03-T02 copies the D02-2 append-only pattern; S03-T07 consumes D02-3's apply entrypoint contract. All twelve D02 decisions are recorded, none Pending |
| `gradle/libs.versions.toml` | `sha256 6c95b50f2dd4996a` | 2026-09-16 (S03 start) | All | Unchanged since S02 started: no pinned version moved under S02, so D00-1 still governs every version S03 may use |
| `settings.gradle.kts` (module layout) | `d65384c 2026-09-15` | 2026-09-16 (S03 start) | S03-T03, S03-T05 | Unchanged. `libs:outbox`, `libs:auth` and `services:order-service` are already declared by D00-2, so S03 adds code to existing module slots rather than changing the layout |
| `libs/money`, `libs/contracts` (artifacts) | `sha256 d50a89254b266541`, `sha256 6f80eabfd4959492` (directory digests) | 2026-09-16 (S03 start) | S03-T01, S03-T03, S03-T07 | `libs/money` changed only through CR-S02-04 (clearing classification), which does not affect the validator rules, fee maths or currency rules S03 consumes. `libs/contracts` is untouched since S01, so the money-order and payment-event schemas and the golden payloads are exactly what S01 accepted |
| `docker-compose.yml`, ledger `V2__ledger_schema.sql`, `openapi/ledger-service.yaml` | `sha256 55d1cc64293788ae`, `sha256 eeec98e34cfa785d`, `sha256 22404306d8056bdc` | 2026-09-16 (S03 start) | S03-T02, S03-T05, S03-T07 | Recorded as the baseline S03 starts from. The Compose file is unchanged since S02 started; the ledger migration is the source of the D02-2 append-only pattern S03-T02 copies; the ledger OpenAPI file is the sibling contract S03-T04 mirrors in style |
| `docker-compose.yml` | — | — | S03-T05 | — |
| `infra/postgres/init.sql` and order-service Flyway baseline | `init.sql` `d65384c 2026-09-15`; `V1__baseline.sql` `d65384c 2026-09-15` | 2026-09-16 (S03-T01) | S03-T01, S03-T02, S03-T05 | Consumed unchanged. The baseline is empty by design, so `V2__orders_schema.sql` is the first orders migration; the D00-4 roles `orders_owner` and `orders_app` are used exactly as S00 created them |
| `.env.example` | — | — | S03-T03 | — |
| `.github/workflows/ci.yml` | — | — | All | — |
| `docs/adr/` (ADR template, ADR-0003) | — | — | S03-T05, S03-T07 | — |
| `libs/money/` (directory hash) | — | — | S03-T03, S03-T07 | — |
| `libs/contracts/` (directory hash) | — | — | S03-T03, S03-T04, S03-T05, S03-T07 | — |
| Ledger append-only migration (path per D02-2) | — | — | S03-T02 | — |
| Master gap: DDL enforces adjustment existence but not the same-group rule 6 check | — | 2026-09-15 (doc 1.1) | S03-T01 | Resolved in master v1.2 (§0.3 C2) |
| Master gap: entries-only zero-sum trigger misses orders without entries | — | 2026-09-15 (doc 1.1) | S03-T02 | Resolved in master v1.2 (§0.3 C3) |
| Master gap: idempotency header vs body source key relationship undefined | — | 2026-09-15 (doc 1.1) | S03-T03 | Resolved in master v1.2 (§0.3 C1) |
| Master gap: no repository location for the shared auth module | — | 2026-09-15 (doc 1.1) | S03-T03 | Resolved in master v1.2 (§0.3 C9) |
| Master gap: mapper quarantine location (quarantine is defined only in the ledger DB) | — | 2026-09-15 (doc 1.1) | S03-T07 | Resolved in master v1.2 (§0.3 C8, C25) |
| Master gap: payment event cannot distinguish payout failure after acceptance | — | 2026-09-15 (doc 1.1) | S03-T07 | Resolved in master v1.2 (§0.3 C5) |
| Master gap: ledger-service read APIs not wired to auth | — | 2026-09-15 (doc 1.1) | S03-T03 | Resolved in master v1.2 (§0.3 C9) |
| Master §0.3 C6, C7, C10–C12, E1, O3 | — | 2026-09-15 (doc 1.1) | S03-T01, S03-T05, S03-T06, S03-T07 | Resolved in master v1.2 |

<a id="handoff"></a>
## J. Handoff

**Outputs consumed by later steps**

| Output (decision) | Consuming step and tasks | What they use |
|---|---|---|
| Relay, outbox table, producer usage (D03-5) | [docs/step_04_kafka_pipeline.md#s04-t01](step_04_kafka_pipeline.md#s04-t01), [docs/step_04_kafka_pipeline.md#s04-t05](step_04_kafka_pipeline.md#s04-t05), [docs/step_04_kafka_pipeline.md#s04-t06](step_04_kafka_pipeline.md#s04-t06), [docs/step_04_kafka_pipeline.md#s04-t07](step_04_kafka_pipeline.md#s04-t07), [docs/step_04_kafka_pipeline.md#s04-c01](step_04_kafka_pipeline.md#s04-c01) | Topics used by the relay and consumer (provisioned in S04-T01, which replaces the provisional topic, D04-1 and D04-2 settings); API → relay → ledger e2e, including relay crash after send and the downstream half of M4(c); trace-context capture; lag metrics for SP2; relay replacement if SP2 triggers |
| Mapper poison handling and order-service quarantine table (D03-1, D03-6) | [docs/step_04_kafka_pipeline.md#s04-t03](step_04_kafka_pipeline.md#s04-t03) | The D04-4 policy applies; provisional DLQ and retry settings are replaced through change detection |
| Outbox stats endpoint (D03-7) | [docs/step_04_kafka_pipeline.md#s04-t04](step_04_kafka_pipeline.md#s04-t04), [docs/step_05_instruments_fake_providers.md#s05-t10](step_05_instruments_fake_providers.md#s05-t10) | Freshness inputs; payout-run freshness sum |
| Shared auth module (D03-4) | [docs/step_05_instruments_fake_providers.md#s05-t10](step_05_instruments_fake_providers.md#s05-t10) and the other instrument-service endpoints | Role tokens and principals for instrument-service APIs |
| `libs/outbox` library and publish-path rule (D03-5) | [docs/step_05_instruments_fake_providers.md#s05-t07](step_05_instruments_fake_providers.md#s05-t07) | Instrument outbox table, writer, relay, stats query, ArchUnit fixture |
| Payment-event mapper and consumer (D03-6) | [docs/step_05_instruments_fake_providers.md#s05-t09](step_05_instruments_fake_providers.md#s05-t09), [docs/step_05_instruments_fake_providers.md#s05-c01](step_05_instruments_fake_providers.md#s05-c01), [docs/step_06_reconciliation_verifier.md#s06-t02](step_06_reconciliation_verifier.md#s06-t02) | Collection and refund orders from instrument events; G2 alternative; settlement mapping |
| Outbox and mapper metrics (D03-5, D03-6) | [docs/step_07_observability_performance.md#s07-t01](step_07_observability_performance.md#s07-t01), [docs/step_07_observability_performance.md#s07-t03](step_07_observability_performance.md#s07-t03) | Metric name registry (D07-1) and the outbox backlog alert |
| Idempotency semantics, validation and outbox write sites (D03-1, D03-2, D03-3, D03-5) | [docs/step_08_fault_injection_ablation.md#s08-t01](step_08_fault_injection_ablation.md#s08-t01), [docs/step_08_fault_injection_ablation.md#s08-t03](step_08_fault_injection_ablation.md#s08-t03) | F11 same-key workloads; A2 dual-write and A4 validation-off seams added through change requests |
| OpenAPI and M2–M4 evidence (D03-2) | [docs/step_09_demo_docs_release.md#s09-t02](step_09_demo_docs_release.md#s09-t02) | Traceability table and published API docs |

**Where consumers resolve current definitions.** Always read this step's register ([docs/step_03_order_service_outbox.md#decisions-and-outputs](step_03_order_service_outbox.md#decisions-and-outputs)): H.1 for the selected decision, H.2 for actual implementation and configuration paths, H.3 for artifact paths and revisions. Then read the referenced artifact itself (`openapi/order-service.yaml`, the migrations, `libs/outbox`, the ADRs). Never rely on the planned paths or descriptions in sections D and E of this document.

**Handoff conditions**

1. S03 has no gate. All section G items are checked, or recorded as Blocked with a change-request reference and accepted by the orchestrator.
2. H.1 lists every D03 decision as Accepted with rationale and alternatives. H.2 and H.3 have actual paths. H.4 has results and evidence paths for every check.
3. I.1 shows every task Done, or Blocked with the exact dependency. I.2 shows revisions recorded at step start, at each phase boundary and before handoff, with review outcomes.
4. Change requests raised during S03 (for example to D00-1 for new libraries) are recorded with their status in I.2. The items formerly planned as change requests (the D00-2 auth module location, D01-8 payout event types, D02-7 auth wiring) are resolved in master v1.2 (§0.3 C5, C9).
5. S04 acknowledges the handoff in H.6 after running its own change detection against this register.
