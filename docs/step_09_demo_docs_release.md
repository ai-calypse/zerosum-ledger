# Step 09 — Demo, docs, release

> **Pack:** ZeroSum Ledger implementation docs · **Master:** [docs/zerosum_ledger_mvp_plan.md#step-09](zerosum_ledger_mvp_plan.md#step-09) (v1.2)
> **Doc version:** 1.1 · **Date:** 2026-09-15 · **Step status:** Planned (no implementation exists)
> **Planned effort:** 14 h (master schedule; see [docs/zerosum_ledger_mvp_plan.md#schedule-overview](zerosum_ledger_mvp_plan.md#schedule-overview)), plus 2 h pre-allocated contingency for S09-T07 ([docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates); §0.3 O4 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)) · **Gate:** G5
> **Shared procedures:** [docs/README.md#change-detection](README.md#change-detection) · [docs/README.md#source-of-truth](README.md#source-of-truth) · [docs/README.md#status-legend](README.md#status-legend)

<a id="purpose-and-outcome"></a>
## A. Purpose and outcome

**The problem this step solves.** By the end of S08 the system is built and the evidence exists: perf results, chaos and ablation results, and the verifier. But the project's real audience is hiring managers and interviewers ([docs/zerosum_ledger_mvp_plan.md#users](zerosum_ledger_mvp_plan.md#users)), and they can't yet use it. A reviewer can't reach a working scenario quickly, can't see how the design maps to the requirements, and can't tell which claims are measured. Two risks follow: reviewers never run or read the project (R12), and résumé claims go beyond the evidence (R7) ([docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register)). S09 turns the finished code and results into a release a reviewer can check. Every claim in it traces to a results file.

**Concrete deliverables** (all planned):

1. A root `README.md` with a results summary and a quickstart. The quickstart comes with a timed fresh-clone record (M14(a)).
2. `docs/architecture.md` with diagrams, a cleaned ADR index, the finalized `docs/runbook.md`, and the M1–M14 traceability table that the release checklist requires.
3. The Ledger Explorer: a single `explorer.html` served by ledger-service (should-have S2), with exact entity-ID lookup (§0.3 C15).
4. The audit-task study (H3) and the developer integration test (H4). Each ends as a results write-up or as an honest "not run" record.
5. A demo video whose length meets M14(c), linked from the README.
6. Release execution (S09-T07, contingency-funded): the tag CI workflow (§0.3 O4), the completed release checklist, the release tag and notes, and a résumé bullets draft taken only from results files.
7. Conditionally: the optional S5 hosted demo with its `demo-public` profile (S09-C01), and its restore drill before tagging (S09-C02), both per §0.3 O5. The v1.2 clarifications binding this step are in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications).

**Contribution to the MVP.** S09 closes M14 and should-have S2. It produces the evidence for the soft targets H3 and H4 and tests hypothesis H5 ([docs/zerosum_ledger_mvp_plan.md#facts-vs-hypotheses](zerosum_ledger_mvp_plan.md#facts-vs-hypotheses)). It also evaluates gate G5 ([docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates)). It is the last step in the pack.

**In scope**

- Documentation that *describes and links* the selected implementation. It never redefines it.
- A read-only Explorer built on the existing read APIs.
- Human-evaluation protocols, facilitation and write-ups.
- The demo video, the release checklist walk-through, tagging and release notes.
- Deriving résumé bullets from results.
- Conditionally, the hosted demo and its restore drill.

**Explicitly out of scope**

| Excluded | Owner |
|---|---|
| New money-movement features, contract or schema changes | Upstream owners, only through [docs/README.md#conflict-resolution](README.md#conflict-resolution) |
| Perf reruns and SP4 follow-up | S07 ([docs/step_07_observability_performance.md#decisions-and-outputs](step_07_observability_performance.md#decisions-and-outputs)); S07-C01 |
| Chaos or ablation reruns and regression fixes | S08 ([docs/step_08_fault_injection_ablation.md#decisions-and-outputs](step_08_fault_injection_ablation.md#decisions-and-outputs)); S08-C01 |
| Hot-entity sharding (S4) | [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have), decided in S07 |
| Ledger replay tool | Conditional in [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery); no step assignment ([docs/README.md#known-gaps](README.md#known-gaps)) |
| Holds, FX, disputes, Stripe test-mode adapter, TigerBeetle comparison, Kubernetes, and the rest of the deferred list | [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred) |
| A polished end-user UI (the Explorer stays a single diagnostic page) | [docs/zerosum_ledger_mvp_plan.md#non-goals](zerosum_ledger_mvp_plan.md#non-goals) |
| Cost tracking of any kind | [docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates) |

<a id="agent-prompt"></a>
## B. Step-specific agent prompt

```text
You are the implementation agent for Step 09 (Demo, docs, release) of the ZeroSum Ledger project.

Repository root: zerosum-ledger/
Step document: docs/step_09_demo_docs_release.md

1. Read docs/README.md (source-of-truth, conflict-resolution, change-detection, status-legend), then this
   document in full, then every source in its section C at the linked anchors: the master sections in C.1
   (including §0.3, the v1.2 decomposition clarifications) and the registers (section H) of steps S00-S08
   listed in C.2.
2. Inspect the current repository and all upstream execution records (sections H and I of S00-S08). Resolve
   every path, command, endpoint, version, threshold and result from the owning register and the artifact it
   references, never from this document or from the master's proposals. In particular, read the actual
   values of D05-12 (scenario runner), D02-7 (ledger read API), D06-5 (verifier), D07-5 (perf results) and
   D08-5 (chaos and ablation results) before writing any documentation or claim.
3. Run the change-detection procedure in docs/README.md#change-detection before changing anything. Record
   the revisions and hashes of every source and consumed artifact in section I.2.
4. Complete only this step's remaining authorized work, in task-dependency order: S09-T01 ... S09-T06, then
   S09-C01 and S09-C02 (only if their triggers hold and contingency allows; both run before tagging),
   then S09-T07. Check
   section C.4 first: if an input is missing, do the independent preparation named there and record the
   blocker.
5. Verify each task exactly as its "Verification and definition of done" field specifies. Record evidence
   paths in H.4 and I.1.
6. Record every decision (with rationale and alternatives), actual implementation and configuration paths,
   produced artifacts, limitations and blockers in section H. Add trace comments next to authoritative
   configuration values (see D.4).
7. Never invent results. Study observations, timings, video content and résumé numbers must come from
   recorded evidence. Mark unexecuted evidence "Not run" with the reason, and blocked work "Blocked" with
   the exact missing dependency. Never mark blocked evidence as passed. Never implement future or deferred
   scope, and never rerun or reinterpret S07/S08 experiments yourself.
8. Re-run change detection at every phase boundary and before handoff. If an upstream result changes after
   you documented it (for example an S08-C01 rerun), mark the affected S09 tasks "Needs review" and redo them.
9. If you need to change anything owned by the master or an upstream step (an API, a profile, a threshold, a
   results file), stop and raise a change request per docs/README.md#conflict-resolution. Never weaken a
   release gate, and never silently edit another step's artifact or register.

Finish by completing the section G checklist and section J handoff conditions. Report: tasks done, evidence
paths, the gate G5 result, open blockers, and change requests raised.
```

<a id="required-reading"></a>
## C. Required reading and prerequisites

### C.1 Master sections

- [docs/zerosum_ledger_mvp_plan.md#step-09](zerosum_ledger_mvp_plan.md#step-09): the step objective, its task list, and its G5 exit criterion.
- [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications): v1.2 resolutions binding this step: C15 (Explorer lookup), E10 (study condition B), E11 (where M6(c) is verified), O4 (tag workflow), O5 (demo profile and restore drill), O6 (M14(a) and M1(d) evidence), O7 (facilitation time).
- [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist): the definition of done that S09-T07 walks item by item.
- [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates): G5, and the G4 "narrow scope" rule, which decide between `v1.0.0` and `v0.9`.
- [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go): hard gates versus soft targets, including the rule for dropping the throughput bullet.
- [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have): M14 acceptance, plus every M-criterion the traceability table maps.
- [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have): S2 Explorer, the optional S5 hosted demo, and the cut order.
- [docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval): the audit-task study design, conditions and H3 threshold; the developer integration test and H4 threshold.
- [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes): the confidence wording the README and bullets must use.
- [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling): the "not run" rule for blocked evidence.
- [docs/zerosum_ledger_mvp_plan.md#portfolio-value](zerosum_ledger_mvp_plan.md#portfolio-value): the résumé bullet templates and the "personal project" framing.
- [docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app): the Explorer's intended functions, the scenario catalog, and the Swagger UI and Grafana reuse.
- [docs/zerosum_ledger_mvp_plan.md#release-procedure](zerosum_ledger_mvp_plan.md#release-procedure): the tag, notes and rollback sequence.
- [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd): what the tag workflow owned by S09-T07 runs (§0.3 O4); demo deploy and health-check rollback (S09-C01).
- [docs/zerosum_ledger_mvp_plan.md#environments](zerosum_ledger_mvp_plan.md#environments), [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets), [docs/zerosum_ledger_mvp_plan.md#spending-controls](zerosum_ledger_mvp_plan.md#spending-controls): the demo environment, deploy secrets and public-demo quotas (S09-C01 only).
- [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery): backups and the restore drill (S09-C02).
- [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring): the dashboards, alerts and runbook skeleton that `docs/runbook.md` must cover.
- [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) and [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting): TB1/TB4 controls that the Explorer and the demo profile must respect.
- [docs/zerosum_ledger_mvp_plan.md#workflows](zerosum_ledger_mvp_plan.md#workflows): the W1–W6 narratives for the README, the video and the study tasks.
- [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut): what S09 keeps if the minimum cut is in force.
- [docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates): cost is not a driver; T07 is funded from pre-allocated contingency.
- [docs/zerosum_ledger_mvp_plan.md#waiting-time](zerosum_ledger_mvp_plan.md#waiting-time): participant scheduling lead time and hosted-account verification.

### C.2 Earlier step documents and their registers

| Step | Register | Decisions consumed |
|---|---|---|
| S00 | [docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs) | D00-1 pinned versions and ADR-0002 (cited in the architecture doc and release notes) · D00-2 repository layout (planned paths in this doc) · D00-3 compose topology and profiles (quickstart) · D00-4 databases and roles, including the read-only `verifier` role (study condition D) · D00-5 CI workflow structure (base for the S09-T07 tag workflow; nightly e2e) · D00-7 SP3 result (architecture doc) · D00-8 environment and secret conventions (quickstart `.env`, demo secrets) · D00-9 ADR process and `docs/results/TEMPLATE.md` (every write-up) · D00-10 build conventions (quickstart commands, version string) |
| S01 | [docs/step_01_domain_contracts.md#decisions-and-outputs](step_01_domain_contracts.md#decisions-and-outputs) | D01-1 Money API and overflow policy (Explorer amount display) · D01-5 validator rule set (entity-ID input validation) · D01-6 chart of accounts and ADR-0003 (architecture doc, Explorer normal-side display) · D01-9 golden payloads (release checklist) · D01-10 seed-reporting convention (study datasets) |
| S02 | [docs/step_02_ledger_core.md#decisions-and-outputs](step_02_ledger_core.md#decisions-and-outputs) | D02-3 apply engine and D02-4 lock strategy / ADR-0005 (architecture doc) · D02-6 hash-chain canonical form (verify display) · D02-7 ledger read API contract (Explorer's only backend) · D02-8 invariant queries · D02-10 SP1 result and S3 decision (architecture doc, bullets) |
| S03 | [docs/step_03_order_service_outbox.md#decisions-and-outputs](step_03_order_service_outbox.md#decisions-and-outputs) | D03-2 money-order API contract (Explorer order links, quickstart) · D03-3 idempotency semantics (README explanation) · D03-4 auth module and role mapping (Explorer token handling, demo token) · D03-5 outbox design / ADR-0008 · D03-6 mapper / ADR-0006 |
| S04 | [docs/step_04_kafka_pipeline.md#decisions-and-outputs](step_04_kafka_pipeline.md#decisions-and-outputs) | D04-1 topics and partition key / ADR-0007 · D04-4 error-handling policy (runbook) · D04-5 freshness contract (runbook, README) · D04-6 pipeline e2e suite (traceability; M6(c) evidence per §0.3 E11) · D04-7 SP2 decision (architecture doc) |
| S05 | [docs/step_05_instruments_fake_providers.md#decisions-and-outputs](step_05_instruments_fake_providers.md#decisions-and-outputs) | D05-1 `PaymentInstrument` interface (H4 brief) · D05-2 fake-providers API and fault knobs (video) · D05-9 ADR-0010 quiet period (limitations) · D05-10 provider contract suite (H4 pass criterion) · D05-11 kill switches (runbook) · D05-12 scenario catalog and runner (quickstart W1, video) · D05-13 instrument OpenAPI (M14(b)) |
| S06 | [docs/step_06_reconciliation_verifier.md#decisions-and-outputs](step_06_reconciliation_verifier.md#decisions-and-outputs) | D06-2 break types and D06-4 reconciliation API (runbook, video) · D06-5 verifier CLI (study ground truth, restore drill) · D06-6 W5–W6 scenarios (study tasks) |
| S07 | [docs/step_07_observability_performance.md#decisions-and-outputs](step_07_observability_performance.md#decisions-and-outputs) | D07-1 metric registry (runbook) · D07-2 dashboards (screenshots, video) · D07-3 alert rules and runbook stubs (runbook finalization) · D07-5 perf results, including whether T1 was met (README, bullets) · D07-6 SP4 decision (architecture doc, ADR-0005 status) |
| S08 | [docs/step_08_fault_injection_ablation.md#decisions-and-outputs](step_08_fault_injection_ablation.md#decisions-and-outputs) | D08-1 simulator CLI (study datasets) · D08-3 ablation flags and startup guard (study baseline condition, release checklist guard test) · D08-4 results format · D08-5 chaos and ablation results, validity evaluation and confidence statements (README, video, bullets, G5) · D08-6 pre-registered run plan (validity threshold per ablation, claim wording) |

### C.3 Artifacts that must already exist

The paths below are the *planned* paths from the master and the upstream briefs. **Resolve each actual path from the owning register (H.2/H.3 of that step), not from this list.**

| Artifact (planned path) | Owner |
|---|---|
| `README.md` skeleton with quickstart placeholder | D00-9 / S00-T06 |
| `docker-compose.yml`, `.env.example` | D00-3, D00-8 |
| `.github/workflows/ci.yml` (build, test and nightly jobs; the tag workflow is S09-T07's per §0.3 O4) | D00-5 |
| `docs/adr/0001-*.md` … `docs/adr/0010-*.md` | D00-9 (process); individual ADR owners in S00–S07 |
| `docs/results/TEMPLATE.md` | D00-9 |
| `openapi/order-service.yaml`, `openapi/ledger-service.yaml`, `openapi/instrument-service.yaml` | D03-2, D02-7, D05-13 |
| Ledger read API implementation in `services/ledger-service` | D02-7 |
| Scenario catalog (`scenarios/`) and runner; W1–W6 files | D05-12, D06-6 |
| `tools/simulator`, `tools/verifier`, `tools/chaos` | D08-1, D06-5, D08-2 |
| `infra/grafana/dashboards/*.json`, `infra/alerts/*.yaml`, `docs/runbook.md` (stubs) | D07-2, D07-3 |
| `docs/results/sp1-lock-study.md`, `docs/results/perf-*.md` | D02-10, D07-5 |
| `docs/results/chaos-*.md`, `docs/results/ablation-*.md` | D08-5 |
| Provider contract test suite | D05-10 |
| Golden payload tests in `libs/contracts` | D01-9 |

### C.4 Blocking vs independent work

| Missing input | Blocks | Independent preparation that can proceed |
|---|---|---|
| Scenario runner and W1 scenario (D05-12) | S09-T01 timing run; S09-T06 recording | Draft the README structure and wording; write the timing script against the documented commands; draft the video script |
| Ledger read API (D02-7) or auth module (D03-4) | S09-T03 wiring and verification | Build the page layout, the rendering functions and XSS-safe helpers against example responses from `openapi/ledger-service.yaml` |
| S08 results and validity evaluation (D08-5) | Traceability rows for M13 in S09-T02; the results segment of S09-T06; all bullets and the release decision in S09-T07 | Write the architecture doc sections that don't cite results; leave bullet templates with unfilled placeholders marked "pending D08-5" |
| Perf results (D07-5) or SP4 decision (D07-6) | README perf summary; throughput bullet; ADR-0005 status in S09-T02 | Everything else in S09-T02 |
| W5 scenario (D06-6), simulator (D08-1) or ablation flags (D08-3) | S09-T04 dataset preparation and sessions | Write the protocol, task sheets, consent text and analysis sheet; send invitations |
| Study participants (external; builder facilitation hours come from unallocated contingency, §0.3 O7) | S09-T04 counted sessions; S09-T05 peer sessions | Protocol, pilot scheduling and the integration brief. If participants stay unavailable, write the results files as "Not run" with the reason ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)). |
| Provider interface and contract suite (D05-1, D05-10) | S09-T05 | The participant brief skeleton and measurement sheet |
| Dashboards (D07-2) | Grafana segments of S09-T06 | Script and non-Grafana segments |
| Runbook stubs and alert rules (D07-3) | Runbook finalization in S09-T02 | Architecture doc and traceability table |
| CI workflow structure and image builds (D00-5, D00-10) | The tag workflow and tagging in S09-T07 | Draft the tag workflow on a branch; checklist walk-through, release notes draft, bullet draft |
| Hosting account verification (external, S5 only) | S09-C01 | None required: C01 is optional |

<a id="ownership-and-requirements"></a>
## D. Ownership and engineering requirements

### D.1 Decisions and contracts owned by this step

| Decision ID | What is decided | Master's proposed starting point |
|---|---|---|
| D09-1 | README structure and quickstart command sequence. Also the concrete fresh-clone timing procedure (stage boundaries and the Docker image-cache prune) that implements the evidence method in §0.3 O6, and which CI e2e run is cited as supporting evidence. | [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) (M14(a)); [docs/zerosum_ledger_mvp_plan.md#step-09](zerosum_ledger_mvp_plan.md#step-09); §0.3 O6 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) |
| D09-2 | `docs/architecture.md` structure and diagrams. The M1–M14 traceability table format (criterion → named test or results file → evidence link). The location of the finalized runbook. | [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist); [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components); [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring) |
| D09-3 | ADR index location and format. The final status of each ADR (Accepted, Superseded, and so on), including updates made upstream through change requests. | [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) |
| D09-4 | The Ledger Explorer: file location and serving route, which read endpoints it calls, exact entity-ID lookup (§0.3 C15), how order links are resolved, token handling, security headers, and amount display rules. | [docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app); [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have) (S2); §0.3 C15 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) |
| D09-5 | Audit study protocol: condition preparation, including how the condition-B access restriction (§0.3 E10) is enforced; dataset seeds; task sets and ground truth; counterbalancing matrix; timing method; facilitation hours actually spent (§0.3 O7); and results. | [docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval); §0.3 E10 and O7 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) |
| D09-6 | Developer integration test protocol: the starting commit, the definition of "core files", the participant brief, the measurement sheet, and results. | [docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval) |
| D09-7 | Demo video: script, segments, where it is hosted, and the commit and results revisions it shows. | [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) (M14(c)); [docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app) |
| D09-8 | Résumé bullets, derived only from results files, with each number linked. Also which template bullets are dropped and why. | [docs/zerosum_ledger_mvp_plan.md#portfolio-value](zerosum_ledger_mvp_plan.md#portfolio-value); [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go) |
| D09-9 | Release: the tag CI workflow (version-tagged GHCR images, migration test mode, pre-tag secret scan; §0.3 O4), the tagged version (`v1.0.0`, or `v0.9` under the G4/G5 rules), release commit, release notes, and checklist outcome. | [docs/zerosum_ledger_mvp_plan.md#release-procedure](zerosum_ledger_mvp_plan.md#release-procedure); [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd); [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates); §0.3 O4 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) |
| D09-10 | S5 hosted demo decision (optional, decided on showcase value). If pursued: the `demo-public` profile implementation and test (§0.3 O5), hosting target, deploy mechanism and pre-tag image build path, backup schedule, and the restore-drill result recorded before tagging. | [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have) (S5); [docs/zerosum_ledger_mvp_plan.md#environments](zerosum_ledger_mvp_plan.md#environments); [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery); §0.3 O5 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) |

### D.2 Inherited decisions

| Decision | Owner | How this step must use it |
|---|---|---|
| Pinned versions, ADR-0002 | D00-1 ([docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs)) | README and architecture doc link to ADR-0002 and never list versions themselves. Release notes cite the ADR revision. |
| Compose topology and profiles | D00-3 | The quickstart uses the default profile exactly as the owner defines it. The video may show the chaos profile only with its guard. |
| Read-only verifier role | D00-4 | Reused as the read-only SQL access for study condition D. Condition B's balances-only access (§0.3 E10) is enforced as D09-5 records; a new restricted role is a change request to D00-4, never an ad-hoc grant. |
| CI workflow structure and test tags | D00-5 | The S09-T07 tag workflow (§0.3 O4) follows this structure. T07 tags only when the owner's build, test and nightly jobs are green on the release candidate commit. |
| Environment and secret conventions | D00-8 | The quickstart generates `.env` the owner's way. Demo secrets (C01) follow [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets) plus D00-8. |
| Results template, ADR process | D00-9 | Every S09 results file uses the template. ADR edits follow the ADR process. |
| Sign convention and normal sides | D01-6 / ADR-0003 | The Explorer shows balances as the API returns them and does not recompute signs on the client. |
| Ledger read API | D02-7 ([docs/step_02_ledger_core.md#decisions-and-outputs](step_02_ledger_core.md#decisions-and-outputs)) | This is the only backend the Explorer calls on ledger-service; lookup is by exact entity ID (§0.3 C15). A missing endpoint means a change request to S02, not a new endpoint added in S09. |
| Hash chain and verify semantics | D02-6, D02-7 | The Explorer shows the verify result fields exactly as defined. |
| Auth roles and principal mapping | D03-4 ([docs/step_03_order_service_outbox.md#decisions-and-outputs](step_03_order_service_outbox.md#decisions-and-outputs)) | The Explorer uses the reader role only. It never ships or stores tokens. Any cross-origin allowance on order-service is a change request to D03-4. |
| Provider interface and contract suite | D05-1, D05-10 ([docs/step_05_instruments_fake_providers.md#decisions-and-outputs](step_05_instruments_fake_providers.md#decisions-and-outputs)) | These define the H4 task boundary and its pass criterion. |
| Scenario catalog and runner | D05-12 | The single source for the quickstart's W1 command and the video's scenario runs. |
| Verifier CLI | D06-5 ([docs/step_06_reconciliation_verifier.md#decisions-and-outputs](step_06_reconciliation_verifier.md#decisions-and-outputs)) | Ground truth for study answers. Pass/fail check for the restore drill. |
| Dashboards, alerts, runbook stubs | D07-2, D07-3 ([docs/step_07_observability_performance.md#decisions-and-outputs](step_07_observability_performance.md#decisions-and-outputs)) | Screenshots and video come from provisioned dashboards. The runbook gets one section per alert rule the owner defines. |
| Perf results and SP4 | D07-5, D07-6 | README and bullets quote only these files. If T1 was missed, the throughput bullet is dropped ([docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go)). |
| Ablation flags and startup guard | D08-3 ([docs/step_08_fault_injection_ablation.md#decisions-and-outputs](step_08_fault_injection_ablation.md#decisions-and-outputs)) | Study condition B runs on the B0 build (§0.3 E10). The guard test is a release checklist item. |
| Chaos and ablation results, validity | D08-5, D08-6 | Correctness claims and confidence wording come only from here. Invalid ablations remove the matching claims. |
| G5 and G4 outcomes | Master [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) | Decide the tagged version in D09-9. They are never reinterpreted. |

### D.3 Engineering requirements

**Module and repository boundaries**

- **Documentation files.** S09 writes `README.md`, `docs/architecture.md`, `docs/runbook.md` (finalizing S07's stubs), the ADR index, and results or evaluation files. It edits ADR bodies only to fix status or cross-links, following D00-9. A change to an ADR's substance belongs to that ADR's owning step.
- **Explorer location.** The Explorer is a static resource inside `services/ledger-service`. It adds no Java endpoint, no build tool, no JavaScript framework and no CDN dependency. It is one HTML file with inline CSS and JS, which keeps it reviewable and works offline on the local stack.
- **No product code beyond the Explorer.** S09 may add small scripts (timing, checklist helpers) under the tools layout defined by D00-2.

**Interfaces and data flows**

- **W5 audit flow in the Explorer.** Entity ID → balances → changelog page → money order and source idempotency key → verify. M6(c) ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)) is verified in S04's end-to-end tests (§0.3 E11 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)); the Explorer walkthrough records its own call count from the browser network log as supporting evidence.
- **Explorer backends.** The ledger read endpoints from D02-7. The money-order read endpoint from D03-2, but only if D09-4 records an approved way to reach it from the page. See S09-T03 for the options.
- **Quickstart flow.** Clone → environment file → build/pull → compose up healthy → run W1 through the D05-12 runner → observe the expected final state. The runner's own assertion is the definition of "completed W1".
- **Documentation links.** Values are linked, not copied (see [docs/README.md#source-of-truth](README.md#source-of-truth), rule 5). Reviewer-facing docs may *quote* a measured number only with a link to the results file and the git SHA it was measured on.

**Lifecycle behavior**

- **Explorer.** It is stateless, and a page reload resets it. Only one verify request runs at a time. Retries are user-initiated only, because verify can be expensive on long changelogs. Changelog paging uses the API's cursor, never "load all". A refresh shows the updated sequence marker so a stale view is obvious.
- **Study sessions.** A session interrupted by a system fault is recorded, not repeated silently. The protocol freezes after the pilot, and its hash goes in I.2.
- **Release.** A tag is immutable: never move or delete a pushed tag. A defect found after tagging is fixed in a new patch version. Rollback follows [docs/zerosum_ledger_mvp_plan.md#release-procedure](zerosum_ledger_mvp_plan.md#release-procedure).
- **Hosted demo (C01).** Deploy, health check and rollback follow [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd). Shutdown and reset follow the demo profile.

**Security and trust boundaries**

- **TB1 (Explorer).**
  - The reader token is typed into the page at runtime and held only in memory. It never goes into `localStorage`, cookies, URLs or logs.
  - All API data is rendered with `textContent` or equivalent, never `innerHTML`.
  - The page is served with a Content-Security-Policy that allows only same-origin connections (plus any origin D09-4 explicitly approves) and blocks framing.
  - No admin actions, such as quarantine retry, appear in the page.
- **TB4 (demo).** S09-C01 implements and deploys the `demo-public` profile (§0.3 O5). The admin and fault endpoints must be proven disabled by the profile test and from outside the VM.
- **Study data.** Only anonymized participant IDs. No names, emails or recordings of faces or voices are stored in the repository ([docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval), ethics).
- **Video and screenshots.** Use throwaway tokens generated for the recording. Never show a real `.env`, terminal history with secrets, or personal accounts.
- **Before tagging.** The S09-T07 tag workflow includes the secret scan required by [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets) (§0.3 O4). Tagging waits for a clean scan on the release candidate commit.

**Deployment constraints**

- **Local by default.** The release is local-first. The hosted demo is optional and never blocks G5.
- **Additive migrations only.** No S09 task adds a migration. If C01 needs a data reset, it uses the profile's reset mechanism, never manual SQL against append-only tables.

### D.4 Configuration ownership

| Authoritative value introduced by S09 | Planned location | Tracing |
|---|---|---|
| Explorer serving route and security headers (CSP, frame options) | ledger-service configuration or security config under `services/ledger-service/src/main/resources/` (resolve the actual path via D00-2) | `# decision: D09-4 — docs/step_09_demo_docs_release.md#decisions-and-outputs` |
| Explorer order-link base URL (only if an order-service origin is approved) | Same ledger-service configuration, injected into the page at serve time or configured in the page header comment | `# decision: D09-4 — …`; the matching allowance on order-service carries a D03-4 trace |
| Fresh-clone timing stages and image-cache prune (method per §0.3 O6) | Timing script under the tools layout (planned `tools/release/fresh-clone-timing.sh`) | Header comment `# decision: D09-1 — docs/step_09_demo_docs_release.md#decisions-and-outputs` |
| Study seeds, entity selection, Latin-square order, task sets | Planned `docs/evaluation/audit-study-protocol.md` (not under `docs/results/`, which holds measured evidence only) | Front matter line `decision: D09-5` |
| Integration test starting commit and "core files" definition | Planned `docs/evaluation/integration-test-brief.md` | Front matter line `decision: D09-6` |
| Release version string | The build location defined by D00-10 | `# decision: D09-9 — …` next to the value |
| Tag workflow: GHCR image publishing, migration test mode, secret-scan configuration (§0.3 O4) | Planned `.github/workflows/release.yml` (job structure per D00-5) | `# decision: D09-9 — …` in the workflow file |
| `demo-public` profile settings per service (C01 only; §0.3 O5) | Profile configuration in each service's resources (paths per D00-2) | `# decision: D09-10 — …` |
| Demo deploy configuration, backup schedule and retention (C01/C02 only) | Planned `infra/demo/` (compose override and backup job) | `# decision: D09-10 — …` |

**Permitted alternatives S09 must handle** (read the selected outcome from the owner's register; never assume the default):

| Alternative | Owner | Effect on S09 |
|---|---|---|
| SP3 fallback (Prometheus scraping, or the fallback Boot line) | D00-7 | Architecture diagram and observability description |
| SP1 outcome (batched apply becomes must-have) | D02-10 | Architecture doc, ADR-0005 status, bullet wording |
| G1 alternative: account-level optimistic locking | D02-4 (S02-C01) | Architecture doc and ADR index |
| SP2: Debezium replaces the polling relay | D04-7 → D03-5 (S04-C01) | Component diagram, ADR-0008 status, quickstart container list |
| G2 alternative to ADR-0006 | D03-6 (S05-C01) | Component diagram, data-flow description, ADR-0006 status |
| SP4 option (a), (b) or (c) | D07-6 | Architecture doc and throughput bullet |
| T1 missed | D07-5 | Throughput bullet dropped; README reports the measured ceiling |
| G4 violations unresolved or harness invalid | D08-5 | Affected claims removed; the release becomes `v0.9` with known issues |
| S5 hosted demo pursued or not | D09-10 (S09-C01) | If pursued, the `demo-public` profile and S09-C02 are done before tagging, and their checklist items become hard (§0.3 O5). The README lists the hosted URL only if it exists. |

<a id="phases-and-tasks"></a>
## E. Phases and tasks

| Phase | Tasks | Hours |
|---|---|---|
| Phase 1 — Documentation | S09-T01, S09-T02 | 4 |
| Phase 2 — Ledger Explorer | S09-T03 | 4 |
| Phase 3 — Human evaluation | S09-T04, S09-T05 | 4 |
| Phase 4 — Demo | S09-T06 | 2 |
| **Step total (master schedule)** | | **14** |
| Phase 5 — Release (contingency-funded) | S09-T07 | 2 (pre-allocated contingency, §0.3 O4) |

<a id="phase-1"></a>
### Phase 1 — Documentation

**Objective:** a reviewer can follow the README to a completed W1 scenario and can trace every must-have requirement to its evidence.
**Exit checkpoint:** the fresh-clone timing record exists (pass or honest miss); `docs/architecture.md` contains the traceability table with a row for every M1–M14 criterion; the ADR index and runbook are complete; change detection re-run and I.2 updated.

<a id="s09-t01"></a>
#### S09-T01 — README quickstart and timed fresh-clone test
- **Outcome:** A root README that states what the project is and shows its measured results, and a quickstart a fresh clone can follow to a completed W1 scenario. The quickstart has a recorded timing that proves or disproves M14(a).
- **Estimate:** 2 h
- **Inputs:**
  - M14(a) and R12 at [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) and [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register).
  - Honesty items at [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist).
  - Reference hardware at [docs/zerosum_ledger_mvp_plan.md#assumptions](zerosum_ledger_mvp_plan.md#assumptions).
  - M14(a) evidence method: §0.3 O6 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications).
  - Upstream decisions: D00-3, D00-8, D00-9, D00-10, D05-12, D07-5, D08-5.
  - README skeleton from [docs/step_00_foundations.md#s00-t06](step_00_foundations.md#s00-t06); scenario runner from [docs/step_05_instruments_fake_providers.md#s05-t13](step_05_instruments_fake_providers.md#s05-t13).
- **Depends on:** S00-T06, S00-T09, S05-T13, S08-T05
- **Instructions:**
  1. Read the actual quickstart commands from the owners: environment file (D00-8), build or image pull (D00-10, D00-5), compose startup and health (D00-3), and the W1 runner invocation and its pass signal (D05-12). Don't design new commands. If a step is missing (for example, no single command waits for health), record it in H.5 and raise a change request to the owner.
  2. Write the README in this order:
     - a one-paragraph description as a personal learning project using simulated providers, crediting Uber's published principles and not claiming Uber's implementation;
     - a results summary where each number links to its results file and commit SHA (from D07-5 and D08-5), with the confidence wording from [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes);
     - prerequisites, linked to the topology and assumption sections rather than copied;
     - the quickstart;
     - "what you just saw" (how to inspect balances through the Explorer or API);
     - links to the architecture doc, ADRs, OpenAPI specs, runbook, results directory and demo video;
     - limitations.
  3. Write the timing script (planned `tools/release/fresh-clone-timing.sh`). It clones into an empty directory, runs the documented commands verbatim, and prints an ISO-8601 timestamp at each stage boundary: clone done, build/pull done, stack healthy, W1 passed. It exits non-zero if any stage fails. Keep it a plain shell script with no new dependency.
  4. Apply the evidence method in §0.3 O6: run on the reference laptop from a fresh directory with the Docker image cache pruned. The script performs and logs the prune before the clock starts. Record the exact procedure in D09-1. Cite a CI e2e run on the same commit as supporting evidence only, never as the M14(a) result.
  5. Save the raw script output and a short write-up using the D00-9 template (planned `docs/results/quickstart-timing.md`). Link it from the README next to the quickstart.
- **Edge cases and failure behavior:**
  - The measured time exceeds the M14(a) limit. Record the miss with a per-stage breakdown and investigate the dominant stage (for example, image pulls versus local builds). Never edit the target or cherry-pick a warm run as the result. A change to how images are obtained is a change request to D00-5/D00-10. Version-tagged images exist only after tagging (§0.3 O4), so the timing measures the image path the quickstart documents at the release candidate commit.
  - Docker memory allocation is below what [docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology) requires. The README states the prerequisite, and the script fails early with a clear message instead of timing out.
  - Port collisions or a leftover stack from an earlier run. The script refuses to start if the compose project is already running, so the timing isn't contaminated.
  - CPU architecture differences between the laptop and the runner, such as multi-arch images. Record the architecture in the results file.
  - A results file changes after the README quotes it (for example, an S08-C01 rerun). The change-detection hash in I.2 flags it, and the summary is regenerated.
- **Outputs:** `README.md` (planned; final version), `tools/release/fresh-clone-timing.sh` (planned), `docs/results/quickstart-timing.md` (planned).
- **Verification and definition of done:**
  - The timing script exits 0 on the reference laptop after a logged image-cache prune in a fresh directory (§0.3 O6), and its output shows the W1 pass signal defined by D05-12.
  - The elapsed time is compared with M14(a), and the result (met or missed) is recorded in H.4.
  - Every number in the README links to a file under `docs/results/`. Check with a grep for digits in the results summary; each hit must have a link on the same line.
  - A second person or a clean directory reproduces the run without undocumented steps. Any question asked becomes a README fix.

<a id="s09-t02"></a>
#### S09-T02 — Architecture document, diagrams, ADR index, runbook, and M1–M14 traceability table
- **Outcome:** `docs/architecture.md` explains the *selected* architecture (not the master proposal) with current diagrams. It maps every M1–M14 acceptance criterion to a named passing test or results file. The ADR index shows final statuses, and `docs/runbook.md` covers every alert.
- **Estimate:** 2 h
- **Inputs:**
  - [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components), [docs/zerosum_ledger_mvp_plan.md#diagram](zerosum_ledger_mvp_plan.md#diagram), [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows), [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) (context only).
  - [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions), [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have), [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring).
  - All C.2 registers, especially H.4 evidence rows in each step and the selected alternatives listed in D.4.
  - Runbook stubs from [docs/step_07_observability_performance.md#s07-t03](step_07_observability_performance.md#s07-t03).
  - `docs/adr/`, `openapi/`.
  - §0.3 O6, E11 and C14 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications).
- **Depends on:** S09-T01 (for the M14(a) row), S07-T03, S08-T05
- **Instructions:**
  1. Build the traceability table first, since it is a hard release checklist item. Use one row per lettered criterion (M1(a) … M14(c)). Columns: criterion ID; evidence type (test or results file); the test's fully qualified name, or the results file path; the owning decision ID; and the evidence link and commit SHA taken from the owning step's H.4. Leave the Result column empty if an H.4 row says "Not run" or is missing. Never infer a pass from code existence. Per §0.3, M1(d) evidence covers O1–O7 plus the O8 variant (O6), and M6(c) evidence comes from S04's end-to-end tests (E11).
  2. Write `docs/architecture.md`:
     - purpose and non-goals (linked);
     - components and data ownership (one writer per table), reflecting the selected alternatives from D.4;
     - a Mermaid component diagram and Mermaid sequences for W1 and W4, redrawn to match the implementation (for example, include the SP2 or G2 alternative if one was selected);
     - trust boundaries TB1–TB5 with links to the implementing decisions;
     - crash points and recovery;
     - known limitations (the release checklist honesty list);
     - a section for adding a payment provider that points to D05-1, D05-10 and the ArchUnit boundary, which S09-T05 depends on.
  3. Clean up the ADR index (planned `docs/adr/README.md`). List ADR-0001…ADR-0010 with title, status, date and superseding ADR. Confirm that statuses updated upstream through change requests (for example ADR-0005 after D07-6, or ADR-0008 after D04-7) are reflected. Fix only index entries and cross-links. If an ADR body contradicts its register, raise a change request to that owner.
  4. Finalize `docs/runbook.md` from the S07 stubs. Add one section per alert rule in `infra/alerts/` (D07-3), with anchors matching the alert annotations. Each section has detection, immediate action (including the kill switches from D05-11 where money movement must freeze), diagnosis with the verifier (D06-5), fix with compensating orders only, and a record step. Link to commands and flags; don't copy them. The quarantine section documents the manual re-publish procedure owned by D04-4, because the retry endpoint is deferred (§0.3 C14).
  5. Link the published OpenAPI specs and explain how to open Swagger UI (M14(b)).
- **Edge cases and failure behavior:**
  - A criterion has no evidence row upstream. Mark the row "Missing evidence — owner Sxx", add an H.5 blocker, and notify the owner. Don't write the test in S09.
  - An alert rule has no runbook anchor, or an annotation points to a missing anchor. Add the section; if the alert annotation itself is wrong, raise a change request to D07-3.
  - Mermaid syntax errors. Render-check with the repository's Markdown preview or CI lint if one exists (D00-5). Otherwise syntax-review, and record that the diagram wasn't rendered.
  - The architecture doc starts restating versions or thresholds. Replace them with links to ADR-0002 or the owning register.
- **Outputs:** `docs/architecture.md` (planned), `docs/adr/README.md` (planned index), `docs/runbook.md` (planned final), Mermaid sources inline in the architecture doc.
- **Verification and definition of done:**
  - The traceability table has exactly one row per lettered M-criterion in the master, and each row links to a test name or file that exists at the release candidate commit. A small script or grep confirms every linked path exists.
  - Every alert rule in `infra/alerts/` has a matching runbook anchor (checked by listing alert annotations against runbook headings).
  - The ADR index lists ADR-0001…ADR-0010 with statuses matching the registers.
  - A Markdown link check over `README.md` and `docs/*.md` reports no broken relative links.

<a id="phase-2"></a>
### Phase 2 — Ledger Explorer

**Objective:** a reviewer or study participant can answer a W5 audit question in a browser, using only the ledger and order read APIs, and the walkthrough records its call count (M6(c) itself is verified in S04, §0.3 E11).
**Exit checkpoint:** `explorer.html` is served by ledger-service with the security headers from D09-4, a scripted W5 walkthrough is recorded against the compose stack, and the XSS and large-amount checks pass; change detection re-run.

<a id="s09-t03"></a>
#### S09-T03 — Ledger Explorer (`explorer.html`, S2)
- **Outcome:** A single self-contained HTML page served by ledger-service. It looks up an entity by exact ID (§0.3 C15), shows its balances, pages through its changelog, links each row to its money order and source idempotency key, and runs verify with a clear result.
- **Estimate:** 4 h
- **Inputs:**
  - [docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app), [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have) (S2).
  - M6 at [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have); TB1 at [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries).
  - Upstream decisions: D02-6, D02-7 (`openapi/ledger-service.yaml`), D03-2 (`openapi/order-service.yaml`), D03-4, D01-1, D01-5, D01-6.
  - Read APIs from [docs/step_02_ledger_core.md#s02-t04](step_02_ledger_core.md#s02-t04) and [docs/step_02_ledger_core.md#s02-t05](step_02_ledger_core.md#s02-t05); auth from [docs/step_03_order_service_outbox.md#s03-t03](step_03_order_service_outbox.md#s03-t03).
  - §0.3 C15 and E11 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications).
- **Depends on:** S02-T04, S02-T05, S02-T06, S03-T03, S03-T04
- **Instructions:**
  1. From the current OpenAPI files, list the exact endpoints, parameters, page limits and response fields the page needs. Record them in D09-4 as references to the spec revision, not copies.
  2. **Entity lookup.** Exact entity-ID input only; search is deferred (§0.3 C15). Validate client-side against the entity-ID rule owned by D01-5/D03-2, and show the server's 404 as "not found".
  3. **Balances panel.** Show each account's balance on its normal side exactly as returned, with the sequence marker the API provides (D02-7). Never recompute signs client-side (ADR-0003).
  4. **Changelog panel.** Page with the API cursor and page size. Each row shows sequence, order ID, account, currency, delta, balance after, and the source idempotency key (M6(a)). Add a "next page" control only; there is no bulk load.
  5. **Amount display.** Parse amounts so no precision is lost. If a value isn't a JavaScript safe integer, show the raw digits and flag it rather than rounding. Show integer minor units with the currency code. Format as a decimal only when the API response itself provides the minor-unit exponent. Never embed a copy of the ISO minor-unit table (D01-2 owns it) and never use floating-point division.
  6. **Order links.** Choose and record one option in D09-4:
     - (a) the order-service read endpoint is reachable from the page because D03-4 approves a cross-origin allowance for the Explorer origin (change request to S03 if not already present);
     - (b) show the order ID and a copyable request against the documented endpoint, with no cross-origin call;
     - (c) a ledger-service proxy endpoint (change request to S02, discouraged because it couples services).
     Default to (b) unless (a) already exists. Whichever you choose, record the walkthrough call count as supporting evidence for M6(c) (§0.3 E11).
  7. **Verify.** A button calls verify for the current entity and shows consistency, rows checked and the first bad sequence. An inconsistent result is highlighted prominently. Only one request runs at a time. Show elapsed time.
  8. **Token handling.** A password-type input for the reader token, kept in a JS variable only and sent as a bearer header. On 401/403, clear it and prompt again. Never put it in the URL, storage, cookies or console.
  9. **Rendering safety.** Build DOM nodes with `textContent` and attribute setters only. No `innerHTML`, no `eval`, no third-party script or font.
  10. **Serving.** Place the file where ledger-service serves static resources (resolve via D00-2). Configure response headers per D.3: a CSP restricting connections to self and the approved origins, framing denied, `nosniff`. Add the `# decision: D09-4` trace comment. If the inline script needs a CSP hash, compute it and record it next to the header configuration.
  11. Add one ledger-service integration test, using the test tags from D00-10. It asserts the page is served with status 200, the HTML content type and the configured security headers.
- **Edge cases and failure behavior:**
  - Empty changelog (an entity with no activity) shows an explicit "no entries" state, not an error.
  - A very long changelog pages without freezing. Verify on a large entity may take seconds, so show progress and never auto-retry.
  - An idempotency key, reason or metadata string containing HTML (such as `<img src=x onerror=…>`) renders as literal text.
  - A network failure or 5xx shows a retry button and leaves the previous data visible, marked stale.
  - Amounts at the maximum magnitude allowed by D01-1, and aggregated balances beyond the JavaScript safe-integer range, display exact digits.
  - With the `demo-public` profile (if C01 happens), the page works with whatever reader access that profile grants and shows no admin controls.
  - A page opened directly from disk (`file://`) can't call the APIs. Show a message telling the user to open it through the ledger-service URL.
- **Outputs:** `services/ledger-service/src/main/resources/static/explorer.html` (planned; actual path per D00-2), the security header configuration (planned), the ledger-service serving test (planned), and `docs/results/explorer-walkthrough.md` with screenshots and a HAR-derived call count (planned).
- **Verification and definition of done:**
  - The serving test passes in CI.
  - Scripted W5 walkthrough against the compose stack after running W1 and the W5 scenario (D05-12, D06-6):
    - starting from the driver entity in the W5 scenario, reach the source order and idempotency key;
    - the browser network log's call count is recorded in the walkthrough file as supporting evidence for M6(c), whose acceptance evidence comes from S04 (§0.3 E11);
    - verify reports consistent.
  - Negative check: against a hand-corrupted database copy (the S06 negative-test procedure in D06-5), verify shows inconsistent and the first bad sequence. If no corrupted copy is available, record this check as Not run.
  - XSS check: submit an order through the documented API with an HTML payload in the idempotency key, open its entity, and confirm the payload displays as text with no script execution or CSP violation in the console. If D03-2 accepts HTML characters in no free-text field that the Explorer renders, record that as the result.
  - A large-amount check using the maximum allowed entry amount shows exact digits.

<a id="phase-3"></a>
### Phase 3 — Human evaluation

**Objective:** produce honest, directional evidence for H3 (audit effort) and H4 (integration effort), or a documented "not run".
**Exit checkpoint:** `docs/results/audit-study.md` and `docs/results/integration-test.md` exist with results, a partial result clearly labeled, or "Not run" with the reason; the protocols are frozen and hashed in I.2; change detection re-run.

Participant time is external waiting time ([docs/zerosum_ledger_mvp_plan.md#waiting-time](zerosum_ledger_mvp_plan.md#waiting-time)). The builder's facilitation time is not: it is drawn from unallocated contingency when the sessions run (§0.3 O7 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)). Invitations may go out during S05/S06 ([docs/README.md#execution-order](README.md#execution-order)).

<a id="s09-t04"></a>
#### S09-T04 — Audit-task study: protocol, pilot, sessions, write-up
- **Outcome:** A frozen protocol, a pilot, the counted sessions specified by the master, and a write-up. The write-up reports individual data points and medians for conditions B, D and E against the H3 threshold, worded as directional only.
- **Estimate:** 3 h
- **Inputs:**
  - Study design, metrics, threshold and ethics at [docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval).
  - B0 at [docs/zerosum_ledger_mvp_plan.md#ablation](zerosum_ledger_mvp_plan.md#ablation); W5 at [docs/zerosum_ledger_mvp_plan.md#workflows](zerosum_ledger_mvp_plan.md#workflows).
  - Condition B access and facilitation budget: §0.3 E10 and O7 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications).
  - Upstream decisions: D00-4 (read-only role), D01-10 (seed reporting), D06-5 (verifier), D06-6 (W5 scenario), D08-1 (simulator), D08-3 (ablation flags), D09-4 (Explorer).
  - [docs/step_06_reconciliation_verifier.md#s06-t05](step_06_reconciliation_verifier.md#s06-t05), [docs/step_08_fault_injection_ablation.md#s08-t01](step_08_fault_injection_ablation.md#s08-t01), [docs/step_08_fault_injection_ablation.md#s08-t03](step_08_fault_injection_ablation.md#s08-t03).
- **Depends on:** S09-T03, S06-T05, S08-T01, S08-T03
- **Instructions:**
  1. Write the protocol (planned `docs/evaluation/audit-study-protocol.md`, decision D09-5). It covers:
     - participant criteria and the pilot participant, as in the master;
     - conditions B, D and E;
     - isomorphic task sets per condition, on different entities, using the master's example question types;
     - the Latin-square order matrix;
     - the per-task cap from the master;
     - timing (start when the question is shown, stop when an answer is committed);
     - success scoring against ground truth, confidence scale, wrong-answer counting;
     - facilitator script (what help is allowed);
     - stop rules and consent text.
  2. **Condition B (§0.3 E10).** Condition B runs on the B0 build (D08-3). Participants may use only current balances (the accounts table) and that run's service logs; the changelog, order store and Explorer are withheld. Record in D09-5 how the restriction is enforced: for example, a prepared balances-only query session under facilitator observation, or a balances-only read role requested from D00-4 through a change request. Never use an ad-hoc grant.
  3. **Prepare datasets.** Generate the ZeroSum dataset (conditions D and E) and the B0 dataset (condition B) with the simulator (D08-1), including W5-style histories (adjustments, returns, duplicate submissions). Record seeds per D01-10. Derive every task's ground-truth answer with the verifier and order store (D06-5) before any session, and store answers outside participant view.
  4. **Condition D access.** Provide read-only SQL through the existing read-only role (D00-4), with a one-page schema pointer that links to the migrations rather than copying DDL.
  5. **Pilot.** Run the pilot participant, fix ambiguities in tasks and instructions, then freeze the protocol and record its hash in I.2. Pilot data is not counted.
  6. **Counted sessions.** Record each task in a raw data sheet (planned `docs/results/audit-study-data.csv`): anonymized participant ID, condition, task ID, order position, time, success, confidence, wrong answers, facilitator notes. Log the builder's facilitation hours per session; they are charged to unallocated contingency (§0.3 O7), and the total goes in D09-5 and I.1.
  7. **Analysis.** Per condition: individual points and the median time and success rate. B→D is the data-model contribution and D→E the tooling contribution. Compare with the H3 threshold as written in the master. No significance tests or claims.
  8. **Write-up.** `docs/results/audit-study.md` using the D00-9 template, with protocol link and hash, dataset seeds, commit SHA, raw data link, results, deviations, threats to validity (small sample, peers familiar with the builder, learning effects), and an explicit "directional only" statement.
- **Edge cases and failure behavior:**
  - Fewer counted participants than the master specifies. Report the actual number, label the result partial, and don't substitute pilot data. With zero counted sessions, write "Not run" with the reason ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)).
  - A participant hits the cap. Record the task as unsuccessful at the cap time; don't extrapolate.
  - In condition B, a participant sees withheld data (changelog, orders or Explorer). Mark the task invalid, record the deviation, and tighten the enforcement recorded in D09-5 before the next session.
  - Remaining unallocated contingency can't cover the facilitation hours (§0.3 O7). Run fewer sessions and label the result partial, or write "Not run" with the reason; never shorten sessions below the protocol.
  - An Explorer or stack fault during a session. Log it, finish the session if possible, and flag the affected task. Don't fix code mid-study unless the fault invalidates a condition; if you do, record the protocol deviation and the commit.
  - A participant withdraws. Delete their data if they ask, and report the withdrawal count.
  - A ground-truth disagreement discovered after sessions (for example, a verifier bug). Mark the affected tasks invalid, report it, and raise an issue to the S06 owner.
  - The minimum cut is in force ([docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut)). The study isn't run; write the results file as "Not run — removed by minimum cut".
- **Outputs:** `docs/evaluation/audit-study-protocol.md` (planned), `docs/results/audit-study-data.csv` (planned), `docs/results/audit-study.md` (planned), seeded dataset generation commands recorded in the write-up (planned).
- **Verification and definition of done:**
  - The protocol hash in I.2 matches the file at the time of the first counted session.
  - Every row in the data sheet has a condition and order consistent with the Latin-square matrix.
  - Medians in the write-up recompute from the CSV (a short script or spreadsheet formula, recorded).
  - The write-up contains no significance claim and no personal data (grep for email or name patterns).
  - H4 is untouched here. The H3 outcome (met, not met, partial or not run) is recorded in H.4.

<a id="s09-t05"></a>
#### S09-T05 — Developer integration test: setup and write-up
- **Outcome:** A ready-to-run brief for peers to add a `FakeWallet` adapter using only the repository docs, a measurement sheet, and a write-up reporting the H4 metrics, or "Not run".
- **Estimate:** 1 h
- **Inputs:**
  - Developer integration test design and H4 threshold at [docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval); M7 at [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have); R13 at [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register).
  - Upstream decisions: D05-1, D05-2, D05-10; the "adding a payment provider" section from S09-T02.
  - Contract suite from [docs/step_05_instruments_fake_providers.md#s05-t06](step_05_instruments_fake_providers.md#s05-t06).
- **Depends on:** S09-T01, S09-T02, S05-T06
- **Instructions:**
  1. Write the brief (planned `docs/evaluation/integration-test-brief.md`, decision D09-6): the task as described in the master, the starting commit or tag, the time-tracking method, and the rule that only repository docs may be used. Log questions to the facilitator; each counts as a metric, and an answer must be a pointer to docs, never code.
  2. **Define "core files" before any session**, because the master is ambiguous about fake-provider endpoints. Default: core means every module except the new adapter package (M7(b) boundary from D05-1) and configuration. New endpoints inside `fake-providers` are counted and reported separately as "simulator changes", not as core changes. Record the definition and rationale in D09-6.
  3. Define measurement: wall-clock hours from start to contract suite green; the file list from `git diff --name-only <start>..<end>`, classified as adapter, configuration, simulator or core; the contract suite result (D05-10) on the peer's final commit; question count.
  4. After each peer session, write `docs/results/integration-test.md` with the D00-9 template: per-peer metrics, the classified file list, the comparison with the H4 threshold in the master, and doc gaps the peers hit (feed these back into README or architecture doc fixes and record the commits).
- **Edge cases and failure behavior:**
  - A peer changes a core file because an extension point is missing. That is a valid H4 finding (R13). Report it; don't revert it to pass.
  - The contract suite fails for reasons unrelated to the adapter (a flaky test). Rerun once and report both runs; money-path flakiness follows [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling).
  - A peer abandons the task. Report hours spent and the stopping point as a result, not as "not run".
  - No peers available. "Not run" with the reason.
- **Outputs:** `docs/evaluation/integration-test-brief.md` (planned), `docs/results/integration-test.md` (planned).
- **Verification and definition of done:**
  - The brief has been read by one person other than the builder, or by the pilot peer, with no unanswered setup questions.
  - The write-up's file classification recomputes from the recorded `git diff` output.
  - The contract suite result links to a CI run or a saved test report.
  - The H4 outcome is recorded in H.4.

<a id="phase-4"></a>
### Phase 4 — Demo

**Objective:** a demo video that shows the system working and the evidence behind its claims, within the M14(c) length.
**Exit checkpoint:** the video is published and linked from the README, and its script records the commit SHA and results revisions shown; change detection re-run.

<a id="s09-t06"></a>
#### S09-T06 — Demo video: script and recording
- **Outcome:** A recorded demo video within the M14(c) length. It walks from the problem through a live scenario, audit and observability views to the measured evidence and limitations, with every number on screen taken from results files.
- **Estimate:** 2 h
- **Inputs:**
  - M14(c) at [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have); [docs/zerosum_ledger_mvp_plan.md#workflows](zerosum_ledger_mvp_plan.md#workflows), [docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app), [docs/zerosum_ledger_mvp_plan.md#portfolio-value](zerosum_ledger_mvp_plan.md#portfolio-value).
  - Upstream decisions: D05-2, D05-12, D06-4, D07-2, D07-5, D08-5, D09-1, D09-4.
  - Dashboards from [docs/step_07_observability_performance.md#s07-t02](step_07_observability_performance.md#s07-t02).
- **Depends on:** S09-T01, S09-T03, S07-T02, S08-T05
- **Instructions:**
  1. Write the script (planned `docs/demo/script.md`, decision D09-7) with timed segments whose total fits M14(c):
     - (1) the problem in one sentence per failure class, from [docs/zerosum_ledger_mvp_plan.md#problem](zerosum_ledger_mvp_plan.md#problem);
     - (2) the quickstart command and W1 passing (a pre-warmed stack is allowed if the video says so; the timing claim links to T01's record);
     - (3) one trace across API → outbox → Kafka → ledger and the flow dashboard;
     - (4) W4: an unknown provider outcome resolved without a duplicate charge, using the fault knobs from D05-2;
     - (5) W5 in the Explorer, including verify;
     - (6) W6 reconciliation breaks;
     - (7) evidence: A0 versus ablation variants and the confidence wording, shown from the results files;
     - (8) limitations and "personal learning project, inspired by Uber's published principles".
  2. List in the script the commit SHA of the recorded build and the revision of every results file shown.
  3. Record with throwaway tokens and a clean terminal profile, at a readable font size. Use screenshots of dashboards from the provisioned D07-2 dashboards.
  4. Publish on a host that allows public, unlisted or public-link viewing without sign-in, and record the choice in D09-7. Add the link and the SHA to the README.
- **Edge cases and failure behavior:**
  - A scenario fails during recording. Stop and investigate; never splice a failing run to look like a pass. If it's a real defect, raise it to the owning step and re-record later.
  - A results file is regenerated after recording (for example, S08-C01). Change detection flags the video as "Needs review"; re-record the affected segment or add a dated correction in the README next to the link.
  - The video runs over the M14(c) limit. Cut segments (6 first, then 4), never speed up footage to fit.
  - Secrets appear on screen. Discard the take and rotate the throwaway tokens.
- **Outputs:** `docs/demo/script.md` (planned), screenshots under `docs/demo/` (planned), the published video URL in `README.md` (planned).
- **Verification and definition of done:**
  - The published video's duration is within M14(c) as displayed by the host, recorded in H.4.
  - Every number spoken or shown matches the listed results file revision (checked segment by segment against the script).
  - No token, `.env` content or personal data is visible (checked by one full watch-through).
  - The README link opens without sign-in in a private browser window.

<a id="phase-5"></a>
### Phase 5 — Release (contingency-funded, 2 h)

**Objective:** run the tag CI workflow (§0.3 O4), evaluate G5 honestly, tag the release the gates allow, and publish release notes and résumé bullets derived only from results.
**Exit checkpoint:** every release checklist item is checked with an evidence link, or recorded as failed or soft-missed; the tag exists; H.6 is completed; change detection re-run before handoff.

<a id="s09-t07"></a>
#### S09-T07 — Release execution: tag CI workflow, checklist, tag, notes, résumé bullets
- **Outcome:** The tag CI workflow exists and runs (version-tagged GHCR images, migration test, secret scan; §0.3 O4), the release checklist is completed with evidence links, the gate G5 result is recorded, the permitted version is tagged, and release notes and a résumé bullets draft are written strictly from results files. Funded from pre-allocated contingency ([docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates); §0.3 O4 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)).
- **Estimate:** 2 h
- **Inputs:**
  - [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist), [docs/zerosum_ledger_mvp_plan.md#release-procedure](zerosum_ledger_mvp_plan.md#release-procedure), [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go), [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) (G4, G5), [docs/zerosum_ledger_mvp_plan.md#portfolio-value](zerosum_ledger_mvp_plan.md#portfolio-value), [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes), [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets), [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd).
  - Upstream decisions: D00-5, D00-8, D00-10, D07-5, D08-3, D08-5, D08-6, D09-10 (if S09-C01 ran), and all traceability evidence from S09-T02.
  - Perf results from [docs/step_07_observability_performance.md#s07-t05](step_07_observability_performance.md#s07-t05); chaos and ablation results from [docs/step_08_fault_injection_ablation.md#s08-t05](step_08_fault_injection_ablation.md#s08-t05).
- **Depends on:** S09-T01, S09-T02, S09-T03, S09-T04, S09-T05, S09-T06 (T03–T05 may end as documented "Not run" soft items); S09-C02 when S09-C01 is pursued (the drill precedes tagging, §0.3 O5)
- **Instructions:**
  1. **Tag CI workflow (§0.3 O4).** Write the tag-triggered workflow (planned `.github/workflows/release.yml`, following the D00-5 job structure; decision D09-9). On a version tag it:
     - builds the service images and pushes them to GHCR with the version tag (nothing is published on merges to main in the MVP);
     - runs the migration test in the mode §0.3 O4 defines (fresh install for `v1.0.0`; upgrade from the previous tag for later releases);
     - runs the secret scan;
     - publishes the release notes from step 7.
     S09-C01 adds the optional demo deploy job. Dry-run the workflow by manual dispatch with publishing disabled, and link the run in H.4.
  2. Copy the release checklist into a release record (planned `docs/release/v1.0.0-checklist.md`, or the `v0.9` name if applicable). For each item, add the evidence link (test, CI run, results file) and a status: passed, failed, soft-missed, or not applicable with the reason (for example, the `demo-public` profile and restore drill items when S5 isn't pursued, §0.3 O5).
  3. **Hard-gate evaluation.** Confirm every hard gate at [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go) on the *release candidate commit*: CI green on all layers (D00-5), the nightly e2e job green on that commit, and results files valid per D08-5 and the D08-6 run plan. Confirm the chaos startup guard test exists and passes (D08-3); if it doesn't exist upstream, record a blocker and raise a change request. If S09-C01 was pursued, confirm its `demo-public` profile test passed and the S09-C02 drill is recorded, because both items are then hard (§0.3 O5).
  4. **Secret scan before tagging.** Run the workflow's secret-scan configuration on the release candidate commit and record its output. Don't tag until it is clean.
  5. **Decide the version** per G5 and G4. All hard items pass → `v1.0.0`. Any hard item fails, or G4 violations remain unfixed → `v0.9` with a known-issues section and the affected claims removed. Record the decision in D09-9 with rationale.
  6. Set the version string at the D00-10 location, tag the release commit, and let the tag workflow run. Record in D09-9 the pushed image references and the migration test mode that ran.
  7. **Release notes** (planned `docs/release/<version>.md`, mirrored in the GitHub release): scope delivered, selected alternatives (D.4), the results summary with links, soft targets missed, and the limitations list from the checklist honesty section.
  8. **Résumé bullets** (planned `docs/release/resume-bullets.md`, decision D09-8). Start from the templates at [docs/zerosum_ledger_mvp_plan.md#portfolio-value](zerosum_ledger_mvp_plan.md#portfolio-value). Fill each placeholder only from a results file, and put the link and SHA beside each number. Drop the throughput bullet if D07-5 records T1 as missed. Drop or reword any correctness bullet whose ablation failed its validity rule (D08-5, D08-6). Use the rule-of-three wording, never "zero failures guaranteed". Frame it as a personal project with simulated providers.
  9. Fill H.6 and the G checklist, and complete section J.
- **Edge cases and failure behavior:**
  - The tag workflow fails after the tag is pushed. Don't move or delete the tag. Fix forward with a patch version, record both in D09-9, and mark any images already pushed for the failed version as not released in the release notes.
  - The GHCR push fails (permissions or registry error). Fix the workflow credentials following D00-8 and re-run the failed jobs for the same tag; re-running jobs doesn't move the tag.
  - The migration test runs in the wrong mode (for example, an upgrade attempt with no previous tag). Treat it as a workflow bug: fix it and re-run before recording G5.
  - A results file changes between the checklist walk-through and tagging. Change detection forces the checklist rows to be redone.
  - The secret scan finds a secret. Stop the release, rotate the secret, and purge it following the S00 conventions (D00-8). Release only after a clean scan.
  - A soft item is missing (Explorer, study, integration test, hosted URL). Release proceeds with an honest note ([docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates), G5 narrow scope).
  - A bullet placeholder has no results source. Delete the bullet; never estimate.
  - Hours run over 2 h. Record the overrun against unallocated contingency; don't skip checklist items.
- **Outputs:** `.github/workflows/release.yml` (planned), version-tagged images in GHCR (planned), `docs/release/<version>-checklist.md` (planned), `docs/release/<version>.md` and the GitHub release (planned), the git tag (planned), `docs/release/resume-bullets.md` (planned).
- **Verification and definition of done:**
  - The manual dry-run of the tag workflow is green and linked in H.4.
  - Every hard checklist item has an evidence link that resolves at the tagged commit (link check).
  - The tag workflow run for the release tag is green and linked in H.4: GHCR lists the images with the version tag, the migration test log shows the mode §0.3 O4 requires, and the secret scan passed.
  - A grep of `resume-bullets.md` and the README for digits shows a results link on every line with a number.
  - G5 is recorded in H.6 as passed (`v1.0.0`) or failed (`v0.9`), with rationale.

<a id="conditional-work"></a>
### Conditional and deferred work

<a id="s09-c01"></a>
#### S09-C01 — S5 hosted demo (optional)
- **Outcome:** A `demo-public` profile implemented and tested in every service (§0.3 O5), and a public, read-mostly demo running it, with a URL in the README, deployed and rolled back through CI.
- **Estimate:** 0 h
- **Inputs:**
  - S5 at [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have); [docs/zerosum_ledger_mvp_plan.md#environments](zerosum_ledger_mvp_plan.md#environments), [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets), [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd), [docs/zerosum_ledger_mvp_plan.md#spending-controls](zerosum_ledger_mvp_plan.md#spending-controls) (quotas and resource caps), TB4 at [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries), R10 at [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register).
  - Upstream decisions: D00-2, D00-3, D00-5, D00-8, D03-4, D05-2, D08-3.
  - §0.3 O4 (images publish on tags only) and O5 (profile ownership, drill before tagging) in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications); profile rules at [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting).
- **Depends on:** S09-T01, S09-T06 (runs before S09-T07, because S09-C02 must precede tagging, §0.3 O5)
- **Instructions:**
  1. **Trigger (evidence):** D09-10 records a showcase-value decision to pursue S5. For example, S09-T05 peers or other reviewers reported quickstart friction, or the builder wants a live link for applications. Pursuing S5 makes the `demo-public` profile item and the restore drill hard for this release (§0.3 O5), so decide before Phase 5. If contingency can't cover S09-C01 and S09-C02 before tagging, don't pursue it. Cost isn't a criterion ([docs/zerosum_ledger_mvp_plan.md#constraint-updates](zerosum_ledger_mvp_plan.md#constraint-updates)).
  2. **Funding:** unallocated contingency, 3 h as given in the master roadmap ([docs/zerosum_ledger_mvp_plan.md#weekly-roadmap](zerosum_ledger_mvp_plan.md#weekly-roadmap)). Confirm and record the remaining contingency before starting.
  3. Provision the environment described at [docs/zerosum_ledger_mvp_plan.md#environments](zerosum_ledger_mvp_plan.md#environments), including its memory adjustments (no chaos container; remote observability export). Record the target in D09-10.
  4. **Implement the `demo-public` profile (§0.3 O5).** In each service, add the profile settings that TB4 and [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) require: admin and fault endpoints disabled, writer tokens disabled except the single rate-limited demo token, and the chaos profile refused. Configure the auth module (D03-4), the fault endpoints (D05-2) and the chaos guard (D08-3); a code change inside those modules is a change request to their owners. Add a test per service that starts it with the profile and asserts the disabled endpoints respond as the profile defines. Trace settings with `# decision: D09-10`.
  5. **Deploy job.** Add the optional demo deploy job to the S09-T07 tag workflow (§0.3 O4): pull, up, health wait, W1 smoke with the demo token, and automatic redeploy of the previous tag on failure. Images publish on tags only, so the pre-tag deployment used here and by S09-C02 builds images from the release candidate commit on the VM (record this in D09-10). After tagging, the deploy job moves the VM to the version-tagged images.
  6. Store secrets per [docs/zerosum_ledger_mvp_plan.md#secrets](zerosum_ledger_mvp_plan.md#secrets). Put the demo compose override and backup job under planned `infra/demo/` with `# decision: D09-10` traces.
  7. Apply the public-demo quotas, resource caps and nightly reset from [docs/zerosum_ledger_mvp_plan.md#spending-controls](zerosum_ledger_mvp_plan.md#spending-controls). Its usage-measurement and invoice-reconciliation bullets are cost tracking and are not performed.
  8. Configure nightly per-database dumps per [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery). This triggers S09-C02.
- **Edge cases and failure behavior:**
  - The profile test fails in any service. Don't deploy. Fix the profile configuration, or raise a change request if the owning module can't be configured that way.
  - The health check fails after deploy. CI redeploys the previous tag. If there is no previous tag on the VM, take the stack down rather than leave it half-up.
  - Abuse or quota exhaustion. The rate limit returns 429 as designed; the nightly reset restores data. Never raise limits without an R10 re-assessment.
  - Hosting account verification is delayed (external). C01 stays Blocked; the release is unaffected.
- **Outputs:** `demo-public` profile settings and tests in each service (planned), `infra/demo/` compose override and backup job (planned), demo deploy job in the tag workflow (planned), hosted URL in `README.md` (planned), `docs/results/demo-deploy.md` with smoke and exposure checks (planned).
- **Verification and definition of done:**
  - The `demo-public` profile test passes in every service.
  - From outside the VM, the W1 smoke scenario passes with the demo token.
  - Admin and fault endpoints return the disabled response defined by the profile.
  - A burst above the quota yields 429 (R10 check).
  - A deliberately broken deploy rolls back automatically, with the CI run linked.
  - S09-C02 is scheduled before tagging.

<a id="s09-c02"></a>
#### S09-C02 — Restore drill (only with S09-C01)
- **Outcome:** A measured restore of the demo databases from backup onto a fresh stack, verified by the verifier, with the recovery time recorded.
- **Estimate:** 0 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery), [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist) (operations item for the demo VM); upstream decisions D06-5 (verifier), D00-4 (roles), D09-10; §0.3 O5 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications).
- **Depends on:** S09-C01
- **Instructions:**
  1. **Trigger (evidence):** S09-C01 is executed and a demo VM with nightly dumps exists (D09-10).
  2. **Funding:** unallocated contingency, with the hours given in §0.3 O5. Confirm the contingency remaining before starting.
  3. Take the latest nightly dump set, restore it onto a fresh local stack or a fresh VM using the owner-defined roles (D00-4), and start the services.
  4. Run the verifier (D06-5) after quiesce and record its JSON output.
  5. Measure recovery time from "restore started" to "verifier passed" with timestamps, and write `docs/results/restore-drill.md` with the D00-9 template.
  6. **Ordering with the release.** Run and record the drill before S09-T07 tags the release (§0.3 O5); the T07 checklist record links this result.
- **Edge cases and failure behavior:**
  - The restore fails or the verifier reports violations. Record it as a failed drill, never as passed. Keep the demo running only if its own verifier run is clean, and raise an issue.
  - The dumps are incomplete (a missing database). Fix the backup job in `infra/demo/` and repeat. The first failed drill stays in the record.
- **Outputs:** `docs/results/restore-drill.md` (planned), verifier output file (planned).
- **Verification and definition of done:** The verifier exits successfully on the restored stack, the measured recovery time and the dump timestamps are recorded in H.4, and the checklist item is updated.

**Deferred improvements** (not planned in this step; recorded for post-MVP)

- Stripe test-mode adapter as a third provider, reusing the S09-T05 brief and findings: [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred).
- TigerBeetle backend comparison with a benchmark: [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred).
- Synchronous in-session payments, Kubernetes/Helm, partitioning and archival, multi-region: [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred).
- S4 hot-entity sharding, if not adopted in S07: [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have).
- S5 hosted demo, if C01 isn't pursued: [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have).
- Explorer entity search, deferred per §0.3 C15 (a future endpoint would be owned by D02-7), and any UI polish beyond a diagnostic page, which stays a non-goal ([docs/zerosum_ledger_mvp_plan.md#non-goals](zerosum_ledger_mvp_plan.md#non-goals)).
- Ledger replay tool: conditional per [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery), with no step assignment.

<a id="risks-and-recovery"></a>
## F. Risks and recovery

| Risk | Detection (signal or test) | Recovery / fallback |
|---|---|---|
| Study participants unavailable (master S09 risk; waiting item at [docs/zerosum_ledger_mvp_plan.md#waiting-time](zerosum_ledger_mvp_plan.md#waiting-time)) | Fewer confirmations than the protocol needs by the start of Phase 3 | Run what is possible and label it partial; otherwise write "Not run" with the reason. Release anyway: H3/H4 are soft ([docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go)). |
| Fresh-clone quickstart misses M14(a) (R12 at [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register)) | S09-T01 per-stage timestamps from a pruned-cache run (§0.3 O6) | Attack the dominant stage. Pulling prebuilt images instead of building locally is a change request to D00-5/D00-10 (images publish on tags only, §0.3 O4). It is a hard gate, so `v1.0.0` waits or the release becomes `v0.9`. The target is never edited. |
| Overclaiming in README, video or bullets (R7) | Digit-to-link grep; the results revision list in the video script; T07 checklist honesty items | Remove or reword the claim. Use the confidence wording from [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes). Drop bullets without a results source. |
| Upstream results change after S09 documents them (S07-C01 or S08-C01 reruns) | I.2 hash mismatch during change detection | Mark S09-T01/T02/T06/T07 "Needs review", regenerate summaries, re-record the affected video segment or add a dated correction. |
| Docs duplicate mutable values that later drift | Review for version numbers or thresholds in `docs/architecture.md` and the README; change detection | Replace them with links to the owning register or ADR ([docs/README.md#source-of-truth](README.md#source-of-truth)). |
| Explorer XSS or token leakage (TB1 at [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries)) | S09-T03 XSS check; grep of the page for `innerHTML`, `localStorage`, `document.cookie`; CSP violations in the console | Fix before release. If unresolved, release without linking the Explorer (S2 is soft) and record the limitation. |
| Explorer needs a cross-origin order read the APIs don't provide (search is deferred, §0.3 C15) | T03 step 1 endpoint inventory; W5 walkthrough | Order-link option (b). A change request to D03-4 only if evidence shows the page fails W5 without it. |
| Precision loss when showing large amounts in JavaScript | T03 large-amount check | Show raw digits and flag them; never round. |
| Condition B participants reach withheld data: changelog, orders or Explorer (§0.3 E10 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)) | Facilitator observation; enforcement check in the pilot | Invalidate the task, tighten the enforcement recorded in D09-5, report the deviation. |
| Facilitation hours exhaust unallocated contingency (§0.3 O7) | Hours logged per session versus remaining contingency | Stop scheduling sessions and report partial results; never shorten sessions. |
| Wrong study ground truth | Verifier cross-check of every answer before sessions | Invalidate affected tasks, report them, raise an issue to S06. |
| A hard gate fails at release ([docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) G5) | S09-T07 checklist walk-through | Tag `v0.9` with a known-issues section and remove the affected claims; never weaken the gate. |
| Tag workflow misbehaves: wrong migration test mode, failed GHCR push, or skipped secret scan (§0.3 O4) | Manual dry-run of the tag workflow; job logs for the tag | Fix the workflow before tagging; after a pushed tag, fix forward with a patch version. |
| Secret exposed in the repository or on video | Secret scan before tag; full watch-through of the video | Rotate, purge following D00-8, discard the take, re-scan before tagging. |
| Hosted demo abuse or exposure (R10; C01 only) | 429 burst check; external probe of admin and fault endpoints | Take the demo down and fix the profile (S09-C01 owns it, §0.3 O5). A pushed release stands; the demo returns only after the fix. |
| Restore drill fails (C02) | Verifier exit status on the restored stack | Record it as failed; fix the backup job; repeat. Never report a pass. |
| Capacity shortfall at S09 (R11) | Cumulative hours versus [docs/zerosum_ledger_mvp_plan.md#schedule-overview](zerosum_ledger_mvp_plan.md#schedule-overview) | Apply [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut): keep T01, T02, T06 and T07; record T03–T05 as removed by the cut. |

<a id="acceptance-checklist"></a>
## G. Acceptance checklist

- [ ] **M14(a):** the recorded fresh-clone timing on the reference laptop, from a fresh directory with the Docker image cache pruned (§0.3 O6 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)), shows a completed W1 scenario within the limit in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have). A CI e2e run is supporting evidence only. This is hard gate 5 in [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go).
- [ ] **M14(b):** `docs/architecture.md`, the ADR index (ADR-0001…ADR-0010) and the three OpenAPI specs are published, match the implementation, and are linked from the README.
- [ ] **M14(c):** the demo video is within the length in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) and is linked from the README.
- [ ] Every M1–M14 acceptance criterion maps to a named passing test or results file in the traceability table ([docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist), Functionality).
- [ ] `docs/runbook.md` covers every alert defined in D07-3 ([docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring)).
- [ ] *(soft, S2)* The Explorer is served with the D09-4 security headers. The W5 walkthrough call count is recorded (M6(c) acceptance evidence is S04's, §0.3 E11), and the XSS and large-amount checks pass; or the Explorer is recorded as soft-missed ([docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have)).
- [ ] *(soft, H3)* `docs/results/audit-study.md` reports results against the H3 threshold in [docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval) as directional only, or "Not run" with the reason.
- [ ] *(soft, H4)* `docs/results/integration-test.md` reports results against the H4 threshold in [docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval), or "Not run" with the reason.
- [ ] Every number in the README, release notes and résumé bullets links to a results file. Confidence statements follow [docs/zerosum_ledger_mvp_plan.md#sample-sizes](zerosum_ledger_mvp_plan.md#sample-sizes). The throughput bullet is absent if T1 was missed ([docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go)).
- [ ] The honesty items in [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist) hold: personal project with simulated providers, Uber credited for principles only, limitations listed.
- [ ] The A0 evidence for invariants I1–I12 ([docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants)) and the ablation validity cited in release materials come from D08-5 at the recorded revision.
- [ ] The secret scan is clean before tagging, and the chaos startup guard test passes on the release commit ([docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist), Security and hygiene). The `demo-public` profile test is required only if S5 is pursued (§0.3 O5).
- [ ] All hard gates in [docs/zerosum_ledger_mvp_plan.md#go-no-go](zerosum_ledger_mvp_plan.md#go-no-go) pass on the release commit, and the S09-T07 tag workflow is green: GHCR images carry the version tag, the migration test ran in the mode §0.3 O4 defines, and the secret scan passed.
- [ ] **G5** ([docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates)) is evaluated and recorded in H.6: `v1.0.0` tagged, or `v0.9` with known issues.
- [ ] If S09-C01 ran: the `demo-public` profile test passes, the demo is deployed with it, exposure and quota checks pass, and the restore drill is recorded before tagging ([docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery); §0.3 O5).
- [ ] Section H is filled with actual paths and evidence, I.1 and I.2 are current, and change detection was re-run before handoff ([docs/README.md#change-detection](README.md#change-detection)).

<a id="decisions-and-outputs"></a>
## H. Decisions and outputs register

### H.1 Decisions and rationale

| ID | Decision | Rationale | Alternatives considered | Status | Date |
|---|---|---|---|---|---|
| D09-1 | — | — | — | Pending | — |
| D09-2 | — | — | — | Pending | — |
| D09-3 | — | — | — | Pending | — |
| D09-4 | — | — | — | Pending | — |
| D09-5 | — | — | — | Pending | — |
| D09-6 | — | — | — | Pending | — |
| D09-7 | — | — | — | Pending | — |
| D09-8 | — | — | — | Pending | — |
| D09-9 | — | — | — | Pending | — |
| D09-10 | — | — | — | Pending | — |

### H.2 Implementation and configuration locations

| Item | Planned path | Actual path | Traced to |
|---|---|---|---|
| README (final) | `README.md` | — | D09-1 |
| Fresh-clone timing script | `tools/release/fresh-clone-timing.sh` | — | D09-1 |
| Architecture document and diagrams | `docs/architecture.md` | — | D09-2 |
| Runbook (final) | `docs/runbook.md` | — | D09-2 (stubs: D07-3) |
| ADR index | `docs/adr/README.md` | — | D09-3 |
| Ledger Explorer page | `services/ledger-service/src/main/resources/static/explorer.html` | — | D09-4 |
| Explorer security header configuration | `services/ledger-service/src/main/resources/` (config or security class per D00-2) | — | D09-4 |
| Explorer serving test | ledger-service test source set (per D00-10) | — | D09-4 |
| Audit study protocol | `docs/evaluation/audit-study-protocol.md` | — | D09-5 |
| Integration test brief | `docs/evaluation/integration-test-brief.md` | — | D09-6 |
| Demo script | `docs/demo/script.md` | — | D09-7 |
| Release version string | Location defined by D00-10 | — | D09-9 |
| Demo deploy override and backup job (C01/C02) | `infra/demo/` | — | D09-10 |
| Tag CI workflow (GHCR images, migration test, secret scan) | `.github/workflows/release.yml` | — | D09-9 |
| Demo deploy job in the tag workflow (C01) | `.github/workflows/release.yml` | — | D09-10 |
| `demo-public` profile settings and tests (C01) | Each service's resources and test source set (per D00-2, D00-10) | — | D09-10 |

### H.3 Produced artifacts

| Artifact | Planned path | Actual path | Revision/hash |
|---|---|---|---|
| Quickstart timing record | `docs/results/quickstart-timing.md` | — | — |
| Explorer walkthrough (screenshots, call count) | `docs/results/explorer-walkthrough.md` | — | — |
| Audit study raw data | `docs/results/audit-study-data.csv` | — | — |
| Audit study write-up | `docs/results/audit-study.md` | — | — |
| Integration test write-up | `docs/results/integration-test.md` | — | — |
| Demo screenshots | `docs/demo/` | — | — |
| Demo video | URL recorded in `README.md` | — | — |
| Release checklist record | `docs/release/<version>-checklist.md` | — | — |
| Release notes | `docs/release/<version>.md` and GitHub release | — | — |
| Résumé bullets draft | `docs/release/resume-bullets.md` | — | — |
| Release tag | git tag (`v1.0.0` or `v0.9`) | — | — |
| Version-tagged service images | GHCR (references recorded in D09-9) | — | — |
| Hosted demo deploy record (C01) | `docs/results/demo-deploy.md` | — | — |
| Restore drill record (C02) | `docs/results/restore-drill.md` | — | — |

### H.4 Validation results and evidence

| Check | Method | Result | Evidence path | Date |
|---|---|---|---|---|
| M14(a) fresh-clone to W1 | S09-T01 timing script on the reference laptop | Not run | — | — |
| README numbers linked to results | Digit-to-link grep | **Not run** — the README was written so every number cites an executed run, but no automated check enforces it | — | — |
| Traceability table complete (M1–M14) | Compiled from `docs/results/**`, scope-decisions and test sources; plan text never treated as evidence | **Passed** — 44 lettered sub-criteria: 20 MET, 7 PARTIAL, 16 NOT MET, 1 UNKNOWN | [docs/architecture.md](architecture.md) | 2026-09-16 |
| End-to-end money path against the running stack | `./gradlew :infra:tests:e2eTest --rerun-tasks` on the Compose stack | **Passed** — 1 test, 0 failures, 0.718 s | [docs/results/s09/e2e-money-path.md](results/s09/e2e-money-path.md) | 2026-09-17 |
| Runbook covers every alert | Alert annotations versus runbook anchors | Not run | — | — |
| Docs link check | Markdown relative-link check | Not run | — | — |
| Explorer serving and headers | ledger-service integration test | Not run | — | — |
| Explorer W5 walkthrough and M6(c) call count | Scripted walkthrough, browser network log | Not run | — | — |
| Explorer verify on corrupted copy | S06 negative-test procedure | Not run | — | — |
| Explorer XSS and large-amount checks | Payload order; maximum-amount order | Not run | — | — |
| H3 audit study | Protocol D09-5 | Not run | — | — |
| H4 integration test | Protocol D09-6 | Not run | — | — |
| M14(c) video length and content | Host duration; segment-by-segment results check; watch-through for secrets | Not run | — | — |
| Secret scan before tag | Scanner output | Not run | — | — |
| Hard gates on release commit | Checklist walk-through with CI links | Not run | — | — |
| Tag CI workflow dry-run | Manual dispatch with publishing disabled | Not run | — | — |
| Tag workflow for the release tag (GHCR images, migration test mode, secret scan) | CI run for the tag | Not run | — | — |
| G5 | [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates) | Not run | — | — |
| `demo-public` profile test (C01) | Per-service profile test | Not run | — | — |
| Hosted demo smoke, exposure and quota (C01) | External probes | Not run | — | — |
| Restore drill (C02) | Verifier on the restored stack | Not run | — | — |

### H.5 Known limitations and blockers

| Item | Type (limitation/blocker) | Impact | Owner/next action |
|---|---|---|---|
| Single-broker Kafka, static tokens, no holds, FX or disputes, payout/adjustment race (R1 metric), FakeBank quiet-period assumption (inherited from [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist) honesty items) | limitation | Must appear in the README and release notes | S09-T01, S09-T07 |
| Explorer entity search had no API behind it ([docs/zerosum_ledger_mvp_plan.md#demo-app](zerosum_ledger_mvp_plan.md#demo-app)) | limitation | The Explorer offers exact entity-ID lookup; search is deferred | Resolved in master v1.2 (§0.3 C15) |
| Study condition B was described as a "mutable balance table" that no build produces ([docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval)) | limitation | Condition B restricts access instead; enforcement is recorded in D09-5 | Resolved in master v1.2 (§0.3 E10) |
| The `demo-public` profile test was hard with no implementing step | blocker (potential) | None: hard only if S5 is pursued, and implemented in S09-C01 | Resolved in master v1.2 (§0.3 O5) |
| No previous release schema for the first tag's migration test; no owner for GHCR publishing or the pre-tag secret scan ([docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd)) | limitation | The tag workflow belongs to S09-T07 and uses the migration test mode O4 defines | Resolved in master v1.2 (§0.3 O4) |
| M14(a) evidence machine (laptop or CI) and M1(d) scope ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)) | limitation | The pruned-cache laptop run is the evidence and CI supports it; M1(d) covers O1–O7 plus the O8 variant | Resolved in master v1.2 (§0.3 O6) |
| Restore drill had no hours and a conflicting order relative to tagging ([docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery)) | limitation | Hours and before-tag ordering are fixed | Resolved in master v1.2 (§0.3 O5) |
| Audit-study facilitation time was unbudgeted ([docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval)) | limitation | Facilitation draws on unallocated contingency when sessions run | Resolved in master v1.2 (§0.3 O7) |
| Images publish on tags only (§0.3 O4), but the demo VM and its restore drill must exist before tagging (§0.3 O5) | limitation | The pre-tag demo runs images built from the release candidate commit | S09-C01 records the build path in D09-10; raise a master change request if that isn't acceptable |

### H.6 Completion status

| Field | Value |
|---|---|
| Step status | **Partially delivered.** S09-T01 (README) and S09-T02 (`docs/architecture.md` with the M1–M14 traceability table) are done, plus the first `@Tag("e2e")` test, which was not a named S09 task but closed CI's hollow e2e job. S09-T03 (explorer), T04 and T05 (human-subject studies), T06 (video), T07 (release tag), C01 and C02 are **not started**. |
| Gate result | **Not evaluated.** G5 requires the hard gates in the release checklist, which has not been walked. |
| Completed on | — (step not complete) |
| Completed by | — |
| Handoff accepted by next step | — |

<a id="execution-record"></a>
## I. Execution and change record

### I.1 Task execution record

| Task ID | Status | Output paths | Evidence | Blockers |
|---|---|---|---|---|
| S09-T01 | Planned | — | — | — |
| S09-T02 | Planned | — | — | — |
| S09-T03 | Planned | — | — | — |
| S09-T04 | Planned | — | — | — |
| S09-T05 | Planned | — | — | — |
| S09-T06 | Planned | — | — | — |
| S09-T07 | Planned | — | — | — |
| S09-C01 | Planned | — | — | — |
| S09-C02 | Planned | — | — | — |

<a id="change-record"></a>
### I.2 Consumed sources and change record

| Source/artifact path | Revision or hash | Recorded at | Affected tasks | Review outcome |
|---|---|---|---|---|
| `docs/zerosum_ledger_mvp_plan.md` (v1.2) | — | 2026-09-15 | T01, T02, T03, T04, T07, C01, C02 | Resolved in master v1.2 (§0.3 C15, E10, E11, O4, O5, O6, O7); this document updated to v1.1 |
| `docs/README.md` | — | — | All | — |
| `docs/step_00_foundations.md` (register) | — | — | T01, T02, T07, C01 | — |
| `docs/step_01_domain_contracts.md` (register) | — | — | T02, T03, T04 | — |
| `docs/step_02_ledger_core.md` (register) | — | — | T02, T03 | — |
| `docs/step_03_order_service_outbox.md` (register) | — | — | T02, T03 | — |
| `docs/step_04_kafka_pipeline.md` (register) | — | — | T02 | — |
| `docs/step_05_instruments_fake_providers.md` (register) | — | — | T01, T02, T05, T06 | — |
| `docs/step_06_reconciliation_verifier.md` (register) | — | — | T02, T04, T06, C02 | — |
| `docs/step_07_observability_performance.md` (register) | — | — | T01, T02, T06, T07 | — |
| `docs/step_08_fault_injection_ablation.md` (register) | — | — | T01, T02, T04, T06, T07 | — |
| `README.md` (S00 skeleton) | — | — | T01 | — |
| `docker-compose.yml`, `.env.example` | — | — | T01, C01 | — |
| `.github/workflows/ci.yml` (D00-5 structure; S09-T07 adds the tag workflow, §0.3 O4) | — | — | T01, T07, C01 | — |
| `docs/adr/` (directory hash) | — | — | T02 | — |
| `docs/results/TEMPLATE.md` | — | — | T01, T04, T05, C02 | — |
| `openapi/` (directory hash) | — | — | T02, T03 | — |
| Scenario catalog and runner (actual path per D05-12; directory hash) | — | — | T01, T04, T06 | — |
| `tools/simulator/`, `tools/verifier/` (directory hashes) | — | — | T04, C02 | — |
| `infra/grafana/dashboards/`, `infra/alerts/` (directory hashes) | — | — | T02, T06 | — |
| `docs/runbook.md` (S07 stubs) | — | — | T02 | — |
| `docs/results/` perf, SP1, chaos and ablation files (directory hash) | — | — | T01, T02, T06, T07 | — |
| `docs/evaluation/audit-study-protocol.md` (frozen after pilot) | — | — | T04 | — |

<a id="handoff"></a>
## J. Handoff

**This is the last step in the pack.** No later step consumes S09. Two audiences use its outputs instead: reviewers of the released repository, and any future post-MVP iteration, which would get its own planning documents.

**What a post-MVP iteration consumes**

| Output | Where to resolve the current definition | Use |
|---|---|---|
| Release tag, release commit, release notes | D09-9 in H.1/H.3 of this document | The baseline for any further work; the next release's tag workflow runs the upgrade migration test from this tag (§0.3 O4) |
| Selected architecture and M1–M14 traceability | D09-2; `docs/architecture.md` at the tagged revision | The authoritative description of what was built, including the selected alternatives in D.4 |
| Known limitations and open blockers | H.5 of this document and of every upstream register | The starting backlog |
| Measured ceilings and bottlenecks | D07-5, D07-6 ([docs/step_07_observability_performance.md#decisions-and-outputs](step_07_observability_performance.md#decisions-and-outputs)) | Inputs to [docs/zerosum_ledger_mvp_plan.md#scaling-triggers](zerosum_ledger_mvp_plan.md#scaling-triggers); S4 sharding if not adopted |
| Correctness evidence and harness validity | D08-5, D08-6 ([docs/step_08_fault_injection_ablation.md#decisions-and-outputs](step_08_fault_injection_ablation.md#decisions-and-outputs)) | Regression baseline; the seeds of any failing run |
| H4 findings: doc gaps, missing extension points | D09-6; `docs/results/integration-test.md` | Input to the deferred Stripe test-mode adapter ([docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred)) and R13 follow-up |
| H3 findings and Explorer limitations | D09-4, D09-5 | Whether the deferred entity search (§0.3 C15; a change to D02-7) is justified |
| Hosted demo state, backups, restore result | D09-10 (if S09-C01 ran) | Operating or decommissioning the demo |
| Deferred and unexercised scope | [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred), [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have), the conditional replay tool in [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery) | Candidate scope, each needing its own ADR and plan |

Always resolve definitions from the registers and the artifact paths they record, at the tagged revision. Never use copies in this document or the master's proposals.

**Handoff conditions**

1. G5 is evaluated and recorded in H.6. The tag matching the outcome (`v1.0.0`, or `v0.9` with known issues) exists, and its pipeline run is linked in H.4.
2. H.1–H.5 are filled: every decision has rationale and status, every planned path has an actual path or an explicit "not produced" reason, and every check has a result ("Not run" only with its reason).
3. I.1 shows a final status for every task and conditional task, with evidence or the exact blocker.
4. I.2 is current: change detection was re-run immediately before handoff, and every changed source has a review outcome.
5. Open change requests raised by S09 against upstream owners are listed in I.2 with their state.
