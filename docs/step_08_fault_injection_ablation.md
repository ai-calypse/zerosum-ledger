# Step 08 — Fault injection and ablation

> **Pack:** ZeroSum Ledger implementation docs · **Master:** [docs/zerosum_ledger_mvp_plan.md#step-08](zerosum_ledger_mvp_plan.md#step-08) (v1.2)
> **Doc version:** 1.1 · **Date:** 2026-09-15 · **Step status:** Planned (no implementation exists)
> **Planned effort:** 16 h (master schedule; see [docs/zerosum_ledger_mvp_plan.md#schedule-overview](zerosum_ledger_mvp_plan.md#schedule-overview)) · **Gate:** G4
> **Shared procedures:** [docs/README.md#change-detection](README.md#change-detection) · [docs/README.md#source-of-truth](README.md#source-of-truth) · [docs/README.md#status-legend](README.md#status-legend)

<a id="purpose-and-outcome"></a>
## A. Purpose and outcome

**The problem this step solves.** By the end of S07, the ledger has invariants, idempotent consumers, an outbox, uncertain-outcome handling and a verifier. Each of these has passed its own tests. What the project still can't show is that the protections hold *together* under realistic failures. It also can't show that the test harness is strong enough to catch a failure when a protection is missing. The master names this as the biggest uncertainty ([docs/zerosum_ledger_mvp_plan.md#summary](zerosum_ledger_mvp_plan.md#summary)): if ablated variants don't fail, a "0 violations" result proves nothing (risk R6 in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register)).

**The concrete deliverable.** Five planned artifacts:

1. A seeded simulator CLI (`tools/simulator`, planned). It drives realistic trip workloads through the public APIs. Its workloads include the client duplicate submissions of F11 and the fare-split bug injection that A4 and B0 need (§0.3 E6).
2. Seeded chaos scripts for faults F1–F12 (`tools/chaos`, planned). They use container kill and restart, Toxiproxy toxics, provider fault profiles, and an in-process crash hook for F3.
3. Ablation flags for A1–A5 (B0 is their composite), placed as seams in the owning services. A startup guard keeps them from running outside the chaos profile.
4. A single-command run orchestrator. For each run it performs reset, load, faults, quiesce, verifier and metrics collection, then writes JSON and Markdown results.
5. The executed evidence: `docs/results/chaos-*.md` and `docs/results/ablation-*.md` (planned), with a validity evaluation per ablation and honest confidence statements.

**Contribution to the MVP.** This step implements must-have M13 ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)). It tests hypothesis H1 ([docs/zerosum_ledger_mvp_plan.md#facts-vs-hypotheses](zerosum_ledger_mvp_plan.md#facts-vs-hypotheses)) and decides gate G4 ([docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates)). It also produces the evidence behind hard gates 1, 2 and 4 in [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go) and the correctness-evidence section of [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist). The master's minimum cut says the ablation experiment and honest labeling of results can never be cut ([docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut)).

**Scope: in.**
- The simulator workloads, fault definitions and seeded schedules for the fault matrix in [docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix).
- The ablation seams and startup guard for the variants in [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation). These are added to S02, S03 and S05 code through change requests to those owners.
- Run orchestration, the results format, execution of the run matrix in [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes), analysis, validity evaluation and the write-up.
- The regression-fix loop when evidence finds a violation (S08-C01).

**Scope: explicitly excluded.**
- **The invariant checks themselves.** `tools/verifier` and its I1–I12 implementations belong to S06 (D06-5). This step calls the verifier; it never redefines an invariant. Weakening an invariant to pass is prohibited ([docs/zerosum_ledger_mvp_plan.md#step-08](zerosum_ledger_mvp_plan.md#step-08)).
- **Performance measurement and load scripts** (k6, SP4). These belong to S07. This step consumes the measured sustainable rate but runs no perf matrix.
- **The audit study, résumé bullets, README results summary and release.** These belong to S09 ([docs/step_09_demo_docs_release.md#handoff](step_09_demo_docs_release.md#handoff)).
- **Faults outside the matrix,** such as Kafka volume loss with RF=1 ([docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery)), and the conditional ledger replay tool. The latter has no step assignment ([docs/README.md#known-gaps](README.md#known-gaps)).
- **Running chaos in CI.** The master places fault injection in the "Local, scheduled" layer ([docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers)). CI runs only the guard and seam tests.
- **Deferred items and non-goals:** Debezium, Kubernetes, multi-region and similar ([docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred), [docs/zerosum_ledger_mvp_plan.md#non-goals](zerosum_ledger_mvp_plan.md#non-goals)).

**External waiting time.** The run matrix needs unattended machine time; the estimate is in [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes) and [docs/zerosum_ledger_mvp_plan.md#waiting-time](zerosum_ledger_mvp_plan.md#waiting-time). That time isn't part of the 16 engineering hours. When it's unavailable, the affected evidence is recorded as "Not run" ([docs/README.md#status-legend](README.md#status-legend)).

<a id="agent-prompt"></a>
## B. Step-specific agent prompt

```text
You are the implementation agent for Step 08 (Fault injection and ablation) of the ZeroSum Ledger project.

Repository root: zerosum-ledger/
Step document: docs/step_08_fault_injection_ablation.md

1. Read docs/README.md (source-of-truth, conflict-resolution, change-detection, status-legend), then
   this document in full, then every source in its section C at the linked anchors: master sections
   decomposition-clarifications (v1.2 items E2-E9, C10), step-08, fault-matrix, ablation,
   invariants, sample-sizes, go-no-go, test-failure-handling,
   must-have (M13), decision-gates (G4), demo-app, trust-boundaries (TB4), and the registers
   (section H) of steps S00-S07.
2. Inspect the current repository and the execution records (section I) of S00-S07. Resolve every
   contract you consume from the owning register entry and the artifact it references (verifier CLI
   D06-5, fault knobs D05-2, apply engine D02-3, outbox D03-5, compose chaos profile D00-3, metric
   registry D07-1, perf results D07-5), never from copies in documents.
3. Run the change-detection procedure (docs/README.md#change-detection) before touching code. Record
   revisions and hashes of the documents and directly consumed artifacts in section I.2.
4. Complete only this step's remaining authorized tasks, in dependency order:
   S08-T01 -> S08-T02 -> S08-T03 -> S08-T04 -> S08-T05. S08-C01 runs only when its trigger evidence
   exists. Do not implement future-step scope (S09 README results, audit study, release), deferred
   items, or non-goals.
5. Verify every task exactly as its "Verification and definition of done" field says. Record
   evidence paths in H.4 and I.1.
6. Record every decision (with rationale and alternatives), actual implementation and configuration
   paths, and produced artifacts in section H. Put trace comments next to authoritative config values.
7. Never invent results. Chaos and ablation results are "Not run" until the run JSON exists. Pilot
   runs are never evidence. Never mark blocked evidence as passed. Never hand-type a number into a
   results file; generate it from run JSON. Never drop or reclassify a run after seeing its result
   except under the classification rules pre-registered in D08-6.
8. Ablation seams live in code owned by S02, S03 and S05. Before changing that code, raise a change
   request per docs/README.md#conflict-resolution: update the owning register, write an impact
   assessment, and mark affected tasks "Needs review". The same applies if you need to change the
   verifier, quiesce detection, fault-knob schema, compose topology or any master target. Never
   weaken an invariant definition or an acceptance gate.
9. Re-run change detection at every phase boundary and before handoff. An A0 violation stops feature
   work and triggers S08-C01. Complete section J handoff conditions and the section G checklist,
   then report: tasks done, evidence, G4 result, open blockers, change requests raised.
```

<a id="required-reading"></a>
## C. Required reading and prerequisites

### C.1 Master sections

| Section | Why |
|---|---|
| [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) | v1.2 resolutions this step applies: E2 (injected-fault log), E3 (quiesce), E4 (run allocation), E5 (validity threshold), E6 (B0 workload), E7 (A3), E8 (A4), E9 (F3 hook), C10 (A2 seam exemption); C9 and C16 for module locations |
| [docs/zerosum_ledger_mvp_plan.md#step-08](zerosum_ledger_mvp_plan.md#step-08) | Step objective, task hours, exit criteria (G4), risk "real bugs discovered" |
| [docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix) | F1–F12 injection mechanisms and expected A0 behavior |
| [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation) | A0–A5 and B0 definitions, flag guard, comparison metrics, validity rule |
| [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants) | Invariant IDs I1–I12, I6b, reported metric R1, and the quiesce definition (extended in v1.2, §0.3 E3) |
| [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes) | Run counts per cell, trips per run, rule-of-three wording, machine-time estimate |
| [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go) | Hard gates 1, 2 and 4 that this step's evidence decides |
| [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling) | Flaky money tests, the A0-violation stop rule, blocked-evidence labeling |
| [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) | M13 acceptance criteria (a)–(c); M3, M4, M5, M8–M11 behaviors exercised by faults |
| [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) | G4 actions: proceed, narrow scope, change architecture, stop |
| [docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app) | Simulator responsibilities and distributions; scenario catalog; B0 as baseline |
| [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) | TB4 (fault and admin endpoints, chaos opt-in) and TB5 (read-only verifier role) |
| [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows) | Crash points and recovery paths that F1–F5 target |
| [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded) | Expected behavior while Kafka, PostgreSQL or providers are degraded |
| [docs/zerosum_ledger_mvp_plan.md#cold-warm](zerosum_ledger_mvp_plan.md#cold-warm) | Recovery time to zero lag as a recorded chaos metric |
| [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation) | Single-clock assumption; result-file metadata convention |
| [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology) | Container set, Toxiproxy under the chaos profile, shared PostgreSQL container |
| [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut) | The reduced fault set permitted if the minimum cut is in force |
| [docs/zerosum_ledger_mvp_plan.md#waiting-time](zerosum_ledger_mvp_plan.md#waiting-time) | Overnight execution; writing analysis while runs execute |
| [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) | R2, R5, R6, R7, R9 |
| [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist) | Correctness-evidence and chaos-guard items that consume this step's outputs |
| [docs/zerosum_ledger_mvp_plan.md#portfolio-value](zerosum_ledger_mvp_plan.md#portfolio-value) | Résumé placeholders that S09 fills only from these results |
| [docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates) | Cost is not a driver; unallocated contingency (remaining balance per [docs/zerosum_ledger_mvp_plan.md#changelog](zerosum_ledger_mvp_plan.md#changelog), v1.2) funds S08-C01 |

### C.2 Earlier step documents and their registers

| Upstream register | Decision IDs consumed |
|---|---|
| [docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs) | D00-1 pinned versions (including the Toxiproxy image), D00-2 module layout, D00-3 compose chaos profile and health checks, D00-4 read-only verifier role, D00-5 CI job split, D00-8 environment and secret conventions, D00-9 ADR process and results template, D00-10 test tags |
| [docs/step_01_domain_contracts.md#decisions-and-outputs](step_01_domain_contracts.md#decisions-and-outputs) | D01-1 Money limits, D01-4 FareSplitter remainder rule, D01-5 ZeroSumValidator, D01-10 seeded generators (in `libs/money` test fixtures, §0.3 C16) and seed reporting (ADR-0009) |
| [docs/step_02_ledger_core.md#decisions-and-outputs](step_02_ledger_core.md#decisions-and-outputs) | D02-3 apply engine entrypoint (A1 seam; A4 ledger re-check per §0.3 E8), D02-4 retry classification, D02-10 SP1 result and batch-apply decision, D02-11 concurrency stress harness (flags-off regression) |
| [docs/step_03_order_service_outbox.md#decisions-and-outputs](step_03_order_service_outbox.md#decisions-and-outputs) | D03-1 orders schema and deferred zero-sum trigger (A4 seam), D03-2 money-order API and error codes, D03-3 idempotency semantics, D03-4 auth module (`libs/auth`, §0.3 C9) and principal mapping, D03-5 `libs/outbox` design and relay-only publishing rule, with the A2 seam exemption (§0.3 C10), D03-6 event mapper |
| [docs/step_04_kafka_pipeline.md#decisions-and-outputs](step_04_kafka_pipeline.md#decisions-and-outputs) | D04-2 client configuration, D04-3 listener design, D04-4 error-handling policy (pause, DLQ, quarantine), D04-5 freshness endpoint, D04-6 pipeline e2e suite and crash-point seams, D04-7 SP2 decision |
| [docs/step_05_instruments_fake_providers.md#decisions-and-outputs](step_05_instruments_fake_providers.md#decisions-and-outputs) | D05-2 fake-providers API, fault-knob schema, ground truth and injected-fault log (§0.3 E2), D05-3 webhook verification, D05-4 instruments schema, D05-5 attempt state machines (A3 seam per §0.3 E7, F3 hook per §0.3 E9), D05-7 payout run, D05-8 sweeper and UNKNOWN schedule, D05-9 ADR-0010 quiet period, D05-11 kill switches, D05-12 scenario catalog and runner, D05-13 instrument API |
| [docs/step_06_reconciliation_verifier.md#decisions-and-outputs](step_06_reconciliation_verifier.md#decisions-and-outputs) | D06-1 settlement discrepancy knobs, D06-2 break types, D06-4 reconciliation API and scheduler (§0.3 E3), D06-5 verifier CLI (invariants, output format, quiesce detection) |
| [docs/step_07_observability_performance.md#decisions-and-outputs](step_07_observability_performance.md#decisions-and-outputs) | D07-1 metric registry, D07-2 dashboards, D07-3 alert rules, D07-5 perf results (max sustainable rate), D07-6 SP4 hot-entity mitigation (final apply-engine shape) |

### C.3 Artifacts that must already exist

These are the paths *planned* by upstream registers. **Resolve each actual path from the upstream register (section H.2/H.3 of the owning step), not from this list.**

| Planned artifact | Owner |
|---|---|
| `docker-compose.yml` with a `chaos` profile that adds Toxiproxy | D00-3 |
| `gradle/libs.versions.toml` and compose image tags | D00-1 |
| `infra/postgres/init.sql` with the read-only verifier role | D00-4 |
| `docs/results/TEMPLATE.md` | D00-9 |
| `.env.example` and the secret and redaction conventions | D00-8 |
| `libs/money` (`Money`, `FareSplitter`, `ZeroSumValidator`) and the seeded generator utilities | D01-1, D01-4, D01-5, D01-10 |
| `services/ledger-service` apply engine and its stress harness | D02-3, D02-11 |
| `services/order-service`, orders migrations and `libs/outbox` | D03-1, D03-2, D03-5 |
| Ledger listener, DLQ/quarantine handling, freshness endpoint, e2e crash-point tests | D04-3, D04-4, D04-5, D04-6 |
| `services/fake-providers` with fault knobs and ground truth; `services/instrument-service` | D05-2, D05-5, D05-11 |
| Scenario catalog and runner (`scenarios/`, planned) | D05-12 |
| `openapi/order-service.yaml`, `openapi/instrument-service.yaml` | D03-2, D05-13 |
| `tools/verifier` CLI | D06-5 |
| `infra/grafana/dashboards/`, `infra/alerts/`, `docs/results/perf-*.md` | D07-2, D07-3, D07-5 |
| ADR-0005, ADR-0009, ADR-0010 under `docs/adr/` | D07-6 (ADR-0005 as updated), D01-10, D05-9 |

### C.4 Blocking vs independent work

| Missing input | Blocks | Can proceed with independent preparation |
|---|---|---|
| Verifier CLI or its quiesce detection (D06-5) | S08-T04 verification, S08-T05 | S08-T01, S08-T02, S08-T03; the T04 orchestrator skeleton, results schema and aggregation script (verification stays Blocked) |
| Fault-knob schema, admin API or ground truth (D05-2) | F7, F8, F12 profiles in S08-T02; A3 seam test in S08-T03 | Container and Toxiproxy faults (F1, F2, F4, F5, F6, F9), F11 via the simulator, A1/A2/A4 seams |
| Settlement discrepancy knobs, reconciliation API and scheduler, or the injected-fault log (D06-1, D06-4, D05-2; §0.3 E2, E3) | F10 in S08-T02; quiesce in every S08-T04/S08-T05 run; I12 evidence in S08-T05 | All fault scripts and seams; orchestrator skeleton |
| Compose chaos profile with Toxiproxy routing (D00-3) | F6, F9 in S08-T02 | Raise the change request on D00-3; prepare proxy definitions; all non-Toxiproxy faults |
| Crash-point seam usable at runtime, not only in tests (D04-6) | Preferred F2 mechanism | F2 via seeded container kill (fallback, recorded) |
| Final apply-engine shape after SP4 (D07-6) or G3 not passed | A1 seam in S08-T03; S08-T05 | S08-T01, S08-T02; the A2–A5 seams |
| Measured sustainable rate (D07-5) | Final workload rate in S08-T01; S08-T05 | S08-T01–T04 with a provisional rate labeled provisional in H.2 |
| Owner acceptance of change requests (S02, S03, S05 registers) | Seam code in S08-T03 | S08-T01, S08-T02; the guard and its tests |
| Unattended machine time | Evidence runs in S08-T05 | Pilot runs, run-plan pre-registration (D08-6), analysis scripts |
| Scenario runner location (D05-12) | Choice of simulator module location in S08-T01 | Workload generation logic against the order API |

<a id="ownership-and-requirements"></a>
## D. Ownership and engineering requirements

### D.1 Decisions and contracts owned by this step

| Decision ID | What is decided | Master's proposed starting point |
|---|---|---|
| D08-1 | Simulator workload CLI contract: inputs (seed, profile, rate, injection toggles), client retry policy, idempotency-key derivation, F11 duplicate and same-key-different-body injection, the A4 fare-split bug-injection seam, and the workload manifest format | [docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app), F11 in [docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix), A4 in [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation) |
| D08-2 | Chaos scripts F1–F12: fault-definition format, seeded schedule generation, injection mechanisms (container, Toxiproxy, provider profile, in-process hook), clear rules, fault composition for F12, knob coverage table | [docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix), TB4 in [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) |
| D08-3 | Ablation flags A1–A5 and B0 composition, startup guard, seam placement in S02/S03/S05 code (owners updated via change procedure), A3 same-attempt resubmission with a fresh provider key (§0.3 E7), A4 scope covering application validation, DB trigger and ledger re-check (§0.3 E8), A2 seam placement inside `libs/outbox` (§0.3 C10) | [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation) |
| D08-4 | Run orchestration: single command, per-run phases, reset strategy, pre-run assertions, run classification mechanics including *not quiesced* (§0.3 E3), metric derivation, run JSON schema, Markdown results format and paths | M13 in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have), quiesce in [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants), [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation) |
| D08-5 | Chaos and ablation results; validity evaluation per ablation; confidence statements; G4 evaluation | [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation), [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes), [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go) |
| D08-6 | *(appended)* Pre-registered run plan, committed before the first evidence run: cell list with the run allocation of §0.3 E4, the crash-timing-dependent cells that use the weaker validity threshold (§0.3 E5), the maximum quiesce wait (§0.3 E3), B0 workload per §0.3 E6, run-classification rules, run ordering, A5 inclusion | [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) (E3–E6), [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes), validity rule in [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation) |

D08-6 is appended because master v1.2 (§0.3 E3–E6) names the S08 run plan as the owner of the run allocation, the crash-timing-dependent cells, the B0 workload and the maximum quiesce wait. Fixing them before results are visible is the honesty control.

### D.2 Inherited decisions

| Decision | Owner | How this step must use it |
|---|---|---|
| Fault matrix, ablation variants, validity rule, run counts, go/no-go thresholds | Master [docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix), [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation), [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes), [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go) | Read the values from the master at execution time. Changing any of them is a master change request, never a local edit. |
| Invariant definitions and quiesce definition | Master [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants); implementation D06-5 | Invoke the verifier as selected in D06-5. Never reimplement or relax an invariant in `tools/chaos`. Derived metrics may be computed from the same read-only sources. Quiesce includes the v1.2 conditions (§0.3 E3); I12 uses the injected-fault log (§0.3 E2). |
| Compose topology, chaos profile, memory limits | D00-3 | Target services by the names D00-3 selected. Routing connections through Toxiproxy is a D00-3 change if not already present. |
| Pinned versions | D00-1 | Record them in every run JSON from the version catalog and compose file; don't copy them into scripts. |
| Environment variable and secret conventions | D00-8 | Name flags and the chaos opt-in per D00-8. Read tokens from the environment; never write them to results. |
| Results template, ADR process | D00-9 | Every results Markdown file follows the template sections. |
| Test tags and CI split | D00-5, D00-10 | Guard and seam tests run in CI under the existing tags; chaos runs stay local. |
| FareSplitter, ZeroSumValidator, generators, seed reporting | D01-4, D01-5, D01-10 (ADR-0009) | The simulator reuses these unchanged. Bug injection wraps FareSplitter output inside the simulator; `libs/money` is not modified. |
| Apply engine, retry classification, batch mode | D02-3, D02-4, D02-10, as changed by D07-6 | The A1 seam targets the apply engine shape currently recorded, including batch or sharding choices. |
| Deferred zero-sum trigger, API error codes, idempotency semantics, auth | D03-1, D03-2, D03-3, D03-4 | The simulator interprets responses using D03-2/D03-3. The A4 seam disables the application check, the DB trigger and the ledger re-check (§0.3 E8), nothing else. |
| Outbox design and relay-only publishing rule | D03-5 (ADR-0008); D04-7 if SP2 changed it | The A2 seam lives inside `libs/outbox`, which §0.3 C10 exempts from the relay-only rule; application code stays covered. If Debezium replaced the relay, re-plan the A2 seam through a change request. |
| Listener, error policy, freshness, crash-point seams | D04-3, D04-4, D04-5, D04-6 | Use the freshness endpoint or lag metrics for quiesce support and recovery time. Prefer the D04-6 crash-point seam for F2. |
| Fault knobs, ground truth, magic tokens | D05-2 | Provider fault profiles conform to the selected knob schema; ground truth feeds duplicate-charge metrics; the injected-fault log (§0.3 E2) is the I12 reference. |
| Attempt state machines, uniqueness, sweeper schedule, quiet period | D05-4, D05-5, D05-8, D05-9 | The A3 seam (same attempt, fresh provider key; §0.3 E7) and the F3 hook (§0.3 E9) are added to D05-5 code by change request. Quiesce waits until no attempt is in SUBMITTING or UNKNOWN (§0.3 E3). |
| Kill switches | D05-11 | Assert collections and payouts are enabled before each run; use the switches in S08-C01 reproduction per the runbook in [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring). |
| Scenario catalog and runner | D05-12 | Extend the existing runner module if it is the natural home of the simulator; never fork a second generator. |
| Discrepancy knobs, reconciliation API | D06-1, D06-4 | F10 profile. Reconciliation runs come from the D06-4 scheduler during quiesce (§0.3 E3); the orchestrator only waits for them. |
| Metric registry, dashboards, alert rules | D07-1, D07-2, D07-3 | Metric names come from D07-1. Dashboards and alerts are observed during runs; alert firings in A0 are recorded. |
| Measured sustainable rate | D07-5 | The chaos workload rate must sit below the measured sustainable rate, or runs won't quiesce. |

### D.3 Engineering requirements

**Module boundaries** (actual layout per D00-2)

- **`tools/simulator` (planned).** A CLI module.
  - Depends on `libs/money`, including the generator fixtures that §0.3 C16 places there (D01-10). May use `libs/contracts` to self-check that generated bodies are schema-valid.
  - Talks only to the public APIs of order-service and instrument-service (TB1), using writer tokens from the environment.
  - Never reads any database.
- **`tools/chaos` (planned).** Holds the fault definitions, schedule generator and executor, Toxiproxy proxy definitions, provider fault profiles, run orchestrator, results schema and aggregation.
  - Talks to the Docker Compose CLI, the Toxiproxy admin API, the fake-providers admin API (admin token, TB4), the reconciliation API (D06-4), the freshness endpoint (D04-5), and the metrics backend's query API (D00-6/D07-1).
  - Talks to the database only through `tools/verifier` with the read-only role (TB5).
  - Keep it on the existing build toolchain (D00-10). Adding a new language runtime needs a recorded decision in D08-2/D08-4.
- **Seams in services** (code owned by S02, S03 and S05; changed through change requests):

  | Seam | Location |
  |---|---|
  | A1 | ledger-service apply engine |
  | A2 | `libs/outbox` as used by order-service (exempt from the relay-only rule, §0.3 C10) |
  | A4 | order-service validation path, orders migrations and the ledger-service zero-sum re-check (§0.3 E8) |
  | A3, A5, F3 crash hook | instrument-service |

  Each seam is a small branch on a flag read once at startup. There is no plugin framework and no per-request flag lookup.
- **Startup guard.** A single component reused by the three services. Place it in an existing shared module, such as `libs/auth` (§0.3 C9; D03-4). Create a new module only if the D00-2 layout forbids that placement.

**Interfaces**

The exact CLI flags and file schemas are recorded in D08-1, D08-2 and D08-4, not here.

- **Simulator.** Input is a seed, a workload profile file and injection toggles. Output is a workload manifest JSON and exit status.
- **Fault executor.** Input is a run seed, fault IDs and the compose project. Output is a planned schedule file and an executed action log.
- **Orchestrator.** Input is cells (variant, fault set), run count and base seed. Output is per-run JSON, aggregate JSON and Markdown under `docs/results/`.

**Data flow for one run**

1. Reset the stack.
2. Start it with the chaos profile and the variant's flags.
3. Pre-run assertions:
   - health checks pass;
   - the active ablation set matches the intended set, per service;
   - the zero-sum trigger is in its intended state;
   - kill switches are enabled;
   - fault knobs are at baseline;
   - no toxics are present;
   - stores start empty.
4. Start the simulator and the fault schedule concurrently.
5. At the end of generation, stop new injections and remove toxics.
6. Wait until every container is healthy.
7. Wait for quiesce as defined in v1.2 (§0.3 E3), using D06-5 detection; reconciliation runs come from the D06-4 scheduler. Waiting stops at the maximum wait recorded in D08-6, and such a run is *not quiesced*.
8. Run the verifier.
9. Collect metrics.
10. Write the run JSON atomically.
11. On violation, preserve state; otherwise reset.

**Seeds.** Every random choice derives from one base seed. The derivation is a pure function of (base seed, cell, run index, component name), so composing faults (F12) doesn't shift any component's sequence. Seeds are printed at start and on failure (ADR-0009).

**Lifecycle behavior**

- **Startup:** services refuse to start with any ablation flag outside the guard conditions. The orchestrator refuses to produce evidence from a dirty git tree, or from images not built from HEAD.
- **Shutdown:** Ctrl-C or orchestrator termination removes all toxics, resets fault profiles and records the in-progress run as a harness error.
- **Crash:** if the orchestrator dies, the next invocation detects the unfinished run (no valid JSON), classifies it as a harness error and reruns it with the same seed. Completed runs are skipped.
- **Retry:** only harness errors are rerun. Violations and quiesce failures are findings, never retried away.
- **Host sleep:** overnight runs on macOS must prevent sleep (for example with `caffeinate`). A wall-clock jump detected against a monotonic clock marks the run a harness error.

**Security and trust boundaries**

- **TB4.**
  - Fault knobs, the F3 hook and ablation flags exist only under the chaos profile and require the chaos opt-in variable.
  - The `demo-public` profile refuses them unconditionally.
  - The Toxiproxy admin API and fake-provider admin endpoints are reachable only on the local compose network or host loopback.
- **TB5.** The verifier uses the read-only role (D00-4).
- **Secrets.** Tokens are read from `.env` (D00-8). Results files and logs copied into `docs/results/` pass a redaction check before commit, per the guideline from [docs/step_00_foundations.md#s00-t09](step_00_foundations.md#s00-t09).
- **Target safety.** The executor refuses to run against anything other than the local compose project.

**Deployment constraints**

- Runs execute on the reference laptop only ([docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers)), within the memory budget in [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology).
- All containers share one host clock, which recovery-time measurement relies on ([docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation)).
- The topology is identical across every cell: under the chaos profile, connections pass through Toxiproxy even when no toxic is active. That keeps A0 and ablation cells comparable.

### D.4 Configuration ownership

| Authoritative value | Planned location | Traced to |
|---|---|---|
| Workload profiles (distributions, rates, injection fractions referencing the master rows) | `tools/simulator/profiles/*.yaml` | `# decision: D08-1 — docs/step_08_fault_injection_ablation.md#decisions-and-outputs` |
| Idempotency-key derivation and client retry policy | `tools/simulator` source, one constants class | D08-1 |
| Fault definitions F1–F12 and schedule parameters | `tools/chaos/faults/` | D08-2 |
| Toxiproxy proxy definitions | `tools/chaos/toxiproxy/` | D08-2; routing in compose traced to D00-3 |
| Provider fault and discrepancy profiles | `tools/chaos/profiles/` | D08-2; schema owners D05-2, D06-1 |
| Ablation flag defaults (off) | Each owning service's main configuration | D08-3 plus the owner's decision ID (D02-3, D03-5, D03-1/D03-2, D05-5) |
| Ablation flag bindings for the chaos profile | Each owning service's chaos-profile configuration | D08-3 |
| Run plan, including the maximum quiesce wait and the crash-timing-dependent cells (§0.3 E3, E5) | `tools/chaos/run-plan.yaml` | D08-6 |
| Results schema, reset strategy | `tools/chaos/` | D08-4; quiesce detection itself is D06-5 |
| Results | `docs/results/chaos-*.md`, `docs/results/ablation-*.md`, raw run JSON under `docs/results/` (layout per D08-4) | D08-4, D08-5 |

**Permitted alternatives this step must handle**

- **SP2/Debezium (S04-C01, D04-7 → D03-5).** If the relay is replaced, F1/F4 expectations stay the same, but the A2 seam's location must be re-planned through a change request.
- **G2 alternative (S05-C01, D03-6).** If instrument-service writes orders directly, the A4 seam must also disable the shared validation used on that path, and I8 evidence reads the changed mapping.
- **SP4 options (S07-T06, D07-6 → D02-3/D02-4).** The A1 seam must double-apply in the selected batch or sharded mode. I2 aggregates sub-entities as D02-8/D06-5 define.
- **G1 alternative (S02-C01).** If account-level optimistic locking was adopted, the A1 seam targets that apply path.
- **SP3 fallback (D00-7).** Recovery-time metrics come from whichever metrics path was selected.
- **T1 missed (D07-5).** The chaos rate is chosen below the measured ceiling, never at the T1 target.
- **Minimum cut ([docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut)).** If in force, only the fault subset named there runs, and the other A0 cells are "Not run" with that reason.
- **A5 optional.** If A5 isn't run, it is recorded "Not run" with the reason. It is never omitted silently.

**Alternatives downstream (S09) must handle:**
- G4 narrow-scope outcome: a `v0.9` release with affected claims removed.
- An ablation judged invalid: no correctness claim for that protection.
- Minimum-cut fault subset.
- A5 not run.

<a id="phases-and-tasks"></a>
## E. Phases and tasks

| Phase | Tasks | Hours |
|---|---|---|
| Phase 1 — Workloads | S08-T01 | 3 |
| Phase 2 — Fault injection | S08-T02 | 4 |
| Phase 3 — Ablations | S08-T03 | 3 |
| Phase 4 — Runner and evidence | S08-T04, S08-T05 | 6 |
| **Total** | | **16** |

<a id="phase-1"></a>
### Phase 1 — Workloads

**Objective:** a seeded, reproducible simulator that generates realistic trip traffic through the public APIs, including the client-side faults (F11) and the bug injection A4 depends on.
**Exit checkpoint:** the simulator's determinism and injection tests pass. A short A0 smoke run shows one order per idempotency key and bug-injected orders rejected. Change detection has been re-run and I.2 is updated.

<a id="s08-t01"></a>
#### S08-T01 — Simulator workloads: seeded trips, duplicates, same-key-different-body, fare-split bug injection
- **Outcome:** `tools/simulator` generates seeded workloads of riders, drivers, trips, adjustments and payout runs through the public APIs. It can inject F11 client duplicates and A4 fare-split bugs, and it writes a workload manifest that later analysis compares against the stores.
- **Estimate:** 3 h
- **Inputs:**
  - Distributions and responsibilities: [docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app).
  - F11 row in [docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix); A4 row in [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation).
  - Explicit-entries rule in [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract); trips-per-run sizing in [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes).
  - D01-4 FareSplitter and D01-10 generators ([docs/step_01_domain_contracts.md#s01-t02](step_01_domain_contracts.md#s01-t02), [docs/step_01_domain_contracts.md#s01-t06](step_01_domain_contracts.md#s01-t06)); D01-5 validator; D01-1 Money limits.
  - D03-2 API and error codes, D03-3 idempotency semantics, D03-4 writer principals.
  - D05-13 instrument registration and payout-run API; D05-2 magic tokens; D05-7 payout freshness refusal.
  - D05-12 scenario runner ([docs/step_05_instruments_fake_providers.md#s05-t13](step_05_instruments_fake_providers.md#s05-t13)); D07-5 measured sustainable rate ([docs/step_07_observability_performance.md#s07-t05](step_07_observability_performance.md#s07-t05)).
  - Artifacts: `libs/money` with its generator fixtures (§0.3 C16), `openapi/order-service.yaml`, `openapi/instrument-service.yaml`.
- **Depends on:** none (upstream: S01-T02, S01-T06, S05-T13, S07-T05)
- **Instructions:**
  1. **Choose the location.** Read D05-12. If the S05 scenario runner already lives in `tools/simulator`, extend that module. Otherwise, create `tools/simulator` per the D00-2 layout and have the runner reuse the same generation code. Record the choice in H.2. Never maintain two generators.
  2. **Record the CLI contract in D08-1.**
     - Inputs: base seed, workload profile path, trip count, offered rate, target base URLs, and token environment-variable names (D00-8).
     - Injection toggles: duplicate resend, same-key-different-body, fare-split bug.
     - Output directory.
     - Exit status: success, or a harness failure such as a configuration or auth error.
  3. **Seed the entity population.** Create riders and drivers from the seed. Register fake instruments through the D05-13 API, using a seeded mix of the D05-2 magic tokens so that declines and returns occur at the profile's rates.
  4. **Generate trip lifecycles.**
     - For each trip, build the COMMERCE order from FareSplitter output as explicit entries.
     - With the profile's adjustment probability, send an adjustment order that references the trip's first order in the same group.
     - Start payout runs on a seeded cadence, each with its own idempotency key.
     - Put every distribution parameter in the profile file with a trace comment that references the master row it came from; never hard-code it.
  5. **Derive idempotency keys** deterministically from (seed, trip index, event kind). Running the same seed twice produces identical keys and bodies.
  6. **Client retry policy.**
     - Retry with the *same key and same body* on transport errors, 5xx responses and the in-progress response defined in D03-2, using bounded, seeded-jitter backoff.
     - Never retry definitive responses: created, replayed, validation failure, key reused, not zero-sum.
     - When the retry budget is exhausted, record the key as "client gave up".
     - Never mint a new key for a retry.
  7. **F11 injection.**
     - After a definitive response, resend a seeded fraction of requests with the same key and body.
     - Send a seeded fraction with the same key and a *different but valid, zero-sum* body.
     - Both fractions come from the F11 row via the profile.
  8. **A4 bug injection (seam owned by S08).**
     - For a seeded fraction of COMMERCE orders, taken from the A4 row via the profile, perturb one entry of the FareSplitter output by ±1 minor unit. The body stays schema-valid.
     - Mark each such key in the manifest.
     - `libs/money` and FareSplitter stay unchanged.
     - B0 runs enable this injection too (§0.3 E6).
  9. **Pace arrivals on an open-model schedule** fixed from the seed, so that a slow system can't silently lower the offered load. Record offered vs achieved rate.
  10. **Write the workload manifest** atomically. It contains:
      - the seed, profile hash and git SHA;
      - counts by request class (normal, duplicate resend, different body, bug-injected, adjustment, payout run);
      - counts by response class;
      - the key list per class, in a sidecar file referenced by hash.
  11. Print the seed at start and on any failure (ADR-0009).
- **Edge cases and failure behavior:**
  - **A different-body key's first request never committed** (it failed with 5xx and the client gave up): the later different-body request legitimately creates the order. The manifest records "different-body first commit". Analysis asserts *one order per key*, never "every different-body request was rejected".
  - **A bug-injected order in A0** receives the not-zero-sum rejection (D03-2). It counts as an expected rejection and is never retried with a corrected body.
  - **A retry of a bug-injected request** after a 5xx keeps the same unbalanced body.
  - **An adjustment for a trip whose first order never committed** is skipped and counted.
  - **A payout run refused as stale** (D05-7) is recorded as an expected refusal and retried per the D05-13 key semantics.
  - **401/403 responses or a missing token** abort the run immediately as a harness error; nothing is written as evidence.
  - **A generated amount outside D01-1 limits** is a simulator bug and fails fast with the seed printed.
  - **Achieved rate below offered rate** beyond the tolerance in the profile: the run is flagged in the manifest for the orchestrator to classify.
- **Outputs:** planned — `tools/simulator/` module; `tools/simulator/profiles/*.yaml`; the manifest schema file in `tools/simulator/`; unit tests in the module; the D08-1 register entry.
- **Verification and definition of done:**
  - **Determinism test** (unit tag per D00-10): the same seed and profile produce an identical request-sequence digest twice; a different seed produces a different digest.
  - **Split-integrity test:**
    - With bug injection off, every generated COMMERCE body passes ZeroSumValidator (D01-5).
    - With it on, exactly the manifest-marked bodies fail and all others pass.
  - **Retry test** against a stubbed HTTP server returning 503 then 201: one key, one body, no new key minted.
  - **A0 smoke run** on the local stack, labeled pilot and not evidence:
    - the verifier (D06-5) reports no violations;
    - the order store holds exactly one order per submitted key;
    - no bug-injected key exists in the order store;
    - response counts in the manifest reconcile with request counts.
  - The D08-1 entry is recorded in H.1 and the paths in H.2.

<a id="phase-2"></a>
### Phase 2 — Fault injection

**Objective:** every fault F1–F12 can be injected on a seeded, recorded schedule and cleared cleanly, with evidence that it actually took effect.
**Exit checkpoint:** schedule determinism test passes. Each fault's smoke run shows actions applied, an observable effect and a clean clear. The knob coverage table is complete. Change requests for Toxiproxy routing and the F3 hook are recorded. Change detection has been re-run.

<a id="s08-t02"></a>
#### S08-T02 — Chaos scripts F1–F12 with seeded schedules
- **Outcome:** `tools/chaos` holds one definition per fault F1–F12, plus a seeded schedule generator and an executor. Each run produces a planned schedule file and an executed action log. Container, network, provider-profile and in-process faults are all supported, and F12 is composed from its parts.
- **Estimate:** 4 h
- **Inputs:**
  - Faults: [docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix).
  - Crash points: [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows).
  - Expected degraded behavior: [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded).
  - Timeouts: [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting).
  - TB4: [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries); [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology).
  - D00-3 chaos profile ([docs/step_00_foundations.md#s00-t03](step_00_foundations.md#s00-t03)); D00-1 Toxiproxy tag; D00-8.
  - D04-6 crash-point seams and D04-4 pause behavior ([docs/step_04_kafka_pipeline.md#s04-t05](step_04_kafka_pipeline.md#s04-t05), [docs/step_04_kafka_pipeline.md#s04-t03](step_04_kafka_pipeline.md#s04-t03)).
  - D05-2 fault knobs, admin API and ground truth ([docs/step_05_instruments_fake_providers.md#s05-t03](step_05_instruments_fake_providers.md#s05-t03)); D05-5 transition flow; D05-8 schedule.
  - D06-1 discrepancy knobs ([docs/step_06_reconciliation_verifier.md#s06-t01](step_06_reconciliation_verifier.md#s06-t01)); D07-1 metric names.
  - Artifacts: `docker-compose.yml`, fake-providers admin API, S08-T01 simulator.
- **Depends on:** S08-T01
- **Instructions:**
  1. **Record the fault-definition format in D08-2.** Each definition covers:
     - fault ID;
     - targets, as compose service names resolved from D00-3 (never hard-coded elsewhere);
     - mechanism;
     - schedule parameters, each referencing its master row;
     - the clear action and when it runs;
     - the invariant IDs the master expects to hold;
     - the observable effect used to prove injection.
  2. **Schedule generation.** Before the generation phase, derive a schedule file (a timed action list) from the run seed and fault ID. The executor applies it and appends actual timestamps and results to an action log. Both files are run evidence. The same seed must produce a byte-identical schedule file.
  3. **Container faults.**
     - F1: seeded kill intervals for order-service with seeded downtime, then restart.
     - F4: broker restart.
     - F5: PostgreSQL restart. All four databases share one container ([docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology)), so every service is affected; say so in the results.
     - Choose one restart approach and record it: either the compose restart policy is disabled under chaos and the executor restarts containers, or the policy restarts them. Mixing the two makes downtime unmeasurable.
  4. **F2 (ledger crash mid-batch).**
     - Prefer the D04-6 crash-point seam, which halts between the ledger's database commit and the offset acknowledgement with a seeded probability under the chaos profile. That window is the one that exercises redelivery dedupe.
     - If D04-6 exists only in tests, raise a change request on D04-6 to make it available at runtime under the guard. Until then, use seeded container kills during apply and record the fallback in D08-2.
  5. **F3 hook.**
     - Per §0.3 E9, S08-T02 owns F3's breakpoint probability hook. Add it to instrument-service via a change request on D05-5: halt the JVM between receiving the provider response and persisting the attempt transition, with a seeded probability.
     - Gate it exactly like the ablation flags: chaos profile plus opt-in variable. S08-T03's guard tests cover it.
     - The executor restarts the container after each halt.
  6. **Toxiproxy (F6, F9).**
     - Define proxies for instrument-service → fake-providers (F6, adapter direction) and for each service → PostgreSQL (F9).
     - Under the chaos profile, route those connections through the proxies for *every* chaos run, so the topology is constant. If D00-3 doesn't do this, raise a change request on D00-3.
     - Toxic parameters reference the master rows.
     - Apply toxics for their seeded windows. At the end of generation, remove all toxics and confirm through the Toxiproxy API that none remain.
     - `tools/verifier` connects directly, never through a proxy.
  7. **Provider profiles (F7, F8, F10).**
     - Write profile payloads conforming to the D05-2 knob schema and the D06-1 discrepancy knobs, with the seed field set from the run seed.
     - Apply them through the admin API with the admin token.
     - Read the effective configuration back and store it in the action log. A readback mismatch aborts the run.
     - After quiesce, collect the fake-providers injected-fault log (D05-2, §0.3 E2) into the run evidence; the verifier checks I12 against it.
  8. **Per-fault clear rules.** "Clear" means stop injecting *new* faults.
     - Knobs whose effects legitimately continue into quiesce stay set until the end of quiesce: webhook redelivery for F8, and the settlement discrepancy knobs for F10 until reports for the run's settlement cycles are produced. Quiesce itself waits for pending redeliveries (§0.3 E3).
     - Document each fault's rule in its definition.
  9. **F11** is not an infrastructure fault. Its definition only switches the simulator toggles from S08-T01.
  10. **F12 composition.** Run F1, F3, F7 and F8 concurrently. Each sub-schedule derives from (run seed, fault ID), so composed and individual schedules for the same seed are identical per component.
  11. **Knob coverage table.** List every knob in the selected D05-2 schema and D06-1 discrepancy set, and map each to at least one fault ID. This is the R5 measurement in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register). Unexercised knobs are listed as limitations in H.5.
  12. **Target safety.** The executor refuses to act unless the compose project is the local project with the chaos profile active and the opt-in variable set. Tokens come from the environment and are never echoed.
- **Edge cases and failure behavior:**
  - **Toxiproxy or the admin API is unreachable** when a fault needs it: abort the run as a harness error. A fault silently not applied would falsify the evidence.
  - **A container doesn't become healthy after restart** within the D00-3 health-check budget: the run becomes a harness error with logs captured, unless the failure is caused by the product. For example, a migration interrupted by a kill that can't recover is a finding, recorded for S08-C01.
  - **Kafka restart outlasts producer and relay timeouts:** the expected behavior is backoff and backlog drain ([docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded)). It is not a harness error.
  - **The ledger listener pauses** after transient-error retries (D04-4): if D04-4 requires an operator resume, the executor performs exactly that documented action and logs it as an intervention. Otherwise a paused listener that never resumes makes the run `NOT_QUIESCED` at the D08-6 maximum wait.
  - **Scheduled actions beyond the generation phase** are dropped and logged as truncated.
  - **The admin API rejects a profile payload** because the schema changed: abort and run change detection on D05-2.
  - **Docker Desktop on macOS:** kill timing is coarse. Record requested vs executed timestamps; don't assume they're equal.
- **Outputs:** planned — `tools/chaos/faults/` (F01–F12 definitions); schedule generator and executor under `tools/chaos/`; `tools/chaos/toxiproxy/`; `tools/chaos/profiles/`; knob coverage table in `tools/chaos/`; F3 hook in instrument-service (via change request); change-request records in I.2; the D08-2 register entry.
- **Verification and definition of done:**
  - **Schedule determinism test** (unit tag): same seed produces a byte-identical schedule file for each fault and for F12's components.
  - **Per-fault smoke run** (A0, pilot, short): for each F-ID, the action log shows every action applied and cleared, and the observable effect is present:

    | Fault | Observable effect |
    |---|---|
    | F1, F2, F4, F5 | Container restart counts and timestamps |
    | F3 | Hook halts in the container exit log |
    | F6 | Toxics listed by the Toxiproxy API during the window; adapter timeout outcomes in the D07-1 provider-call metrics |
    | F7 | Knob readback matches the profile; ground truth shows withheld responses |
    | F8 | Duplicate and reordered webhooks recorded by the receiver |
    | F9 | Raised database latency in the D07-1 metrics |
    | F10 | Injected discrepancies present in the settlement report and in the injected-fault log (§0.3 E2) |
    | F11 | Manifest injection counts |

  - **After clear:** zero toxics, all containers healthy, knobs per the clear rules.
  - **Refusal test:** the executor exits non-zero without the chaos profile or the opt-in variable.
  - **Knob coverage table:** no unmapped knob, or each gap is listed in H.5.
  - The verifier reports no violations on each A0 smoke run. Any violation triggers S08-C01 before Phase 3.

<a id="phase-3"></a>
### Phase 3 — Ablations

**Objective:** each protection named in the ablation table can be disabled on its own, only under guarded conditions, without changing flags-off behavior.
**Exit checkpoint:**
- Change requests are accepted in the S02, S03 and S05 registers.
- Guard tests and per-seam tests pass in CI.
- The owners' flags-off suites are green.
- The A0 startup log shows an empty ablation set.
- Change detection has been re-run.

<a id="s08-t03"></a>
#### S08-T03 — Ablation flags A1–A5, B0 and startup guard in owning services
- **Outcome:** chaos-profile-only flags disable exactly one protection each, and B0 sets A1–A4 together. A shared startup guard refuses unsafe combinations and logs the active set. The seams are recorded in the owners' registers through change requests.
- **Estimate:** 3 h
- **Inputs:**
  - Ablation definitions and flag guard: [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation).
  - TB4: [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries).
  - Chaos-guard item in [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist).
  - Protected behaviors: [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm), [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract), [docs/zerosum_ledger_mvp_plan.md#state-machines](zerosum_ledger_mvp_plan.md#state-machines), [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting).
  - Change procedure: [docs/README.md#conflict-resolution](README.md#conflict-resolution).
  - A1: D02-3/D02-10 as changed by D07-6 ([docs/step_02_ledger_core.md#s02-t02](step_02_ledger_core.md#s02-t02), [docs/step_07_observability_performance.md#s07-t06](step_07_observability_performance.md#s07-t06)).
  - A2: D03-5 ([docs/step_03_order_service_outbox.md#s03-t05](step_03_order_service_outbox.md#s03-t05)).
  - A4: D03-1, D03-2 ([docs/step_03_order_service_outbox.md#s03-t02](step_03_order_service_outbox.md#s03-t02), [docs/step_03_order_service_outbox.md#s03-t03](step_03_order_service_outbox.md#s03-t03)).
  - A3: D05-4, D05-5 ([docs/step_05_instruments_fake_providers.md#s05-t12](step_05_instruments_fake_providers.md#s05-t12)).
  - A5: D05-3/D05-4 webhook receiver ([docs/step_05_instruments_fake_providers.md#s05-t11](step_05_instruments_fake_providers.md#s05-t11)).
  - D02-11 stress harness ([docs/step_02_ledger_core.md#s02-t03](step_02_ledger_core.md#s02-t03)); D04-6 e2e suite; D00-8; D00-10.
- **Depends on:** S08-T01, S08-T02
- **Instructions:**
  1. **Raise change requests before any seam code.** Record each in I.2 with the need, the affected tasks and an impact assessment, and update the owners' registers.
     - S02: A1 and the A4 ledger re-check in D02-3 (§0.3 E8).
     - S03: A2 in D03-5; A4 in D03-1 and D03-2.
     - S05: A3 in D05-5; A5 in the webhook receiver; the F3 hook from S08-T02 (§0.3 E9).
     - The impact statement for every seam is "flags-off behavior unchanged; owner suites re-run".
  2. **Flag model (D08-3).**
     - One boolean per ablation, default off, bound only in the chaos-profile configuration and named per D00-8.
     - B0 is a configuration that sets A1–A4 together; there is no B0 code path.
  3. **Startup guard.** For any service with an ablation flag or the F3 hook enabled:
     - refuse to start if the chaos profile isn't active;
     - refuse if the opt-in variable isn't set;
     - refuse unconditionally under `demo-public`;
     - otherwise, log one line listing the active ablations and expose the same set read-only, as a startup log line or an actuator info entry recorded in D08-3, so the orchestrator can assert it.
  4. **A1 seam (ledger consumer dedupe).**
     - Apply every structurally valid order in the batch whether or not it was applied before. Recording applied orders must not abort the batch on a conflict.
     - Locking, changelog and hash-chain behavior stay unchanged.
     - The seam must work in the apply mode currently selected (batch size, sharding) per D02-10/D07-6.
  5. **A2 seam (outbox → dual write).**
     - With the flag on, order-service writes no outbox row. After the database commit it sends the message directly.
     - A failed or interrupted send isn't persisted or retried by application code. Producer configuration stays as D04-2 selected.
     - Implement the direct send *inside* `libs/outbox`. §0.3 C10 exempts that seam from the relay-only publishing rule in D03-5 (ArchUnit, M4(b)). Keep the exemption scoped to the seam so the rule still covers all application code; never widen it.
     - If an outbox row were still written, the relay would republish and mask the failure.
  6. **A3 seam (UNKNOWN handling).**
     - On an unknown submit result, resubmit immediately with a *fresh* provider idempotency key instead of entering UNKNOWN resolution. The sweeper must not later reconcile the duplicate.
     - Per §0.3 E7, the resubmission reuses the same attempt record; no new attempt row is created, so the D05-4 uniqueness constraint is untouched.
     - The predicted failure class is I7 ([docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation)).
  7. **A4 seam (zero-sum validation).**
     - Disable the application-level zero-sum rule in order-service.
     - Disable the deferred zero-sum trigger on both order tables (D03-1; the header attachment comes from §0.3 C3), through a chaos-only mechanism executed with the owner role. Append-only triggers stay enabled.
     - Log the trigger's state at startup.
     - Also disable the ledger's defense-in-depth zero-sum re-check (D02-3), as §0.3 E8 requires; otherwise unbalanced orders would be quarantined instead of producing the predicted I1/I2 violations. Include it in the S02 change request.
  8. **A5 seam (webhook event dedupe).** Skip provider-event dedupe in the receiver. Order-level idempotency (D03-3) stays intact.
  9. **Per-seam integration tests**, tagged per D00-10. With the flag on, each demonstrates its disabled protection:

     | Seam | Demonstration |
     |---|---|
     | A1 | One order delivered twice doubles the affected balances |
     | A2 | A halt between commit and send leaves a committed, unpublished order |
     | A3 | A provider timeout yields two provider-side charges in ground truth |
     | A4 | An unbalanced order persists in the order store |
     | A5 | A duplicate webhook is recorded twice, and still only one money order exists |

  10. **Flags-off regression.** Run the owners' suites unchanged: D02-11 stress harness, S03 idempotency and immutability tests, D04-6 e2e, S05 transition-table and contract tests, and ArchUnit.
  11. **Guard tests:**
      - no flag → starts;
      - flag without chaos profile → refuses;
      - flag with chaos profile but no opt-in → refuses;
      - flag with chaos profile and opt-in → starts and logs the set;
      - any flag under `demo-public` → refuses.
- **Edge cases and failure behavior:**
  - **Trigger state persists in the database across restarts.** A later A0 run on the same volume would silently run without the zero-sum trigger. At startup, order-service must verify that the trigger state matches its flags and refuse to start on a mismatch. The orchestrator also asserts it.
  - **The variant is configured on only some services:** the guard can't see that. The orchestrator's per-service assertion (S08-T04) catches it.
  - **An ablation flag leaks into `.env`:** outside chaos the guard refuses; in A0 the orchestrator asserts an empty set.
  - **An owner changes the seamed code later** (for example an SP4 follow-up): change detection marks S08-T03 "Needs review", and the seam tests re-run.
  - **The time budget is exceeded:** A5 is optional in the master, so it is dropped first. It is recorded "Not run" with the reason, never omitted.
- **Outputs:** planned — seam branches in ledger-service, `libs/outbox`, order-service and its migrations, and instrument-service (actual paths recorded by owners); startup guard in the shared module chosen in D08-3; chaos-profile flag bindings; seam and guard tests; change-request records; the D08-3 register entry.
- **Verification and definition of done:**
  - All guard tests and per-seam tests pass in CI.
  - The owners' flags-off suites and ArchUnit are green on the same commit.
  - A0 startup logs show an empty ablation set and an enabled trigger.
  - Each change request shows as accepted in the owning register, with the date and impact assessment.
  - H.4 rows are filled for the guard and seam tests.

<a id="phase-4"></a>
### Phase 4 — Runner and evidence

**Objective:** one command produces independent, fully attributed runs, and the executed matrix yields results, a validity evaluation per ablation and a G4 decision.
**Exit checkpoint:**
- The pilot batch validates against the results schema.
- D08-6 is committed before the first evidence run.
- Results files are generated from run JSON.
- G4 is evaluated in H.6.
- Change detection has been re-run before handoff.

<a id="s08-t04"></a>
#### S08-T04 — Run orchestration and results format
- **Outcome:** a single command runs named cells for N seeded runs. Each run goes through reset, assertions, load and faults, clear, quiesce, verifier and metrics. The command writes per-run JSON, aggregate JSON and Markdown to `docs/results/`, satisfying M13(a) and M13(c).
- **Estimate:** 3 h
- **Inputs:**
  - M13 in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have).
  - Quiesce definition (v1.2, §0.3 E3) and I12's injected-fault log (§0.3 E2) in [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants).
  - Comparison metrics in [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation); run sizing in [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes).
  - Recovery time: [docs/zerosum_ledger_mvp_plan.md#cold-warm](zerosum_ledger_mvp_plan.md#cold-warm). Result metadata: [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation).
  - [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling).
  - D06-5 verifier and quiesce detection ([docs/step_06_reconciliation_verifier.md#s06-t04](step_06_reconciliation_verifier.md#s06-t04)); D06-4 reconciliation API.
  - D04-5 freshness; D07-1 metrics ([docs/step_07_observability_performance.md#s07-t01](step_07_observability_performance.md#s07-t01)).
  - D00-9 results template ([docs/step_00_foundations.md#s00-t06](step_00_foundations.md#s00-t06)); D00-1; D01-10.
  - D05-11 kill switches ([docs/step_05_instruments_fake_providers.md#s05-t10](step_05_instruments_fake_providers.md#s05-t10)); D05-2 ground truth.
  - S08-T01, S08-T02 and S08-T03 outputs.
- **Depends on:** S08-T01, S08-T02, S08-T03
- **Instructions:**
  1. **Command contract (D08-4).**
     - Inputs: cell list (variant plus fault set), run count, base seed, output location, and a `--pilot` mode whose outputs are labeled non-evidence.
     - The run matrix itself comes from the D08-6 run plan, never from ad-hoc flags in evidence mode.
  2. **Pre-flight (evidence mode).**
     - Refuse on a dirty git tree.
     - Build or verify images from HEAD and record their digests.
     - Record hardware, OS, Docker/Compose versions, pinned versions (D00-1), SHA, and the run-plan hash.
  3. **Reset strategy.** Choose and record one in D08-4, with the measured per-run wall time:
     - (a) full volume reset; or
     - (b) targeted reset of the four databases, Kafka topics and consumer offsets, and fake-provider state, keeping the observability container.
     - Either way, the pre-run assertions must prove independence: empty order store and ledger, no consumer offsets, empty ground truth.
  4. **Pre-run assertions.** Any failure before load means a harness error.
     - All health checks pass.
     - The per-service active ablation set equals the intended set.
     - Zero-sum trigger state is as intended.
     - Kill switches are enabled; with money movement frozen, ablations would look invalid.
     - Knobs are at baseline and no toxics are present.
  5. **Execute the phases.** Start the simulator and schedule, then clear. Then wait for quiesce using D06-5 detection; reconciliation runs come from the D06-4 scheduler (§0.3 E3), and waiting stops at the maximum wait pre-registered in D08-6. Then run the verifier with the read-only role, then collect metrics.
  6. **Classify each run** using the rules pre-registered in D08-6:
     - `PASS`;
     - `VIOLATION` (with invariant IDs);
     - `NOT_QUIESCED` (the master's *not quiesced* class, §0.3 E3: never a pass, never silently rerun);
     - `HARNESS_ERROR`, limited to the enumerated pre-load or infrastructure causes, and rerun with the same seed.
  7. **Derive metrics.**

     | Metric | Source |
     |---|---|
     | Violations by invariant ID | D06-5 output |
     | Duplicate charges and payouts (count, total minor units) | Ground truth vs attempts |
     | Missing orders | I6 set difference |
     | Unexplained drift | Sum of absolute I6b differences |
     | Recovery time to zero lag | From the last fault clear to zero lag, via D04-5 or D07-1 on the single host clock |
     | Stuck attempts | I10 counts; for `NOT_QUIESCED` runs, attempts still non-terminal at the maximum wait |
     | R1 count | Reported only, never a violation |

     If the D06-5 output lacks a needed detail, compute it in `tools/chaos` from the same read-only sources. Never alter invariant logic.
  8. **Run JSON contents:**
     - run ID, cell, index, base and derived seeds;
     - SHA, image digests, versions, hardware;
     - per-service active flags;
     - planned schedule and executed action log;
     - knob readbacks and the workload manifest;
     - phase timings, verifier output, derived metrics;
     - classification and log paths.

     Validate the JSON against `tools/chaos/results.schema.json` before writing it atomically.
  9. **Preserve state on violation.** For `VIOLATION` and `NOT_QUIESCED`, skip reset and dump the databases and logs to a preserved location recorded in the run JSON before the next run. S08-C01 needs them.
  10. **Aggregation.** A script regenerates the aggregate JSON and Markdown from run JSON alone, following the D00-9 template sections. Per cell it shows:
      - classification counts and violations by invariant;
      - metric medians and min–max ranges;
      - for ablations, the fraction of runs showing the predicted class.

      Result paths follow `docs/results/chaos-*.md` and `docs/results/ablation-*.md`, with names recorded in D08-4.
  11. **Resumability and cleanup.** Skip runs that already have valid JSON; treat an unfinished run as `HARNESS_ERROR`. On interrupt, remove toxics and reset profiles. Prevent host sleep and detect clock jumps.
- **Edge cases and failure behavior:**
  - **The verifier exits non-zero:** distinguish "violations found" from "verifier crashed" using the D06-5 exit contract. A crash is a harness error, and only after the output is preserved.
  - **Maximum quiesce wait exceeded:** `NOT_QUIESCED` (§0.3 E3). For A0 it triggers S08-C01 investigation. Raising the D08-6 maximum after seeing results needs an amendment with rationale, applied to all cells and their reruns.
  - **Attempts in SUBMITTING or UNKNOWN, or pending webhook redeliveries, remain at the maximum wait:** quiesce requires neither since v1.2 (§0.3 E3), so the run is `NOT_QUIESCED`; record the counts in the run JSON.
  - **Metrics backend retention loses data** during long batches: collect each run's metrics immediately after the run.
  - **Disk pressure from logs and dumps:** cap copied logs per run. Keep preserved dumps outside git and reference them by path and hash.
  - **Results contain a token:** the redaction check blocks the write.
- **Outputs:** planned — orchestrator and aggregation script in `tools/chaos/`; `tools/chaos/results.schema.json`; raw run JSON layout under `docs/results/`; pilot outputs labeled pilot; the D08-4 register entry.
- **Verification and definition of done:**
  - **Pilot batch** (`--pilot`): A0 × F1 and A1 × F2/F4, a few runs each at reduced size.
    - Every run JSON validates.
    - The Markdown is generated with the template sections and M13(c) metadata.
    - The A1 pilot shows the predicted class, or the absence is recorded as an early validity warning.
  - **Resumability test:** kill the orchestrator mid-run; the next invocation marks that run `HARNESS_ERROR`, reruns it with the same seed and completes the batch.
  - **Independence test:** the pre-run assertions fail when the reset is deliberately skipped.
  - **Reproducible aggregation:** regenerating the Markdown from the same JSON gives an empty diff.
  - The single command is recorded in D08-4 and H.2.

<a id="s08-t05"></a>
#### S08-T05 — Execute runs, analysis, validity evaluation and write-up
- **Outcome:** the pre-registered matrix is executed on unattended machine time, and results files report every cell honestly. Each ablation has a validity verdict, confidence statements follow the master's wording, and G4 is evaluated with evidence paths.
- **Estimate:** 3 h
- **Inputs:**
  - Run counts and confidence wording: [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes).
  - Validity rule and A5 exception: [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation).
  - Hard gates: [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go). G4: [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates).
  - [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling); correctness evidence in [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist).
  - [docs/zerosum_ledger_mvp_plan.md#waiting-time](zerosum_ledger_mvp_plan.md#waiting-time); [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut).
  - D07-2 dashboards and D07-3 alerts ([docs/step_07_observability_performance.md#s07-t02](step_07_observability_performance.md#s07-t02)); D07-5 rate; S08-T04 orchestrator.
- **Depends on:** S08-T04
- **Instructions:**
  1. **Pre-register D08-6 and commit it before the first evidence run.**
     - The cell list follows the fault matrix, ablation table and run counts.
     - Allocate runs per §0.3 E4 and [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes): A0 per fault; each ablation's runs split evenly across its targeted faults, one cell per (ablation, fault).
     - B0 runs F12 plus A4's bug injection (§0.3 E6).
     - The default validity threshold applies everywhere; name the crash-timing-dependent cells that use the weaker threshold (§0.3 E5), each with a timing-based rationale.
     - Record the maximum quiesce wait (§0.3 E3).
     - Include the classification rules, run ordering and whether A5 runs.
     - Record the plan hash in every run JSON.
  2. **Order runs to surface problems early.** The first batch is a slice of every cell, so harness problems and A0 violations show up before most machine time is spent. Then interleave cells across nights, so time-of-night and thermal effects don't line up with a single variant. This changes the order, never the counts.
  3. **Launch batches on unattended machine time** (external). Record batch start and end times. Watch the D07-2 dashboards at batch start; record any A0 alert firings (D07-3) as observations.
  4. **On any A0 `VIOLATION` or `NOT_QUIESCED`,** or an F10 cell below the I12 detection gate: stop evidence runs on that SHA and trigger S08-C01. After a fix:
     - rerun every cell whose image digests changed;
     - keep a cell's earlier runs only if all its image digests and the run plan are unchanged.
  5. **Evaluate validity.** Run the aggregation, then compare each ablation's observed fraction with its pre-registered threshold.
     - A5 is judged against its "no violation" prediction.
     - B0 is judged on showing multiple violation classes.
     - A violation class other than the predicted one doesn't count toward validity. Record it and analyze it.
  6. **When an ablation is invalid,** mark the A0 claim for that protection invalid in the results and in H.5, and trigger S08-C01's harness branch. G4's stop condition says there are no correctness claims until the harness is fixed.
  7. **Write confidence statements** using each cell's *actual* run count and the wording in [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes). The per-run bound comes first; any per-trip figure carries the correlated-faults caveat. Make no other statistical claims.
  8. **Write the results files** from the D00-9 template, with tables generated by the aggregation script.
     - `docs/results/chaos-*.md` covers the A0 cells; `docs/results/ablation-*.md` covers A1–A5 and B0.
     - Each links the run plan and records deviations, harness-error counts and interventions.
     - Cells not executed are marked "not run" with the reason, including minimum-cut or machine-time limits.
  9. **Evaluate G4 in H.6** against hard gates 1, 2 and 4 and M13. Record the proceed, narrow-scope or stop action from [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates).
- **Edge cases and failure behavior:**
  - **Machine time runs out:** unfinished cells are "Not run" with the reason. Nothing is extrapolated. Moving to the minimum-cut subset requires the recorded schedule decision in [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut).
  - **Harness errors cluster in one cell:** investigate the cause before counting any of that cell's runs. Report the count either way.
  - **R1 driver debt appears in A0:** report it; it is not a violation.
  - **Batches on different nights disagree markedly:** report per-batch figures alongside the pooled figures.
  - **A reboot or update interrupts a batch:** resume; affected runs are harness errors.
- **Outputs:** planned — `docs/results/chaos-*.md`, `docs/results/ablation-*.md` and their raw JSON; `tools/chaos/run-plan.yaml` (D08-6); validity table and confidence statements inside the results; D08-5 and D08-6 register entries; H.4 and H.6 rows.
- **Verification and definition of done:**
  - The D08-6 commit precedes the first evidence run's timestamp; verify with `git log`.
  - Every cell in the plan has either complete runs or a "not run" reason.
  - Regenerating all results Markdown from run JSON gives an empty diff.
  - Every number in the results traces to run JSON.
  - The validity table covers A1–A4 and B0, and reports the A5 outcome.
  - Confidence statements match the master's wording with actual counts.
  - H.6 records the G4 result with evidence paths; the H.4 rows for hard gates 1, 2 and 4 show Pass or Fail, never estimates.

<a id="conditional-work"></a>
### Conditional and deferred work

<a id="s08-c01"></a>
#### S08-C01 — Regression-fix loop (A0 violations and invalid ablations)
- **Outcome:** a violation or invalid ablation is root-caused and fixed in its owner. A regression test reproduces the failing seed, and the affected cells are rerun, so G4 is evaluated on corrected code or corrected harness.
- **Estimate:** 0 h
- **Inputs:** the preserved run state and run JSON from S08-T04; [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling); [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) (G4); the runbook in [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring); [docs/README.md#effort](README.md#effort); [docs/README.md#conflict-resolution](README.md#conflict-resolution); the owning registers (D02-*, D03-*, D04-*, D05-*, D06-5); D05-11 kill switches; D01-10 seeds.
- **Depends on:** S08-T04 (trigger evidence may arise in S08-T02 smoke runs, the S08-T04 pilot or S08-T05)
- **Instructions:**
  1. **Trigger (evidence):** any of
     - an A0 `VIOLATION` or `NOT_QUIESCED`;
     - an F10 cell below the I12 detection gate;
     - an ablation failing its pre-registered validity threshold;
     - a seam change request exposing a defect in owner code.
  2. **Funding and hours.** No fixed hours. Each iteration draws from unallocated contingency, whose remaining balance is recorded in [docs/zerosum_ledger_mvp_plan.md#changelog](zerosum_ledger_mvp_plan.md#changelog) (v1.2; see also [docs/README.md#effort](README.md#effort) and [docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates)), and its actual hours are recorded in I.1.
  3. **Stop feature work** ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)). When reproducing on a long-lived stack, freeze money movement first with the D05-11 switches, per the runbook.
  4. **Product branch.**
     - Replay the failing seed several times and record the reproduction rate; timing makes replays nondeterministic, and ADR-0009 has no shrinking.
     - Locate the first bad changelog row via the verifier and entity verify, then trace it to the order and source event.
     - Identify the owner and raise a change request.
     - Fix the owner's code.
     - Add a regression test to the owner's suite that reproduces the failing seed.
     - Rerun the affected cells per the image-digest rule in S08-T05.
     - If the root cause is a design decision, revise the owning ADR (G4 "change architecture").
  5. **Harness branch** (invalid ablation).
     - Establish why the protection wasn't exercised: fault timing, a seam not active, or a workload not reaching the path.
     - Fix the S08-T01–T04 artifact concerned.
     - Amend D08-6 only for the harness fix itself; never lower the threshold.
     - Rerun that ablation cell, plus the A0 cells whose fault definition changed.
  6. **Never weaken an invariant definition,** a validity threshold or a gate to make results pass ([docs/zerosum_ledger_mvp_plan.md#step-08](zerosum_ledger_mvp_plan.md#step-08)).
  7. **If unresolved when the schedule ends,** record the known issue in H.5 and the G4 narrow-scope outcome in H.6, so S09 releases `v0.9` with the affected claims removed.
- **Edge cases and failure behavior:**
  - **The failing seed doesn't reproduce in any replay:** keep the preserved state as the primary evidence, and add a targeted test for the suspected interleaving. The violation stays open; it is never declared flaky ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)).
  - **The fix touches several services:** rerun all cells whose image digests changed.
  - **The fix changes a contract** consumed by S09: mark the affected S09 inputs in the handoff.
- **Outputs:** planned — owner code fix and regression test (owner paths); updated owner register and ADR if needed; rerun results; C01 row in I.1 with hours and evidence.
- **Verification and definition of done:**
  - The regression test fails on the pre-fix SHA and passes on the fix SHA; both are recorded in H.4.
  - The rerun cells classify `PASS`, or the ablation is valid.
  - The owner's register shows the change with its date.
  - Affected tasks marked "Needs review" have been revalidated.

**Deferred improvements** (not planned here; recorded so later work knows where the harness applies)

- **Debezium CDC** ([docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred)). If adopted post-MVP, re-plan the A2 seam and rerun F1/F4 cells.
- **Kubernetes/Helm with multiple instances** ([docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred)). Needs new faults for relay and sweeper leader election.
- **Table partitioning and archival, and a TigerBeetle backend comparison** ([docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred)). Either could reuse the orchestrator and verifier contract.
- **S4 hot-entity sharding and S3 batched apply** ([docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have)). If adopted after this step's runs, they change the A1 seam and require a rerun of the A0 and A1 cells.
- **S1 hash chain** ([docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have)). If cut under the minimum cut, I5 is reported as not applicable, never as passed.

<a id="risks-and-recovery"></a>
## F. Risks and recovery

| Risk | Detection (signal or test) | Recovery / fallback |
|---|---|---|
| Harness doesn't exercise a protection, so "0 violations" is meaningless (R6 in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register)) | Pilot negative controls in S08-T04; validity evaluation in S08-T05 | S08-C01 harness branch. Prefer the D04-6 and F3 crash hooks over random kills for narrow windows. No claim for that protection until it is valid. |
| Real correctness bugs in A0 (R2) | Verifier `VIOLATION`; A0 alerts during runs | S08-C01 product branch; stop feature work; regression test with the failing seed |
| Seams drift from the v1.2 resolutions: A2 exemption widened beyond `libs/outbox` (§0.3 C10), A3 creating attempt rows (§0.3 E7), A4 leaving the ledger re-check on (§0.3 E8) | ArchUnit no longer flags application-code publishes; unique-constraint errors in A3 tests; A4 pilot shows quarantine instead of I2 | Fix the seam to match D08-3 and the master; never widen the exemption or relax an owner rule |
| Seam state leaks into A0 (persistent trigger disablement, stray flags) | Startup trigger-state check; per-service active-set assertion | The service refuses to start; the run is a harness error; reset the volume |
| Machine time exceeds the estimate in [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes) because of reset cost, or because quiesce now waits for attempt resolution and webhook redeliveries (§0.3 E3) | Phase timings in pilot run JSON | Switch the reset strategy (D08-4); run across more nights; raise change requests on compressed-time knobs (D05-2/D05-8). Never reduce run counts silently; unfinished cells are "Not run". |
| Faults silently not applied (Toxiproxy down, profile rejected, kill missed) | Executed action log; knob readbacks; observable-effect checks | The run is a harness error; fix and rerun with the same seed |
| Workload rate above the sustainable rate, so lag never drains | `NOT_QUIESCED` in pilot; lag trajectory | Choose the rate from D07-5 and record it in D08-1; it is not a product finding if the rate exceeded the measured ceiling |
| Runs routinely hit the maximum quiesce wait (§0.3 E3) | `NOT_QUIESCED` rate in the pilot and first batch | Investigate as a product finding for A0; raise the D08-6 maximum only by a pre-evidence amendment with rationale |
| Owners' code changes after seams land (SP4 follow-ups, fixes) | Change detection at phase boundaries | Mark S08-T03 "Needs review"; rerun seam tests and affected cells |
| T03 budget too small for five seams | Hours tracked in I.1 | Drop optional A5 first, recorded "Not run"; overrun comes from contingency |
| Noisy or sleeping laptop during overnight runs (R9) | Clock-gap detection; per-batch comparison | Sleep prevention; interleaved run order; report per-batch figures |
| Overclaiming from results (R7) | Aggregation diff check; wording review against [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes) | Numbers only from run JSON; claims removed for invalid ablations |
| Secrets in committed results | Redaction check before write | Block the write; rotate the exposed token per D00-8 |

<a id="acceptance-checklist"></a>
## G. Acceptance checklist

- [ ] `tools/simulator` produces deterministic seeded workloads with F11 and A4 injection and a workload manifest (S08-T01 verification in H.4).
- [ ] F1–F12 definitions exist, and each has a smoke run showing the applied action, an observable effect and a clean clear. The knob coverage table maps every knob (R5 in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register)).
- [ ] Ablation flags for A1–A4 exist (A5 exists, or is recorded "Not run"). B0 is a configuration of A1–A4. Guard tests pass, satisfying the chaos-startup-guard item in [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist).
- [ ] Change requests for every seam are accepted in the S02, S03 and S05 registers, and the owners' flags-off suites are green.
- [ ] M13(a): a single command runs a named cell for N seeded runs and writes JSON and Markdown to `docs/results/` ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)).
- [ ] M13(c): every run JSON and results file records hardware, versions, git SHA, image digests and seeds.
- [ ] D08-6 run plan committed before the first evidence run, covering run allocation, crash-timing-dependent cells, B0 workload and maximum quiesce wait (§0.3 E3–E6).
- [ ] No run classified *not quiesced* is counted as a pass ([docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants)).
- [ ] A0 × F1–F12 executed at the run counts in [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes) (or cells marked "not run" with reasons), meeting hard gate 1 in [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go): I1–I12 and duplicate charges and payouts.
- [ ] The F10 cell meets hard gate 4 in [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go) (I12 detection).
- [ ] M13(b) and hard gate 2: A1–A4 and B0 each meet the validity rule in [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation); the A5 outcome is reported explicitly.
- [ ] Confidence statements use actual run counts and the wording in [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes).
- [ ] `docs/results/chaos-*.md` and `docs/results/ablation-*.md` satisfy the correctness-evidence items of [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist); every number regenerates from run JSON.
- [ ] Any A0 violation was handled through S08-C01, with a regression test reproducing the failing seed ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)).
- [ ] The G4 result and action per [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) are recorded in H.6.
- [ ] H.1–H.6 are filled; I.1 is current for every task; I.2 has been re-checked before handoff ([docs/README.md#change-detection](README.md#change-detection)).

<a id="decisions-and-outputs"></a>
## H. Decisions and outputs register

### H.1 Decisions and rationale

| ID | Decision | Rationale | Alternatives considered | Status | Date |
|---|---|---|---|---|---|
| D08-1 | — | — | — | Pending | — |
| D08-2 | — | — | — | Pending | — |
| D08-3 | — | — | — | Pending | — |
| D08-4 | — | — | — | Pending | — |
| D08-5 | — | — | — | Pending | — |
| D08-6 | — | — | — | Pending | — |

### H.2 Implementation and configuration locations

| Item | Planned path | Actual path | Traced to |
|---|---|---|---|
| Simulator CLI module | `tools/simulator/` | — | D08-1 |
| Workload profiles | `tools/simulator/profiles/*.yaml` | — | D08-1 |
| Workload manifest schema | `tools/simulator/` | — | D08-1 |
| Fault definitions F1–F12 | `tools/chaos/faults/` | — | D08-2 |
| Schedule generator and executor | `tools/chaos/` | — | D08-2 |
| Toxiproxy proxy definitions | `tools/chaos/toxiproxy/` | — | D08-2, D00-3 |
| Provider fault and discrepancy profiles | `tools/chaos/profiles/` | — | D08-2, D05-2, D06-1 |
| F3 crash hook | instrument-service (owner path per D05-5) | — | D08-2, D05-5 |
| Startup guard | Shared module chosen in D08-3 | — | D08-3 |
| A1 seam | ledger-service apply engine (owner path per D02-3) | — | D08-3, D02-3 |
| A2 seam | `libs/outbox` (owner path per D03-5; exempt from the relay-only rule, §0.3 C10) | — | D08-3, D03-5 |
| A3 seam | instrument-service (owner path per D05-5) | — | D08-3, D05-5 |
| A4 seam | order-service validation, orders migrations and ledger-service re-check (owner paths per D03-1/D03-2/D02-3) | — | D08-3, D03-1, D03-2, D02-3 |
| A5 seam | instrument-service webhook receiver (owner path per D05-3) | — | D08-3 |
| Chaos-profile flag bindings | Each owning service's chaos-profile configuration | — | D08-3 |
| Run orchestrator and aggregation | `tools/chaos/` | — | D08-4 |
| Results schema | `tools/chaos/results.schema.json` | — | D08-4 |
| Run plan | `tools/chaos/run-plan.yaml` | — | D08-6 |

### H.3 Produced artifacts

| Artifact | Planned path | Actual path | Revision/hash |
|---|---|---|---|
| A0 chaos results | `docs/results/chaos-*.md` | — | — |
| Ablation results | `docs/results/ablation-*.md` | — | — |
| Raw run JSON and aggregates | under `docs/results/` (layout per D08-4) | — | — |
| Pre-registered run plan | `tools/chaos/run-plan.yaml` | — | — |
| Knob coverage table | `tools/chaos/` | — | — |
| Validity evaluation and confidence statements | inside `docs/results/ablation-*.md` and `docs/results/chaos-*.md` | — | — |
| Change-request records (S02, S03, S05, D00-3, D04-6) | I.2 of this document and the owners' registers | — | — |

### H.4 Validation results and evidence

| Check | Method | Result | Evidence path | Date |
|---|---|---|---|---|
| Simulator determinism and split integrity | S08-T01 unit tests | Not run | — | — |
| F11 and A4 injection smoke (A0, pilot) | S08-T01 smoke run and verifier | Not run | — | — |
| Schedule determinism | S08-T02 unit test | Not run | — | — |
| Per-fault injection smoke F1–F12 | S08-T02 action logs and effect checks | Not run | — | — |
| Executor refusal outside chaos | S08-T02 refusal test | Not run | — | — |
| Startup guard cases | S08-T03 guard tests (CI) | Not run | — | — |
| Seam behavior A1–A5 | S08-T03 integration tests (CI) | Not run — the seams do not exist; the 2026-09-18 evidence pass could not edit `services/**` or `libs/**` | — | — |
| Owners' flags-off suites and ArchUnit | S08-T03 CI run | Not run | — | — |
| Pilot batch schema validation and M13(c) metadata | S08-T04 pilot | Not run | — | — |
| Resumability and independence | S08-T04 tests | Not run | — | — |
| Hard gate 1: A0 invariants and duplicates | S08-T05 runs and verifier | Not run | — | — |
| Hard gate 2: ablation validity | S08-T05 validity evaluation | Not run | — | — |
| Hard gate 4: I12 detection | S08-T05 F10 cell | Not run | — | — |
| Results regenerate from run JSON | S08-T05 aggregation diff | Not run | — | — |
| M4 (a): `kill -9` order-service between commit and publish (outside the T01–T05 tooling) | `infra/tests/.../recovery/OrderPublishAfterCrashE2ETest`, 5 × 20 orders, broker paused to hold the window | Pass: 100/100 published and applied once; last publish 3.75–3.82 s after container start, 126–148 ms after Spring started | [docs/results/s08/m4a-crash-recovery.md](results/s08/m4a-crash-recovery.md) | 2026-09-18 |
| M8 (b): 10,000 charges at FakeCard `timeout_after_commit_rate=0.2` (outside the T01–T05 tooling) | `infra/tests/.../volume/CardTimeoutVolumeE2ETest`, 5 × 2,000, seeds 8180001–8180005, per-row fault attribution | Pass: 10,000/10,000 exactly one successful charge; 1,946 faults matched 1:1 to lost responses | [docs/results/s08/m8b-card-timeout-volume.md](results/s08/m8b-card-timeout-volume.md) | 2026-09-18 |
| M13 (b): ablations A1–A4 | A2 emulated by state, A4 at the DB layer only (rolled back); A1, A3, B0 not run | Not met: no seams. A2 emulation lost 67/100 in 5/5 runs; A4 DB layer stored a +1 order in 5/5 | [docs/results/s08/m13b-ablations.md](results/s08/m13b-ablations.md) | 2026-09-18 |

### H.5 Known limitations and blockers

| Item | Type (limitation/blocker) | Impact | Owner/next action |
|---|---|---|---|
| A zero-failure result bounds the per-run failure probability; it doesn't prove impossibility. Per-trip figures are weakened by correlated faults ([docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes)) | Limitation | Claim wording | S08-T05 confidence statements; S09 résumé wording |
| A5 is predicted to show no violation (layered defense) | Limitation | A5 isn't a validity failure | S08-T05 reports it explicitly |
| Kafka RF=1 volume loss isn't in the fault matrix ([docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery)) | Limitation | No evidence for that loss window | Post-MVP; listed in S09 limitations |
| FakeBank quiet-period residual risk (ADR-0010) | Limitation | Duplicate payout possible outside simulated bounds | D05-9; stated in results |
| Payout/adjustment race produces driver debt (R1) | Limitation | Reported metric, not a violation | S08-T04 metric; S09 limitations |
| Single-host laptop runs; simulated providers only | Limitation | Results are environment baselines | Stated in every results file |
| At FakeCard `timeout_after_commit_rate=0.2` alone, every lost response is settled by the provider webhook while `SUBMITTING`; 0 of 10,000 attempts reached `UNKNOWN` | Limitation | M8 (b) as worded does not exercise the S05-T12 resolver | Covered by a supplementary run with `webhook_drop_rate=1.0` (229 `UNKNOWN` → idempotent retry, one charge each); F7/F12 cells should drop or delay webhooks if they mean to test the resolver |
| `UNKNOWN` is entered 7.0 s after `SUBMITTING` (FakeCard's withhold), not at the configured 5 s adapter read timeout | Limitation (observed 2026-09-18, not investigated) | Each lost response holds a policy thread 7 s; throughput and the D05-14 timeout contract | Owner of D05-14 |
| Quiesce ignored unresolved attempts and pending redeliveries | Limitation | None after v1.2 | Resolved in master v1.2 (§0.3 E3); applied in D08-4, D08-6 |
| Run allocation for ablations with several targeted faults | Limitation | None after v1.2 | Resolved in master v1.2 (§0.3 E4); applied in D08-6 |
| Which validity threshold applies | Limitation | None after v1.2 | Resolved in master v1.2 (§0.3 E5); crash-timing-dependent cells named in D08-6 |
| B0 workload lacked A4 bug injection | Limitation | None after v1.2 | Resolved in master v1.2 (§0.3 E6); applied in D08-1, D08-6 |
| A3 vs attempt uniqueness | Limitation | None after v1.2 | Resolved in master v1.2 (§0.3 E7); applied in D08-3 |
| A4 ledger re-check scope | Limitation | None after v1.2 | Resolved in master v1.2 (§0.3 E8); applied in D08-3 |
| F3 breakpoint hook had no owner | Limitation | None after v1.2 | Resolved in master v1.2 (§0.3 E9); S08-T02 via change request on D05-5 |
| A2 seam vs relay-only ArchUnit rule | Limitation | None after v1.2 | Resolved in master v1.2 (§0.3 C10); applied in D08-3 |
| I12 injected fault log undefined | Limitation | None after v1.2 | Resolved in master v1.2 (§0.3 E2); provided by D05-2 |
| Audit-study condition B described as a B0 build | Limitation | None after v1.2 | Resolved in master v1.2 (§0.3 E10); owned by D09-5 |

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
| S08-T01 | Planned | — | — | — |
| S08-T02 | Planned | — | — | — |
| S08-T03 | Planned | — | — | — |
| S08-T04 | Planned | — | — | — |
| S08-T05 | Planned | — | — | — |
| S08-C01 | Planned | — | — | — |

<a id="change-record"></a>
### I.2 Consumed sources and change record

| Source/artifact path | Revision or hash | Recorded at | Affected tasks | Review outcome |
|---|---|---|---|---|
| `docs/zerosum_ledger_mvp_plan.md` | — | — | All | — |
| `docs/zerosum_ledger_mvp_plan.md` §0.3 (v1.2) | — | 2026-09-15 (planning; doc v1.1) | S08-T01, S08-T02, S08-T03, S08-T04, S08-T05, S08-C01 | Resolved in master v1.2 (§0.3 E2, E3, E4, E5, E6, E7, E8, E9, C10); tasks updated in place |
| `docs/README.md` | — | — | All | — |
| `docs/step_00_foundations.md` | — | — | S08-T02, S08-T04 | — |
| `docs/step_01_domain_contracts.md` | — | — | S08-T01 | — |
| `docs/step_02_ledger_core.md` | — | — | S08-T03 | — |
| `docs/step_03_order_service_outbox.md` | — | — | S08-T01, S08-T03 | — |
| `docs/step_04_kafka_pipeline.md` | — | — | S08-T02, S08-T04 | — |
| `docs/step_05_instruments_fake_providers.md` | — | — | S08-T01, S08-T02, S08-T03 | — |
| `docs/step_06_reconciliation_verifier.md` | — | — | S08-T02, S08-T04, S08-T05 | — |
| `docs/step_07_observability_performance.md` | — | — | S08-T01, S08-T04, S08-T05 | — |
| `docker-compose.yml` | — | — | S08-T02, S08-T04 | — |
| `gradle/libs.versions.toml` | — | — | S08-T04 | — |
| `.env.example` | — | — | S08-T02, S08-T03 | — |
| `docs/results/TEMPLATE.md` | — | — | S08-T04, S08-T05 | — |
| `libs/money/` | — | — | S08-T01 | — |
| `libs/outbox/` | — | — | S08-T03 | — |
| `services/ledger-service/` (apply engine) | — | — | S08-T03 | — |
| `services/order-service/` (validation, migrations) | — | — | S08-T03 | — |
| `services/instrument-service/` (attempts, webhooks) | — | — | S08-T02, S08-T03 | — |
| `services/fake-providers/` (knob schema, ground truth) | — | — | S08-T02, S08-T04 | — |
| `scenarios/` and scenario runner (D05-12) | — | — | S08-T01 | — |
| `openapi/order-service.yaml`, `openapi/instrument-service.yaml` | — | — | S08-T01 | — |
| `tools/verifier/` | — | — | S08-T04, S08-T05 | — |
| `infra/grafana/dashboards/`, `infra/alerts/` | — | — | S08-T05 | — |
| `docs/results/perf-*.md` (D07-5) | — | — | S08-T01, S08-T05 | — |
| `docs/adr/` (ADR-0005, ADR-0009, ADR-0010) | — | — | S08-T01, S08-T03, S08-C01 | — |

<a id="handoff"></a>
## J. Handoff

**What S09 consumes**

| Output | Consumer | Resolve from |
|---|---|---|
| A0 chaos results, confidence statements and G4 result | [docs/step_09_demo_docs_release.md#s09-t01](step_09_demo_docs_release.md#s09-t01) (README results summary) and [docs/step_09_demo_docs_release.md#s09-t07](step_09_demo_docs_release.md#s09-t07) (release checklist, résumé bullets filled only from results per [docs/zerosum_ledger_mvp_plan.md#portfolio-value](zerosum_ledger_mvp_plan.md#portfolio-value)) | D08-5 register entry and the H.3 actual paths |
| Ablation results and validity table, including the A5 outcome and any invalid protection | [docs/step_09_demo_docs_release.md#s09-t07](step_09_demo_docs_release.md#s09-t07) (claims removed for invalid ablations) | D08-5 |
| M13 evidence: single command, results metadata | [docs/step_09_demo_docs_release.md#s09-t02](step_09_demo_docs_release.md#s09-t02) (M1–M14 traceability table) | D08-4 and H.4 |
| Chaos-guard tests (release checklist security item) | [docs/step_09_demo_docs_release.md#s09-t07](step_09_demo_docs_release.md#s09-t07) | D08-3 and H.4 |
| Simulator workloads for audit-study data | [docs/step_09_demo_docs_release.md#s09-t04](step_09_demo_docs_release.md#s09-t04) | D08-1. Audit-study condition B is an access restriction, not the B0 variant (§0.3 E10, D09-5, [docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval)); S08 provides no seam for it. |
| Simulator and orchestrator for demo footage of a chaos run | [docs/step_09_demo_docs_release.md#s09-t06](step_09_demo_docs_release.md#s09-t06) | D08-1, D08-4 |
| Known issues and G4 narrow-scope outcome, if any | [docs/step_09_demo_docs_release.md#decisions-and-outputs](step_09_demo_docs_release.md#decisions-and-outputs) (release notes, `v0.9` path) | H.5 and H.6 of this document |

**Where to resolve definitions.** S09 reads the D08-1…D08-6 entries in [docs/step_08_fault_injection_ablation.md#decisions-and-outputs](step_08_fault_injection_ablation.md#decisions-and-outputs) and the actual artifact paths in H.2/H.3. It never copies numbers from this document; every number lives only in the run JSON and generated results files.

**Handoff conditions**

1. G4 is evaluated in H.6 with evidence paths, and its action from [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) is recorded (proceed, or narrow scope with known issues).
2. H.1 has no `Pending` decision for executed work. H.3 and H.4 hold actual paths and results; unexecuted evidence is "Not run" with a reason.
3. I.1 is current for every task, including S08-C01's hours if it ran.
4. Change detection has been re-run, and I.2 is current. Every change request raised against S00, S02, S03, S04, S05 or S06 is closed in the owner's register or listed in H.5.
5. The results files pass the redaction check and regenerate from run JSON with an empty diff.
