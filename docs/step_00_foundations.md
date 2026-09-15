# Step 00 — Foundations

> **Pack:** ZeroSum Ledger implementation docs · **Master:** [docs/zerosum_ledger_mvp_plan.md#step-00](zerosum_ledger_mvp_plan.md#step-00) (v1.2)
> **Doc version:** 1.1 · **Date:** 2026-09-15 · **Step status:** Planned (no implementation exists)
> **Planned effort:** 12 h (master schedule; see [docs/zerosum_ledger_mvp_plan.md#schedule-overview](zerosum_ledger_mvp_plan.md#schedule-overview)) · **Gate:** G0
> **Shared procedures:** [docs/README.md#change-detection](README.md#change-detection) · [docs/README.md#source-of-truth](README.md#source-of-truth) · [docs/README.md#status-legend](README.md#status-legend)

<a id="purpose-and-outcome"></a>
## A. Purpose and outcome

**The problem this step solves.** Every later step assumes things that don't exist yet:

- a build that compiles from a fresh clone, with versions pinned in one place;
- local infrastructure (PostgreSQL, Kafka, the observability backend) that starts the same way every time;
- database ownership enforced by roles instead of by convention;
- a CI pipeline that separates fast unit tests from container-based layers;
- proof that the riskiest framework combination works together.

Without S00, each later step would find integration problems while doing unrelated work. Two design rules in [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components) would also stay unenforced: "exactly one service writes each table" and "services never read each other's databases". Risk R4 (tooling eats learning time) and R8 (framework churn) in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) are addressed here first, through spike SP3 on Day 2.

**Concrete deliverables** (all planned; actual paths are recorded in section H):

1. A public Git repository with a license, a root README stub (later a quickstart skeleton), and ADR-0001, which defines how architecture decisions are recorded.
2. A Gradle multi-project build that matches the repository layout, with a version catalog, a pinned Java toolchain, the Spring Boot BOM, empty Spring Boot applications for the four services, and test tasks split by JUnit tag.
3. A Compose file for PostgreSQL, single-node Kafka in KRaft mode and otel-lgtm, with health checks and memory limits. PostgreSQL loads `pg_stat_statements` (master §0.3 O12). Toxiproxy runs only under the `chaos` Compose profile.
4. PostgreSQL initialization that creates one database per service with an owner role and an application role per service, a read-only verifier role and a stats-reading role (master §0.3 O3, O12). It comes with a Flyway baseline per service and a cross-database isolation test.
5. A GitHub Actions workflow with a build + unit job, an integration job, and an e2e job skeleton, each selected by test tag.
6. ADR-0002 with every pinned version rechecked on the execution day, `docs/results/TEMPLATE.md` for all measured evidence, and a README quickstart skeleton.
7. The SP3 spike result, the G0 decision and any fallback, recorded as evidence.
8. OpenTelemetry agent attachment and metrics export for all four services, following the SP3 outcome.
9. `.env.example`, conventions for environment variables and secrets, and a log-redaction guideline.

**Contribution to the MVP.** S00 passes gate G0 ([docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates)). It also lays groundwork for several must-haves in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have):

- **M12:** the cross-service trace and the metrics pipeline.
- **M13(c):** the provenance that every results file must record.
- **M14(a):** the fresh-clone quickstart.

It also implements trust boundaries TB3 and TB5 ([docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries)). Several hygiene items in [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist) start here: the license file, no secrets in git, and `.env.example` holding placeholders only.

**Scope boundaries**

| In scope (S00) | Explicitly excluded (owner) |
|---|---|
| Repository, build, catalog, toolchain, empty apps and library modules (including `libs/auth`, master §0.3 C9), test-tag conventions | `Money` and the Day-2 one-hour money task: [docs/step_01_domain_contracts.md#s01-t01](step_01_domain_contracts.md#s01-t01). The `libs/auth` implementation: [docs/step_03_order_service_outbox.md#s03-t03](step_03_order_service_outbox.md#s03-t03) |
| Databases, owner and application roles per service, verifier and stats roles, default privileges, `pg_stat_statements` (master §0.3 O3, O12), empty Flyway baselines | Real tables, append-only triggers and table-level REVOKEs: [docs/step_02_ledger_core.md#s02-t01](step_02_ledger_core.md#s02-t01), [docs/step_03_order_service_outbox.md#s03-t01](step_03_order_service_outbox.md#s03-t01), [docs/step_03_order_service_outbox.md#s03-t02](step_03_order_service_outbox.md#s03-t02). The lock-wait sampler that reads statistics: [docs/step_07_observability_performance.md#s07-t04](step_07_observability_performance.md#s07-t04) |
| Kafka broker container and its broker-level settings | Topic provisioning and producer/consumer client configuration: [docs/step_04_kafka_pipeline.md#s04-t01](step_04_kafka_pipeline.md#s04-t01) |
| Agent attachment, metrics export mode, service naming | Custom spans, the metric name registry, dashboards and alerts: [docs/step_07_observability_performance.md#s07-t01](step_07_observability_performance.md#s07-t01), [docs/step_07_observability_performance.md#s07-t02](step_07_observability_performance.md#s07-t02), [docs/step_07_observability_performance.md#s07-t03](step_07_observability_performance.md#s07-t03) |
| Toxiproxy container under the `chaos` Compose profile | Proxies, toxics, chaos scripts and the Spring-side chaos startup guard (TB4): [docs/step_08_fault_injection_ablation.md#s08-t02](step_08_fault_injection_ablation.md#s08-t02), [docs/step_08_fault_injection_ablation.md#s08-t03](step_08_fault_injection_ablation.md#s08-t03) |
| Environment and secret *conventions*, S00's own variables, the redaction guideline | API token variables and the auth log-redaction test: [docs/step_03_order_service_outbox.md#s03-t03](step_03_order_service_outbox.md#s03-t03). Webhook HMAC secrets: [docs/step_05_instruments_fake_providers.md#s05-t11](step_05_instruments_fake_providers.md#s05-t11) |
| README quickstart *skeleton* | Final quickstart and timed fresh-clone test: [docs/step_09_demo_docs_release.md#s09-t01](step_09_demo_docs_release.md#s09-t01) |
| CI build, unit, integration and e2e job skeleton, with no image publishing (master §0.3 O4) | The tag `v*` workflow (versioned GHCR images, migration test on tag, secret scan) described in [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd), owned by [docs/step_09_demo_docs_release.md#s09-t07](step_09_demo_docs_release.md#s09-t07) (master §0.3 O4) |
| — | The `demo-public` profile and hosted demo: S09-C01 ([docs/step_09_demo_docs_release.md#conditional-work](step_09_demo_docs_release.md#conditional-work)). Kubernetes/Helm, Debezium and schema registry: [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred). Everything in [docs/zerosum_ledger_mvp_plan.md#non-goals](zerosum_ledger_mvp_plan.md#non-goals). |

<a id="agent-prompt"></a>
## B. Step-specific agent prompt

```text
You are the implementation agent for Step 00 (Foundations) of the ZeroSum Ledger project.

Repository root: zerosum-ledger/
Step document:   docs/step_00_foundations.md
Gate:            G0 (SP3 stack compatibility decision)

1. Read docs/README.md (source-of-truth, conflict-resolution, change-detection, status-legend),
   then this step document in full, then every master section listed in its section C.1
   at the linked anchors.
2. Inspect the current repository. S00 is the first step, so there are no upstream registers.
   Confirm what exists (initially only docs/). Read this document's own sections H and I
   for any earlier partial execution, and continue from there instead of redoing Done tasks.
3. Before changing anything, run the change-detection procedure in docs/README.md#change-detection.
   Until the first commit exists, record sha256 hashes; after that, record git revisions.
   Write them in section I.2.
4. Complete only the remaining S00 tasks (S00-T01 to S00-T09), in the dependency order given
   in their "Depends on" fields. Do not implement Money (S01), real tables or grants on them
   (S02/S03), topics or client configuration (S04), custom spans, dashboards or alerts (S07),
   chaos scripts or ablation guards (S08), or anything deferred or a non-goal.
5. Recheck every pinned version at its primary source on the execution day. Never take
   versions from memory, and never copy them from the master without rechecking.
   Record them in ADR-0002 and decision D00-1.
6. Run SP3 inside its timebox; only the contingency allowance in master §0.3 O1 may extend it
   before the G0 fallback. Evaluate G0 right after S00-T07 (§0.3 O2). G0 passes only with
   recorded trace and metric evidence, or with a fallback that has been selected and verified.
   Application health alone is never evidence for G0.
7. Verify each task exactly as its "Verification and definition of done" field says.
   Record evidence paths in H.4 and I.1. Never invent results. Mark unexecuted evidence
   "Not run" and blocked work "Blocked", naming the exact missing dependency
   (for example: GitHub access, Docker memory allocation, registry access).
8. Record decisions D00-1 to D00-10 in H.1 (decision, rationale, alternatives), actual
   paths in H.2 and H.3, and add a trace comment next to every authoritative configuration value.
9. Re-run change detection at every phase boundary and before handoff. Mark affected Done tasks
   "Needs review".
10. If a task needs to change the master's intent, scope, targets or schedule, stop and follow
    docs/README.md#conflict-resolution. Never override an owner silently and never weaken a gate.
11. Finish with section G checked and section J handoff conditions met. Report tasks done,
    evidence, open blockers and change requests raised.
```

<a id="required-reading"></a>
## C. Required reading and prerequisites

### C.1 Master sections

| Section | Why this step reads it |
|---|---|
| [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) | v1.2 resolutions binding S00: O1 (SP3 contingency allowance), O2 (G0 timing), O3 (role model), O4 (tag-only image publishing, owned by S09-T07), O12 (`pg_stat_statements` and stats role), C9 (`libs/auth`), C16 (JSON Schema validator and ArchUnit pins) |
| [docs/zerosum_ledger_mvp_plan.md#step-00](zerosum_ledger_mvp_plan.md#step-00) | Objective, outputs, exit criteria, risks and the Gradle-vs-Maven decision for this step |
| [docs/zerosum_ledger_mvp_plan.md#first-two-days](zerosum_ledger_mvp_plan.md#first-two-days) | The Day 1 and Day 2 task definitions and "done when" conditions that S00-T01 to S00-T07 decompose |
| [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack) | Proposed stack and versions to recheck and pin, and the rule to prefer Boot-managed versions |
| [docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure) | Proposed module layout and the module boundaries later enforced with ArchUnit |
| [docs/zerosum_ledger_mvp_plan.md#deliverables](zerosum_ledger_mvp_plan.md#deliverables) | Proposed paths for infrastructure, CI and documentation artifacts |
| [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology) | Containers, images, memory limits and exposed ports (ASSUMPTIONS sized for the reference laptop) |
| [docs/zerosum_ledger_mvp_plan.md#cold-warm](zerosum_ledger_mvp_plan.md#cold-warm) | The cold-start target for a healthy Compose stack and how it is measured |
| [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components) | The service-to-database mapping and the single-writer and no-cross-read design rules |
| [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) | TB3 (roles per service, private network), TB4 (chaos gating, owned later) and TB5 (read-only verifier role) |
| [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) | M2(a), which distinguishes owner and application roles; M12, M13(c) and M14(a), which depend on S00 outputs |
| [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas) | Context only: later DDL REVOKEs privileges from an application role, so S00's role model must allow that |
| [docs/zerosum_ledger_mvp_plan.md#migrations](zerosum_ledger_mvp_plan.md#migrations) | Flyway per service at startup, additive-only migrations, and checksummed history |
| [docs/zerosum_ledger_mvp_plan.md#environments](zerosum_ledger_mvp_plan.md#environments) | The local, CI and demo environments, profile names, and why there is no staging environment |
| [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets) | Which secrets exist, local and CI handling, generation method, rotation and hygiene |
| [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) | The environment-based authentication contract (names owned later) and the graceful-shutdown contract that Compose must not cut short |
| [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd) | CI triggers, job contents and target durations; images publish on tags only |
| [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) | The test layers that the tag conventions and CI job split must represent |
| [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling) | No auto-retry of money-path tests; "not run" labeling that the results template must support |
| [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes) | SP3: the question, the pass criterion, the fallbacks, the timebox and its contingency allowance |
| [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) | The G0 proceed and change-architecture rules |
| [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits) | Explicit Kafka client settings to exercise in SP3; Flyway over Liquibase; no jqwik |
| [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation) | What the observability wiring must later carry, that `pg_stat_statements` is enabled, and what results files must contain |
| [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) | ADR numbers already reserved by the master, which ADR-0001 must respect |
| [docs/zerosum_ledger_mvp_plan.md#mvp-vs-production](zerosum_ledger_mvp_plan.md#mvp-vs-production) | Documented MVP limitations of the Kafka and PostgreSQL setup |
| [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) | R4 and R8, which this step mitigates |
| [docs/zerosum_ledger_mvp_plan.md#sources](zerosum_ledger_mvp_plan.md#sources) | Primary sources for rechecking versions on the execution day |
| [docs/zerosum_ledger_mvp_plan.md#assumptions](zerosum_ledger_mvp_plan.md#assumptions) | A7: the host JDK differs from the pinned toolchain, and the hardware baseline to record |
| [docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates) | Cost is not a decision driver, so this step has no cost-tracking work |

### C.2 Earlier step documents and their registers

None. S00 is the first step and consumes no upstream decision IDs. All later registers depend on this step's register ([#decisions-and-outputs](#decisions-and-outputs)). The pack-level ownership view is in [docs/README.md#dependency-map](README.md#dependency-map).

### C.3 Artifacts that must already exist

**Repository artifacts.** Only the documentation pack exists: `docs/zerosum_ledger_mvp_plan.md`, `docs/README.md` and the `docs/step_NN_*.md` files. No code, build, Compose file or register entries exist yet. No upstream register lists planned paths for S00 to resolve. From S00 onward, every later step resolves paths from this document's section H, not from the task text.

**Environment prerequisites** (not repository artifacts; record what was actually used in H.4):

| Prerequisite | Needed by | Notes |
|---|---|---|
| Docker Engine with Compose, and a VM memory allocation at least as large as the note in [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology) | S00-T03, S00-T04, S00-T07, S00-T08 | Record the host CPU, RAM and Docker allocation for the results template |
| A JDK that can run the Gradle wrapper; the pinned toolchain JDK is installed or can be provisioned | S00-T02 onward | The reference host has a different JDK installed than the pinned toolchain (A7) |
| git, plus a GitHub account that can create a public repository (`gh` authenticated) | S00-T01 push, S00-T05 | Without it, only the push and CI evidence are blocked |
| Network access to Maven Central, the Gradle Plugin Portal, Docker Hub, GHCR and the primary sources in [docs/zerosum_ledger_mvp_plan.md#sources](zerosum_ledger_mvp_plan.md#sources) | S00-T02, S00-T03, S00-T06, S00-T07 | Rate limits or proxies must be recorded as blockers, never worked around by using unpinned versions |

### C.4 Blocking vs independent work

| Missing input | Blocks | Can proceed with independent preparation |
|---|---|---|
| GitHub account or `gh` authentication | S00-T01 (push only), S00-T05 (CI run evidence) | Local repository commits; writing `ci.yml`; running the same Gradle tasks locally as partial evidence (recorded as partial, never as CI green). S00-T02 to S00-T04 and S00-T06 to S00-T09 are unaffected. |
| Docker daemon, or enough VM memory | S00-T03, S00-T04 (Testcontainers test and service boot), S00-T07, S00-T08 | S00-T01, S00-T02, the S00-T05 build/unit job, ADR-0002 research and TEMPLATE in S00-T06, and the S00-T09 conventions document |
| Registry or network access for images and dependencies | S00-T02 (dependency resolution), S00-T03, S00-T07 | S00-T01 (ADR-0001), the S00-T06 TEMPLATE and README skeleton, and S00-T09 drafting |
| SP3 outcome (D00-7) | S00-T08 metrics-export mode; final status of ADR-0002 | S00-T08 preparation that doesn't depend on the export mode: the agent attach method, service naming, image build and actuator exposure |
| S00-T02 build skeleton (D00-2, D00-10) | S00-T04, S00-T05, S00-T06, S00-T07 | S00-T03 Compose infrastructure |
| S00-T04 role model (D00-4) | S00-T08 service containers, the S00-T09 variable inventory, the S00-T05 integration job's first real test | S00-T06, S00-T07 (the spike uses its own disposable database objects) |

<a id="ownership-and-requirements"></a>
## D. Ownership and engineering requirements

### D.1 Decisions and contracts owned by this step

| Decision ID | What is decided | Master's proposed starting point |
|---|---|---|
| D00-1 | **Pinned versions and the dependency catalog.** ADR-0002 records every version with its check date and source: `gradle/libs.versions.toml`, Compose image tags (and digests, if used), Gradle wrapper, OTel Java agent, CI action versions, the JSON Schema validator library and schema draft, and ArchUnit (master §0.3 C16). Also the **pin-update policy**: later steps may add catalog entries for tools already pinned in ADR-0002 (recording the addition in their own register), but changing a pinned version needs a change request against D00-1. | [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack), [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology), [docs/zerosum_ledger_mvp_plan.md#first-two-days](zerosum_ledger_mvp_plan.md#first-two-days) (Day 1, task 5), [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (C16) |
| D00-2 | **Actual repository and module layout**, including the `libs/auth` module (master §0.3 C9): which directories are Gradle projects and which are script directories, where infrastructure tests and helper scripts live, and the location of convention plugins | [docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure), [docs/zerosum_ledger_mvp_plan.md#deliverables](zerosum_ledger_mvp_plan.md#deliverables), [docs/zerosum_ledger_mvp_plan.md#first-two-days](zerosum_ledger_mvp_plan.md#first-two-days) (Day 1, task 2), [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (C9) |
| D00-3 | **Compose topology:** default profile contents; the `chaos` profile adding toxiproxy; memory limits; PostgreSQL server configuration, including the `pg_stat_statements` preload (master §0.3 O12); health checks and start periods; ports and host binding; volumes; startup ordering; stop grace period; restart policy; service image build method; broker-level Kafka settings such as auto topic creation and internal-topic replication for a single broker. Also the **extension rule**: later steps add containers or settings in their own register with trace comments, while changes to existing D00-3 choices use a change request. | [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology), [docs/zerosum_ledger_mvp_plan.md#environments](zerosum_ledger_mvp_plan.md#environments), [docs/zerosum_ledger_mvp_plan.md#cold-warm](zerosum_ledger_mvp_plan.md#cold-warm), [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation), [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (O12) |
| D00-4 | **Databases and roles:** one database per service; per service, an owner role (migrations) and an application role (runtime), as fixed by master §0.3 O3, with their selected names and attributes; the read-only `verifier` role; a stats-reading role with monitoring access, and the `pg_stat_statements` extension objects (master §0.3 O12); CONNECT revocation from PUBLIC; default privileges; how role passwords reach the init scripts | [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components), [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) (TB3, TB5), [docs/zerosum_ledger_mvp_plan.md#first-two-days](zerosum_ledger_mvp_plan.md#first-two-days) (Day 2, task 2), [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (O3, O12) |
| D00-5 | **CI workflow structure:** triggers, the job split (build + unit, integration, e2e), job timeouts, permissions, action pinning, report upload, and behavior when a tag selects no tests | [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd), [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) |
| D00-6 | **Observability wiring:** the OTel agent attach method; the metrics export mode (Micrometer OTLP, or scraping); which component exports which telemetry, so nothing is exported twice; service naming and resource attributes; telemetry off in tests | [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack) (Tracing, Metrics and local observability backend rows), [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation) |
| D00-7 | **SP3 result and any fallback:** pass, "Prometheus scraping instead of OTLP metrics", or "pin to the previous Boot line", with the components that forced the choice; decided within the SP3 timebox plus the contingency allowance of master §0.3 O1, and evaluated right after S00-T07 (O2) | [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes), [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) (G0), [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (O1, O2) |
| D00-8 | **Environment and secret conventions:** the `ZS_*` naming rule; `.env` and `.env.example` handling; fail-fast behavior for required variables; generation; CI throwaway secrets; the log-redaction guideline and where the redaction tests must live | [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets), [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) |
| D00-9 | **ADR process (ADR-0001) and `docs/results/TEMPLATE.md`:** ADR format, numbering, status lifecycle, reserved numbers; the required template sections, including "Not run" | [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions), [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) (M13 c), [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling) |
| D00-10 | **Build conventions:** Gradle vs Maven (default Gradle); Java toolchain and provisioning; convention plugins; BOM import style; source-set strategy; JUnit tag names and the Gradle test tasks that select them | [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack) (Build row), [docs/zerosum_ledger_mvp_plan.md#step-00](zerosum_ledger_mvp_plan.md#step-00), [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) |

### D.2 Inherited decisions

S00 has no upstream step registers. Everything it inherits comes from the master, whose §4 and §5 content is PROPOSED ([docs/README.md#source-of-truth](README.md#source-of-truth)).

| Decision | Owner | How this step must use it |
|---|---|---|
| Stack choices and the "prefer Boot-managed versions" rule | [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack) | Recheck and pin the stack in D00-1. A patch or minor drift found on recheck is recorded in ADR-0002. Replacing a component (for example Maven for Gradle, or another broker) is a decision change and goes through [docs/README.md#conflict-resolution](README.md#conflict-resolution). |
| Single-writer tables; no cross-service database reads, except the read-only verifier | [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components) | Enforce it with database-level CONNECT privileges and roles (D00-4), and prove it with a test, not by convention |
| TB3 and TB5 controls; Kafka without authentication as a documented MVP limitation | [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries), [docs/zerosum_ledger_mvp_plan.md#mvp-vs-production](zerosum_ledger_mvp_plan.md#mvp-vs-production) | Implement the role model (per service an owner and an application role, plus the verifier; master §0.3 O3) and the network parts. Record the Kafka limitation in H.5; don't add broker authentication. |
| TB4 chaos gating | [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries); implemented by S08 (D08-3) | S00 only creates the Compose `chaos` profile for toxiproxy. It must not implement the Spring startup guard, but must not prevent it either. |
| Flyway (not Liquibase); no jqwik | [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits) | Use Flyway through Boot's Flyway starter; keep jqwik out of the catalog |
| Additive migrations, run per service at startup | [docs/zerosum_ledger_mvp_plan.md#migrations](zerosum_ledger_mvp_plan.md#migrations) | The baseline migrations are additive and never edited after they are applied. The role that runs Flyway must own the objects it creates (D00-4). |
| Reserved ADR numbers | [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions); ADR-0010 at [docs/zerosum_ledger_mvp_plan.md#state-machines](zerosum_ledger_mvp_plan.md#state-machines) | ADR-0001 lists these reservations, so later steps don't collide with them |
| Environments and profile names; no staging environment | [docs/zerosum_ledger_mvp_plan.md#environments](zerosum_ledger_mvp_plan.md#environments) | Use the `local` and `ci` profile names as given. Don't create a `demo-public` profile (S09). |
| Honest evidence labeling | [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling), [docs/README.md#status-legend](README.md#status-legend) | TEMPLATE supports "Not run" with a reason. SP3 evidence is recorded as measured, or marked "Not run" / "Blocked". |
| v1.2 clarifications O1–O4, O12, C9, C16 | [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) | Binding inputs for D00-1, D00-2, D00-3, D00-4, D00-5 and D00-7. S00 records the selected detail in its register; it doesn't re-decide the resolution. |
| Cost is not a decision driver | [docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates) | No cost tasks. Tool and version choices are made on compatibility and showcase value. |

### D.3 Engineering requirements

**Module and package boundaries**

- The Gradle projects follow D00-2, starting from the layout in [docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure).
- `libs/money` gets no dependencies, not even through convention plugins. S01 adds the ArchUnit rule that proves this, so S00 must not apply Spring or any library to library projects by default.
- Only service projects apply the Spring Boot plugin. Library projects produce plain JARs.
- `libs/auth` is created as an empty library module (master §0.3 C9). Its contents and wiring belong to S03 (D03-4).
- `tools/chaos` and `tools/k6` are script directories, not Gradle projects, unless D00-2 records otherwise.
- Temporary spike code (SP3) never remains in the main build after the decision is recorded.

**Interfaces this step creates.** These are the contracts later steps call.

| Interface | Contract to define (recorded in the named decision) |
|---|---|
| Gradle tasks | `./gradlew build` runs compile and the untagged fast test layer, without containers. Separate tasks run the integration and e2e tags (D00-10). |
| Compose commands | `docker compose up -d --wait` starts the default profile and waits for health. `docker compose --profile chaos up -d --wait` adds toxiproxy. `docker compose down -v` resets all data (D00-3). |
| Database access | Each service migrates with its owner credentials and runs with its application credentials, against its own database only. The verifier connects read-only to every database. A stats role reads query statistics but no table data (D00-4). |
| Telemetry | Each service exports traces, and metrics per D00-7, to the otel-lgtm container on the Compose network, under a stable service name (D00-6). |
| Configuration | Services and Compose read environment variables following D00-8. `.env.example` is the index of every variable. |
| Evidence | Every measured result uses `docs/results/TEMPLATE.md` (D00-9). |

**Data flows**

1. *SP3 spike flow:* HTTP request → JDBC insert (Flyway-created table) → Kafka produce → listener consume → JDBC insert. The trace context travels in Kafka record headers, added by the agent.
2. *Telemetry flow:* service JVM (agent for traces; Micrometer or agent for metrics, per D00-6) → OTLP to the otel-lgtm collector → Tempo (traces) and Prometheus (metrics) → Grafana. Under the scraping fallback, metrics are instead pulled from each service's actuator endpoint.
3. *Initialization flow:* on an empty PostgreSQL volume, the container entrypoint runs the scripts under `infra/postgres/`. They create the databases and roles, and the passwords come from environment variables. Each service's Flyway then applies its baseline at startup.

**Lifecycle behavior**

- **Startup:** Compose waits on health conditions for PostgreSQL and Kafka before starting services. Services still have to tolerate a slow dependency, for example with connection-pool startup timeouts, rather than depending only on Compose ordering. otel-lgtm is not a hard dependency: a service must start and serve without it.
- **Shutdown:** the service container's stop grace period must be at least as long as the graceful-shutdown timeout in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting). The JVM must receive SIGTERM directly, so use an exec-form entrypoint. Otherwise later listener steps (S04) would be killed mid-transaction.
- **Crash and restart:** record the restart policy in D00-3, because S08 fault scripts (F1–F5 in [docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix)) need predictable restart behavior. Initialization scripts run only against an empty data volume, so changes to them need `docker compose down -v`.
- **Retry:** CI never retries money-path test steps automatically ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)). Infrastructure pull failures in CI may be retried, but they must be reported as infrastructure failures.

**Security and trust boundaries**

- All containers share one private Compose network. Only the ports listed in [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology) are published. "Local only" ports bind to `127.0.0.1`. Any extra local-only port (for example OTLP for host-run spikes) is recorded in D00-3 with its reason.
- The PostgreSQL superuser is used only by the initialization scripts, never by a service or a tool. The stats role has monitoring access only, never table privileges.
- Actuator exposure over HTTP is limited to health (and the metrics endpoint under the scraping fallback). Endpoints that can reveal configuration or memory, such as environment, configuration properties or heap dumps, are never exposed.
- No literal secret is committed. Initialization scripts, Compose and services read secrets from the environment (D00-8).
- Kafka has no authentication (MVP limitation; H.5).

**Deployment constraints**

- The memory limits and VM allocation are the ASSUMPTIONS in [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology). Changing a limit is recorded in D00-3 with evidence, for example an observed OOM kill.
- One instance per service, which the outbox relay design later relies on (ADR-0008).
- The Compose file doesn't use the obsolete top-level `version:` key ([docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack)).
- Pinned image tags must exist for the host architecture. On ARM hosts, emulated amd64 images are recorded, because they distort S07 measurements.
- CI runs on public-repository hosted runners ([docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack) CI row). No self-hosted runner is introduced.

### D.4 Configuration ownership

Every authoritative value introduced by S00 lives in exactly one file. It carries a trace comment in the file's comment syntax, for example:

```text
# decision: D00-1 — docs/step_00_foundations.md#decisions-and-outputs      (TOML, YAML, .env.example, shell)
-- decision: D00-4 — docs/step_00_foundations.md#decisions-and-outputs     (SQL)
// decision: D00-10 — docs/step_00_foundations.md#decisions-and-outputs    (Kotlin DSL)
```

| Value or area | Authoritative file (planned) | Decision |
|---|---|---|
| Library, plugin and BOM versions | `gradle/libs.versions.toml` | D00-1 |
| Gradle version and distribution checksum | `gradle/wrapper/gradle-wrapper.properties` | D00-1 |
| Image tags, memory limits, health checks, profiles, ports, grace periods, broker settings, PostgreSQL server settings (`pg_stat_statements` preload) | `docker-compose.yml` | D00-1 (tags), D00-3 (everything else) |
| Databases, owner/application/verifier/stats roles, grants, default privileges, `pg_stat_statements` extension | `infra/postgres/init.sql` and its entrypoint wrapper script, if used | D00-4 |
| Per-service datasource and Flyway credentials wiring (by variable reference, never literal values) | `services/<service>/src/main/resources/application.yml` | D00-4, D00-8 |
| Baseline migrations | `services/<service>/src/main/resources/db/migration/V1__baseline.sql` | D00-4 |
| CI triggers, jobs, timeouts, action versions | `.github/workflows/ci.yml` | D00-5 (structure), D00-1 (action versions) |
| Agent attach, export endpoint, service names, export mode | Service image definition (`services/<service>/Dockerfile` or Boot image configuration), `docker-compose.yml` environment, `infra/otel/` (only if collector or scrape configuration is needed) | D00-6, D00-7 |
| Toolchain, convention plugins, tag names, test tasks | `build-logic/` (or `buildSrc/`, per D00-10), `settings.gradle.kts` | D00-10 |
| Variable names and placeholders | `.env.example` | D00-8 |
| ADR format and results template | `docs/adr/0001-record-architecture-decisions.md`, `docs/results/TEMPLATE.md` | D00-9 |

ADR-0002 is the dated record of the pinned values. The artifacts above hold the values the build and runtime actually use. The verification in S00-T06 keeps them identical.

**Permitted alternatives that downstream work must handle**

| Alternative | Decided in | Downstream impact |
|---|---|---|
| **SP3 fallback A:** Prometheus scraping of the actuator metrics endpoint instead of Micrometer OTLP export | S00-T07 (D00-7) | S00-T08 wires scraping instead of OTLP push. [docs/step_04_kafka_pipeline.md#s04-t06](step_04_kafka_pipeline.md#s04-t06) checks traces only through the agent. S07 dashboards and alert queries ([docs/step_07_observability_performance.md#s07-t02](step_07_observability_performance.md#s07-t02), [docs/step_07_observability_performance.md#s07-t03](step_07_observability_performance.md#s07-t03)) must use the metric names as they appear under scraping. |
| **SP3 fallback B:** pin to the previous Spring Boot line named in [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes) | S00-T07 (D00-7, D00-1) | Every later step reads Boot-managed versions (spring-kafka, Kafka clients, Flyway, Testcontainers, JUnit) from D00-1, not from the master. APIs used by S03–S05 must exist in the pinned line. |
| **Build tool:** Gradle (default) or Maven | S00-T02 (D00-10) | Later steps run the build and test commands recorded in D00-10. If Maven were chosen, every Gradle command in the pack maps to the equivalent recorded in D00-10. |

<a id="phases-and-tasks"></a>
## E. Phases and tasks

Phase totals: P1 2 h · P2 3 h · P3 2 h · P4 5 h · **step total 12 h**. Run change detection ([docs/README.md#change-detection](README.md#change-detection)) at every phase exit checkpoint.

<a id="phase-1"></a>
### Phase 1 — Repository and build skeleton

**Objective:** a versioned repository whose build compiles every planned module from a fresh clone on the pinned toolchain.
**Exit checkpoint:** `./gradlew build` is green from a fresh clone. ADR-0001 is committed. D00-2 and D00-10 are drafted in H.1. Change detection has been re-run and I.2 now holds git revisions instead of hashes.

<a id="s00-t01"></a>
#### S00-T01 — Repository init, license, README stub, ADR-0001
- **Outcome:** a public GitHub repository `zerosum-ledger` holding the existing documentation pack (unchanged), a license file, a root README stub, `.gitignore` and an accepted ADR-0001.
- **Estimate:** 0.5 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#first-two-days](zerosum_ledger_mvp_plan.md#first-two-days) (Day 1, task 1, including the license choice); [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) and [docs/zerosum_ledger_mvp_plan.md#state-machines](zerosum_ledger_mvp_plan.md#state-machines) (reserved ADR numbers); [docs/README.md#source-of-truth](README.md#source-of-truth) (how ADRs relate to registers); existing `docs/`.
- **Depends on:** none
- **Instructions:**
  1. Record `shasum -a 256` for every file in `docs/` in I.2. No git history exists yet.
  2. Run `git init` in the repository root, with default branch `main` (the branch [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd) refers to).
  3. Make the first commit contain only `docs/`, byte-for-byte unchanged, so change detection can use `git log` from then on.
  4. Add the license file, and a `.gitignore` covering build outputs, IDE files, OS files and `.env`. Don't ignore `.env.example` or `docs/results/`.
  5. Write the root `README.md` stub: a one-paragraph description, a "planning/under construction" status line, and links to `docs/README.md` and the master. Don't touch `docs/README.md`, which is the pack index, a different file.
  6. Write `docs/adr/0001-record-architecture-decisions.md` (D00-9, first half), covering:
     - the format (context, decision, status, consequences);
     - `NNNN-kebab-title.md` naming with sequential numbers;
     - the status lifecycle: Proposed → Accepted → Superseded by NNNN;
     - that accepted ADR text is never rewritten, only its status and supersession links;
     - the ADR numbers the master already reserves, each with its owning step;
     - the rule that a register row references the ADR, the ADR carries the rationale, and artifacts carry the values.
  7. Create the public remote and push (for example `gh repo create zerosum-ledger --public --source . --push`). Record the actual remote URL in H.2.
- **Edge cases and failure behavior:**
  - The repository name is already taken on GitHub: record the actual name in H.2. Don't overwrite or delete an existing repository.
  - No GitHub credentials: mark only the push as Blocked in H.5 and I.1. Local commits continue.
  - A `.gitattributes` or editor setting that normalizes line endings would change `docs/` hashes. Verify the hashes after committing.
  - Don't add CI, build or Compose files here; later tasks own them.
- **Outputs:** planned `LICENSE`, `.gitignore`, `README.md`, `docs/adr/0001-record-architecture-decisions.md`, git remote `origin`.
- **Verification and definition of done:**
  - `git log --reverse --stat` shows the first commit contains only `docs/`.
  - `shasum -a 256` of every `docs/` file matches I.2.
  - `git check-ignore .env` prints `.env`, and `git check-ignore .env.example` prints nothing.
  - `git ls-remote origin` succeeds, or the H.5 blocker is recorded.
  - ADR-0001 has status Accepted and lists the reserved numbers.

<a id="s00-t02"></a>
#### S00-T02 — Gradle multi-project, version catalog, toolchain, Boot BOM, empty apps, test-tag conventions
- **Outcome:** a multi-project Gradle build with the planned modules, a version catalog, a provisioned Java toolchain, BOM-managed dependencies, four empty Boot services with a health endpoint, and test tasks selected by JUnit tag. `./gradlew build` is green.
- **Estimate:** 1.5 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack) (Build and Framework rows; prefer Boot-managed versions); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (C9); [docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure); [docs/zerosum_ledger_mvp_plan.md#first-two-days](zerosum_ledger_mvp_plan.md#first-two-days) (Day 1, task 2); [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers); [docs/zerosum_ledger_mvp_plan.md#assumptions](zerosum_ledger_mvp_plan.md#assumptions) (A7); S00-T01 repository.
- **Depends on:** S00-T01
- **Instructions:**
  1. On the execution day, look up the Gradle release that supports the pinned Java toolchain and the Boot Gradle plugin. Generate the wrapper with a distribution checksum, and commit the wrapper files. Record the version in D00-1; the full ADR follows in S00-T06.
  2. In `settings.gradle.kts`, include the projects from [docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure): the four libraries, including `libs/auth` (master §0.3 C9), the four services, and the simulator and verifier tools. Add a toolchain resolver so the pinned JDK is provisioned when the host JDK differs. Record the layout in D00-2.
  3. In `gradle/libs.versions.toml`, declare the Boot BOM and the plugins. Import the BOM as a Gradle `platform(...)` so BOM-managed libraries carry no version of their own. Add only dependencies used now: web and actuator starters for services, and test dependencies through the BOM. Later steps add their own libraries under the D00-1 pin-update policy.
  4. Create convention plugins in `build-logic/` (an included build) or `buildSrc/`: a Java convention (toolchain, UTF-8, `-parameters`, reproducible archives), a service convention (Boot plugin, BOM, actuator) and a library convention (no dependencies added). Record the choice in D00-10.
  5. For each service, add an `@SpringBootApplication` class, `application.yml` with the application name, and actuator health. Don't add JDBC or Flyway yet (S00-T04 adds them), so apps start without infrastructure. Tools get a plain `main` that prints usage. Libraries get a `package-info.java` only.
  6. Define the tag conventions (D00-10). Suggested starting point:
     - one `src/test` source set per project;
     - untagged tests run in `test`, which `build` depends on (unit, seeded generative, contract and ArchUnit tests that need no containers);
     - tag `integration` runs in `integrationTest` (Testcontainers);
     - tag `e2e` runs in `e2eTest` (requires the Compose stack).
     Use `@Tag` directly; no custom meta-annotations are needed.
  7. For each test task, configure explicitly what happens when a tag selects no tests: report it, don't fail silently or pass silently. Record the behavior in D00-10.
- **Edge cases and failure behavior:**
  - The host JDK is newer than the toolchain, or newer than Gradle supports for its own daemon: configure the daemon JVM criteria or toolchain so compilation still targets the pinned version, and record the configuration.
  - The toolchain download is blocked (proxy or offline): mark it Blocked. Don't fall back to the host JDK.
  - Applying the Boot plugin to libraries produces an unwanted `bootJar`: apply it only through the service convention.
  - A dependency version written in a `build.gradle.kts`, bypassing the catalog, fails review. See the grep check below.
  - The seed-reporting convention for generative tests belongs to S01 (ADR-0009). Don't implement it here.
- **Outputs:** planned `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `build-logic/**`, `libs/{money,contracts,outbox,auth}/build.gradle.kts`, `services/*/build.gradle.kts` with main class and `application.yml`, `tools/{simulator,verifier}/build.gradle.kts`.
- **Verification and definition of done:**
  - From a fresh clone, `./gradlew build` exits 0.
  - `./gradlew projects` lists exactly the D00-2 projects.
  - `./gradlew test integrationTest e2eTest --dry-run` resolves all three tasks.
  - `grep -rnE '"[a-z0-9.-]+:[a-z0-9.-]+:[0-9]' --include='*.gradle.kts' .` finds no literal dependency coordinates with versions.
  - `javap -v` on one compiled class shows the class-file major version of the pinned toolchain.
  - Each service started with `bootRun` reports health UP (command and output recorded in H.4).

<a id="phase-2"></a>
### Phase 2 — Local infrastructure

**Objective:** reproducible local infrastructure, with database ownership enforced by roles and proven by a test.
**Exit checkpoint:** from `docker compose down -v`, the default profile becomes healthy with `--wait`. The isolation test passes, and its negative control has been observed failing. Each service boots against its own database. D00-3 and D00-4 are drafted in H.1. Change detection has been re-run.

<a id="s00-t03"></a>
#### S00-T03 — Compose: PostgreSQL, Kafka KRaft, otel-lgtm, toxiproxy under `chaos`, health checks, memory limits
- **Outcome:** `docker-compose.yml` starts PostgreSQL, a single-node KRaft Kafka and otel-lgtm healthy within the cold-start target, with `pg_stat_statements` preloaded. The `chaos` profile adds toxiproxy.
- **Estimate:** 1.5 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology) (containers, memory limits, ports); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (O12) and [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation) (`pg_stat_statements`); [docs/zerosum_ledger_mvp_plan.md#cold-warm](zerosum_ledger_mvp_plan.md#cold-warm) (start-up target and method); [docs/zerosum_ledger_mvp_plan.md#environments](zerosum_ledger_mvp_plan.md#environments) (reset with `down -v`); [docs/zerosum_ledger_mvp_plan.md#mvp-vs-production](zerosum_ledger_mvp_plan.md#mvp-vs-production) (single broker); [docs/zerosum_ledger_mvp_plan.md#first-two-days](zerosum_ledger_mvp_plan.md#first-two-days) (Day 1, task 3); image versions per D00-1, checked today at the sources in [docs/zerosum_ledger_mvp_plan.md#sources](zerosum_ledger_mvp_plan.md#sources).
- **Depends on:** S00-T01
- **Instructions:**
  1. Create `docker-compose.yml` at the repository root with an explicit top-level `name:` and no `version:` key. Use exact image tags from D00-1, never `latest` or a major-only tag. Pinning by digest is recommended; record the choice.
  2. Define `postgres`, `kafka` and `otel-lgtm` in the default profile, and `toxiproxy` with `profiles: [chaos]`. Don't define proxies or toxics (S08).
  3. Apply the memory limits from [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology) with a key that plain `docker compose` enforces, and confirm it with `docker inspect`. Set the Kafka heap through the image's environment variable.
  4. Publish only the ports listed there. Bind "local only" ports to `127.0.0.1`. If host-run spikes or tests need an additional local-only port (for example OTLP), record it in D00-3 with the reason.
  5. Configure Kafka as a combined broker and controller:
     - separate internal (Compose network) and host listeners, each with its own advertised address;
     - a fixed cluster ID, so volumes stay reproducible;
     - internal-topic replication settings valid for a single broker.
     Decide on broker auto topic creation and record it in D00-3. The suggested default is disabled, so that S04-T01 provisioning stays authoritative.
  6. Add health checks using tools that actually exist in each image. Confirm with `docker compose exec <svc> sh -c 'command -v <tool>'`.
     - PostgreSQL: readiness of the server.
     - Kafka: an API-versions or metadata request to the internal listener, using the image's bundled CLI.
     - otel-lgtm: Grafana's health endpoint.
     - toxiproxy: its API.
     Set interval, retries and `start_period` so that `--wait` reflects real readiness.
  7. Declare named volumes for PostgreSQL and Kafka data. Mount `infra/postgres/` into the PostgreSQL init directory; S00-T04 fills it.
  8. Preload `pg_stat_statements` through the PostgreSQL server configuration (a `command:` override or a mounted configuration file; record which in D00-3), as master §0.3 O12 requires. Creating the extension and granting access happen in S00-T04.
- **Edge cases and failure behavior:**
  - Docker VM memory too small: containers exit with an OOM kill (check `docker inspect --format '{{.State.OOMKilled}}'`). Record the allocation. Don't lower limits silently.
  - Wrong advertised listeners: clients inside the network work, but host clients hang. Test both paths.
  - A health check tool missing from the image leaves the container permanently in `starting`, so `--wait` times out. Treat that as a failure, not flakiness.
  - A pinned tag without a manifest for the host architecture: record it, and pick a multi-arch tag if one exists.
  - A host port already in use: fail visibly and record it. Don't remap silently.
  - Stale volumes can hide configuration mistakes. Every verification starts from `docker compose down -v`.
  - A preload setting only takes effect at server start. Confirm it on the running container, not just in the file.
- **Outputs:** planned `docker-compose.yml`; `infra/postgres/` (mount point).
- **Verification and definition of done:**
  - `docker compose config --quiet` exits 0.
  - After `docker compose down -v`, `time docker compose up -d --wait` exits 0. Record the elapsed time against [docs/zerosum_ledger_mvp_plan.md#cold-warm](zerosum_ledger_mvp_plan.md#cold-warm) in H.4.
  - `docker compose ps` shows no toxiproxy container. `docker compose --profile chaos up -d --wait` adds it, healthy.
  - `docker inspect` memory limits match [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology).
  - One message produced and consumed with the bundled console tools over the internal listener, and once from the host listener.
  - Grafana answers on its published port.
  - `SHOW shared_preload_libraries`, run in the PostgreSQL container, lists `pg_stat_statements`.

<a id="s00-t04"></a>
#### S00-T04 — PostgreSQL databases and roles, verifier role, Flyway baselines, cross-database isolation test
- **Outcome:** PostgreSQL initializes one database per service, with an owner role and an application role per service, a read-only verifier role and a stats-reading role (master §0.3 O3, O12). Each service boots and applies an empty Flyway baseline with its owner credentials, then runs with its application credentials. An integration test proves that a service can't connect to another service's database, and that the verifier can read but not write.
- **Estimate:** 1.5 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components) (databases, single writer, no cross-reads); [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) (TB3, TB5); [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) (M2 a: application and owner roles); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (O3, O12); [docs/zerosum_ledger_mvp_plan.md#schemas](zerosum_ledger_mvp_plan.md#schemas) (later REVOKEs target an application role); [docs/zerosum_ledger_mvp_plan.md#migrations](zerosum_ledger_mvp_plan.md#migrations); [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets) (role passwords are secrets); [docs/zerosum_ledger_mvp_plan.md#first-two-days](zerosum_ledger_mvp_plan.md#first-two-days) (Day 2, task 2); D00-3 (S00-T03), D00-10 (S00-T02).
- **Depends on:** S00-T02, S00-T03
- **Instructions:**
  1. Implement the role model fixed by master §0.3 O3, and record the selected names and attributes in D00-4:
     - per service, an *owner* role that owns the database objects and is used only by Flyway (migrations);
     - per service, an *application* role for runtime, with DML granted by the owner;
     - one read-only `verifier` role;
     - one stats-reading role with PostgreSQL's predefined monitoring access (for example `pg_monitor`) and no table privileges (master §0.3 O12).
     This makes M2(a) testable for both the owner and the application role in S02-T01 and S03-T02.
  2. Write `infra/postgres/init.sql`. Passwords must not be literals: SQL can't read environment variables, so run the SQL from a small entrypoint script that passes them as psql variables (`psql -v`), or use an equivalent mechanism recorded in D00-4.
  3. For each database:
     - create it owned by the migration owner;
     - `REVOKE CONNECT ... FROM PUBLIC` (PostgreSQL grants CONNECT to PUBLIC by default), then grant CONNECT only to that service's roles, `verifier` and the stats role;
     - revoke CREATE on schema `public` from PUBLIC explicitly;
     - add `ALTER DEFAULT PRIVILEGES FOR ROLE <owner>`, so that tables Flyway creates later are readable by `verifier` and writable by the application role.
     Never grant table privileges through PUBLIC; S02 and S03 must be able to REVOKE them from the application role.
  4. Configure the verifier role as `LOGIN`, with no CREATE or TEMP privileges, no membership in service roles, and `default_transaction_read_only = on` as defense in depth.
  5. Create the `pg_stat_statements` extension in each service database as the superuser (the preload comes from S00-T03), and record where it exists in D00-4.
  6. Add the JDBC starter and Boot's Flyway starter to each service. Point the datasource at the service's own database using runtime credentials, and point Flyway at the owner credentials, all from environment variables (D00-8). Add `db/migration/V1__baseline.sql` with no schema objects; if the pinned Flyway rejects a comment-only script, use a no-op statement and record that. Don't enable baseline-on-migrate.
  7. Write `DatabaseIsolationIT` (tag `integration`) in the test location recorded in D00-2 (suggested: a test-only project `infra/tests`, because it tests infrastructure rather than service code). It starts Testcontainers PostgreSQL with the D00-1 image, mounts the *same* `infra/postgres/` files (never a copy) and applies the same `pg_stat_statements` server setting as D00-3, generates throwaway passwords, runs each service's Flyway baseline, and asserts:
     - (a) each application role connects to its own database;
     - (b) each application and owner role gets a permission error connecting to every other service's database;
     - (c) `verifier` connects to all four databases and can `SELECT` from each `flyway_schema_history`, which proves default privileges;
     - (d) `verifier` `INSERT` and `CREATE TABLE` fail;
     - (e) the application role can't create tables in `public`;
     - (f) the stats role can read `pg_stat_statements`, including other roles' statements, and can't `SELECT` from `flyway_schema_history`.
- **Edge cases and failure behavior:**
  - Init scripts run only on an empty data volume: document `docker compose down -v` for script changes (README skeleton, S00-T06).
  - A script fails part-way: the PostgreSQL container must end unhealthy, not half-initialized and healthy. Verify from a fresh volume.
  - Passwords with shell or SQL metacharacters: use psql variable quoting (`:'name'`), never string concatenation.
  - Default privileges apply only to objects created by the named role. If a different role runs Flyway, `verifier` silently loses visibility; assertion (c) catches this.
  - The superuser bypasses all checks. Services, tools and tests never use it except to run the init scripts.
  - Negative control: the test must be able to fail. Run it once with the `REVOKE CONNECT` commented out, observe the failure, restore it, and record both runs.
- **Outputs:** planned `infra/postgres/init.sql`, `infra/postgres/` entrypoint wrapper script (if used), `services/*/src/main/resources/db/migration/V1__baseline.sql`, datasource and Flyway sections of `services/*/src/main/resources/application.yml`, `DatabaseIsolationIT` at the D00-2 test location.
- **Verification and definition of done:**
  - `./gradlew integrationTest --tests '*DatabaseIsolationIT'` passes. The negative-control failure is recorded in H.4.
  - After `docker compose down -v && docker compose up -d --wait`, each service started with `bootRun` against Compose reports health UP.
  - `flyway_schema_history` in each database shows V1 installed by the owner role.
  - A manual `psql` session as `verifier` can `SELECT` but not `INSERT`, and a session as the stats role can read `pg_stat_statements` but no table data (transcript paths in H.4).

<a id="phase-3"></a>
### Phase 3 — CI and conventions

**Objective:** every push is checked automatically by layer, and pinned versions and evidence formats are written down before any measurement exists.
**Exit checkpoint:** the build and integration jobs are green on `main`, or recorded as Blocked. ADR-0002 is Proposed with every pin. TEMPLATE and the README skeleton are committed. D00-1, D00-5 and D00-9 are drafted in H.1. Change detection has been re-run.

<a id="s00-t05"></a>
#### S00-T05 — CI workflow (build + unit now; integration and e2e job skeletons wired to tags)
- **Outcome:** `.github/workflows/ci.yml` runs build + unit on every push and pull request, runs the integration tag with Testcontainers, and has an e2e job, run nightly and on manual dispatch, that brings the Compose stack up healthy and runs the e2e tag.
- **Estimate:** 1.0 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd) (triggers, job contents, target durations); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (O4: images publish on tags only); [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers); [docs/zerosum_ledger_mvp_plan.md#environments](zerosum_ledger_mvp_plan.md#environments) (CI profile, throwaway secrets); [docs/zerosum_ledger_mvp_plan.md#waiting-time](zerosum_ledger_mvp_plan.md#waiting-time) (e2e cadence); [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling); D00-10 tasks (S00-T02); D00-3 (S00-T03); `DatabaseIsolationIT` (S00-T04).
- **Depends on:** S00-T02, S00-T03, S00-T04
- **Instructions:**
  1. Triggers: `push`, `pull_request`, a nightly `schedule` and `workflow_dispatch`. Add a `concurrency` group per ref that cancels superseded runs. Set top-level `permissions: contents: read`.
  2. Job `build`: check out, set up the JDK from D00-1 so the toolchain resolver finds it, set up Gradle with caching, run `./gradlew build`, and upload test reports when the job fails.
  3. Job `integration` (after `build`): `./gradlew integrationTest`, using the runner's Docker for Testcontainers.
  4. Job `e2e` (schedule and dispatch only):
     - generate a throwaway `.env` with the single command that S00-T09 finalizes (until then, an inline generation step marked for replacement);
     - mask the generated values;
     - run `docker compose up -d --wait`, then `./gradlew e2eTest`;
     - dump `docker compose logs` on failure, and always run `docker compose down -v`.
     Until S05 adds scenarios, the Compose health wait is the job's real check; the empty e2e selection is reported as such (D00-10).
  5. Set `timeout-minutes` per job, derived from the targets in [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd). Pin third-party actions to a commit SHA or immutable release tag, and record the versions in D00-1.
  6. Don't publish images on merge to `main` or on any other trigger in this workflow. In the MVP, images publish on tags only, and the tag `v*` workflow (versioned GHCR images, migration test on tag, secret scan) belongs to [docs/step_09_demo_docs_release.md#s09-t07](step_09_demo_docs_release.md#s09-t07) (master §0.3 O4). Leave no placeholder job for it.
- **Edge cases and failure behavior:**
  - Never print `docker compose config` in CI logs: it shows interpolated secrets.
  - Docker Hub rate limits or pull failures: the job fails as an infrastructure failure. Re-running is allowed, but never recorded as a test pass.
  - Pull requests from forks have no repository secrets. The workflow must not need any.
  - No `continue-on-error` or automatic retry around test steps. A flaky money-path test is a product bug ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)).
  - Scheduled workflows run only on the default branch, and GitHub can disable them in inactive repositories. Note this in D00-5.
- **Outputs:** planned `.github/workflows/ci.yml`.
- **Verification and definition of done:**
  - A push to `main` produces green `build` and `integration` jobs. Record the run URL and durations in H.4, compared with [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd) and the exit criteria in [docs/zerosum_ledger_mvp_plan.md#step-00](zerosum_ledger_mvp_plan.md#step-00).
  - `gh workflow run ci.yml` produces a green `e2e` job with a healthy stack.
  - A throwaway branch with a deliberately failing unit test turns `build` red (run URL recorded, branch deleted).
  - `grep -nE 'ghcr\.io|docker/login-action|docker/build-push-action' .github/workflows/ci.yml` finds nothing (master §0.3 O4).
  - Without GitHub access: Blocked in H.5, and local `./gradlew build integrationTest` recorded as partial evidence only.

<a id="s00-t06"></a>
#### S00-T06 — ADR-0002 (pinned versions rechecked on execution day), `docs/results/TEMPLATE.md`, README quickstart skeleton
- **Outcome:** ADR-0002 records every pinned version with its source and check date, and matches the artifacts exactly. A neutral results template exists for all evidence files. The root README has a quickstart skeleton that claims nothing unverified.
- **Estimate:** 1.0 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack); [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology); [docs/zerosum_ledger_mvp_plan.md#sources](zerosum_ledger_mvp_plan.md#sources); [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits) and [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist) (license notes); [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) (M13 c, M14 a); [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation) and [docs/zerosum_ledger_mvp_plan.md#perf-tests](zerosum_ledger_mvp_plan.md#perf-tests) (result file contents, repetitions and ranges); [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling); [docs/README.md#source-of-truth](README.md#source-of-truth) (rule 6, dated external facts); catalog (S00-T02), Compose file (S00-T03).
- **Depends on:** S00-T02, S00-T03
- **Instructions:**
  1. Go through each stack row in [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack) and each image in [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology), plus the Gradle wrapper, OTel Java agent, k6, Toxiproxy, CI actions, the JSON Schema validator library and schema draft, and ArchUnit (master §0.3 C16). Add catalog entries for the validator and ArchUnit now; they stay unused until S01. Recheck the current release at the primary source on the execution day. Select the Boot-managed version where one exists, and never a pre-release.
  2. Write `docs/adr/0002-stack-and-pinned-versions.md` with one table row per component: selected version or tag (and digest if pinned), where it's managed (BOM, catalog, Compose, workflow), source URL, check date, and any deviation from the master proposal with its reason. Status stays **Proposed** until S00-T07 records SP3.
  3. A deviation that changes a *decision* (a different component, or leaving the Boot-managed line other than through SP3 fallback B) needs a change request ([docs/README.md#conflict-resolution](README.md#conflict-resolution)). A plain version recheck is recorded only here and in D00-1.
  4. In consequences, record the license notes: k6 and the Grafana stack used as unmodified tools; no jqwik; Flyway rather than Liquibase.
  5. Write `docs/results/TEMPLATE.md` with these sections:
     - evidence ID and type;
     - **status** (`Measured` or `Not run — <reason>`; estimates are never allowed);
     - provenance covering every M13(c) item;
     - host and Docker allocation;
     - scenario, workload and seeds;
     - exact commands;
     - raw data links;
     - results with repetitions and ranges;
     - the gate or threshold it is compared against, as a link to the master, never a copied number;
     - deviations and limitations.
     Keep it neutral; S07 and S08 specialize it.
  6. Turn the root `README.md` into a quickstart skeleton: prerequisites (link to the topology memory note), configure `.env` (S00-T09), start and verify the stack (`docker compose up -d --wait`), run the tests (D00-10 tasks), observability (S00-T08), reset (`down -v`, and that init scripts run only on an empty volume), and further reading (docs pack). Mark every section that isn't verified yet as "Planned — finalized in S09-T01". Make no timing claims.
- **Edge cases and failure behavior:**
  - A primary source is unreachable: record "not rechecked; master proposal retained", with the date and reason. Never guess a newer version.
  - The latest upstream release conflicts with the Boot BOM: keep the BOM-managed version and note the newer one.
  - Floating or missing tags in the master proposal: pin exact tags, and record which master entries lacked one.
  - A tag without a manifest for the host architecture: record it (S00-T03 edge case).
- **Outputs:** planned `docs/adr/0002-stack-and-pinned-versions.md`, `docs/results/TEMPLATE.md`, `README.md` (quickstart skeleton).
- **Verification and definition of done:**
  - A comparison of every version in `gradle/libs.versions.toml`, every `image:` in `docker-compose.yml`, every `uses:` in `.github/workflows/ci.yml` and the wrapper distribution against the ADR-0002 table shows zero mismatches (command and output in H.4).
  - `grep -nE 'image: .*(:latest)?$' docker-compose.yml` shows only explicitly tagged images.
  - TEMPLATE contains the `Not run` status option and every M13(c) item.
  - The README contains no statement that a timed quickstart has passed.

<a id="phase-4"></a>
### Phase 4 — Integration proof and observability

**Objective:** prove the risky framework combination (G0), then wire telemetry for every service according to the proven mode.
**Exit checkpoint:** G0 is recorded in H.6 with evidence. All four service containers export telemetry per D00-7. `.env.example` covers every variable. D00-6, D00-7 and D00-8 are Accepted in H.1. Change detection has been re-run before handoff.

<a id="s00-t07"></a>
#### S00-T07 — SP3 spike and decision
- **Outcome:** G0 is evaluated. Either the proposed combination is demonstrated with trace, metric, Flyway and Testcontainers evidence, or a fallback from [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes) is selected and verified within the timebox plus the contingency allowance of master §0.3 O1. G0 is evaluated right after this task (O2). D00-7 and ADR-0002 are finalized.
- **Estimate:** 2.0 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes) (SP3 question, criterion, fallbacks, timebox and contingency allowance); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (O1, O2); [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) (G0); [docs/zerosum_ledger_mvp_plan.md#first-two-days](zerosum_ledger_mvp_plan.md#first-two-days) (Day 2, task 1); [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack); [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits) (explicit Kafka client settings); [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) (R4, R8); ADR-0002 draft and D00-1 (S00-T06); Compose stack D00-3 (S00-T03); `docs/results/TEMPLATE.md`.
- **Depends on:** S00-T02, S00-T03, S00-T06
- **Instructions:**
  1. Record the start time in I.1. The timebox is the SP3 timebox in [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes). Master §0.3 O1 allows a stated amount of contingency on top of it before the G0 fallback applies. Record any contingency hours used in I.1 and H.5.
  2. Create a throwaway Gradle project `spikes/sp3-stack` that uses **only catalog versions**. An ad-hoc version would stop the spike from proving the pinned combination.
  3. Build the flow: an HTTP POST endpoint → `JdbcClient` insert into a Flyway-created table → Kafka send to an explicitly created spike topic → `@KafkaListener` → `JdbcClient` insert. Increment one custom Micrometer meter in the listener.
  4. Set the producer and consumer settings that [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits) requires to be explicit, so SP3 proves the pinned broker accepts them. Their final values belong to S04 (D04-2).
  5. Run the app with the OTel Java agent from D00-1 exporting traces to otel-lgtm, and with Micrometer OTLP metrics export.
  6. Collect the evidence:
     - **A (trace):** one trace ID in Grafana/Tempo containing the HTTP server, Kafka produce, Kafka consume and JDBC spans. Record whether the consumer span is a child or a span link; S04-T06 needs this.
     - **B (metric):** the custom meter queryable in Grafana under the spike's service name.
     - **C (Testcontainers):** a spike integration test of the same flow against Testcontainers PostgreSQL and Kafka passes, without the agent.
     - **D (Flyway):** the migration was applied at startup through Boot's Flyway starter.
  7. Decide, and record the decision in D00-7:
     - A–D all pass: proceed with the proposed wiring.
     - B fails but A, C and D pass: fallback A (scraping). Verify it within the O1 allowance by seeing the meter in Grafana through scraping.
     - A, C or D fails because of a Boot-line incompatibility: fallback B (previous Boot line). Rerun the failing evidence, and record which components forced the change.
  8. If the proposed wiring isn't proven when the timebox ends, continue only within the O1 contingency allowance. If it still fails after that, apply the G0 fallback from [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) and verify it. If no option can be verified, record G0 as **not passed** and don't start S00-T08.
  9. Write `docs/results/sp3-stack-compat.md` from TEMPLATE, with trace ID, screenshots, the test report and versions. Set ADR-0002 to Accepted with an SP3 section. Evaluate G0 and record it in H.6 now, before S00-T08 starts (master §0.3 O2).
  10. Commit the spike and record its SHA in the results file. Then remove `spikes/` from `settings.gradle.kts` and delete the directory in a follow-up commit.
- **Edge cases and failure behavior:**
  - Two separate traces instead of one: check for the trace header on the record with a console consumer that prints headers. Broken propagation is an SP3 failure on criterion A, never something to hand-wave.
  - The agent doesn't support the pinned JVM or Boot line: it may start without instrumenting anything. Judge by spans and agent startup logs, not by application health.
  - A client–broker incompatibility shows up as consumer-group or protocol errors under the explicit settings. Capture the logs as evidence for the fallback decision.
  - Metrics exported twice (the agent's own exporter plus Micrometer): record which component exported what; the resolution belongs to D00-6.
  - A metric doesn't appear yet: wait at least one export interval before concluding it failed, and record the interval.
  - A host-run spike can't reach OTLP: use the local-only port recorded in D00-3, or run the spike as a container.
- **Outputs:** planned `docs/results/sp3-stack-compat.md` (with screenshots under `docs/results/`), updated `docs/adr/0002-stack-and-pinned-versions.md`, `spikes/sp3-stack/**` (temporary, removed after its SHA is recorded).
- **Verification and definition of done:**
  - The results file status is `Measured`, with A–D evidence or a verified fallback, a commit SHA and versions.
  - H.1 D00-7 is Accepted, naming the selected path and fallback (or "none").
  - H.6 gate result is Passed or Not passed, never blank, and recorded before S00-T08 starts (master §0.3 O2).
  - Any contingency hours beyond the estimate are recorded in I.1 and H.5 and stay within the O1 allowance.
  - `./gradlew projects` no longer lists the spike, and `./gradlew build` is green after removal.

<a id="s00-t08"></a>
#### S00-T08 — OTel agent and metrics export wiring for all four services, per the SP3 outcome
- **Outcome:** all four services run as Compose containers with the agent attached, distinct service names, and traces and metrics reaching otel-lgtm in the D00-7 mode. Services keep running without the telemetry backend, and tests never need it.
- **Estimate:** 2.0 h
- **Inputs:** D00-7 (S00-T07); D00-1; D00-3 (S00-T03); D00-4 (S00-T04); [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack); [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation); [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology) (service containers, limits, ports); [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) (graceful shutdown); [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) (TB1 exposure); [docs/zerosum_ledger_mvp_plan.md#environments](zerosum_ledger_mvp_plan.md#environments).
- **Depends on:** S00-T04, S00-T07
- **Instructions:**
  1. Choose the agent attach method (D00-6). The suggested starting point: the service image contains the agent JAR, resolved by Gradle from its catalog coordinates, and attached through `JAVA_TOOL_OPTIONS`. The version then stays in D00-1 and images stay self-contained. Record the alternatives (volume mount, download at image build) in H.1.
  2. Define the service image build (D00-3): a Dockerfile with an exec-form entrypoint, or Boot's image build. Size the heap as a percentage of the container limit, leaving room for agent overhead.
  3. Add the four services to `docker-compose.yml`:
     - `depends_on` with health conditions for PostgreSQL (and Kafka, recorded for S04); no hard dependency on otel-lgtm;
     - credentials by variable reference (D00-8);
     - an explicit OTel service name per service, the collector endpoint on the Compose network, and resource attributes for service version and environment;
     - actuator health checks;
     - ports per [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology);
     - a `stop_grace_period` covering the graceful-shutdown timeout.
  4. Metrics per D00-7: either Micrometer OTLP export to the collector, or actuator metrics exposure plus scrape configuration under `infra/otel/`. Configure a single exporter for metrics and record in D00-6 which component exports what.
  5. Restrict actuator HTTP exposure to health (plus the metrics endpoint under fallback A).
  6. Tests and CI: don't attach the agent in `test` or `integrationTest`, and disable metric export in test configuration, so builds pass with no backend running.
  7. Don't add custom spans or metric names ([docs/step_07_observability_performance.md#s07-t01](step_07_observability_performance.md#s07-t01)) or cross-service business traces ([docs/step_04_kafka_pipeline.md#s04-t06](step_04_kafka_pipeline.md#s04-t06)).
- **Edge cases and failure behavior:**
  - otel-lgtm stopped: services stay healthy, and exporter errors are logged at a bounded rate.
  - A container OOM kill after attaching the agent: adjust the heap percentage first. Changing a limit from [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology) needs a D00-3 record with evidence.
  - A shell-form entrypoint keeps SIGTERM from reaching the JVM, so the container is killed after the grace period (exit 137). Verify a clean stop.
  - Health-check requests generate trace noise: acceptable locally. Record any filtering choice.
  - Under fallback A, scraping needs the service hostnames on the Compose network. Verify a target is up, not just that configuration exists.
- **Outputs:** planned `services/*/Dockerfile` (or Boot image configuration), service entries in `docker-compose.yml`, observability sections of `services/*/src/main/resources/application.yml`, `infra/otel/` (only if needed).
- **Verification and definition of done:**
  - After `docker compose down -v`, `time docker compose up -d --wait` has infrastructure and all four services healthy. Record the elapsed time against [docs/zerosum_ledger_mvp_plan.md#cold-warm](zerosum_ledger_mvp_plan.md#cold-warm) in H.4.
  - Tempo search shows spans for each of the four service names, and one JVM metric per service can be queried in Grafana (screenshots in H.4).
  - After `docker compose stop otel-lgtm`, all services still report health UP.
  - `docker compose stop order-service` ends with exit code 143, not 137.
  - `./gradlew build integrationTest` is green with otel-lgtm stopped.

<a id="s00-t09"></a>
#### S00-T09 — `.env.example`, secrets conventions, log-redaction guideline
- **Outcome:** every variable S00 introduced is listed with a placeholder. The conventions later steps follow are written down. A single generation command serves local use and CI. The log-redaction guideline defines what later redaction tests must prove.
- **Estimate:** 1.0 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets) (secrets, generation, rotation, hygiene); [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) (authentication variables, whose names S03 owns); [docs/zerosum_ledger_mvp_plan.md#environments](zerosum_ledger_mvp_plan.md#environments); [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) (TB4 chaos flag, owned by S08); [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist) (security and hygiene); D00-3, D00-4, D00-5, D00-6.
- **Depends on:** S00-T04, S00-T05, S00-T08
- **Instructions:**
  1. Inventory every variable referenced in `docker-compose.yml`, `infra/postgres/`, `services/*/src/main/resources/application.yml` and `.github/workflows/ci.yml`.
  2. Write the conventions (D00-8):
     - project variables use the `ZS_` prefix in upper snake case; variables required by third-party images or the OTel SDK keep their standard names;
     - secret-bearing names share a recognizable suffix;
     - list-valued variables follow the format in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting);
     - required secrets have no defaults and fail fast (Compose `${VAR:?message}` interpolation; no fallback values in Boot configuration).
  3. Write `.env.example`: each variable with a placeholder that can't work as a real secret, a purpose comment and a trace comment, grouped by owning step. Include a header telling later steps (S03 tokens, S05 webhook secrets and kill switches, S08 chaos allow flag) to append their variables here. Don't invent their names now.
  4. Add one generation script (suggested `tools/dev/generate-env.sh`; record the path in D00-2) that copies `.env.example` to `.env`, replacing placeholders with values generated by the method in [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets). It refuses to overwrite an existing `.env` unless forced. Switch the CI e2e job and the README skeleton over to it.
  5. Write `docs/secrets.md`, the secrets note (a README section is the permitted alternative; record which). It covers: what the secrets are, local and CI handling, and a pointer to the demo-VM and rotation rules in [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets). It also holds the **log-redaction guideline**:
     - never log bearer tokens or `Authorization` values, HMAC signatures, webhook secrets, database passwords, credential-bearing JDBC URLs, or environment dumps;
     - request logging excludes headers by default;
     - authentication errors never echo the presented credential.
     Required redaction tests, which live where the secret is handled: a known sentinel secret flows through the code path with logging at its most verbose, and the captured log output is asserted not to contain it. Owners: [docs/step_03_order_service_outbox.md#s03-t03](step_03_order_service_outbox.md#s03-t03) for tokens, [docs/step_05_instruments_fake_providers.md#s05-t11](step_05_instruments_fake_providers.md#s05-t11) for webhook signatures.
- **Edge cases and failure behavior:**
  - `.env` committed by mistake: `git rm --cached .env`, regenerate every value, and record the incident in H.5.
  - Generated values containing `$` or quotes break Compose interpolation. Use the hex output of the documented method.
  - A missing variable must fail at `docker compose config` with a message, never start PostgreSQL with an empty password.
  - Placeholders left in a real `.env`: the generation script exits non-zero if any placeholder marker remains.
  - Windows line endings in `.env` corrupt values. The script writes LF.
- **Outputs:** planned `.env.example`, `tools/dev/generate-env.sh`, `docs/secrets.md`, updated `.github/workflows/ci.yml` (e2e job) and `README.md` (configure section).
- **Verification and definition of done:**
  - Every variable found by the inventory appears in `.env.example`: a scripted diff shows zero missing.
  - In a fresh clone, `tools/dev/generate-env.sh && docker compose up -d --wait` comes up healthy.
  - Unsetting one required variable makes `docker compose config` fail with its message.
  - After the stack has run, `docker compose logs` contains none of the generated secret values: a `grep -F` per value finds zero matches. This is a smoke check; it doesn't replace the S03 and S05 unit tests.
  - `git check-ignore .env` still prints `.env`.
  - The CI e2e job uses the script.

<a id="conditional-work"></a>
### Conditional and deferred work

**Conditional tasks:** none are defined for S00 (pack ownership map). The only overrun path is SP3. Master §0.3 O1 ([docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)) allows contingency hours on top of the SP3 timebox before the G0 fallback ([docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes), [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates)). Those hours come from unallocated contingency ([docs/README.md#effort](README.md#effort)) and are recorded in I.1 and H.5. No new task is created.

**Deferred improvements** (not built in S00):

- Kubernetes/Helm manifests, a Debezium/Kafka Connect container and a schema registry: [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred). The Compose topology leaves no placeholders for them.
- The `demo-public` profile with Grafana Cloud export and without toxiproxy: S5 in [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have), decided in S09 ([docs/step_09_demo_docs_release.md#conditional-work](step_09_demo_docs_release.md#conditional-work)).
- AOT/CDS startup optimization: deferred per [docs/zerosum_ledger_mvp_plan.md#cold-warm](zerosum_ledger_mvp_plan.md#cold-warm).
- Kafka authentication and replication for production: [docs/zerosum_ledger_mvp_plan.md#mvp-vs-production](zerosum_ledger_mvp_plan.md#mvp-vs-production).

<a id="risks-and-recovery"></a>
## F. Risks and recovery

| Risk | Detection (signal or test) | Recovery / fallback |
|---|---|---|
| The selected Boot line doesn't work with the OTel agent, Kafka clients, Flyway or Testcontainers (R8 in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register)) | SP3 evidence A–D in S00-T07; agent startup warnings; no spans despite healthy apps | Fallback A or B per [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes), recorded in D00-7. Downstream steps handle the alternatives listed in D.4. |
| Infrastructure debugging eats learning time (R4) | S00 actual hours in I.1 exceed estimates; the R4 trigger in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) | Apply the R4 mitigation (for example scraping instead of OTLP, fewer moving parts). Never add unplanned tooling. |
| Kafka advertised listeners wrong for one of the two client paths | The host produce/consume check in S00-T03 hangs while the internal path works | Separate internal and host listeners; record in D00-3; re-run both checks |
| Docker VM memory below the topology assumption | `OOMKilled=true`; `docker compose up --wait` times out | Raise the VM allocation and record it. A limit change goes into D00-3 with evidence. |
| Database isolation not actually enforced (PostgreSQL grants CONNECT to PUBLIC by default) | `DatabaseIsolationIT` and its recorded negative control | Explicit REVOKE in `infra/postgres/init.sql`. The test runs in every CI integration job. |
| Default privileges tied to the wrong grantor role, so the verifier can't see tables created later | `DatabaseIsolationIT` assertion (c); later read failures in [docs/step_06_reconciliation_verifier.md#s06-t04](step_06_reconciliation_verifier.md#s06-t04) | Flyway runs as the owner role. If S02, S03 or S05 need a different grantor, they raise a change request against D00-4. |
| `pg_stat_statements` not loaded, or the stats role able to read table data | `SHOW shared_preload_libraries` (S00-T03); `DatabaseIsolationIT` assertion (f) | Fix the D00-3 server setting (it needs a server restart); restrict the stats role to predefined monitoring access (D00-4) |
| Version drift or floating image tags break reproducibility | The ADR-0002 vs artifact comparison in S00-T06; grep for untagged images | Exact tags or digests; rechecks recorded in ADR-0002 with dates |
| Host JDK differs from the pinned toolchain (A7 in [docs/zerosum_ledger_mvp_plan.md#assumptions](zerosum_ledger_mvp_plan.md#assumptions)) | Class-file major-version check in S00-T02 | Toolchain provisioning; the CI JDK comes from D00-1 |
| Telemetry exported twice, or a telemetry outage takes services down | Duplicate series in Grafana; the health check with otel-lgtm stopped (S00-T08) | One metrics exporter (D00-6); no hard startup dependency on otel-lgtm |
| Secrets leak through logs, CI output or a committed `.env` | The log grep smoke check (S00-T09); `git check-ignore`; CI log review | D00-8 conventions; regenerate values after any leak; redaction tests in S03 and S05; the secret scan in the tag workflow ([docs/step_09_demo_docs_release.md#s09-t07](step_09_demo_docs_release.md#s09-t07); master §0.3 O4) |
| GitHub access unavailable | `gh auth status` fails | Record the Blocked status for push and CI evidence in H.5. Local results count only as partial evidence. |
| MVP limitations (no Kafka authentication, single broker) are later presented as production properties (R7) | Review of README and ADR-0002 wording | List them in H.5 and in ADR-0002 consequences, consistent with the honesty items in [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist) |

<a id="acceptance-checklist"></a>
## G. Acceptance checklist

- [ ] **G0 decided right after S00-T07** (master §0.3 O2, [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)): H.6 records the gate result, and `docs/results/sp3-stack-compat.md` holds evidence for the SP3 criterion in [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes), or the fallback is chosen per G0 in [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates). D00-7 is Accepted.
- [ ] **Fresh clone to healthy stack:** the measured time from clone to all containers healthy is recorded in H.4. It meets the exit criterion in [docs/zerosum_ledger_mvp_plan.md#step-00](zerosum_ledger_mvp_plan.md#step-00) and the Compose cold-start target in [docs/zerosum_ledger_mvp_plan.md#cold-warm](zerosum_ledger_mvp_plan.md#cold-warm), or the miss is recorded with its cause.
- [ ] **CI:** the `build` and `integration` jobs are green on `main`, and their duration is recorded against the exit criterion in [docs/zerosum_ledger_mvp_plan.md#step-00](zerosum_ledger_mvp_plan.md#step-00) and the push target in [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd). The `e2e` job skeleton has run green at least once.
- [ ] **Tag-only publishing (master §0.3 O4):** `ci.yml` publishes no images; the tag workflow is left to S09-T07.
- [ ] **TB3:** each service role connects only to its own database, and `DatabaseIsolationIT` passes with its negative control recorded ([docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries); design rules in [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components)).
- [ ] **TB5:** the `verifier` role reads every service database and can't write to any of them.
- [ ] **Query statistics (master §0.3 O12):** `pg_stat_statements` is preloaded, and the stats role reads it without any table-data access.
- [ ] **Role model (master §0.3 O3):** each service has an owner role and an application role, both confined to their own database by `DatabaseIsolationIT`, so M2(a) is testable ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)).
- [ ] **Pinned versions:** every version in the catalog (including the JSON Schema validator and ArchUnit, master §0.3 C16), Compose file, wrapper and workflow matches ADR-0002, which has a check date and source per row. No untagged or `latest` images.
- [ ] **Module layout:** `./gradlew projects` lists every D00-2 project, including `libs/auth` (master §0.3 C9).
- [ ] **Topology:** the default profile excludes toxiproxy and the `chaos` profile adds it. Memory limits match [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology), or the deviation is recorded in D00-3.
- [ ] **Telemetry (foundation for M12):** all four service names appear in traces, and metrics are visible in the D00-7 mode. Services stay healthy with otel-lgtm stopped, and builds pass without it.
- [ ] **Graceful stop:** service containers stop cleanly within their grace period (exit 143, not 137).
- [ ] **Evidence format (foundation for M13 c):** `docs/results/TEMPLATE.md` covers every M13(c) item and the "Not run" status ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)).
- [ ] **Hygiene:** a license file is present; `.env` is git-ignored; `.env.example` contains placeholders only and covers every referenced variable; no generated secret appears in container logs (security and hygiene in [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist)).
- [ ] **ADRs:** ADR-0001 and ADR-0002 are Accepted; ADR-0001 lists the reserved ADR numbers.
- [ ] **Spike removed:** `spikes/` is gone from the build, and its commit SHA is recorded in the SP3 results file.
- [ ] **Records complete:** H.1 has every D00 ID Accepted with rationale; H.2 and H.3 have actual paths; H.4 has results and evidence paths; I.1 has every task Done, or Blocked with its exact dependency; I.2 is current after the final change detection.

<a id="decisions-and-outputs"></a>
## H. Decisions and outputs register

### H.1 Decisions and rationale

| ID | Decision | Rationale | Alternatives considered | Status | Date |
|---|---|---|---|---|---|
| D00-1 | — | — | — | Pending | — |
| D00-2 | — | — | — | Pending | — |
| D00-3 | — | — | — | Pending | — |
| D00-4 | — | — | — | Pending | — |
| D00-5 | — | — | — | Pending | — |
| D00-6 | — | — | — | Pending | — |
| D00-7 | **SP3 passed; proceed with the proposed wiring, fallback: none.** OTel Java agent 2.31.1 exports traces; Micrometer OTLP registry exports metrics. Binding wiring findings: (1) add the `spring-boot-opentelemetry` module (not the starter) or Boot 4.1 creates no OTLP registry; (2) disable the agent's own metrics/logs exporters with `-Dotel.metrics.exporter=none -Dotel.logs.exporter=none` JVM properties, never `OTEL_*_EXPORTER` environment variables, which Boot maps onto `management.otlp.metrics.export.enabled`; (3) Kafka consumer span is a child of the producer span, not a link (input to S04-T06). Evidence: [docs/results/sp3-stack-compat.md](results/sp3-stack-compat.md), spike SHA `d4e49f0`. | Criteria A–D all passed within 0.4 h of the 2 h timebox; both B failures had wiring causes that were found and fixed, not incompatibilities | Fallback A (Prometheus scraping of `/actuator/prometheus`) and fallback B (Boot 4.0.x) not needed; `spring-boot-starter-opentelemetry` rejected because its Micrometer Tracing bridge would export traces twice | Accepted | 2026-09-15 |
| D00-8 | — | — | — | Pending | — |
| D00-9 | — | — | — | Pending | — |
| D00-10 | — | — | — | Pending | — |

### H.2 Implementation and configuration locations

| Item | Planned path | Actual path | Traced to |
|---|---|---|---|
| Git remote | GitHub public repository `zerosum-ledger` | — | D00-2 |
| Module layout | `settings.gradle.kts` | — | D00-2 |
| Auth library module (empty until S03) | `libs/auth/build.gradle.kts` | — | D00-2 |
| Version catalog | `gradle/libs.versions.toml` | — | D00-1 |
| Gradle wrapper | `gradle/wrapper/gradle-wrapper.properties` | — | D00-1 |
| Convention plugins, toolchain, test tasks | `build-logic/` (or `buildSrc/`) | — | D00-10 |
| Compose topology | `docker-compose.yml` | — | D00-3 (image tags: D00-1) |
| Database initialization, roles and `pg_stat_statements` extension | `infra/postgres/init.sql` (+ entrypoint wrapper, if used) | — | D00-4 |
| PostgreSQL server configuration (`pg_stat_statements` preload) | `docker-compose.yml` (or a mounted configuration file) | — | D00-3 |
| Datasource, Flyway and observability settings | `services/*/src/main/resources/application.yml` | — | D00-4, D00-6, D00-8 |
| Baseline migrations | `services/*/src/main/resources/db/migration/V1__baseline.sql` | — | D00-4 |
| Isolation test location | `infra/tests/` (suggested) | — | D00-2, D00-4 |
| CI workflow | `.github/workflows/ci.yml` | — | D00-5 (action versions: D00-1) |
| Service images and agent attach | `services/*/Dockerfile` (or Boot image configuration) | — | D00-3, D00-6 |
| Collector or scrape configuration | `infra/otel/` (only if needed) | — | D00-6, D00-7 |
| Environment index | `.env.example` | — | D00-8 |
| Environment generation script | `tools/dev/generate-env.sh` | — | D00-8 |

### H.3 Produced artifacts

| Artifact | Planned path | Actual path | Revision/hash |
|---|---|---|---|
| License | `LICENSE` | — | — |
| Git ignore rules | `.gitignore` | — | — |
| Root README (stub, then quickstart skeleton) | `README.md` | — | — |
| ADR-0001 | `docs/adr/0001-record-architecture-decisions.md` | — | — |
| ADR-0002 | `docs/adr/0002-stack-and-pinned-versions.md` | — | — |
| Results template | `docs/results/TEMPLATE.md` | — | — |
| SP3 results | `docs/results/sp3-stack-compat.md` | — | — |
| SP3 spike code (removed after decision) | `spikes/sp3-stack/` (commit SHA only) | — | — |
| Cross-database isolation test | `DatabaseIsolationIT` at the D00-2 location | — | — |
| Secrets and log-redaction note | `docs/secrets.md` | — | — |

### H.4 Validation results and evidence

| Check | Method | Result | Evidence path | Date |
|---|---|---|---|---|
| Fresh-clone build | `./gradlew build` in a new clone (S00-T02) | Not run | — | — |
| Pinned toolchain used | `javap -v` class-file major version (S00-T02) | Not run | — | — |
| Infrastructure cold start | `time docker compose up -d --wait` after `down -v` (S00-T03) | Not run | — | — |
| Chaos profile isolation | `docker compose ps` with and without `--profile chaos` (S00-T03) | Not run | — | — |
| Memory limits applied | `docker inspect` vs topology (S00-T03) | Not run | — | — |
| `pg_stat_statements` preloaded | `SHOW shared_preload_libraries` (S00-T03) | Not run | — | — |
| Kafka internal and host listeners | Console produce/consume on both paths (S00-T03) | Not run | — | — |
| Database isolation and verifier read-only | `./gradlew integrationTest --tests '*DatabaseIsolationIT'` (S00-T04) | Not run | — | — |
| Stats role reads statistics, no table data | `DatabaseIsolationIT` assertion (f) (S00-T04) | Not run | — | — |
| Isolation negative control | Test run with CONNECT revoke removed (S00-T04) | Not run | — | — |
| Services boot with Flyway baseline | `bootRun` per service against Compose (S00-T04) | Not run | — | — |
| CI build and integration jobs | GitHub Actions run on `main` (S00-T05) | Not run | — | — |
| No image publishing in CI | grep of `ci.yml` (S00-T05) | Not run | — | — |
| CI e2e skeleton | `gh workflow run ci.yml` (S00-T05) | Not run | — | — |
| CI detects failure | Deliberately failing test on a throwaway branch (S00-T05) | Not run | — | — |
| ADR-0002 matches artifacts | Scripted version comparison (S00-T06) | Not run | — | — |
| SP3 evidence A–D and G0 decision | Spike run and Grafana/Tempo evidence (S00-T07) | Not run | — | — |
| Telemetry from all four services | Tempo and Grafana queries (S00-T08) | Not run | — | — |
| Services resilient to telemetry outage | `docker compose stop otel-lgtm` then health (S00-T08) | Not run | — | — |
| Graceful container stop | Exit code after `docker compose stop` (S00-T08) | Not run | — | — |
| Full stack cold start | `time docker compose up -d --wait` with services (S00-T08) | Not run | — | — |
| Environment index complete | Scripted variable inventory diff (S00-T09) | Not run | — | — |
| Required variable fails fast | `docker compose config` with a variable unset (S00-T09) | Not run | — | — |
| Secrets absent from logs | `grep -F` of generated values in `docker compose logs` (S00-T09) | Not run | — | — |

### H.5 Known limitations and blockers

| Item | Type (limitation/blocker) | Impact | Owner/next action |
|---|---|---|---|
| Kafka without authentication on the private Compose network ([docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries), TB3) | limitation | Any container on the network can produce and consume | Documented MVP limitation; listed in the release checklist honesty items (S09) |
| Single broker without replication ([docs/zerosum_ledger_mvp_plan.md#mvp-vs-production](zerosum_ledger_mvp_plan.md#mvp-vs-production)) | limitation | Losing the Kafka volume loses unconsumed messages | Known data-loss window per [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery) |
| Memory limits are laptop ASSUMPTIONS ([docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology)) | limitation | Other hosts may OOM or measure differently | Record the actual host in H.4; S07 records it in its results |
| SP3 timebox vs the longer G0 failure threshold ([docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes), [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates)) | limitation | None after v1.2 | Resolved in master v1.2 (§0.3 O1, O2) |
| Role model: one role per service vs owner and application roles | limitation | None after v1.2 | Resolved in master v1.2 (§0.3 O3) |
| GHCR image publishing, the migration test on tag and the pre-tag secret scan had no owning step ([docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd), [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets)) | limitation | None after v1.2; S00-T05 publishes nothing | Resolved in master v1.2 (§0.3 O4): owned by S09-T07 |

### H.6 Completion status

| Field | Value |
|---|---|
| Step status | In progress |
| Gate result | **G0 Passed** (2026-09-15T21:50Z, right after S00-T07, before S00-T08; master §0.3 O2). SP3 criteria A–D passed with no fallback: [docs/results/sp3-stack-compat.md](results/sp3-stack-compat.md). |
| Completed on | — |
| Completed by | — |
| Handoff accepted by next step | — |

<a id="execution-record"></a>
## I. Execution and change record

### I.1 Task execution record

| Task ID | Status | Output paths | Evidence | Blockers |
|---|---|---|---|---|
| S00-T01 | Planned | — | — | — |
| S00-T02 | Planned | — | — | — |
| S00-T03 | Planned | — | — | — |
| S00-T04 | Planned | — | — | — |
| S00-T05 | Planned | — | — | — |
| S00-T06 | Planned | — | — | — |
| S00-T07 | Planned | — | — | — |
| S00-T08 | Planned | — | — | — |
| S00-T09 | Planned | — | — | — |

<a id="change-record"></a>
### I.2 Consumed sources and change record

| Source/artifact path | Revision or hash | Recorded at | Affected tasks | Review outcome |
|---|---|---|---|---|
| `docs/zerosum_ledger_mvp_plan.md` | — | — | S00-T01 to S00-T09 | — |
| `docs/README.md` | — | — | S00-T01 to S00-T09 | — |
| `docs/zerosum_ledger_mvp_plan.md` v1.2 §0.3 (O1, O2, O3, O4, O12, C9, C16) | — | 2026-09-15 (doc version 1.1) | S00-T02, S00-T03, S00-T04, S00-T05, S00-T06, S00-T07 | Resolved in master v1.2 (§0.3 O1, O2, O3, O4, O12, C9, C16); instructions and verification updated |
| `docs/step_00_foundations.md` | — | — | S00-T01 to S00-T09 | — |
| Primary version sources listed in [docs/zerosum_ledger_mvp_plan.md#sources](zerosum_ledger_mvp_plan.md#sources) (record URL and retrieval date) | — | — | S00-T02, S00-T03, S00-T05, S00-T06, S00-T07 | — |
| `docs/adr/0002-stack-and-pinned-versions.md` (consumed by later S00 tasks once written) | — | — | S00-T07, S00-T08 | — |
| `docker-compose.yml` (consumed by later S00 tasks once written) | — | — | S00-T04, S00-T05, S00-T07, S00-T08, S00-T09 | — |

<a id="handoff"></a>
## J. Handoff

**What later steps consume.** Resolve every item from this document's register (section H) and the artifact it points to, never from the task text above.

| Consumer | Consumes | Resolve from | Consuming tasks (where known) |
|---|---|---|---|
| S01 | Module layout, catalog and pin-update policy (including the JSON Schema validator and ArchUnit pins, master §0.3 C16), test tasks and tags, CI jobs | D00-1, D00-2, D00-5, D00-10; `gradle/libs.versions.toml`, `.github/workflows/ci.yml` | [docs/step_01_domain_contracts.md#s01-t01](step_01_domain_contracts.md#s01-t01), [docs/step_01_domain_contracts.md#s01-t05](step_01_domain_contracts.md#s01-t05), [docs/step_01_domain_contracts.md#s01-t06](step_01_domain_contracts.md#s01-t06), [docs/step_01_domain_contracts.md#s01-t07](step_01_domain_contracts.md#s01-t07) |
| S02 | Ledger database, owner and application roles (master §0.3 O3) and default privileges, Flyway baseline, Testcontainers image pin, integration tag | D00-4, D00-1, D00-10; `infra/postgres/`, ledger-service `db/migration/` | [docs/step_02_ledger_core.md#s02-t01](step_02_ledger_core.md#s02-t01), [docs/step_02_ledger_core.md#s02-t03](step_02_ledger_core.md#s02-t03) |
| S03 | Orders database, owner and application roles and baseline; the empty `libs/auth` module (master §0.3 C9); environment conventions and the redaction-test obligation | D00-2, D00-4, D00-8; `.env.example`, `docs/secrets.md` | [docs/step_03_order_service_outbox.md#s03-t01](step_03_order_service_outbox.md#s03-t01), [docs/step_03_order_service_outbox.md#s03-t02](step_03_order_service_outbox.md#s03-t02), [docs/step_03_order_service_outbox.md#s03-t03](step_03_order_service_outbox.md#s03-t03) |
| S04 | Kafka in Compose (listeners, auto topic creation setting); SP3 evidence for explicit client settings and trace propagation | D00-3, D00-7; `docker-compose.yml`, `docs/results/sp3-stack-compat.md` | [docs/step_04_kafka_pipeline.md#s04-t01](step_04_kafka_pipeline.md#s04-t01), [docs/step_04_kafka_pipeline.md#s04-t05](step_04_kafka_pipeline.md#s04-t05), [docs/step_04_kafka_pipeline.md#s04-t06](step_04_kafka_pipeline.md#s04-t06) |
| S05 | Instruments and fake-providers databases and roles; service container entries; environment conventions for webhook secrets and kill switches | D00-3, D00-4, D00-8 | [docs/step_05_instruments_fake_providers.md#s05-t01](step_05_instruments_fake_providers.md#s05-t01), [docs/step_05_instruments_fake_providers.md#s05-t07](step_05_instruments_fake_providers.md#s05-t07), [docs/step_05_instruments_fake_providers.md#s05-t11](step_05_instruments_fake_providers.md#s05-t11) |
| S06 | Read-only `verifier` role across all service databases | D00-4; `infra/postgres/` | [docs/step_06_reconciliation_verifier.md#s06-t04](step_06_reconciliation_verifier.md#s06-t04) |
| S07 | Agent attach method, metrics export mode (including any SP3 fallback), results template, `pg_stat_statements` and the stats-reading role (master §0.3 O12) | D00-3, D00-4, D00-6, D00-7, D00-9; `docs/results/TEMPLATE.md` | [docs/step_07_observability_performance.md#s07-t01](step_07_observability_performance.md#s07-t01), [docs/step_07_observability_performance.md#s07-t02](step_07_observability_performance.md#s07-t02), [docs/step_07_observability_performance.md#s07-t03](step_07_observability_performance.md#s07-t03), [docs/step_07_observability_performance.md#s07-t04](step_07_observability_performance.md#s07-t04) |
| S08 | `chaos` Compose profile with toxiproxy, restart policy, results template, environment conventions for the chaos allow flag | D00-3, D00-8, D00-9 | [docs/step_08_fault_injection_ablation.md#s08-t02](step_08_fault_injection_ablation.md#s08-t02), [docs/step_08_fault_injection_ablation.md#s08-t03](step_08_fault_injection_ablation.md#s08-t03), [docs/step_08_fault_injection_ablation.md#s08-t04](step_08_fault_injection_ablation.md#s08-t04) |
| S09 | README quickstart skeleton, ADR process and reserved numbers, secrets note, license; the CI structure that the tag workflow extends (master §0.3 O4) | D00-5, D00-8, D00-9; `README.md`, `docs/adr/`, `docs/secrets.md` | [docs/step_09_demo_docs_release.md#s09-t01](step_09_demo_docs_release.md#s09-t01), [docs/step_09_demo_docs_release.md#s09-t02](step_09_demo_docs_release.md#s09-t02), [docs/step_09_demo_docs_release.md#s09-t07](step_09_demo_docs_release.md#s09-t07) |

**Handoff conditions**

1. **Gate:** G0 has a recorded result in H.6, evaluated right after S00-T07 (master §0.3 O2). If it did not pass, the G0 action from [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) has been taken, and the resulting fallback is verified and recorded in D00-7. S01 doesn't start before this.
2. **Register:** every D00 ID in H.1 is Accepted with rationale and alternatives. H.2 and H.3 have actual paths and revisions. H.4 has a result and evidence path for every check in section G.
3. **Execution record:** every task in I.1 is Done, or Blocked with its exact dependency named in H.5. A blocked item is accepted only if S01's C.4 doesn't list it as blocking.
4. **Change record:** change detection has been re-run immediately before handoff, and I.2 shows current revisions with review outcomes.
5. **Acceptance:** section G is fully checked. S01 records the S00 register revision in its own I.2 and fills "Handoff accepted by next step" in H.6.
