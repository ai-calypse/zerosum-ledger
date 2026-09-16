# Step 02 — Ledger core

> **Pack:** ZeroSum Ledger implementation docs · **Master:** [docs/zerosum_ledger_mvp_plan.md#step-02](zerosum_ledger_mvp_plan.md#step-02) (v1.2)
> **Doc version:** 1.1 · **Date:** 2026-09-15 · **Step status:** Planned (no implementation exists)
> **Planned effort:** 24 h (master schedule; see [docs/zerosum_ledger_mvp_plan.md#schedule-overview](zerosum_ledger_mvp_plan.md#schedule-overview)) · **Gate:** G1
> **Shared procedures:** [docs/README.md#change-detection](README.md#change-detection) · [docs/README.md#source-of-truth](README.md#source-of-truth) · [docs/README.md#status-legend](README.md#status-legend)

<a id="purpose-and-outcome"></a>
## A. Purpose and outcome

**The problem.** S01 produces validated, zero-sum money orders as values in memory. Nothing yet turns them into durable balances with a provable history. Without a ledger the project has the failure modes described in [docs/zerosum_ledger_mvp_plan.md#problem](zerosum_ledger_mvp_plan.md#problem): balances nobody can explain, duplicates that silently inflate balances, and no proof that the books still sum to zero. This step is also where the two hardest correctness properties first become concrete: applying each order exactly once when it is delivered several times, and serializing writes on hot entities without deadlocks.

**The deliverable.** A `ledger-service` that runs against PostgreSQL without Kafka and contains:

1. **Schema and immutability.** Flyway migrations for entities, accounts, applied orders, the entity changelog and the quarantine table (an operational table, not append-only; §0.3 C18 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)). Append-only tables reject updates, deletes and truncation for both the application role and the owner role. The enforcement pattern is packaged so S03 and S05 can reuse it.
2. **Apply engine.** One batch-capable entrypoint that deduplicates, provisions entities and accounts, takes entity locks in a deterministic order, applies balances and changelog rows in one transaction, quarantines structurally invalid records and retries only classified transient failures. A test driver calls it in-process.
3. **Read APIs.** Balances, changelog, invariants and verify endpoints, described in `openapi/ledger-service.yaml`.
4. **Hash chain (S1).** A per-entity hash-chained changelog with a fixed canonical form and known-answer test vectors.
5. **Evidence.** A seeded concurrency stress test with duplicates and the SP1 lock study in `docs/results/sp1-lock-study.md`, including the S3 batch-apply decision.

**Contribution to the MVP.** This step satisfies M5 (b)–(d) and M6 (a)–(b) in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) (M6 (c) is verified in S04's end-to-end tests, §0.3 E11) and closes gate G1 in [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates). Downstream steps build directly on it:

- The apply engine is the single write path that S04 wraps with a Kafka listener.
- The balances API feeds S05 payout eligibility.
- The invariant queries and verify feed the S06 verifier.
- The read APIs feed the S09 Ledger Explorer.
- SP1 gives S07 its baseline for SP4 and tests the first half of hypothesis H2 ([docs/zerosum_ledger_mvp_plan.md#facts-vs-hypotheses](zerosum_ledger_mvp_plan.md#facts-vs-hypotheses)).

**In scope**

- Ledger database schema, grants and append-only enforcement (TB3, TB5 in [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries)).
- Apply engine, retry classification, entity and account auto-provisioning, ADR-0004 and ADR-0005.
- Balances, changelog, invariants and verify read APIs and their OpenAPI description.
- Invariant query implementations I2–I5 and the S1 hash chain.
- Concurrency stress harness, SP1 lock study and the S3 must-have decision.

**Explicitly out of scope**

| Excluded work | Owner |
|---|---|
| Kafka listener, manual acknowledgement, topic provisioning, M5 (a) and M6 (c) end-to-end verification (§0.3 E11) | S04 ([docs/step_04_kafka_pipeline.md#s04-t01](step_04_kafka_pipeline.md#s04-t01), [docs/step_04_kafka_pipeline.md#s04-t02](step_04_kafka_pipeline.md#s04-t02), [docs/step_04_kafka_pipeline.md#s04-t05](step_04_kafka_pipeline.md#s04-t05)) |
| DLQ publishing, pause and alert signal, poison-message policy (the apply engine itself writes quarantine rows, §0.3 C18) | S04 ([docs/step_04_kafka_pipeline.md#s04-t03](step_04_kafka_pipeline.md#s04-t03), D04-4) |
| Freshness endpoint | S04 ([docs/step_04_kafka_pipeline.md#s04-t04](step_04_kafka_pipeline.md#s04-t04), D04-5) |
| Order store, orders-DB zero-sum trigger, money-order API, `libs/auth` and its wiring into ledger-service (§0.3 C9) | S03 (D03-1, D03-2, D03-4; [docs/step_03_order_service_outbox.md#s03-t03](step_03_order_service_outbox.md#s03-t03)) |
| Metric and span name registry, dashboards, alerts, permanent lock-wait sampler, k6 | S07 (D07-1, D07-2, D07-3, D07-7) |
| Hot-entity mitigation choice (SP4) and S4 sharding | S07 ([docs/step_07_observability_performance.md#s07-t06](step_07_observability_performance.md#s07-t06)); [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have) |
| Cross-store verifier CLI for I1–I12 | S06 ([docs/step_06_reconciliation_verifier.md#s06-t04](step_06_reconciliation_verifier.md#s06-t04)) |
| A1 dedupe-ablation flag | S08 ([docs/step_08_fault_injection_ablation.md#s08-t03](step_08_fault_injection_ablation.md#s08-t03)) |
| Ledger Explorer page | S09 ([docs/step_09_demo_docs_release.md#s09-t03](step_09_demo_docs_release.md#s09-t03)) |
| Balance-dependent policy such as payout eligibility (ADR-0004) | S05 (D05-7) |
| Ledger replay tool | Conditional, no step ([docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery)) |
| Table partitioning, pending balances, TigerBeetle comparison, a `ledger-updates` topic | [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred), [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components) |
| Quarantine retry admin endpoint | Deferred in master v1.2 (§0.3 C14); the runbook's manual re-publish step belongs to D04-4 |

<a id="agent-prompt"></a>
## B. Step-specific agent prompt

```text
You are the implementation agent for Step 02 (Ledger core) of the ZeroSum Ledger project.

Repository root: zerosum-ledger/
Step document:   docs/step_02_ledger_core.md
Gate:            G1 (docs/zerosum_ledger_mvp_plan.md#decision-gates)

1. Read docs/README.md (source-of-truth, conflict-resolution, change-detection, status-legend), then
   this step document in full, then every source in its section C at the linked anchors, including
   master §0.3 (docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications).
2. Inspect the current repository. Read the registers (section H) and execution records (section I)
   of docs/step_00_foundations.md and docs/step_01_domain_contracts.md. Resolve versions, roles,
   module paths, test tags, Money, ZeroSumValidator, ChartOfAccounts, JSON Schemas, golden payloads
   and generators from those registers and the artifacts they reference, never from copies here.
3. Before changing code, run the change-detection procedure in docs/README.md#change-detection and
   record revisions or hashes of every consumed document and artifact in section I.2.
4. Complete only the remaining authorized S02 tasks, in dependency order (S02-T01, S02-T02, S02-T03,
   S02-T06, S02-T04, S02-T05, S02-T07). Do not add a Kafka listener, freshness endpoint, auth module,
   quarantine retry endpoint, metrics registry, ablation flag or any other scope owned by a later step,
   deferred item or non-goal.
   Execute S02-C01 only if its evidence trigger holds and it is recorded as authorized.
5. Verify every task exactly as its "Verification and definition of done" field specifies. Money-path
   test failures are product bugs until proven otherwise; never rerun a test until it passes.
6. Record every decision (rationale and alternatives), actual implementation and configuration paths,
   produced artifacts, evidence paths and blockers in sections H and I. Put a trace comment next to
   each authoritative configuration value.
7. Never invent measurements or results. Unexecuted evidence stays "Not run"; blocked work is marked
   "Blocked" with the exact missing dependency. Never mark blocked evidence as passed.
8. Re-run change detection at each phase boundary and before handoff; mark affected completed tasks
   "Needs review" and revalidate them.
9. If you need to change anything owned by the master or by S00/S01 (for example a role model, a
   validator rule subset or an invariant definition), stop and follow the change request procedure in
   docs/README.md#conflict-resolution. Never weaken an acceptance gate.

Finish by completing the section G checklist and the section J handoff conditions, then report: tasks
done, evidence paths, the G1 result, open blockers and change requests raised.
```

<a id="required-reading"></a>
## C. Required reading and prerequisites

### C.1 Master sections

- [docs/zerosum_ledger_mvp_plan.md#step-02](zerosum_ledger_mvp_plan.md#step-02): step objective, task hours, exit criteria and the entity-versus-account lock decision.
- [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications): v1.2 clarifications that change S02 inputs: C8, C9, C13, C14, C17, C18, C19, C25, E8, E11 and O3.
- [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas): proposed ledger DDL (with the v1.2 edits from §0.3 C8, C17, C18 and C25) and the orders-DB append-only pattern that D02-1 and D02-2 start from.
- [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm): proposed apply pseudocode, transient failure set, backoff and hash step.
- [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting): concurrency, timeouts, retries, stale-state and recovery contracts, including the per-transaction limit for operational reads (§0.3 C19).
- [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract): validation rules, and which of them (‡) the ledger re-checks.
- [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts) and [docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example): sign convention, normal sides, clearing accounts and the O1–O8 walk-through.
- [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants): definitions of I2–I5 and how I6b and the verifier use them later.
- [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis): proposed ledger read endpoints, roles and conventions, including the v1.2 invariants scope (§0.3 C13), unauthorized and forbidden responses (§0.3 C9) and the deferred quarantine retry (§0.3 C14).
- [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have): M5 (b)–(d) and M6 acceptance criteria.
- [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have): S1 hash chain and the S3 rule tied to SP1.
- [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes) and [docs/zerosum_ledger_mvp_plan.md#perf-tests](zerosum_ledger_mvp_plan.md#perf-tests): SP1 question, writer counts, durations, repetitions, recorded metrics and decision criterion.
- [docs/zerosum_ledger_mvp_plan.md#bottlenecks](zerosum_ledger_mvp_plan.md#bottlenecks): hot-entity estimate and the mitigation order that SP1 informs.
- [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions): ADR-0004 and ADR-0005 proposals.
- [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates): G1 proceed (as clarified by §0.3 E11), narrow-scope and change-architecture conditions.
- [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components) and [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows): single-writer rules, crash points and recovery expectations.
- [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) and [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling): stress-test parameters, generative test expectations and flaky-test policy.
- [docs/zerosum_ledger_mvp_plan.md#migrations](zerosum_ledger_mvp_plan.md#migrations): additive-only migrations and append-only backfill rules.
- [docs/zerosum_ledger_mvp_plan.md#performance](zerosum_ledger_mvp_plan.md#performance), [docs/zerosum_ledger_mvp_plan.md#cold-warm](zerosum_ledger_mvp_plan.md#cold-warm) and [docs/zerosum_ledger_mvp_plan.md#stage-budgets](zerosum_ledger_mvp_plan.md#stage-budgets): measurement environment, warm-up, durability rule and P4 read targets.
- [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack) and [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits): explicit SQL instead of JPA, lock ordering and constraint trigger behavior.
- [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register): R2, R3, R9 and R11.

### C.2 Earlier step documents and their registers

| Upstream register | Decision IDs consumed |
|---|---|
| [docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs) | D00-1 pinned versions and dependency catalog · D00-2 repository and module layout · D00-3 compose topology (PostgreSQL service configuration used for SP1) · D00-4 databases and roles (owner, application and read-only verifier roles; §0.3 O3) · D00-5 CI workflow structure · D00-7 SP3 result (framework line in use) · D00-8 environment and secret conventions · D00-9 ADR process and `docs/results/TEMPLATE.md` · D00-10 build conventions, source sets and test tags |
| [docs/step_01_domain_contracts.md#decisions-and-outputs](step_01_domain_contracts.md#decisions-and-outputs) | D01-1 Money API and overflow policy · D01-5 ZeroSumValidator rule set · D01-6 ChartOfAccounts and ADR-0003 sign convention · D01-7 currency allow-list (context only; the ledger does not apply it) · D01-8 JSON Schemas and versioning rule · D01-9 golden payloads O1–O8 with expected balances · D01-10 seeded generators and seed reporting (ADR-0009) · D01-11 ArchUnit money rules |

Task sections worth reading directly: [docs/step_00_foundations.md#s00-t04](step_00_foundations.md#s00-t04) (roles and Flyway baseline), [docs/step_01_domain_contracts.md#s01-t03](step_01_domain_contracts.md#s01-t03) (validator and chart of accounts), [docs/step_01_domain_contracts.md#s01-t05](step_01_domain_contracts.md#s01-t05) (schemas and golden payloads) and [docs/step_01_domain_contracts.md#s01-t06](step_01_domain_contracts.md#s01-t06) (generators).

### C.3 Artifacts that must already exist

These are the paths *planned* by upstream registers. Resolve each actual path from the upstream register row, not from this list.

| Planned artifact | Owner | Used by |
|---|---|---|
| `settings.gradle.kts`, `gradle/libs.versions.toml` | D00-1, D00-2, D00-10 | All tasks |
| `services/ledger-service/` empty application with its Flyway baseline migration | D00-2, D00-4 | S02-T01 onward |
| `docker-compose.yml` (PostgreSQL service) | D00-3 | S02-T07 |
| `infra/postgres/init.sql` (ledger database, owner and application roles, verifier role, default privileges) | D00-4 | S02-T01, S02-T05 |
| `.github/workflows/ci.yml` with tagged integration job | D00-5 | S02-T01, S02-T03 |
| `.env.example` (ledger database variables) | D00-8 | S02-T02, S02-T04 |
| `docs/adr/0001-*.md`, `docs/adr/0002-*.md`, `docs/results/TEMPLATE.md` | D00-9, D00-1 | S02-T02, S02-T07 |
| `libs/money` (`Money`, `ZeroSumValidator`, `ChartOfAccounts`) and ADR-0003 | D01-1, D01-5, D01-6 | S02-T02, S02-T04, S02-T05 |
| `libs/contracts` (money-order JSON Schema, golden O1–O8 payloads and expected balances) | D01-8, D01-9 | S02-T02, S02-T04 |
| Seeded generator utilities and ADR-0009 | D01-10 | S02-T02, S02-T03, S02-T07 |

### C.4 Blocking vs independent work

| Missing input | Blocks | Independent preparation that can proceed |
|---|---|---|
| D00-4 role model (owner, application and verifier roles per §0.3 O3) | S02-T01 grant and role tests; S02-T05 verifier-role query test | Draft the D02-1 table design and the append-only trigger SQL; write the test helper against a parameterized role name |
| D00-2 / D00-10 module path, source sets, test tags | CI wiring for all S02 tests; location of the shared append-only test helper and the test driver | Write tests untagged in a clearly named package so moving them is mechanical |
| D00-3 compose PostgreSQL configuration | S02-T07 measured runs | Build and smoke-test the SP1 runner against a Testcontainers database, clearly labeled as not the study environment |
| D01-5 validator rule set (‡ subset) | S02-T02 structural checks | Engine transaction, dedupe, locking and retry code driven by already-valid golden payloads |
| D01-6 chart of accounts, normal sides, clearing classification | S02-T02 provisioning; S02-T04 normal-side display; S02-T05 clearing report | Schema, engine skeleton with entity-kind parsing, changelog paging |
| D01-8 / D01-9 schemas and golden payloads with expected balances | S02-T02 worked-example test; S02-T04 balances test | Engine tests using hand-built orders marked for replacement |
| D01-10 generators | S02-T02 permutation test; S02-T03; S02-T07 | Stress harness structure with a fixed order list |
| Reference machine time for SP1 | S02-T07 measured runs only | Runner, invariant checks after each run and report skeleton; mark missing runs "Not run" |
| D03-4 `libs/auth`, wired into ledger-service by S03-T03 (§0.3 C9) | Reader-role enforcement on S02 read APIs (not a G1 condition) | All S02 tasks; ports stay local-only until S03-T03 |

<a id="ownership-and-requirements"></a>
## D. Ownership and engineering requirements

### D.1 Decisions and contracts owned by this step

| Decision ID | What is decided | Master's proposed starting point |
|---|---|---|
| D02-1 | Ledger DB schema and migrations: tables, keys, sort collation, the applied-order source system and idempotency key and the nullable Kafka position columns (§0.3 C17), grants | [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas) |
| D02-2 | Append-only enforcement mechanism (triggers, privilege revokes, reusable test helper) as a pattern for S03 and S05 | [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas), [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) |
| D02-3 | Apply engine entrypoint (batch-capable API, per-record outcomes, default batch mode), transaction design including the quarantine write (§0.3 C18), isolation of non-transient failures, test driver | [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm) |
| D02-4 | Lock strategy, transaction timeouts and retry classification (ADR-0005) | [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions), [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm), [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) |
| D02-5 | Entity and account auto-provisioning rule | [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm), [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts) |
| D02-6 | Changelog hash-chain canonical form, chain rule and known-answer vectors (S1) | [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm), [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have) |
| D02-7 | Ledger read API contract in `openapi/ledger-service.yaml` for balances, changelog, verify and invariants; other paths in that file stay owned by the decision that introduces them (e.g., D04-5) | [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis) |
| D02-8 | Invariant query implementations I2–I5, non-zero clearing balance report (§0.3 C13), snapshot rule and per-transaction timeout for operational reads (§0.3 C19), reuse boundary for `tools/verifier` | [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants) |
| D02-9 | Quarantine table shape (surrogate key, nullable order ID, operational rather than append-only; §0.3 C8, C18), Kafka topic, partition and offset unique together so a redelivered record is quarantined only once (§0.3 C25), resolution handling, error-code set | [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas) |
| D02-10 | SP1 result and the S3 batch-apply must-have decision | [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes), [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have) |
| D02-11 | Concurrency stress harness (parameters source, modes, oracle, CI placement) | [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) |
| D02-12 | ADR-0004, ledger half: apply rejects only structurally invalid orders and never rejects for balance reasons; balances may go negative (appended ID) | [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) |

### D.2 Inherited decisions

| Decision | Owner | How this step must use it |
|---|---|---|
| Pinned versions of PostgreSQL, Flyway, Testcontainers, the test framework and the framework line | D00-1, D00-7 ([docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs)) | Reference catalog entries only; never add a version literal to ledger-service build files |
| Module layout and package root | D00-2 | Resolve the actual `services/ledger-service` path and Gradle project path before writing code |
| Compose PostgreSQL configuration | D00-3 | SP1 runs against this configuration, not a hand-tuned database |
| Databases and roles (owner, application, verifier; §0.3 O3) | D00-4 | Grants and dual-role immutability tests use these roles; create no unmanaged roles in ledger migrations |
| CI structure, source sets, test tags | D00-5, D00-10 | Tag integration, stress and study tests so each lands in the right CI job or stays manual |
| Environment and secret conventions | D00-8 | Database credentials come from environment variables; no secrets in `application.yml` |
| ADR process and results template | D00-9 | ADR-0004 and ADR-0005 follow the ADR process; the SP1 report uses the template |
| `Money` overflow policy | D01-1 ([docs/step_01_domain_contracts.md#decisions-and-outputs](step_01_domain_contracts.md#decisions-and-outputs)) | Balance arithmetic and the stress oracle use the same overflow-checked arithmetic |
| ZeroSumValidator rule set | D01-5 | Apply re-checks only the rules marked ‡ in [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract) |
| ChartOfAccounts and sign convention (ADR-0003) | D01-6 | Entity kinds, allowed accounts, normal sides and clearing classification; never duplicated in ledger-service |
| Currency allow-list | D01-7 | Not applied during apply, so a configuration difference between services cannot quarantine valid orders |
| JSON Schemas and versioning rule | D01-8 | Decoding and unknown-version handling in the apply engine |
| Golden payloads and expected balances | D01-9 | Worked-example apply and balances tests |
| Seeded generators and seed reporting (ADR-0009) | D01-10 | Permutation test, stress harness and SP1 workload |
| ArchUnit money rules | D01-11 | ledger-service code must pass them |
| Invariants I2–I5, M5, M6, G1, SP1 criterion, P4 targets | Master ([docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants), [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have), [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates)) | Implement and test them; never redefine or relax them |

### D.3 Engineering requirements

**Module and package boundaries**

- Among project libraries, `services/ledger-service` depends only on `libs/money` and `libs/contracts`. It does not depend on `libs/outbox` because the ledger publishes no events ([docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components)). S02 code contains no Kafka client or listener code.
- Planned packages (actual names recorded in H.2): `apply` (entrypoint, decoding, structural checks, retry), `store` (explicit SQL through `JdbcClient`, no JPA, per [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack)), `changelog` (hasher and chain verifier), `invariants` (I2–I5 queries), `api` (controllers and problem-details mapping).
- Invariant queries (D02-8) and the chain verifier (D02-6) must work with only SELECT privileges and must not depend on web or apply classes, so that S06-T04 can reuse them from `tools/verifier`.

**Interfaces**

- **Apply entrypoint (D02-3).** This is the only write path into ledger tables. The test driver, the stress harness, the SP1 runner, the S04 listener and the future S08 seams all go through it: A1 bypasses dedupe, and A4 also disables the ledger zero-sum re-check (§0.3 E8). Its signature carries no Kafka types: a record is a raw payload plus an optional source position (Kafka topic, partition and offset). It returns a per-record outcome (applied, duplicate or quarantined with an error code) and raises one typed exception when transient retries are exhausted.
- **Read API (D02-7).** Reader-role HTTP endpoints described in `openapi/ledger-service.yaml`, with problem-details errors that carry a stable code, following the conventions in [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis).
- **Hash canonical form (D02-6).** A versioned byte-level specification plus known-answer vectors that other implementations can check themselves against.

**Data flows**

- **Write.** Caller → decode and ‡ checks (outside the transaction) → one transaction: quarantine rows, dedupe insert, provisioning, sorted entity locks, in-memory computation, batched writes → commit → outcomes returned → caller acknowledges. In S04 the acknowledgement is the Kafka offset commit after the database commit.
- **Read.** HTTP → read-only snapshot transaction → response. Balances are served from the primary only; there is no cache ([docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting)).
- ledger-service never reads another service's database ([docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components), design rule 2).

**Lifecycle behavior**

- **Startup.** Flyway migrates at startup (single instance, [docs/zerosum_ledger_mvp_plan.md#migrations](zerosum_ledger_mvp_plan.md#migrations)). If the database is unreachable the service fails fast and applies nothing.
- **Shutdown.** An in-flight apply transaction either commits or rolls back. The retry backoff is interruptible, so a shutdown never waits out a full retry schedule. The graceful-shutdown timeout in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) is enforced by the S04 listener container; the engine must simply honor thread interruption.
- **Crash.** A crash before commit leaves no effect. A crash after commit makes the re-invocation a duplicate (crash points in [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows)).
- **Retry.** Only the D02-4 transient classes are retried, and every retry re-runs the whole transaction from fresh reads. Money is never skipped.

**Security and trust boundaries**

- **TB3.** The runtime role has no update, delete or truncate privilege on append-only tables, and triggers stop the owner as well. The application never connects as a superuser.
- **TB5.** The verifier role (D00-4) has read access to every ledger table and nothing else.
- **TB1.** Read APIs are reader-role endpoints. Token enforcement comes from `libs/auth` (D03-4), which S03-T03 wires into ledger-service (§0.3 C9). Until then, ledger-service ports stay local-only (D00-3). Do not build a second authentication mechanism.
- **Logging.** Logs never contain credentials. Payloads are synthetic and may be stored in the quarantine table.

**Deployment constraints**

- One ledger-service instance; migrations are additive only; container memory as set in D00-3.
- Durability settings are never relaxed for tests or measurements ([docs/zerosum_ledger_mvp_plan.md#performance](zerosum_ledger_mvp_plan.md#performance)).

### D.4 Configuration ownership

| Authoritative value | Planned location | Traced to |
|---|---|---|
| Apply transaction lock timeout and statement timeout | `services/ledger-service/src/main/resources/application.yml` | D02-4 |
| Transient failure classes, backoff bounds, maximum attempts | `services/ledger-service/src/main/resources/application.yml` | D02-4 |
| Default batch mode for callers | `services/ledger-service/src/main/resources/application.yml` | D02-3, D02-10 |
| Changelog maximum page size | `application.yml` and `openapi/ledger-service.yaml` | D02-7 |
| Per-transaction statement timeout for invariants and verify (§0.3 C19) | `services/ledger-service/src/main/resources/application.yml` | D02-8 |
| Connection pool size for ledger-service (credentials via D00-8) | `services/ledger-service/src/main/resources/application.yml` | D02-3 (revisited by S04 and S07) |
| Hash canonical form version | Constant in the `changelog` package plus the known-answer vector file | D02-6 (not configurable at runtime) |
| Stress harness parameters | `services/ledger-service/src/test/resources/stress/stress.properties` | D02-11 |
| SP1 study parameters | `services/ledger-service/src/test/resources/sp1/` (planned), copied into the report | D02-10 |
| Clearing classification, normal sides, allowed accounts | `libs/money` | D01-6 (consumed, not duplicated) |

Every authoritative value carries a trace comment. The selected values go into the file, never into this document:

```yaml
ledger:
  apply:
    lock-timeout: <selected>   # decision: D02-4 — docs/step_02_ledger_core.md#decisions-and-outputs
```

**Permitted alternatives that downstream work must handle**

- **SP1 outcome (D02-10).** Batched apply becomes must-have. S04-T02 must call the engine in batch mode, and S07-T06 treats batching as required rather than optional.
- **G1 alternative (S02-C01 → D02-4).** Account-level optimistic locking replaces sorted entity locks. This changes retry semantics in S04 and the starting point for S07.
- **SP4 options (D07-6 → D02-3/D02-4).** Batching, sharding hot entities, or both. S07 applies the choice through the change procedure; S08 and S09 claims follow it.
- **A1 and A4 ablation seams (D08-3 → D02-3).** Through the change procedure, S08 adds a dedupe bypass (A1) and a switch that disables the ledger zero-sum re-check (A4, §0.3 E8). The engine keeps dedupe and the ‡ checks in clearly separable steps so both seams stay small.
- **Minimum cut (D02-6).** If [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut) is invoked, S1 is dropped and I5 is unavailable to S06.

<a id="phases-and-tasks"></a>
## E. Phases and tasks

| Phase | Tasks | Hours |
|---|---|---|
| Phase 1 — Schema and immutability | S02-T01 | 4 |
| Phase 2 — Apply engine | S02-T02, S02-T03 | 8 |
| Phase 3 — Read APIs and verification | S02-T04, S02-T05, S02-T06 | 8 |
| Phase 4 — SP1 lock study | S02-T07 | 4 |
| **Total** | | **24** |

Execution order follows dependencies: S02-T01 → S02-T02 → S02-T03 → S02-T06 → S02-T04 → S02-T05 → S02-T07. S02-T04 and S02-T06 both depend only on Phase 2, so inside Phase 3 either may go first, but S02-T05 needs both.

<a id="phase-1"></a>
### Phase 1 — Schema and immutability

**Objective:** The ledger database schema exists as additive Flyway migrations, append-only tables are enforced against both the application and owner roles, and grants match TB3/TB5.

**Exit checkpoint:** S02-T01 verification is green in the integration job, D02-1, D02-2 and D02-9 are recorded in H.1 with rationale, and change detection has been re-run and recorded in I.2.

<a id="s02-t01"></a>
#### S02-T01 — Ledger migrations, append-only triggers, grants and role tests
- **Outcome:** The ledger schema (entities, accounts, applied orders, entity changelog, quarantine) is created by additive migrations. Append-only tables reject update, delete and truncate for the application role and the owner role (§0.3 O3), and the enforcement is packaged as a reusable pattern (D02-2) that S03 and S05 copy.
- **Estimate:** 4 h
- **Inputs:** v1.2 ledger DDL and the orders-DB append-only pattern at [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas); §0.3 C8, C17, C18, C25 and O3 at [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications); migration rules at [docs/zerosum_ledger_mvp_plan.md#migrations](zerosum_ledger_mvp_plan.md#migrations); TB3 and TB5 at [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries); M2 (a) wording for the two identities at [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have); upstream D00-2, D00-4, D00-10 ([docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs)) and D01-6 ([docs/step_01_domain_contracts.md#decisions-and-outputs](step_01_domain_contracts.md#decisions-and-outputs)); artifacts `infra/postgres/init.sql` and the ledger-service baseline migration.
- **Depends on:** none (upstream S00-T04 and S01-T03 must be Done in their registers)
- **Instructions:**
  1. Run change detection and record the D00-4 role model in I.2. Per §0.3 O3 the ledger database has an owner role and an application (runtime) role, plus the read-only verifier role, so dual-role immutability tests need no change request. Raise a change request against D00-4 only if its register records something different; never create roles inside ledger migrations.
  2. Start from the v1.2 ledger DDL and select the schema in D02-1, recording each point below in H.1:
     - **(a) Source key (§0.3 C17).** The applied-order record stores the source system and idempotency key that M6 (a) needs; the changelog API joins them per order.
     - **(b) Source position (§0.3 C17).** The Kafka position columns are nullable, so the test driver and the SP1 runner leave them empty while S04 fills them (D04-3).
     - **(c) Quarantine (§0.3 C8, C18).** A surrogate key, a nullable order ID and a byte payload let undecodable records fit. The table is operational, not append-only, and resolution updates its resolution time. The Kafka topic, partition and offset are recorded and unique together, and stay null for callers without Kafka (§0.3 C25). D02-9 records the error-code set.
     - **(d) Collation (still open).** The database sort order for entity IDs and account keys must equal the order the application uses for inserts and locks; a byte-order collation such as `"C"` satisfies this for the ASCII entity IDs allowed by D01-5.
     - **(e) Hash-chain columns (still open).** Create them in this migration so S02-T06 needs no later non-additive change, and record whether they are mandatory.
  3. Write the migrations as new versions after the S00 baseline, numbered per D00-10. Never edit the baseline or an already-applied migration. Keep every statement transactional (no concurrent index builds), so a failed migration leaves no partial schema.
  4. Implement D02-2 on every append-only table, which means the entity changelog and the applied-orders table. The quarantine table is operational and gets no D02-2 triggers (§0.3 C18). On each append-only table add:
     - a row-level trigger that rejects update and delete;
     - a statement-level trigger that rejects truncate;
     - a revoke of those privileges from the runtime role.
     Use one trigger function with a stable error code, and mark the SQL block in the migration with a comment naming D02-2 so S03 and S05 can copy it verbatim into their own databases.
  5. Grant the runtime role select and insert on all ledger tables, and update only on the mutable tables (entities, accounts, and the quarantine table's resolution field per §0.3 C18). Grant no delete or truncate anywhere. Grant the verifier role (D00-4) select only. Confirm that the default privileges set by S00 do not give the runtime role write access to tables that later migrations add.
  6. Write a reusable test helper that takes a table name and a role and asserts that update, delete and truncate each fail with the D02-2 error code. Put it where D00-2/D00-10 allow shared test code, for example a test-fixtures source set. If no such location exists, record the path so S03 can copy it; do not create a new shared library without a change request to D00-2.
  7. Add the integration tests `LedgerSchemaMigrationIT`, `LedgerAppendOnlyIT` and `LedgerGrantsIT` (named in the verification field) and tag them per D00-10.
- **Edge cases and failure behavior:**
  - A table owner with DDL rights can drop or disable triggers, and a superuser can bypass triggers for a session. The enforcement protects against accidental and application-level mutation, not against a malicious owner. Record this as a limitation in H.5 and rely on S1 and the verifier for tamper evidence.
  - Truncation that cascades from a referencing table must also be rejected; test it if any foreign key points at an append-only table.
  - The Flyway schema history table must stay writable by the migration identity; never attach D02-2 triggers to it.
  - A conflict-skipping insert into an append-only table performs no update, so it must not fire the reject trigger. The apply engine relies on this, so assert it explicitly.
  - Tests cannot truncate append-only tables between cases. Use a fresh database per test class or unique entity IDs per test; never add a bypass for tests to production migrations.
- **Outputs:** planned `services/ledger-service/src/main/resources/db/migration/V<n>__ledger_schema.sql` (version per D00-10); planned shared append-only test helper; planned `LedgerSchemaMigrationIT`, `LedgerAppendOnlyIT`, `LedgerGrantsIT`; H.1 rows D02-1, D02-2, D02-9.
- **Verification and definition of done:**
  - `LedgerSchemaMigrationIT`: on an empty database, migration from the S00 baseline succeeds, and Flyway validation passes on a second start. A second conflict-skipping quarantine insert with the same Kafka topic, partition and offset is a no-op, while rows with null positions do not conflict (§0.3 C25).
  - `LedgerAppendOnlyIT`: parameterized over every append-only table × {update, delete, truncate} × {application role, owner role} (§0.3 O3). Every cell fails with the D02-2 error code, and a conflict-skipping duplicate insert succeeds without error.
  - `LedgerGrantsIT`: the verifier role can select from every ledger table but cannot insert; the runtime role cannot delete from entities, accounts or the quarantine table, and can set a quarantine row's resolution time.
  - The integration test task (Gradle project path per D00-2, task and tag per D00-10) is green locally and in the CI integration job.
  - H.4 rows are recorded with evidence paths, and D02-1, D02-2 and D02-9 are no longer Pending.

<a id="phase-2"></a>
### Phase 2 — Apply engine

**Objective:** A batch-capable apply engine applies every valid order exactly once with deterministic locking and classified retries, and a seeded concurrency stress test proves M5 (b)–(d) under duplicates.

**Exit checkpoint:** S02-T02 and S02-T03 verifications are green in both per-order and batched mode, ADR-0004 and ADR-0005 drafts are committed, D02-3, D02-4, D02-5, D02-11 and D02-12 are recorded, and change detection has been re-run.

<a id="s02-t02"></a>
#### S02-T02 — Batch-capable apply engine with dedupe, sorted entity locks, retry classification and overflow handling
- **Outcome:** One apply entrypoint accepts an ordered batch of records (per-order mode by default). It applies each new valid order exactly once in a single transaction, quarantines structurally invalid records, retries only classified transient failures and isolates non-transient failures without skipping money. ADR-0004 (ledger half) and ADR-0005 are drafted.
- **Estimate:** 6 h
- **Inputs:** proposed pseudocode at [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm); §0.3 C8, C18, C25 and E8 at [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications); concurrency, timeout, retry and recovery contracts at [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting); ‡ rules at [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract); ADR-0004 and ADR-0005 at [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions); lock ordering at [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits); crash points at [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows); upstream D01-1, D01-5, D01-6, D01-8, D01-9, D01-10 ([docs/step_01_domain_contracts.md#decisions-and-outputs](step_01_domain_contracts.md#decisions-and-outputs)), D00-8 and D00-9; S02-T01 outputs (D02-1, D02-9).
- **Depends on:** S02-T01
- **Instructions:**
  1. **Entrypoint (D02-3).** Define one method that takes an ordered list of records (raw payload plus optional source position) and returns one outcome per record: applied, duplicate, or quarantined with an error code. Include the batch timing data SP1 needs (total duration and time spent acquiring entity locks). Batch size is chosen by the caller; the default is per-order mode, configured with a D02-3 trace comment. The signature must not use Kafka types.
  2. **Decode and structural checks, outside the transaction.** Parse the JSON and validate it against the money-order schema version using D01-8. Then apply only the ‡ rules through the D01-5 and D01-6 APIs: the account is allowed for the entity kind, and entries sum to zero per currency. Do not re-check rules that belong to order-service (currency allow-list, source type, idempotency). If D01-5 does not expose the ‡ subset separately, raise a change request to S01 instead of re-implementing the rules. Keep the ‡ checks in one separable step: S08 later adds a switch that disables the zero-sum re-check for ablation A4 through a change request (§0.3 E8).
  3. **Intra-batch dedupe.** Remove repeated order IDs within the batch before any SQL; the first occurrence in input order wins.
  4. **Transaction (one per batch).** Use the isolation level from ADR-0005 and set the D02-4 lock and statement timeouts as transaction-local settings. In order:
     1. Insert quarantine rows for invalid records. The engine owns this write; S04 owns DLQ publishing and the poison-message policy (§0.3 C18). Insert with conflict skipping on the Kafka topic, partition and offset, so quarantining a redelivered record is a no-op (§0.3 C25); callers without Kafka pass null positions.
     2. Insert applied-order records with conflict skipping and collect the IDs actually inserted. Only those orders continue; redeliveries drop out here.
     3. Provision entities, then accounts (D02-5), in sorted order with conflict skipping.
     4. Lock the batch's entity rows in sorted order, using the D02-1 collation.
     5. Read the affected accounts.
     6. Compute in memory, per order in input order and per entry in entry order: the new account balance with overflow-checked addition (D01-1), the next entity sequence number, and the row hash through the changelog hasher (form owned by S02-T06, D02-6).
     7. Write accounts, entities and changelog rows in batched statements, then commit.
  5. **Sequence numbers.** Take the next sequence number from the locked entity row, never from a database sequence object. A rolled-back sequence object leaves gaps and would break M5 (b) and I4.
  6. **Auto-provisioning (D02-5).** The entity kind comes from the entity-ID prefix, and the normal side comes from D01-6. There is no registration API, and the ledger never creates an account the chart of accounts does not allow.
  7. **Hasher.** Implement the changelog hasher once, following the hash step proposed in [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm). S02-T06 freezes its canonical form and may revise it; apply and verification must always share this single implementation.
  8. **Retry classification (D02-4, ADR-0005).**
     - Classify by the SQLSTATE of the root `SQLException`, not by the Spring exception subclass, because translation varies. Start from the transient classes proposed in [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm).
     - Use jittered exponential backoff with the D02-4 bounds and maximum attempts. Each retry re-runs step 4 from fresh reads and discards any in-memory balances.
     - When attempts are exhausted, throw one typed exception that carries the batch. Never skip or quarantine a transient failure; S04 maps this exception to pause plus alert (D04-4).
  9. **Non-transient failures inside the transaction** (for example balance or sequence overflow, or an unexpected constraint violation):
     - Roll back.
     - If the batch has more than one fresh order, re-run its records one at a time to isolate the offending record.
     - Quarantine only that record, with a distinct error code, and apply the rest.
     - In per-order mode, quarantine directly.
     Record this isolation policy in D02-3, because S04 consumes it.
  10. **ADR-0004 (D02-12).** Apply never rejects an order because a balance would become negative or insufficient. Write the ledger half of ADR-0004, and draft ADR-0005 (sorted entity locks, retry classification, and the account-level alternative from G1), both per D00-9.
  11. **Test driver.** Add an in-process test driver in the D00-10 test-fixtures or test source set. It loads golden payloads (D01-9) or generated orders (D01-10), leaves source positions null by default (a test may supply a synthetic topic, partition and offset) and calls the entrypoint. S02-T03 and S02-T07 reuse it.
- **Edge cases and failure behavior:**
  - **Same order in two concurrent transactions.** The second transaction's conflict-skipping insert waits for the first. If the first commits, the second sees a duplicate. If the first rolls back (for example on a retry), the second applies the order. Both paths are tested.
  - **Commit outcome unknown.** A connection lost during commit may or may not have committed. A retry is still safe, because the dedupe insert turns an already-committed batch into duplicates.
  - **Repeated entities and multiple currencies.** An order that touches one entity in several entries, accounts or currencies locks that entity once and advances its sequence number once per entry. Balances are kept per entity, account and currency.
  - **Malformed records.** An undecodable payload, an unknown schema version or a missing order ID is quarantined under the surrogate key, with a null order ID when none can be read (§0.3 C8), and never blocks valid records in the same batch.
  - **Redelivered invalid record.** With a Kafka position the second quarantine insert is skipped (§0.3 C25). Null positions never conflict, so callers without Kafka get one row per call; tests that assert deduplication supply a synthetic position.
  - **Same order ID, different content.** It is treated as a duplicate, because orders are immutable upstream. Log both payload digests at warning level; I6b in S06 exposes any real divergence. Record the behavior in D02-3.
  - **Interrupted retry.** If the thread is interrupted during backoff (shutdown), stop retrying and propagate. No partial state exists because nothing committed.
  - **Empty batch.** No transaction is opened.
  - **Collation mismatch.** If the application's sort order and the database's differ, concurrent provisioning can deadlock. S02-T03 must detect this through its deadlock retry count.
- **Outputs:** planned `apply`, `store` and `changelog` packages under `services/ledger-service/src/main/java/`; planned retry classifier; planned test driver; planned `docs/adr/0004-balance-policy-outside-ledger.md` and `docs/adr/0005-ledger-entity-locks.md` (numbering and naming per D00-9); planned `application.yml` entries with trace comments; H.1 rows D02-3, D02-4, D02-5, D02-12.
- **Verification and definition of done:**
  - `WorkedExampleApplyIT`: applying golden O1–O7 through the driver yields exactly the expected balances in D01-9 (derived from [docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example)), and the O8 variant yields its expected balances.
  - `ApplyDuplicateIT`: each order is applied as many times as the M5 (a) criterion names, sequentially, inside one batch and across batches. Final balances, changelog row counts and sequence numbers are identical to a single application.
  - `SeededApplyPermutationIT`: seeded random order sequences with shuffles and duplicates, applied in per-order and batched mode, produce identical final balances and pass I2–I4 checks. The seed is printed per ADR-0009.
  - `ApplyRetryClassifierTest` (unit): each SQLSTATE class maps to transient or non-transient as recorded in D02-4.
  - `ApplyLockTimeoutRetryIT`: a test transaction holds an entity lock beyond the lock timeout. The engine records at least one retry and applies the order once the lock is released.
  - `ApplyExhaustedRetriesIT`: a lock held beyond the full retry schedule raises the typed exception, and no applied-order row exists for the batch.
  - `ApplyConnectionLossIT`: the backend is terminated during the transaction. The re-invocation applies the order exactly once.
  - `ApplyQuarantineIT`: a ‡-invalid order and a garbage payload each produce one quarantine row with the expected error code (the garbage payload's row has a null order ID), redelivering the same invalid record with the same Kafka topic, partition and offset is a no-op that leaves one row (§0.3 C25), and valid orders in the same batch are applied.
  - `ApplyOverflowIT`: in batched mode an order that would overflow a balance is isolated and quarantined, and the other orders apply.
  - `NegativeBalanceIT`: an order that drives a driver payable into debit is applied (ADR-0004).
  - ADR-0004 and ADR-0005 drafts are committed; D02-3, D02-4, D02-5 and D02-12 are recorded in H.1.

<a id="s02-t03"></a>
#### S02-T03 — Concurrency stress test with duplicates
- **Outcome:** A seeded, repeatable stress harness (D02-11) applies a generated order set with duplicates from many threads, in per-order and batched mode. It proves no lost updates, no unhandled deadlocks, and M5 (b)–(d).
- **Estimate:** 2 h
- **Inputs:** stress parameters in the database-integration row of [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) and the G1 description in [docs/zerosum_ledger_mvp_plan.md#summary](zerosum_ledger_mvp_plan.md#summary); R2 at [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register); flaky-test policy at [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling); CI duration targets at [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd); upstream D01-1, D01-10, D00-5, D00-10; S02-T02 engine and test driver.
- **Depends on:** S02-T02
- **Instructions:**
  1. Take thread count, order count and duplicate ratio from [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers). Store them in the harness configuration with a D02-11 trace comment; assertions must not hard-code them.
  2. Generate orders with the D01-10 seeded generator. Every order touches the hot platform entity. Rider and driver sets overlap, so lock sets intersect in different combinations. Some orders repeat an entity across entries, and currencies with different minor-unit digits (D01-6/D01-1) are mixed in.
  3. Inject duplicates in three forms: re-submission after the original committed, the same order submitted concurrently by two threads, and duplicates inside one batch.
  4. Compute the oracle in memory: expected balances from the unique orders only, using D01-1 arithmetic.
  5. Run the harness twice, once in per-order mode and once in a batched mode whose batch size is set in the harness configuration.
  6. While writers run, a sampler thread repeatedly reads the global per-currency sum in a single statement. Every sample must be zero; this is the "after every committed transaction" part of M5 (c).
  7. After each run, assert that:
     - balances equal the oracle;
     - every account balance equals the sum of its changelog deltas, and every running balance is correct (M5 (d), I3);
     - sequence numbers are gapless per entity (M5 (b), I4);
     - each changelog row's previous-hash field equals the prior row's hash (full hash verification is added by S02-T06);
     - the number of applied-order records equals the number of unique orders;
     - the typed retries-exhausted exception was never raised.
  8. Print the seed and parameters at start and on failure (ADR-0009). Emit retry counts by class (deadlock, lock timeout, connection) as test output for H.4.
  9. **CI placement.** Tag the test per D00-10. If the full size exceeds the pull-request job target in [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd), run full size in the nightly job and a reduced size on pull requests, and record both sizes in D02-11.
- **Edge cases and failure behavior:**
  - Any failure is a product bug until root cause proves otherwise ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)). Never rerun the test until it passes.
  - A non-zero deadlock retry count is a lock-ordering or collation defect even if assertions pass. Investigate it and record the result.
  - If the typed retries-exhausted exception is raised, or sequence gaps persist after root-cause work, that is trigger evidence for S02-C01. Record the evidence path.
  - A connection pool smaller than the writer count measures pool waits, not locks. Size the pool to at least the writer count for this test.
  - Container resource starvation can look like lock timeouts. Record the container configuration and distinguish infrastructure failure from lock contention in the output.
- **Outputs:** planned `LedgerConcurrencyStressIT` under the ledger-service test source set; planned `services/ledger-service/src/test/resources/stress/` configuration; planned CI tag wiring; H.1 row D02-11.
- **Verification and definition of done:**
  - `LedgerConcurrencyStressIT` is green in both modes with the seed printed.
  - A rerun with the same seed yields identical final balances; a run with a different seed is also green.
  - The sampler recorded only zero global sums.
  - Retry counts by class and run durations are recorded in H.4, and D02-11 is recorded in H.1.

<a id="phase-3"></a>
### Phase 3 — Read APIs and verification

**Objective:** Operators can read balances and the changelog, check global invariants and verify any entity against its hash-chained history, through reader endpoints described in `openapi/ledger-service.yaml`.

**Exit checkpoint:** S02-T04, S02-T05 and S02-T06 verifications are green, the OpenAPI contract test passes, the S02-T03 stress test passes with full I5 verification, D02-6, D02-7 and D02-8 are recorded, and change detection has been re-run.

<a id="s02-t04"></a>
#### S02-T04 — Balances and changelog read APIs
- **Outcome:** Reader endpoints return an entity's balances on each account's normal side with an as-of sequence number, plus a keyset-paginated changelog whose rows link to their money order and source idempotency key. The contract lives in `openapi/ledger-service.yaml` (D02-7).
- **Estimate:** 3 h
- **Inputs:** ledger rows and conventions at [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis); §0.3 C9, C17 and E11 at [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications); normal-side display at [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts); M6 (a) and M6 (c) at [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have); stale-state and validation rows at [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting); TB1 at [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries); upstream D01-5, D01-6, D01-9, D00-3; S02-T01 (D02-1 source-key storage) and S02-T02 outputs.
- **Depends on:** S02-T02
- **Instructions:**
  1. Create `openapi/ledger-service.yaml` with the balances and changelog operations, starting from the proposal in [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis). Errors use problem details with a stable code. Declare the unauthorized and forbidden responses from the v1.2 conventions (§0.3 C9); they are enforced once S03-T03 wires `libs/auth`. Record in D02-7 that paths added later by other steps (for example D04-5) are owned by those decisions.
  2. **Balances.** Read the entity row and its accounts in one snapshot, either a single statement or a read-only repeatable-read transaction, so the as-of sequence number matches the balances. Present each balance on its normal side per ADR-0003 (D01-6) with an explicit side indicator. D02-7 records whether the signed value is also returned. An unknown entity returns 404.
  3. **Changelog.** Page by sequence number after a cursor using the entity/sequence primary key, with the maximum page size from the proposal held in configuration (D02-7). Each row carries the order ID, account, currency, delta, running balance, sequence number and recorded time, plus the source system and idempotency key joined from the applied-order record (§0.3 C17). D02-7 also decides whether the hash fields are exposed (the S09 Explorer displays verify results, not raw hashes).
  4. Validate inputs: an entity ID that fails the D01-5 pattern, a non-numeric or negative cursor, or an out-of-range limit returns 400 with a stable code. Unknown entity behavior for the changelog is chosen in D02-7 and applied consistently with balances.
  5. **Authentication.** Do not implement token checks here. S03-T03 wires `libs/auth` (D03-4) into ledger-service (§0.3 C9); until then keep the ledger-service port local-only (D00-3).
  6. **Audit walk (M6 (c)).** Document the walk from a balance to its source order in the OpenAPI operation descriptions and test the ledger calls. M6 (c) itself is verified in S04's end-to-end tests (§0.3 E11), not at G1.
- **Edge cases and failure behavior:**
  - Entity IDs contain a colon. Accept both the literal and the percent-encoded form, and make sure no framework suffix or path-matching rule truncates the ID.
  - Rows are append-only, so keyset pages stay stable while applies continue. A later page may show rows newer than the as-of sequence number returned by an earlier balances call; document this.
  - A request past the last sequence number returns an empty page with no next cursor, not 404.
  - Hot entities have very long changelogs. Confirm once with an execution plan that the page query uses the primary-key range, and record the plan output as evidence.
  - A database outage returns a problem response with 503 semantics after the pool connection timeout, never a partial page ([docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded)).
- **Outputs:** planned `api` package controllers; planned `openapi/ledger-service.yaml` (balances and changelog); planned `BalancesApiIT`, `ChangelogApiIT`, `LedgerOpenApiContractIT`; H.1 row D02-7 (partial until S02-T05).
- **Verification and definition of done:**
  - `BalancesApiIT`: after golden O1–O7, every account shows the D01-9 expected balance on its normal side. The as-of sequence number equals the entity's last sequence. An unknown entity returns 404, a malformed ID returns 400, and both literal and encoded colons work.
  - `ChangelogApiIT`: paging through an entity returns every row exactly once in sequence order. Every row carries its order ID and source idempotency key (M6 (a)). Invalid limits and cursors return 400.
  - `LedgerOpenApiContractIT`: real responses from both operations validate against the response schemas in `openapi/ledger-service.yaml`, using the JSON Schema validator already selected in D00-1/D01-8.
  - The execution-plan evidence is recorded in H.4.

<a id="s02-t05"></a>
#### S02-T05 — Invariants endpoint and verify
- **Outcome:** The invariants endpoint reports I2–I5 results, the unresolved quarantine count and non-zero clearing balances (§0.3 C13) from one consistent snapshot. The verify endpoint rebuilds an entity's balances from its changelog, checks sequence continuity and the hash chain, and reports the first bad sequence number.
- **Estimate:** 3 h
- **Inputs:** I2–I5 at [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants); §0.3 C13 and C19 at [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications); invariants and verify rows at [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis); M5 (c), M5 (d) and M6 (b) at [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have); P4 targets at [docs/zerosum_ledger_mvp_plan.md#stage-budgets](zerosum_ledger_mvp_plan.md#stage-budgets); timeouts at [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting); runbook use of verify at [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring); upstream D00-4 (verifier role), D01-6 (clearing classification); S02-T04 OpenAPI file; S02-T06 chain verifier; D02-9 quarantine resolution rule.
- **Depends on:** S02-T04, S02-T06
- **Instructions:**
  1. Implement the I2 (global per-currency sum), I3 (balance equals the sum of deltas, running balances correct) and I4 (gapless sequence per entity) queries as standalone SQL units in the `invariants` package (D02-8). They must need only select privileges and have no web or apply dependencies, so S06-T04 can reuse them. I5 delegates to the S02-T06 chain verifier.
  2. Run all invariant queries for one response inside a single read-only repeatable-read transaction, so every figure describes the same snapshot.
  3. Per §0.3 C19, operational reads set a longer per-transaction statement timeout than the global one in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting). Choose the invariants and verify limit against the P4 targets in [docs/zerosum_ledger_mvp_plan.md#stage-budgets](zerosum_ledger_mvp_plan.md#stage-budgets), record it in D02-8, and leave the global value unchanged.
  4. Report the count of unresolved quarantine rows, using the resolution rule from D02-9.
  5. Report non-zero clearing balances only, using the clearing classification from D01-6. Never hard-code account codes in ledger-service; if D01-6 has no such classification, raise a change request to S01. Apply no age or settlement-cycle filter: the S06 verifier judges age and break matching for I9 (§0.3 C13).
  6. Choose in D02-8 how the invariants endpoint handles I5 across all entities within the P4 target: a count of failing entities over a bounded scan, or a pointer to per-entity verify plus the S06 verifier. Document the choice in the OpenAPI description.
  7. **Verify.** In one read-only repeatable-read transaction, stream the entity's changelog in sequence order with a bounded fetch size. Recompute each account's running balance, compare every row's running balance and the final balances with stored accounts, check sequence continuity, and recompute the hash chain. Return the fields proposed in [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis), as selected in D02-7.
  8. Add both operations to `openapi/ledger-service.yaml` and extend `LedgerOpenApiContractIT`.
- **Edge cases and failure behavior:**
  - Applies commit while invariants or verify run. The snapshot prevents false inconsistencies, and a test proves it.
  - When several rows are bad, verify reports the lowest bad sequence number.
  - Stored balances can be correct while one row's running balance is wrong. That is still inconsistent.
  - Verify on a very large entity streams rows and never materializes the whole changelog. If the timeout override is exceeded, the response is a problem response with a distinct code, never "consistent".
  - Long snapshots on a busy database delay vacuum. The endpoint is operational, not interactive; state this in the OpenAPI description.
  - Resolved quarantine rows (per D02-9) are not counted.
- **Outputs:** planned `invariants` package with I2–I4 SQL units; planned verify and invariants controllers; planned OpenAPI additions; planned `InvariantsApiIT`, `VerifyApiIT`, `InvariantQueriesVerifierRoleIT`; H.1 row D02-8 and completion of D02-7.
- **Verification and definition of done:**
  - `InvariantsApiIT`: after the worked example, I2 is zero for every currency, I3 and I4 report no violations, the quarantine count is zero and the clearing report lists exactly the clearing accounts with non-zero balances (none after O1–O7, at least one after a partial prefix of the worked example). After a quarantined record, the count is one. On a deliberately corrupted database (triggers bypassed by a superuser session in the test container only), I3 and I4 violations are reported.
  - `VerifyApiIT`: a clean entity is consistent, and the rows checked equal its changelog row count. A tampered delta, a tampered running balance and a removed row are each detected with the correct first bad sequence number. Verify during concurrent applies stays consistent.
  - `InvariantQueriesVerifierRoleIT`: the I2–I4 units run successfully under the D00-4 verifier role.
  - An informal timing of verify and invariants on a large generated entity is recorded in H.4 as context only. P4 is measured in S07, not gated here.

<a id="s02-t06"></a>
#### S02-T06 — Hash-chained changelog
- **Outcome:** The per-entity chain rule and canonical byte form (D02-6) are fixed, versioned and pinned by known-answer vectors. The single hasher used by apply conforms to them, and the chain verifier detects any modified, removed, inserted or reordered row (I5, S1).
- **Estimate:** 2 h
- **Inputs:** S1 at [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have); hash step at [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm); I5 at [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants); S1 cut rule at [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut); D02-1 hash columns; S02-T02 hasher; S02-T03 harness.
- **Depends on:** S02-T02
- **Instructions:**
  1. Specify the canonical form in D02-6, starting from the field list proposed in [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm). Make the encoding unambiguous:
     - fixed field order;
     - length-prefixed strings in UTF-8, or fixed-width fields, never delimiter-joined text;
     - one defined representation each for UUIDs, currency codes and signed amounts;
     - an explicit rule for the first row of an entity, which has no previous hash;
     - a form version identifier.
  2. Decide explicitly whether the database-assigned recorded time is part of the hash. Including it requires reading the value back inside the apply transaction. Record the choice and its reason.
  3. The chain scope is the entity: one chain across all of that entity's accounts and currencies, following the per-entity sequence.
  4. Align the S02-T02 hasher with the specification. Apply and the chain verifier call the same implementation; there is no second copy.
  5. Add a known-answer vector file with a handful of rows covering the first row, a multi-currency entity and negative amounts. The vectors are the portable contract that S06 and S09 check themselves against.
  6. Implement the chain verifier used by verify (S02-T05) and I5. It returns the first sequence number where the stored hash or the previous-hash link fails.
  7. Enable full hash verification in `LedgerConcurrencyStressIT` and rerun it in both modes.
  8. If the minimum cut in [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut) is invoked before this task runs, record D02-6 as "Dropped (minimum cut)", note in H.5 that I5 is unavailable to S06, and skip the remaining instructions.
- **Edge cases and failure behavior:**
  - **Modified row.** A row modified with its own hash recomputed breaks the next row's previous-hash link; verification reports the modified row's sequence number.
  - **Removed row.** Removing a row, with or without renumbering later rows, is caught by either the sequence check or the chain link.
  - **Full rewrite.** Rewriting the whole chain together with the entity's stored head cannot be detected by the chain alone. The chain is tamper-evident only relative to an independently kept head; record this limitation in H.5.
  - **Form changes.** Changing the canonical form after data exists invalidates stored chains. Before release, reset local databases; after release, a change needs a new form version and an ADR.
  - **Encoding ambiguity.** Null versus empty previous hash, byte order and text encoding are specified, not left to library defaults.
- **Outputs:** planned canonical-form specification (code documentation in the `changelog` package, referenced from D02-6); planned `services/ledger-service/src/test/resources/hash-chain/known-answer.json`; planned `HashChainKnownAnswerTest` and `HashChainTamperIT`; H.1 row D02-6.
- **Verification and definition of done:**
  - `HashChainKnownAnswerTest`: computed digests equal the vector file for every row.
  - `HashChainTamperIT`: modification, removal, insertion and reordering are each detected with the expected first bad sequence number (tampering done by a superuser session in the test container only).
  - `LedgerConcurrencyStressIT` is green in both modes with full chain verification of every entity.
  - D02-6 is recorded in H.1 with the form version.

<a id="phase-4"></a>
### Phase 4 — SP1 lock study

**Objective:** Measure per-order apply throughput and apply time when every order locks the hot platform entity, record the result honestly, and decide whether S3 batched apply becomes must-have.

**Exit checkpoint:** `docs/results/sp1-lock-study.md` exists with every planned run either measured or marked "Not run" with a reason, D02-10 is recorded, ADR-0005 cites the report, and G1 has been evaluated in H.6.

<a id="s02-t07"></a>
#### S02-T07 — SP1 lock study, results report and S3 decision
- **Outcome:** The SP1 question in [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes) is answered with measured data in `docs/results/sp1-lock-study.md`. D02-10 records the S3 must-have decision, and ADR-0005 cites the evidence.
- **Estimate:** 4 h
- **Inputs:** SP1 row at [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes) (question, writer counts, decision criterion); SP1 row at [docs/zerosum_ledger_mvp_plan.md#perf-tests](zerosum_ledger_mvp_plan.md#perf-tests) (duration, repetitions, recorded metrics, variance rule); environment and durability rules at [docs/zerosum_ledger_mvp_plan.md#performance](zerosum_ledger_mvp_plan.md#performance); warm-up at [docs/zerosum_ledger_mvp_plan.md#cold-warm](zerosum_ledger_mvp_plan.md#cold-warm); hot-entity estimate at [docs/zerosum_ledger_mvp_plan.md#bottlenecks](zerosum_ledger_mvp_plan.md#bottlenecks); S3 at [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have); R3 and R9 at [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register); upstream D00-1, D00-3, D00-9 (`docs/results/TEMPLATE.md`), D01-10; S02-T02 engine and S02-T03 harness (D02-11).
- **Depends on:** S02-T02, S02-T03
- **Instructions:**
  1. **Runner.** Build the study runner on the D02-11 harness. Use per-order mode; every generated order touches the hot platform entity; each writer thread has its own connection. Take writer counts, window duration and repetitions from [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes) and [docs/zerosum_ledger_mvp_plan.md#perf-tests](zerosum_ledger_mvp_plan.md#perf-tests), store them in study configuration with a D02-10 trace comment, and pre-generate payloads before each window so the client is not the bottleneck.
  2. **Database.** Run against PostgreSQL configured as in D00-3 (image, memory limit, settings). Do not relax durability or hand-tune the database for the study.
  3. **Warm-up.** Warm up per [docs/zerosum_ledger_mvp_plan.md#cold-warm](zerosum_ledger_mvp_plan.md#cold-warm) before every measured window and exclude it from statistics.
  4. **Measurements, per run:**
     - committed orders per second over the window;
     - apply transaction time p50 and p95 from the harness's own timer around the entrypoint;
     - lock-wait distribution from the entrypoint's lock-acquisition timing, optionally cross-checked by sampling lock waits from the database's activity view.
     Name the method in the report. Permanent metric names and the production lock-wait sampler belong to S07 (D07-1, D07-7), so register no production metrics here.
  5. **Invariants after each run.** Record retry counts by class, then run I2–I5. A run with a violation or with retries exhausted is invalid: it is investigated, not reported as a data point.
  6. **Raw data.** Write raw per-run data (JSON or CSV) under `docs/results/sp1/` and link it from the report.
  7. **Report.** Write `docs/results/sp1-lock-study.md` from the D00-9 template:
     - actual machine and Docker VM allocation, versions per D00-1, git SHA, seeds and study configuration;
     - for each writer count, the median of repetitions and the min–max range;
     - the measured ceiling compared with the ESTIMATE in [docs/zerosum_ledger_mvp_plan.md#bottlenecks](zerosum_ledger_mvp_plan.md#bottlenecks), labeled MEASURED vs ESTIMATE;
     - a short bottleneck interpretation.
  8. **Decide D02-10.** Define the ceiling in the report (the highest median-of-repetitions throughput across writer counts), apply the criterion in [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes), and record whether S3 becomes must-have. If it does, D02-3's default batch mode for S04 and the S07-T06 starting point change as described in D.4.
  9. **ADR-0005.** Add the measured evidence to its consequences and link the report.
  10. **Incomplete runs.** If the study machine is not the reference machine in [docs/zerosum_ledger_mvp_plan.md#assumptions](zerosum_ledger_mvp_plan.md#assumptions), label every result with the machine used. Never extrapolate a missing writer count; mark it "Not run" with the reason and record the dependency in H.5.
- **Edge cases and failure behavior:**
  - **Noise (R9).** Close other workloads and keep repetitions. If the ranges straddle the decision criterion, say so and choose the decision that does not weaken the gate: S3 must-have. Record the ambiguity in the rationale.
  - **Client saturation.** A saturated harness CPU or a pool smaller than the writer count invalidates the throughput number. Check both and record them.
  - **Deadlocks.** With one hot entity and sorted locking, deadlocks should not occur. Any deadlock retry must be explained before the report is written.
  - **Timebox.** When the timebox runs out, report what was measured and mark the rest "Not run". Extra runs are funded from unallocated contingency only, and the hours are recorded in I.1.
  - **Environment mismatch.** Results from a Testcontainers database with different settings are smoke checks, not SP1 data.
- **Outputs:** planned SP1 runner in the ledger-service test source set (tag per D00-10, excluded from CI); planned `services/ledger-service/src/test/resources/sp1/` configuration; planned `docs/results/sp1-lock-study.md` and `docs/results/sp1/` raw data; planned ADR-0005 update; H.1 row D02-10.
- **Verification and definition of done:**
  - The report contains every field required by the D00-9 template. Every writer count × repetition cell is either measured or "Not run" with a reason.
  - Every counted run passed I2–I5 with zero exhausted retries, and this is recorded per run in the raw data.
  - D02-10 states the computed ceiling, the criterion reference and the decision. ADR-0005 links the report.
  - The runner command is documented in the report, and a one-writer smoke run completes against the compose database.

<a id="conditional-work"></a>
### Conditional and deferred work

<a id="s02-c01"></a>
#### S02-C01 — Account-level optimistic locking (G1 architecture alternative)
- **Outcome:** Only if triggered: before any S04 work starts, ledger apply switches from sorted entity-level locks to account-level optimistic version checks (the Modern Treasury model), with an ADR and all S02 evidence re-established.
- **Estimate:** 0 h
- **Inputs:** G1 change-architecture clause at [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates); concurrency guidance at [docs/zerosum_ledger_mvp_plan.md#industry-designs](zerosum_ledger_mvp_plan.md#industry-designs); invariant definitions at [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants); contingency rules at [docs/README.md#effort](README.md#effort); change procedure at [docs/README.md#conflict-resolution](README.md#conflict-resolution); D02-1, D02-3, D02-4, D02-6; S02-T03 and S02-T07 evidence.
- **Depends on:** S02-T03, S02-T07
- **Instructions:**
  1. **Trigger.** S02-T03 or S02-T07 evidence shows deadlocks, exhausted retries or sequence gaps that root-cause work within S02 cannot resolve. Record the evidence paths in I.2. Low throughput alone is not a trigger; it belongs to SP4 in S07.
  2. **Funding.** Unallocated contingency ([docs/README.md#effort](README.md#effort)). The master gives no hour estimate, so estimate the conditional hours before starting and record them in H.5 and I.1.
  3. **Before coding.** Update D02-4 and ADR-0005 (superseding the entity-lock decision) with the rationale and an impact assessment covering S04-T02 and S04-T03 retry semantics, S07-T06 SP4 options and the S08-T03 A1 seam.
  4. **Preserve I4 and I5.** A gapless per-entity sequence and a per-entity chain still need a per-entity serialization point. Either keep one (and measure how much of the benefit remains), or move sequence and chain scope to the account. Moving the scope changes master invariant definitions and requires a master change request; never weaken them silently.
  5. Add the version column or columns through a new additive migration ([docs/zerosum_ledger_mvp_plan.md#migrations](zerosum_ledger_mvp_plan.md#migrations)). Classify optimistic conflicts as transient in D02-4.
  6. Re-run all S02-T02, S02-T03, S02-T05 and S02-T06 verifications, and add a new section to the SP1 report with a re-run of the study.
- **Edge cases and failure behavior:**
  - Optimistic conflicts on the hot platform entity can cause retry storms. Retries stay bounded and end in the typed exception, never in a skipped order.
  - A lost update would show up as an oracle mismatch in the stress test. That check must stay in place.
- **Outputs:** planned additive migration; planned ADR-0005 revision; planned updated engine and tests; updated D02-4, D02-10 and H.6.
- **Verification and definition of done:** every S02 verification is green again; G1 is re-evaluated and recorded in H.6; affected downstream tasks are listed in I.2.

**Deferred improvements** (not planned in this step)

- **Batched apply as the default path (S3).** Decided by D02-10 and tuned in SP4: [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have).
- **Hot-entity sharding (S4).** [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have), owned by S07.
- **Single-round-trip PL/pgSQL apply, and asynchronous balances for hot system accounts.** [docs/zerosum_ledger_mvp_plan.md#bottlenecks](zerosum_ledger_mvp_plan.md#bottlenecks). The former is S07-C01; the latter needs an ADR and is not planned.
- **Ledger replay tool.** [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery); conditional, with no step assigned.
- **Table partitioning and archival, pending balances, TigerBeetle backend comparison.** [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred).

<a id="risks-and-recovery"></a>
## F. Risks and recovery

| Risk | Detection (signal or test) | Recovery / fallback |
|---|---|---|
| Deadlocks under mixed entity sets caused by lock-order or collation mismatch (R2 in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register)) | Deadlock retry count in `LedgerConcurrencyStressIT` output; `ApplyLockTimeoutRetryIT` | Fix the collation or ordering in D02-1/D02-3 and rerun; if unresolvable, trigger S02-C01 before any S04 work |
| Double application under concurrent duplicates (R2) | Oracle mismatch or I3 failure in the stress test; `ApplyDuplicateIT` | Keep the dedupe insert ahead of locks and computation; root-cause with the printed seed; never relax the assertion |
| Sequence gaps breaking M5 (b) | I4 check in the stress test and the invariants endpoint | Remove any sequence object or partial-commit path; persistent gaps trigger S02-C01 |
| Hot-entity ceiling below the SP1 criterion (R3) | S02-T07 report | Record S3 as must-have in D02-10; S07 chooses the mitigation in SP4 ([docs/zerosum_ledger_mvp_plan.md#bottlenecks](zerosum_ledger_mvp_plan.md#bottlenecks)) |
| Retry storms or exhausted retries under load | Typed exception count and retry counts by class | Adjust D02-4 bounds within ADR-0005; S04 pauses rather than skips; never quarantine a transient failure |
| Owner or superuser bypasses append-only triggers | Documented in S02-T01 | H.5 limitation; S1 chain and the S06 verifier provide tamper evidence |
| False inconsistencies from non-snapshot reads | Concurrent case in `VerifyApiIT` | Repeatable-read read-only snapshot for verify and invariants; fix before G1 |
| Invariants or verify exceed timeouts on large entities | Informal timing in S02-T05; P4 later in S07 | Transaction-local timeout override (D02-8), streaming reads; S07 measures and may raise a change request |
| Hash canonical form interpreted differently by other implementations | Known-answer vector mismatch in S06 or S09 | Versioned form; vectors are the contract; any change goes through D02-6 and the change procedure |
| Divergence from the v1.2 schema clarifications (§0.3 C8, C17, C18) | S02-T01 review against [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas) | Resolved in master v1.2; if S04 needs a different position or quarantine shape, S04 raises a change request against D02-1/D02-9 |
| Upstream contract changes (D01-5, D01-6, D01-8, D01-9, D00-4) | Change detection at phase boundaries | Mark affected tasks "Needs review", rerun their verifications ([docs/README.md#change-detection](README.md#change-detection)) |
| SP1 noise or a client-bound harness (R9) | Min–max ranges, harness CPU, pool size check | More repetitions, pre-generated payloads, conservative decision recorded with rationale |
| Stress test too slow for the pull-request CI target | CI job duration against [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd) | Full size nightly, reduced size on pull requests (D02-11) |
| Capacity overrun at G1 (R11) | Cumulative hours against the G1 narrow-scope condition | Apply [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut) (S1 dropped, smaller stress test) |
| Read APIs reachable without authentication | H.5 item | Keep ports local-only until S03-T03 wires `libs/auth` (§0.3 C9) |

<a id="acceptance-checklist"></a>
## G. Acceptance checklist

- [x] M5 (b): changelog sequence numbers are gapless per entity in `LedgerConcurrencyStressIT` and in the I4 query ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)). *(I4 asserted green after every stress run, both modes; S02-T03 evidence.)*
- [x] M5 (c): every sampled global per-currency sum during the stress run, and after it, is zero (I2 in [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants)). *(The sampler asserts it read the global sum at least once and that every sample was zero, plus I2 after the run.)*
- [x] M5 (d): every account balance equals the sum of its changelog deltas, with correct running balances (I3). *(I3 asserted green after every stress run, covering both balance sums and running balances.)*
- [x] Ledger-level duplicate application (sequential, concurrent, within a batch) yields identical balances, supporting the M5 (a) criterion that S04 proves end-to-end. *(All three duplicate forms applied in the stress run; balances equal the in-memory oracle, and `ApplyDuplicateIT` covers the sequential case.)*
- [ ] M6 (a): every changelog row returned by the API links to its money order and source idempotency key.
- [ ] M6 (b): verify rebuilds balances from the changelog, matches stored balances and detects each tamper class.
- [ ] M6 (c): the ledger calls of the audit walk are documented and tested. M6 (c) is not a G1 condition; S04's end-to-end tests verify it (§0.3 E11 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)).
- [x] Immutability enforced: every append-only table rejects update, delete and truncate for the application and owner roles (§0.3 O3; G1 proceed condition in [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates)). *(`LedgerAppendOnlyIT`: 2 append-only tables x {UPDATE, DELETE, TRUNCATE} x {`ledger_app`, `ledger_owner`} all rejected with 42501, with a negative control; H.4.)*
- [x] Worked example O1–O7 and the O8 variant applied through the engine match the D01-9 expected balances ([docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example)). *(`WorkedExampleApplyIT`, per-order and batched, against the D01-9 expected balances; H.4.)*
- [x] Seeded generative apply with duplicates and reordering passes, with seeds reported per ADR-0009 ([docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers)). *(`SeededApplyPermutationIT`: 120 orders with duplicates and a bounded shuffle, per-order and batched agree, seed printed; H.4.)*
- [x] The concurrency stress test runs at the parameters in [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) (or the recorded D02-11 PR/nightly split), with zero exhausted retries and every deadlock retry explained. *(Run locally at the full master size, 32 threads x 20 000 orders, 30% duplicates, green in both modes with zero deadlock, lock-timeout and connection retries; the pr size runs on every push and the full size on the nightly schedule per D02-11.)*
- [ ] I2–I4 queries run under the read-only verifier role (TB5), and I5 verification passes for every entity in the stress run ([docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have) S1), or D02-6 records the minimum cut.
- [x] The apply engine writes quarantine rows under the surrogate key, including undecodable records, a duplicate quarantine insert for the same Kafka position is a no-op, and the quarantine table accepts resolution updates (§0.3 C8, C18, C25). *(`ApplyQuarantineIT` for the surrogate key, undecodable records and the repeated Kafka position; `LedgerGrantsIT` for the resolution update; H.4.)*
- [ ] The invariants endpoint reports the quarantine count and non-zero clearing balances only (§0.3 C13), and invariants and verify run under the D02-8 per-transaction timeout (§0.3 C19).
- [ ] `openapi/ledger-service.yaml` covers balances, changelog, invariants and verify, and `LedgerOpenApiContractIT` passes.
- [ ] SP1 is recorded in `docs/results/sp1-lock-study.md` per [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes) and [docs/zerosum_ledger_mvp_plan.md#perf-tests](zerosum_ledger_mvp_plan.md#perf-tests), with unexecuted runs marked "Not run".
- [ ] D02-10 records the S3 must-have decision against the SP1 criterion.
- [ ] ADR-0004 (ledger half) and ADR-0005 are committed, and ADR-0005 cites the SP1 report ([docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions)).
- [ ] G1 has been evaluated against every clause in [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) (the v1.2 proceed clause per §0.3 E11, narrow scope, change architecture) and the result recorded in H.6.
- [ ] Section H is filled with actual paths and evidence, I.1 statuses are current, and I.2 was refreshed before handoff.

<a id="decisions-and-outputs"></a>
## H. Decisions and outputs register

### H.1 Decisions and rationale

| ID | Decision | Rationale | Alternatives considered | Status | Date |
|---|---|---|---|---|---|
| D02-1 | Migration `services/ledger-service/src/main/resources/db/migration/V2__ledger_schema.sql` (after the S00 `V1__baseline`), run by Flyway as `ledger_owner`. It follows the master v1.2 DDL with these selections. **(a) Source key (C17):** `applied_orders.source_system`, `idempotency_key` NOT NULL. **(b) Source position (C17):** nullable `kafka_topic`, `kafka_partition`, `kafka_offset`. `kafka_topic` is added to the master DDL so a position is unambiguous; a CHECK requires all three null or all three set. **(c) Quarantine:** see D02-9. **(d) Collation:** `COLLATE "C"` on `entity_id`, `account_code`, `currency` in `entities`, `accounts`, `entity_changelog`; `LedgerSchemaMigrationIT` proves database order equals Java `String` order. **(e) Hash columns:** created now and mandatory: `entities.last_hash` (null until the first row), `entity_changelog.hash_version smallint NOT NULL`, `prev_hash` (null exactly when `seq = 1`, CHECK), `row_hash NOT NULL`. `hash_version` is added so D02-6 can version the form without a later rewrite. Other CHECKs: `kind` ∈ rider/driver/platform/provider, `normal_side` ∈ DEBIT/CREDIT, `last_seq ≥ 0`, `seq > 0`, `delta_minor ≠ 0`. Index `entity_changelog_order (order_id)`. No foreign key into append-only tables, so no cascading truncate path exists. **Grants:** `ledger_app` has SELECT and INSERT on all five tables, UPDATE only on `entities` and `accounts`, and column UPDATE on `quarantined_orders.resolved_at`; no DELETE or TRUNCATE anywhere. `verifier` has SELECT everywhere through D00-4 default privileges. **Change request CR-S02-01 to D00-4:** `ALTER DEFAULT PRIVILEGES FOR ROLE ledger_owner … REVOKE UPDATE, DELETE ON TABLES FROM ledger_app`, so tables added by later ledger migrations don't inherit runtime write access (ledger database only; `DatabaseIsolationIT` revalidated green). | Master DDL plus v1.2 clarifications; the byte-order collation removes a deadlock source; creating hash and position columns now keeps later migrations additive | Default database collation (sort order could differ from Java, R2); `kafka_partition`/`offset` without topic (ambiguous across topics); leaving S00 default DML grants (runtime DELETE on ledger tables, TB3) | Accepted | 2026-09-15 |
| D02-2 | **Pattern** (block marked `decision: D02-2` in V2, copied verbatim by S03 and S05 with table and role names changed): one function `reject_mutation()` raising **SQLSTATE 42501**; per append-only table a `BEFORE UPDATE OR DELETE … FOR EACH ROW` trigger and a `BEFORE TRUNCATE … FOR EACH STATEMENT` trigger; `REVOKE UPDATE, DELETE, TRUNCATE … FROM <runtime role>`. The runtime role fails at the privilege check and the owner at the trigger, **both with 42501**, so callers and tests see one code. `INSERT … ON CONFLICT DO NOTHING` never fires the triggers. **Append-only tables:** `applied_orders`, `entity_changelog`. `quarantined_orders` is operational and gets no triggers (C18); the Flyway history table never gets them. **Test helper:** `services/ledger-service/src/test/java/dev/zerosum/ledger/support/AppendOnlyAssertions.java` (`assertRejected(connection, table, mutation, setClause, where)`: asserts 42501 and an unchanged row count; needs an existing row so row triggers fire). There is no shared test library (D00-2), so S03 and S05 copy the helper. | One error code across both enforcement layers; copyable SQL with no new shared module | A distinct custom SQLSTATE for triggers (the runtime role would get 42501 from the privilege check instead, giving two codes per cell); rules or views instead of triggers (bypassable by the owner); privileges only (the owner could still mutate, M2 (a) wording) | Accepted | 2026-09-15 |
| D02-3 | **Entrypoint:** `LedgerApplyEngine.apply(List<ApplyRecord>)` → `ApplyBatchResult`, with `applyOne` for the per-order path. `ApplyRecord` is a raw payload plus an optional `SourcePosition(topic, partition, offset)`; no Kafka types appear in the signature. `ApplyBatchResult` carries one `ApplyOutcome` per input record (`APPLIED`, `DUPLICATE`, or `QUARANTINED` with a `QuarantineCode`), plus the SP1 timings (total, lock wait) and retry counts by class. **Outside the transaction:** decode, money-order schema validation, a rejection of non-integer lexical amounts (the schema accepts `2500.0`, D01-8), and the ‡ rules through `RuleSet.LEDGER` (CR-S02-02). **Intra-batch dedupe:** repeated order IDs collapse to the first occurrence, before any SQL. **Transaction (one per batch, READ COMMITTED):** transaction-local timeouts → quarantine inserts → conflict-skipping `applied_orders` inserts returning the fresh IDs → provision entities in sorted order → `SELECT … ORDER BY entity_id FOR UPDATE` → provision accounts (after the lock, because an account insert takes FOR KEY SHARE on its parent entity row) → read balances → compute balances, sequence numbers and hash rows in memory → batched updates and inserts → commit. **Isolation policy:** a non-transient failure in a batch of more than one record re-runs the records one at a time, so only the offending record is quarantined; in per-order mode it is quarantined directly, in a fresh transaction. **Same order ID with different content** is treated as a duplicate (orders are immutable upstream). The master asks for both payload digests to be logged; the ledger stores no payload, so it cannot compare them — divergence is exposed by I6b in S06 (deviation recorded here). **Default mode:** `ledger.apply.default-batch-size: 1`; callers choose the batch size, and D02-10 may change the default. **Test driver:** `ApplyTestDriver` wires the engine against a `DataSource` without a Spring context, and is reused by S02-T03 and S02-T07. | One write path for the listener, stress harness, SP1 runner and the later S08 seams; dedupe before locks keeps redeliveries cheap; isolation never skips money | Per-order-only API (S3 batching would need a second path); quarantining a whole failed batch (would discard valid money orders); comparing payload digests (impossible without storing payloads, and I6b already detects divergence) | Accepted | 2026-09-15 |
| D02-4 | **Isolation:** READ COMMITTED (ADR-0005). **Transaction-local timeouts** from `application.yml`: `lock_timeout` 2 s, `statement_timeout` 5 s (master §5.11). **Transient SQLSTATEs:** `40P01` deadlock, `40001` serialization failure, `55P03` lock not available, the whole `08xxx` connection class, plus **`57P01` admin shutdown and `57P03` cannot connect now**, which the master's list omits: a backend terminated by an operator must never cause money to be quarantined. `57014` query canceled (statement timeout) stays non-transient, because a retry would hit the same limit. Classification reads the SQLSTATE of the deepest `SQLException` in the cause chain, with a bounded walk so a cyclic chain cannot hang. **Backoff:** jittered exponential, 100 ms → 5 s, `max-attempts: 10`; each attempt re-runs the transaction from fresh reads. **Exhaustion:** `RetriesExhaustedException` carrying the batch and the attempt count; S04 maps it to pause plus alert (D04-4). **Interruption** during backoff stops retrying and propagates; nothing was committed. | Money is never skipped or quarantined for a transient failure; SQLSTATE classification survives driver and framework translation differences | Retrying every failure (would loop on constraint violations); Spring exception subclasses (translation varies); unbounded retries (a stuck listener with no alert) | Accepted | 2026-09-15 |
| D02-5 | Entities and accounts are provisioned inside the apply transaction, in sorted order, with `ON CONFLICT DO NOTHING`. **Entities are provisioned before the `FOR UPDATE` lock and accounts after it** (ADR-0005 decision point 3): `accounts.entity_id` references `entities`, so provisioning accounts first would upgrade a `FOR KEY SHARE` lock to `FOR UPDATE` and deadlock concurrent batches that share an entity. The entity kind comes from the entity-ID prefix through `ChartOfAccounts.kindOf`; the account's normal side comes from `ChartOfAccounts.normalSide` (D01-6). There is no registration API, and the ledger never creates an account the chart does not allow, because the ‡ rules reject such an order before the transaction. | One rule for kinds and sides, owned by `libs/money`; no separate onboarding step for new riders, drivers or providers | A registration API (extra step before money can move); provisioning outside the transaction (a concurrent order could see a missing account) | Accepted | 2026-09-15 |
| D02-6 | — | — | — | Pending | — |
| D02-7 | — | — | — | Pending | — |
| D02-8 | — | — | — | Pending | — |
| D02-9 | **Table** `quarantined_orders`: `quarantine_id bigint GENERATED ALWAYS AS IDENTITY` primary key, nullable `order_id uuid` (null when undecodable, C8), `payload bytea NOT NULL`, `error_code text NOT NULL`, `error_detail text`, nullable `kafka_topic`/`kafka_partition`/`kafka_offset` with `UNIQUE NULLS DISTINCT` on the three (a redelivered record is quarantined once, while null positions never conflict, C25), `quarantined_at`, `resolved_at`. **Operational, not append-only** (C18): the runtime role may update `resolved_at` only. **Resolution rule:** a row is unresolved while `resolved_at IS NULL`; the invariants count (D02-8) counts only unresolved rows. **Writer:** the apply engine inserts with `ON CONFLICT (kafka_topic, kafka_partition, kafka_offset) DO NOTHING`; S04 owns DLQ publishing and the poison-message policy. **Error-code set:** selected with the apply engine in S02-T02 (recorded there). | Undecodable records must be storable; redelivery after a crash must not duplicate quarantine rows; operators need to mark rows resolved | `order_id` as the key (undecodable records have none, C8); append-only quarantine (no resolution possible, C18) | Accepted (error codes completed in S02-T02) | 2026-09-15 |
| D02-10 | — | — | — | Pending | — |
| D02-11 | **Harness** `LedgerConcurrencyStressIT` in the ledger-service test source set, tagged `integration` (D00-10). **Parameters** live in `services/ledger-service/src/test/resources/stress/stress.properties` behind a D02-11 trace comment; the test reads them and no assertion hard-codes them. **Two sizes:** `full` is the master §8.2 size (32 threads x 20 000 orders, 30% duplicates); `pr` is 8 threads x 2 000 orders at the same duplicate ratio. `-Pzs.stress.size=full` or `=pr` selects one (default `pr`), and the ledger build forwards it as a system property so changing it invalidates the test task. **CI placement:** the existing `integration` job runs the `pr` size on every push and pull request (master §10.3, <= 10 min) and the `full` size on the nightly `schedule` trigger; no new job or tag. **Orders** come from the D01-10 `TripSequenceGenerator`, once per currency, with USD, JPY and KWD covering 2, 0 and 3 minor-unit digits. Riders are remapped onto a shared pool so rider and driver lock sets intersect in different combinations, and a configured share of platform commissions is split into two entries so an entity repeats across entries (C4). Every generated candidate is revalidated with `ZeroSumValidator` before submission, so a failure is a product bug rather than a harness bug. **Duplicates** appear in three forms: adjacent to the original so both land in one batch, inserted earlier so two threads submit concurrently, and appended so the original has committed. **Oracle:** balances summed in memory with `Money` (D01-1) over the unique orders only. **Modes:** per-order (batch size 1) and batched (size from the configuration). **Sampler:** a verifier-role thread reads `sum(balance_minor)` grouped by currency in one statement while writers run; the test asserts it sampled at least once and that every sample was zero, so a silent no-op sampler cannot pass. **Assertions:** oracle balances, I2-I4, chain links, one applied-order row per unique order, an empty quarantine, no writer exception, and **zero deadlock retries**. **Pool** is sized to writers + 2, so the run measures lock contention and not pool waits. | Keeping the master's numbers in a file stops them drifting into assertions; two sizes keep the pull-request job inside its budget while the nightly run still exercises the master's size; asserting zero deadlock retries turns ADR-0005's claim into a test, which is what found two real lock defects | A separate stress tag and CI job (more wiring than one size property, and the integration job already runs nightly); hard-coded parameters (the master's numbers would drift silently); asserting only "no unhandled deadlocks" (retried deadlocks would have stayed invisible and both defects would have shipped) | Accepted | 2026-09-15 |
| D02-12 | [ADR-0004](adr/0004-balance-policy-outside-ledger.md), ledger half: apply rejects only structurally invalid orders (undecodable, schema-invalid, account not allowed for the kind, or not zero-sum per currency) and **never** rejects for a balance reason. Balances may go negative in either direction; a driver `payable` in debit is driver debt. The only balance-related failure is arithmetic overflow of a `long`, which is a representation limit, not policy: the record is isolated and quarantined with `ARITHMETIC_OVERFLOW`. Balance-dependent policy (payout eligibility, minimum payout, one in-flight payout, freshness) belongs to instrument-service (S05, D05-7), which writes the policy half of the ADR. | A published valid order must always land, so the order store and the ledger converge (I6, I6b); policy needs in-flight knowledge the ledger doesn't have | Ledger-side balance checks (would reject after publish and split the stores); silently clamping at zero (would destroy the audit trail) | Accepted | 2026-09-15 |

### H.2 Implementation and configuration locations

| Item | Planned path | Actual path | Traced to |
|---|---|---|---|
| Ledger migrations (schema, append-only triggers, grants) | `services/ledger-service/src/main/resources/db/migration/` | `services/ledger-service/src/main/resources/db/migration/V2__ledger_schema.sql` (`bc8a3c1`) | D02-1, D02-2, D02-9 |
| Append-only assertion test helper | ledger-service test-fixtures source set (per D00-10) | `services/ledger-service/src/test/java/dev/zerosum/ledger/support/AppendOnlyAssertions.java` — the test source set, since D00-10 defines no test-fixtures set for services (`bc8a3c1`) | D02-2 |
| Apply engine, decoding, retry classifier | `services/ledger-service/src/main/java/<package-root>/apply/` | `services/ledger-service/src/main/java/dev/zerosum/ledger/apply/` (`d69b9f6`) | D02-3, D02-4, D02-5, D02-12 |
| SQL access layer | `services/ledger-service/src/main/java/<package-root>/store/` | `services/ledger-service/src/main/java/dev/zerosum/ledger/store/` (`d69b9f6`) | D02-1, D02-3 |
| Changelog hasher and chain verifier | `services/ledger-service/src/main/java/<package-root>/changelog/` | — | D02-6 |
| Invariant SQL units | `services/ledger-service/src/main/java/<package-root>/invariants/` | — | D02-8 |
| Read API controllers | `services/ledger-service/src/main/java/<package-root>/api/` | — | D02-7 |
| Apply timeouts, retry bounds, default batch mode, pool size | `services/ledger-service/src/main/resources/application.yml` | same (`09cedee`, `0bf9f6e`, `f840db0`, `d69b9f6`) | D02-3, D02-4 |
| Changelog page size, invariants and verify timeout override | `services/ledger-service/src/main/resources/application.yml` | — | D02-7, D02-8 |
| Test driver | ledger-service test-fixtures or test source set (per D00-10) | `services/ledger-service/src/test/java/dev/zerosum/ledger/support/ApplyTestDriver.java` (test source set) (`d69b9f6`) | D02-3 |
| Stress harness configuration | `services/ledger-service/src/test/resources/stress/` | `services/ledger-service/src/test/resources/stress/stress.properties` | D02-11 |
| SP1 study configuration | `services/ledger-service/src/test/resources/sp1/` | — | D02-10 |

### H.3 Produced artifacts

| Artifact | Planned path | Actual path | Revision/hash |
|---|---|---|---|
| Ledger read API specification | `openapi/ledger-service.yaml` | — | — |
| ADR-0004 balance policy outside the ledger | `docs/adr/0004-balance-policy-outside-ledger.md` | same (Accepted) | `d69b9f6` |
| ADR-0005 ledger entity locks and retries | `docs/adr/0005-ledger-entity-locks.md` | same (Accepted) | `d69b9f6`; revised by S02-T03 (decision points 3 and 4) |
| SP1 lock study report | `docs/results/sp1-lock-study.md` | — | — |
| SP1 raw run data | `docs/results/sp1/` | — | — |
| Hash-chain known-answer vectors | `services/ledger-service/src/test/resources/hash-chain/known-answer.json` | — | — |
| Ledger integration, stress and API test suites | `services/ledger-service/src/test/` (and integration source set per D00-10) | `services/ledger-service/src/test/java/dev/zerosum/ledger/{schema,apply,stress,support}/` — the test source set, tagged per D00-10; API suites arrive with S02-T04 and S02-T05 | `bc8a3c1`, `d69b9f6`; stress committed with S02-T03 |

### H.4 Validation results and evidence

| Check | Method | Result | Evidence path | Date |
|---|---|---|---|---|
| Schema migrates from baseline and validates | `LedgerSchemaMigrationIT` | **Passed** (3 tests): Flyway history `1 SUCCESS ledger_owner`, `2 SUCCESS ledger_owner`; second migrate executes 0 migrations and `validate()` passes; repeated Kafka position quarantine insert is a no-op while null positions insert twice (C25); key columns use collation `C` and database `ORDER BY` equals Java sort for 10 mixed-case/underscore/hyphen IDs | [docs/results/s02/s02-t01-schema.txt](results/s02/s02-t01-schema.txt) | 2026-09-15 |
| Append-only enforcement per table, operation and identity | `LedgerAppendOnlyIT` | **Passed** (13 tests): 2 tables × {UPDATE, DELETE, TRUNCATE} × {`ledger_app`, `ledger_owner`} all fail with 42501 and leave row counts unchanged (each cell seeds its own row); conflict-skipping duplicate inserts return 0 rows for both roles. **Negative control:** with the changelog row trigger removed, exactly the two owner cells (`entity_changelog UPDATE/DELETE as ledger_owner`) fail; the file was restored byte-identical and the suite is green again | [docs/results/s02/s02-t01-schema.txt](results/s02/s02-t01-schema.txt) | 2026-09-15 |
| Runtime and verifier grants | `LedgerGrantsIT` | **Passed** (4 tests): verifier SELECTs all 5 tables, INSERT denied (42501) even in a read-write session; runtime role DELETE/TRUNCATE denied on mutable tables, UPDATE allowed on `entities`, `accounts` and `quarantined_orders.resolved_at` only; a table created later by `ledger_owner` gives `ledger_app` INSERT but not UPDATE/DELETE, verifier still SELECT (CR-S02-01). `DatabaseIsolationIT` 6/6 green with V2 applied | [docs/results/s02/s02-t01-schema.txt](results/s02/s02-t01-schema.txt) | 2026-09-15 |
| Worked example O1–O7 and O8 balances | `WorkedExampleApplyIT` | **Passed** (2 tests): per-order mode reproduces the D01-9 balances after O1–O7, batched mode reproduces the O8 variant; I2–I4, chain links and an empty quarantine asserted in both | [docs/results/s02/s02-t02-apply-engine.txt](results/s02/s02-t02-apply-engine.txt) | 2026-09-15 |
| Duplicate application is a no-op | `ApplyDuplicateIT` | **Passed**: O1–O7 applied, then re-applied per order, then the whole set twice inside one batch — every repeat reported `DUPLICATE`; balances, changelog count, `rider:R1` last sequence and the 7 applied-order rows unchanged | [docs/results/s02/s02-t02-apply-engine.txt](results/s02/s02-t02-apply-engine.txt) | 2026-09-15 |
| Seeded permutations with duplicates | `SeededApplyPermutationIT` | **Passed**: 120 generated orders, 20% duplicates and a bounded shuffle, applied per order and in batches of 25 on two separate databases — identical balances, I2–I4 and chain links clean, no quarantine; seed printed per ADR-0009 | [docs/results/s02/s02-t02-apply-engine.txt](results/s02/s02-t02-apply-engine.txt) | 2026-09-15 |
| Retry classification, lock-timeout retry, exhausted retries | `ApplyRetryClassifierTest`, `ApplyLockTimeoutRetryIT` (both cases) | **Passed** (21 + 2 tests): the SQLSTATE table including `57P01`/`57P03`, a bounded walk over a cyclic cause chain; a lock held 3 s causes ≥ 1 lock-timeout retry and the order still applies exactly once; a lock held past a 2-attempt schedule raises `RetriesExhaustedException` with no applied-order row and **no quarantine row**. The planned separate `ApplyExhaustedRetriesIT` is the second case of `ApplyLockTimeoutRetryIT` | [docs/results/s02/s02-t02-apply-engine.txt](results/s02/s02-t02-apply-engine.txt) | 2026-09-15 |
| Connection loss during the transaction | `ApplyConnectionLossIT` | **Passed**: the waiting apply backend is terminated with `pg_terminate_backend`; the engine retries on a fresh connection (attempts ≥ 2) and the order is applied exactly once, with no quarantine row and I3 clean | [docs/results/s02/s02-t02-apply-engine.txt](results/s02/s02-t02-apply-engine.txt) | 2026-09-15 |
| Quarantine and overflow isolation | `ApplyQuarantineIT`, `ApplyOverflowIT` | **Passed** (4 + 1 tests): garbage payload → `UNDECODABLE_PAYLOAD` with a null order ID; a ‡ violation → `STRUCTURALLY_INVALID` while a valid order in the same batch applies; redelivering the same Kafka position leaves one row (C25); `2500.0` → `SCHEMA_INVALID`; an overflowing order is isolated and quarantined with `ARITHMETIC_OVERFLOW` while the other order applies and the seeded balance is untouched | [docs/results/s02/s02-t02-apply-engine.txt](results/s02/s02-t02-apply-engine.txt) | 2026-09-15 |
| ADR-0004 negative balances allowed | `NegativeBalanceIT` | **Passed**: a payout of 1800 against a payable of 1000 is applied, leaving the driver payable in debit (+800 signed), with no quarantine and I2/I3 clean | [docs/results/s02/s02-t02-apply-engine.txt](results/s02/s02-t02-apply-engine.txt) | 2026-09-15 |
| Concurrency stress, both modes, retry counts by class | `LedgerConcurrencyStressIT` | **Passed** (2 tests) at **both** sizes. Full size (master §8.2: 32 threads x 20 000 orders, 30% duplicates): batched 26 058 submissions in 1 043 batches, 3 665 ms, 166 samples; per-order 26 001 submissions, 20 865 ms, 946 samples; **deadlock, lock-timeout and connection retries all 0** in both. Pr size (8 x 2 000): batched 542 ms, per-order 2 002 ms, all retry counts 0. Balances equal the in-memory oracle, I2-I4 and chain links clean, applied-order count equals the unique orders, quarantine empty, no `RetriesExhaustedException`, and the sampler proved it sampled (23-946 reads) with every sample zero. Same seed twice gives digest `51e726f4b9eafd02` in both runs **and** both modes. **Two real lock defects found and fixed** (ADR-0005 points 3 and 4): accounts provisioned before the entity lock (`FOR KEY SHARE` upgraded to `FOR UPDATE`; 14 and 3 deadlock retries) and `applied_orders` inserted in batch order (1 deadlock retry, batched only). **Negative control:** reverting the first fix fails both modes (24 and 7 deadlock retries); the file was restored byte-identical by SHA-256 and the run is green again. The run that failed with 1 retry is itself the control for the second fix | [docs/results/s02/s02-t03-stress.txt](results/s02/s02-t03-stress.txt) | 2026-09-15 |
| Balances and changelog APIs | `BalancesApiIT`, `ChangelogApiIT` | Not run | — | — |
| Changelog page query uses the primary-key range | Execution plan capture | Not run | — | — |
| OpenAPI contract | `LedgerOpenApiContractIT` | Not run | — | — |
| Invariants endpoint and verify | `InvariantsApiIT`, `VerifyApiIT` | Not run | — | — |
| I2–I4 under the verifier role | `InvariantQueriesVerifierRoleIT` | Not run | — | — |
| Hash chain known answers and tamper detection | `HashChainKnownAnswerTest`, `HashChainTamperIT` | Not run | — | — |
| Informal verify and invariants timing (context only) | Timed run on a large generated entity | Not run | — | — |
| SP1 lock study | Study runner and `docs/results/sp1-lock-study.md` | Not run | — | — |
| G1 gate evaluation | Section G against [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) | Not run | — | — |

### H.5 Known limitations and blockers

| Item | Type (limitation/blocker) | Impact | Owner/next action |
|---|---|---|---|
| Append-only triggers stop DML, but not DDL by the table owner or a superuser trigger bypass | limitation | Immutability guards against application and operator mistakes, not a malicious owner | Tamper evidence from S1 and the S06 verifier; list in the S09 release limitations |
| Quarantine inserts are issued in batch order, not key order | limitation | They use the same conflict-skipping pattern as `applied_orders`, so once records carry Kafka positions two batches quarantining the same positions in opposite order can deadlock. The deadlock is classified transient and retried, so no money is lost, and it is unexercised today because positions are null until S04 and `UNIQUE NULLS DISTINCT` never conflicts on null | ADR-0005 decision point 4; S04 orders these inserts when it introduces positions (D04-3) |
| Reader-role token enforcement on ledger APIs depends on the shared auth module | blocker | Ledger APIs stay local-only until wired; not a G1 condition | Resolved in master v1.2 (§0.3 C9): S03-T03 wires `libs/auth` into ledger-service |
| The M6 (c) audit walk ends at the order-service read API | limitation | Only the ledger calls are testable in S02 | Resolved in master v1.2 (§0.3 E11): M6 (c) is verified in S04's end-to-end tests |
| The invariants endpoint described a settlement-cycle age filter that ledger-service cannot compute | limitation | The endpoint reports non-zero clearing balances only | Resolved in master v1.2 (§0.3 C13): the S06 verifier judges age and break matching (I9) |
| The quarantine retry admin endpoint had no owner | limitation | No API re-applies a fixed quarantined record | Resolved in master v1.2 (§0.3 C14): deferred; the runbook's manual re-publish procedure (D04-4) replaces it |
| SP1 results describe the measurement environment, not production hardware | limitation | Numbers are an environment baseline ([docs/zerosum_ledger_mvp_plan.md#performance](zerosum_ledger_mvp_plan.md#performance)) | Label every result with the machine used |
| The hash chain is tamper-evident only relative to an independently kept head | limitation | A coordinated rewrite of a chain and its stored head is undetectable | No work planned (outside master scope) |

### H.6 Completion status

| Field | Value |
|---|---|
| Step status | In progress: S02-T01 Done (`bc8a3c1`); S02-T02 next |
| Gate result | Not evaluated (G1 after S02-T07) |
| Completed on | — |
| Completed by | — |
| Handoff accepted by next step | — |

<a id="execution-record"></a>
## I. Execution and change record

### I.1 Task execution record

| Task ID | Status | Output paths | Evidence | Blockers |
|---|---|---|---|---|
| S02-T01 | Done | `services/ledger-service/src/main/resources/db/migration/V2__ledger_schema.sql`, `services/ledger-service/build.gradle.kts`, `…/src/test/java/dev/zerosum/ledger/support/{LedgerTestDatabase,AppendOnlyAssertions}.java`, `…/schema/{LedgerSchemaMigrationIT,LedgerAppendOnlyIT,LedgerGrantsIT}.java` (`bc8a3c1`) | [docs/results/s02/s02-t01-schema.txt](results/s02/s02-t01-schema.txt): 3 + 13 + 4 tests green; `DatabaseIsolationIT` 6/6 still green with V2 applied; negative control (changelog row trigger removed) fails exactly the two owner-role cells, restored green. A first control run showed that cells shared one seeded row; the test now seeds per cell | CR-S02-01 to D00-4 raised (I.2). CI integration job green on [run 35038096697](https://github.com/ai-calypse/zerosum-ledger/actions/runs/35038096697) (1 m 43 s) |
| S02-T02 | Done | `services/ledger-service/src/main/java/dev/zerosum/ledger/{apply,store,changelog}/**`, `application.yml` (`ledger.apply.*`), `…/src/test/java/dev/zerosum/ledger/{apply,support}/**`, `docs/adr/0004-balance-policy-outside-ledger.md`, `docs/adr/0005-ledger-entity-locks.md` | [docs/results/s02/s02-t02-apply-engine.txt](results/s02/s02-t02-apply-engine.txt): 54 ledger tests green (12 suites), covering the H.4 rows above | Two deviations recorded in D02-3/D02-4: payload digests can't be logged (no stored payload; I6b covers divergence), and the transient set adds `57P01`/`57P03`. `ApplyExhaustedRetriesIT` is folded into `ApplyLockTimeoutRetryIT` |
| S02-T03 | Done | `services/ledger-service/src/test/java/dev/zerosum/ledger/stress/{LedgerConcurrencyStressIT,StressParameters}.java`, `…/src/test/resources/stress/stress.properties`, `…/support/LedgerTestDatabase.java` (pooled data source, pinned image), `…/support/MoneyOrderPayloads.java` (`adjusts_order_id`); fixes in `…/apply/LedgerApplyEngine.java` and `…/store/LedgerStore.java`; `.github/workflows/ci.yml` and `services/ledger-service/build.gradle.kts` (size selector) | [docs/results/s02/s02-t03-stress.txt](results/s02/s02-t03-stress.txt): both sizes green in both modes with zero retries of every class, a same-seed replay pair with identical digests, and a negative control for the lock-order fix | None. The full size is also wired to the nightly CI schedule; that scheduled run has not fired yet |
| S02-T04 | Planned | — | — | — |
| S02-T05 | Planned | — | — | — |
| S02-T06 | Planned | — | — | — |
| S02-T07 | Planned | — | — | — |
| S02-C01 | Planned | — | — | — |

<a id="change-record"></a>
### I.2 Consumed sources and change record

| Source/artifact path | Revision or hash | Recorded at | Affected tasks | Review outcome |
|---|---|---|---|---|
| `docs/zerosum_ledger_mvp_plan.md` | `d65384c 2026-09-15` | 2026-09-15 (S02 start at `2f5dbcf`; rechecked at the Phase 1 and Phase 2 boundaries) | All | No impact: unchanged at both rechecks |
| **CR-S02-02 → D01-5** (change request) | Need: S02-T02 instruction 2 re-checks only the ‡ rules (account allowed for the entity kind, zero-sum per currency) and never order-service rules such as the currency allow-list. D01-5 as first accepted ran the full rule set in both services, with no separable ‡ subset. Change: `ZeroSumValidator.validate(order, RuleSet.LEDGER)`; `validate(order)` stays `RuleSet.FULL`. | 2026-09-15 | S02-T02 (consumer); S01-T03 (owner) | Impact assessment: order-service (S03) unchanged (FULL default). The ledger quarantines only on structural decode failure, the money-order schema (fixed contract, D01-8) and the ‡ rules. S01-T03 marked Needs review, then revalidated with `ZeroSumValidatorTest` (new ledger rule-set test) and the full `libs/money` suite. |
| **CR-S02-03 → D00-1** (recorded fact) | Probe on 2026-09-15: ledger-service depends on `libs/contracts`, and Gradle conflict resolution selects `tools.jackson.core:jackson-databind` **3.2.1** (required by the pinned json-schema-validator 3.0.7) over the Boot 4.1.1 BOM's 3.1.5. `ContractSchemas` validates goldens on that classpath. | 2026-09-15 | S02-T02 onward (every Boot service using `libs/contracts`) | Accepted for now: a Jackson 3.x minor upgrade under Boot. Spring-context integration tests (S02-T04 onward) exercise it. If Boot misbehaves, D00-1 chooses between a validator version built for 3.1.x and a strict Jackson constraint. S01 H.5 row corrected. |
| **CR-S02-01 → D00-4** (change request) | Need: S02-T01 instruction 5 requires that tables added later don't give `ledger_app` write access, but D00-4 default privileges grant DML on every table `ledger_owner` creates. Evidence: `LedgerGrantsIT.laterLedgerTablesDoNotInheritRuntimeUpdateOrDelete`. Change: V2 runs `ALTER DEFAULT PRIVILEGES FOR ROLE ledger_owner IN SCHEMA public REVOKE UPDATE, DELETE ON TABLES FROM ledger_app` in the ledger database only. | 2026-09-15 | S02-T01; D00-4 consumers S03 (orders), S05 (instruments) | Impact assessment: other databases unchanged; `DatabaseIsolationIT` revalidated green; verifier SELECT default unchanged. S03/S05 should adopt the same line for their append-only databases. Recorded in S00 I.2. |
| `docs/zerosum_ledger_mvp_plan.md` §0.3 (v1.2) | — | 2026-09-15 (doc 1.1) | S02-T01, S02-T02, S02-T04, S02-T05 | Resolved in master v1.2 (§0.3 C8, C9, C13, C14, C17, C18, C19, E8, E11, O3); instructions, verification and acceptance items updated |
| `docs/zerosum_ledger_mvp_plan.md` §0.3 C25 and §5.7 quarantine DDL (v1.2) | — | 2026-09-15 (doc 1.1) | S02-T01, S02-T02 | Resolved in master v1.2 (§0.3 C25): quarantine rows are unique per Kafka topic, partition and offset; a duplicate quarantine insert is a no-op |
| `docs/README.md` | — | — | All | — |
| `docs/step_00_foundations.md` (register H) | `8ec5b9c 2026-09-15`; working tree `sha256 e2054a597adf69bd…` at the Phase 2 boundary | 2026-09-15 (S02 start; rechecked at the Phase 2 boundary) | All | Revalidated: the file changed only in its S00-T05 CI evidence and status rows (H.4, H.5, I.1, section G) once the public repository was authorized. No D00 decision content changed, so no completed S02 task needs review |
| `docs/step_01_domain_contracts.md` (register H) | `275dff1 2026-09-15`; working tree `sha256 2eca89fc69ab176c…` at the Phase 2 boundary | 2026-09-15 (S02 start; rechecked at the Phase 2 boundary) | S02-T02 to S02-T07 | Revalidated: only the CI placement evidence row and its acceptance-checklist item changed. No D01 decision content changed; `libs/money` and `libs/contracts` are unchanged, so S02-T02 stays valid |
| `settings.gradle.kts`, `gradle/libs.versions.toml` | — | — | All | — |
| `docker-compose.yml` | — | — | S02-T07 | — |
| `infra/postgres/init.sql` | — | — | S02-T01, S02-T05 | — |
| `services/ledger-service/` baseline migration | — | — | S02-T01 | — |
| `.github/workflows/ci.yml` | — | — | S02-T01, S02-T03 | — |
| `.env.example` | — | — | S02-T02, S02-T04 | — |
| `libs/money/` (directory hash) | — | — | S02-T02 to S02-T06 | — |
| `libs/contracts/` (directory hash) | — | — | S02-T02, S02-T04 | — |
| Seeded generator utilities (path per D01-10) | — | — | S02-T02, S02-T03, S02-T07 | — |
| `docs/adr/` ADR-0002, ADR-0003, ADR-0009 | — | — | S02-T02, S02-T07 | — |
| `docs/results/TEMPLATE.md` | — | — | S02-T07 | — |

<a id="handoff"></a>
## J. Handoff

**Outputs consumed by later steps**

| Output | Resolve from | Consuming step and tasks |
|---|---|---|
| Append-only enforcement pattern and test helper | D02-2, then the migration block and helper path in H.2 | S03 ([docs/step_03_order_service_outbox.md#s03-t02](step_03_order_service_outbox.md#s03-t02)); S05 ([docs/step_05_instruments_fake_providers.md#s05-t07](step_05_instruments_fake_providers.md#s05-t07)) |
| Apply entrypoint, per-record outcomes, default batch mode, non-transient isolation policy | D02-3 | S04 ([docs/step_04_kafka_pipeline.md#s04-t02](step_04_kafka_pipeline.md#s04-t02)); S07 span attributes ([docs/step_07_observability_performance.md#s07-t01](step_07_observability_performance.md#s07-t01)); S08 A1 and A4 seams through the change procedure (§0.3 E8) ([docs/step_08_fault_injection_ablation.md#s08-t03](step_08_fault_injection_ablation.md#s08-t03)) |
| Lock strategy, retry classification, typed exhaustion exception, ADR-0005 | D02-4 | S04 ([docs/step_04_kafka_pipeline.md#s04-t03](step_04_kafka_pipeline.md#s04-t03)); S07 ([docs/step_07_observability_performance.md#s07-t06](step_07_observability_performance.md#s07-t06)) |
| Quarantine table shape and error codes; the engine writes quarantine rows while S04 owns DLQ publishing and the poison-message policy (§0.3 C18); rows are unique per Kafka topic, partition and offset (§0.3 C25) | D02-9 | S04 ([docs/step_04_kafka_pipeline.md#s04-t03](step_04_kafka_pipeline.md#s04-t03)) |
| Applied-order timestamps used by the freshness computation | D02-1 | S04 ([docs/step_04_kafka_pipeline.md#s04-t04](step_04_kafka_pipeline.md#s04-t04)) |
| Balances API | D02-7, then `openapi/ledger-service.yaml` | S05 ([docs/step_05_instruments_fake_providers.md#s05-t10](step_05_instruments_fake_providers.md#s05-t10)) |
| Invariant queries I2–I5, non-zero clearing balances for the verifier's I9 judgment (§0.3 C13), verify, hash canonical form and vectors | D02-8, D02-7, D02-6 | S06 ([docs/step_06_reconciliation_verifier.md#s06-t04](step_06_reconciliation_verifier.md#s06-t04)); S09 ([docs/step_09_demo_docs_release.md#s09-t03](step_09_demo_docs_release.md#s09-t03)) |
| SP1 baseline and S3 decision | D02-10, then `docs/results/sp1-lock-study.md` | S04 batching ([docs/step_04_kafka_pipeline.md#s04-t02](step_04_kafka_pipeline.md#s04-t02)); S07 ([docs/step_07_observability_performance.md#s07-t05](step_07_observability_performance.md#s07-t05), [docs/step_07_observability_performance.md#s07-t06](step_07_observability_performance.md#s07-t06)) |
| Concurrency stress harness | D02-11 | S07 direct-apply comparisons ([docs/step_07_observability_performance.md#s07-t06](step_07_observability_performance.md#s07-t06)); S08 regression tests with failing seeds ([docs/step_08_fault_injection_ablation.md#s08-c01](step_08_fault_injection_ablation.md#s08-c01)) |
| Read APIs for the Ledger Explorer | D02-7 | S09 ([docs/step_09_demo_docs_release.md#s09-t03](step_09_demo_docs_release.md#s09-t03)) |
| Reader-role endpoints to protect with `libs/auth` | D02-7 | S03 ([docs/step_03_order_service_outbox.md#s03-t03](step_03_order_service_outbox.md#s03-t03), §0.3 C9) |
| Audit-walk documentation for M6 (c) | D02-7 | S04 end-to-end tests ([docs/step_04_kafka_pipeline.md#s04-t05](step_04_kafka_pipeline.md#s04-t05), §0.3 E11) |
| Ledger-side limitations | H.5 | S09 release limitations ([docs/step_09_demo_docs_release.md#s09-t07](step_09_demo_docs_release.md#s09-t07)) |

**Where to resolve current definitions.** Consumers read the current row in [docs/step_02_ledger_core.md#decisions-and-outputs](step_02_ledger_core.md#decisions-and-outputs), then the artifact at its actual path in H.2 or H.3, and only then the master proposal for context ([docs/README.md#source-of-truth](README.md#source-of-truth)). Nothing in sections A–G of this document is a definition to copy. A consumer that needs a different contract raises a change request against this register ([docs/README.md#conflict-resolution](README.md#conflict-resolution)).

**Handoff conditions**

1. G1 is evaluated per [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) and recorded in H.6. If the change-architecture clause fired, S02-C01 is Done and G1 has been re-evaluated. S04 does not start before this.
2. D02-1 to D02-12 are no longer Pending. Each is selected, or dropped with a reason (for example D02-6 under the minimum cut).
3. H.2 to H.4 record actual paths and evidence. Unexecuted evidence stays "Not run" with the reason; no result is estimated or invented.
4. Every I.1 row is Done or Blocked with the exact missing dependency. If S02-C01 was not triggered, it stays Planned with "trigger not met" and the evidence path in its Evidence column.
5. Change detection was re-run immediately before handoff, and I.2 shows the review outcome for every source.
6. Open H.5 items are handed to named owners: reader-role authentication goes to S03-T03 (§0.3 C9), and the quarantine retry endpoint stays deferred (§0.3 C14).
7. The next step records its acceptance in H.6 ("Handoff accepted by next step").
