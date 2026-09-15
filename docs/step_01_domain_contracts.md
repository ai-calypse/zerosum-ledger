# Step 01 — Domain model and contracts

> **Pack:** ZeroSum Ledger implementation docs · **Master:** [docs/zerosum_ledger_mvp_plan.md#step-01](zerosum_ledger_mvp_plan.md#step-01) (v1.2)
> **Doc version:** 1.1 · **Date:** 2026-09-15 · **Step status:** Planned (no implementation exists)
> **Planned effort:** 14 h (master schedule; see [docs/zerosum_ledger_mvp_plan.md#schedule-overview](zerosum_ledger_mvp_plan.md#schedule-overview)) · **Gate:** —
> **Shared procedures:** [docs/README.md#change-detection](README.md#change-detection) · [docs/README.md#source-of-truth](README.md#source-of-truth) · [docs/README.md#status-legend](README.md#status-legend)
> **Doc revision 1.1:** aligned with master v1.2 clarifications C1, C4, C5, C6, C7, C16 and O6 ([docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)).

<a id="purpose-and-outcome"></a>
## A. Purpose and outcome

**The problem this step solves.** Every later step creates, stores, moves, checks or displays money. If each service defined amounts, currency precision, rounding, account semantics, the zero-sum rule and message shapes on its own, the definitions would drift. That is the "silent drift" failure the project exists to prevent ([docs/zerosum_ledger_mvp_plan.md#problem](zerosum_ledger_mvp_plan.md#problem)). The master's money, chart-of-accounts and contract sections are PROPOSED designs, not code. S01 turns them into small, tested libraries and contract files. Later steps then import one definition instead of re-deriving it.

**Concrete deliverable.**

- **`libs/money`** (JDK-only):
  - the `Money` value type;
  - currency rules backed by a checked-in ISO 4217 minor-unit table generated from the official list;
  - `FeeCalculator` and `FareSplitter`;
  - `ChartOfAccounts` and `ZeroSumValidator`;
  - seeded generator utilities with a seed-reporting convention;
  - ArchUnit money rules.
- **`libs/contracts`:**
  - JSON Schemas for the stored money order (which is also the published event, §0.3 C1) and the payment event;
  - golden payloads for worked-example orders O1–O8, paired payment events and expected balances;
  - a small schema-validation and golden-loading helper.
- **ADRs:** ADR-0003 (sign convention) and ADR-0009 (seeded generative tests instead of a property-based testing library).
- **Register:** section H of this document, with D01-1…D01-11 selected and traced.

**Contribution to the MVP.**

- **M1.** S01 satisfies M1 ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)), the completion checkpoint the master schedule assigns to this step.
- **Single validation point.** It provides the one validation point that ADR-0006 and design rule 3 rely on ([docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components)).
- **G1 evidence.** It supplies the golden data and generators that S02 uses for the G1 evidence ([docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates)).

**In scope**

- Pure, framework-free domain code and its unit tests.
- Seeded generative tests.
- Contract files, schema tests and golden data.
- The two ADRs.
- ArchUnit rules that protect money arithmetic.

**Explicitly out of scope**

| Excluded item | Owner |
|---|---|
| Database enforcement: CHECK constraints, deferred zero-sum trigger, append-only triggers | S02 ([docs/step_02_ledger_core.md#s02-t01](step_02_ledger_core.md#s02-t01)), S03 ([docs/step_03_order_service_outbox.md#s03-t02](step_03_order_service_outbox.md#s03-t02)) |
| API request contract (separate from the stored order, §0.3 C1), idempotency, request-hash canonicalization, API error codes, principal → `source_system` | S03 (D03-2, D03-3, D03-4) |
| Payment-event → money-order mapper | S03 ([docs/step_03_order_service_outbox.md#s03-t07](step_03_order_service_outbox.md#s03-t07)); the mapping table stays in [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order) until D03-6 selects it |
| Topic names, partitioning, Kafka client configuration | S04 (D04-1, D04-2) |
| Changelog hash canonical form | S02 (D02-6) |
| Provider minor-unit mapping, FakeCard fee schedule as a simulation parameter | S05 ([docs/step_05_instruments_fake_providers.md#s05-t05](step_05_instruments_fake_providers.md#s05-t05)), S06 ([docs/step_06_reconciliation_verifier.md#s06-t01](step_06_reconciliation_verifier.md#s06-t01)) |
| ArchUnit provider-boundary rule and outbox-only Kafka send rule | S05 ([docs/step_05_instruments_fake_providers.md#s05-t06](step_05_instruments_fake_providers.md#s05-t06)), S03 ([docs/step_03_order_service_outbox.md#s03-t05](step_03_order_service_outbox.md#s03-t05)) |
| Simulator workloads and the A4 fare-split bug-injection seam | S08 ([docs/step_08_fault_injection_ablation.md#s08-t01](step_08_fault_injection_ablation.md#s08-t01), [docs/step_08_fault_injection_ablation.md#s08-t03](step_08_fault_injection_ablation.md#s08-t03)) |
| FX conversion, holds and pending balances, Protobuf/Avro with a schema registry | Deferred: [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred) |
| GAAP statements, tax | Non-goals: [docs/zerosum_ledger_mvp_plan.md#non-goals](zerosum_ledger_mvp_plan.md#non-goals) |
| jqwik or any other property-based testing library | Excluded by [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits) |

<a id="agent-prompt"></a>
## B. Step-specific agent prompt

```text
You are the implementation agent for Step 01 (Domain model and contracts) of the ZeroSum Ledger project.

Repository root: zerosum-ledger/
Step document: docs/step_01_domain_contracts.md

1. Read docs/README.md (source-of-truth, conflict-resolution, change-detection, status-legend).
   Then read this step document in full, then every source in its section C at the linked anchors
   (master: decomposition-clarifications C1/C4–C7/C16/O6, step-01, must-have M1, standards,
   platform-limits, decisions ADR-0003/ADR-0009,
   chart-of-accounts, worked-example, money-order-contract, event-contracts, event-to-order,
   test-layers, repo-structure).
2. Inspect the current repository. Read the register (section H) and execution record (section I)
   of docs/step_00_foundations.md. Resolve the module layout (D00-2), pinned versions (D00-1),
   CI jobs (D00-5), environment naming (D00-8), ADR process (D00-9) and build/test-tag conventions
   (D00-10) from that register and the artifacts it references, not from this document or the master.
3. Before changing code, run docs/README.md#change-detection. Record revisions and hashes for every
   source and artifact listed in section I.2, including the downloaded ISO 4217 source file.
4. Complete only the remaining S01 tasks (S01-T01 … S01-T07), in dependency order.
   Keep libs/money JDK-only. Do not build any of these (they belong to S02–S08):
   database triggers or migrations, REST endpoints, Kafka topics, the event mapper,
   provider adapters, simulator workloads, ablation flags or bug-injection seams.
5. Verify each task exactly as its "Verification and definition of done" field says.
   Record commands, test names and evidence paths in H.4 and I.1.
6. Record every D01-n decision with rationale and alternatives in H.1, and actual paths in H.2/H.3.
   Put a trace comment next to every authoritative value: ISO table header, currency allow-list,
   validation limits, schema $comment, RNG algorithm name, seed override variable.
7. Never invent results, and never mark blocked evidence as passed. If the ISO 4217 source file
   cannot be obtained, mark the table work Blocked with that exact dependency; never hand-type
   a production table. Never add jqwik or any other property-based testing library.
8. Re-run change detection at each phase boundary and before handoff. Mark affected tasks
   "Needs review".
9. Some changes need an owner's approval. Examples: a worked-example value looks wrong, a
   master validation rule must change, or the S00 version catalog lacks a needed test or
   JSON Schema library. In those cases stop and raise a change request per
   docs/README.md#conflict-resolution instead of silently overriding the owner.
   Never weaken M1.

Finish by completing sections G and J. Then report: tasks done, evidence, open blockers,
change requests raised.
```

<a id="required-reading"></a>
## C. Required reading and prerequisites

### C.1 Master sections

| Section | Why it matters here |
|---|---|
| [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) | v1.2 clarifications that settle S01 details: C1 (request vs stored order), C4 (repeated lines), C5 (event types), C6 (settlement events), C7 (mapper-order source), C16 (generator home, ArchUnit split, JSON Schema validator pin), O6 (M1(d) scope) |
| [docs/zerosum_ledger_mvp_plan.md#step-01](zerosum_ledger_mvp_plan.md#step-01) | Original task list, output, exit criteria, and the ISO-vs-JDK step risk |
| [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) | M1 (a)–(d), the acceptance target for this step |
| [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards) | ISO 4217 List One source; providers may differ from ISO, so adapters own their own mapping |
| [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits) | Why there is no jqwik, and the seeded JUnit loop pattern that replaces it |
| [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) | ADR-0003 and ADR-0009 proposals |
| [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts) | `Money` proposal, overflow bounds, rounding rule, sign convention, entity kinds, accounts, normal sides |
| [docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example) | O1–O8, the fee derivation, balances after O1–O7, the O8 variant |
| [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract) | Example payload and validation rules 1–9 with the † (database) and ‡ (ledger) markers, plus the v1.2 request-vs-stored, mapper-source and repeated-line notes under rule 8 |
| [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts) | Payment-event example, the v1.2 `event_id` rule and event-type list, and per-currency settlement events |
| [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order) | Mapping rows, including the event types that produce no order, and the net + fee = gross rule; needed to pair golden events with golden orders |
| [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) | The unit, seeded generative and contract layers where S01 tests live |
| [docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure) | Planned contents of `libs/money` and `libs/contracts`; the "JDK only" module rule |
| [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack) | JSON plus JSON Schema as the event format; JUnit and ArchUnit as test tooling |
| [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components) | Design rule 3: zero-sum validation lives in one place |
| [docs/zerosum_ledger_mvp_plan.md#first-two-days](zerosum_ledger_mvp_plan.md#first-two-days) | The Day-2 hour that starts S01-T01 |
| [docs/zerosum_ledger_mvp_plan.md#assumptions](zerosum_ledger_mvp_plan.md#assumptions) | A11: multi-currency data model, zero-sum per currency, no FX |
| [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling) | A failing seed becomes a regression test; money-path flakiness is a bug |
| [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut) | What S01 drops if the minimum cut is invoked |

### C.2 Earlier step documents and their registers

S01 has one upstream step: [docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs).

| Decision consumed | Used for |
|---|---|
| D00-1 pinned versions and dependency catalog | JUnit, ArchUnit, the JSON library and the JSON Schema validator library (pinned there per §0.3 C16) must be catalog entries (T05, T06, T07) |
| D00-2 repository/module layout | Actual Gradle project paths for `libs/money` and `libs/contracts`, and the root Java package (all tasks) |
| D00-5 CI workflow structure | Which job runs unit, seeded generative and contract tests (T06, T07 verification) |
| D00-8 environment and secret conventions | Naming of the seed override variable (T06) |
| D00-9 ADR process | Template, numbering and status values for ADR-0003 and ADR-0009 (T04, T06) |
| D00-10 build conventions | Java toolchain, source sets (including test fixtures), test tags for generative tests (T01, T06, T07) |
| D00-7 SP3 result | Not consumed. S01 is framework-free; confirm the SP3 outcome does not change the toolchain or JUnit line. |

### C.3 Artifacts that must already exist

The paths below are the paths *planned* by S00. **Always resolve the actual path from the S00 register (H.2/H.3), not from this list.**

- `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`: from [docs/step_00_foundations.md#s00-t02](step_00_foundations.md#s00-t02).
- Empty `libs/money/` and `libs/contracts/` Gradle modules that build: from S00-T02.
- `.github/workflows/ci.yml` with unit and integration job wiring: from [docs/step_00_foundations.md#s00-t05](step_00_foundations.md#s00-t05).
- `docs/adr/0001-record-architecture-decisions.md` (ADR template): from [docs/step_00_foundations.md#s00-t01](step_00_foundations.md#s00-t01).
- ADR-0002 (pinned versions): from [docs/step_00_foundations.md#s00-t06](step_00_foundations.md#s00-t06).
- `.env.example` (environment naming convention): from [docs/step_00_foundations.md#s00-t09](step_00_foundations.md#s00-t09).
- **External input:** the ISO 4217 List One file from the publisher cited in [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards). It is downloaded during S01-T01 and its hash is recorded in I.2.

### C.4 Blocking vs independent work

| Missing input | Blocks | Independent preparation that can proceed |
|---|---|---|
| S00 Gradle skeleton or `libs/money` / `libs/contracts` modules | Compiling and verifying S01-T01 … S01-T07 | Draft ADR-0003 text (T04); hand-author golden JSON drafts (T05); write the ISO table generator script (T01), which runs with plain `java` |
| ISO 4217 List One file (no network access, or the publisher is unavailable) | The checked-in table in S01-T01, plus every test that depends on real currency digits | `Money` and `CurrencyRules` against a small test-only table fixture; generator script tested on a hand-made XML sample. Record the blocker in H.5. |
| The actual S00 catalog lacks the JSON Schema validator that §0.3 C16 assigns to D00-1 | Schema validation tests in S01-T05 and the schema agreement check in S01-T06 | Schema files, golden payloads and the manifest. Raise a change request to D00-1. |
| ArchUnit absent from D00-1, or unable to read the pinned toolchain's class files | S01-T07 | Rule definitions and canary fixtures written but not executed. Raise a change request to D00-1. |
| Test-tag or test-fixtures source-set conventions (D00-10) not yet in the build | CI placement of the generative tests (S01-T06) and wiring of the `libs/money` test fixtures (the location itself is fixed by §0.3 C16) | Run tests locally untagged; raise a change request to D00-10 if test fixtures are unsupported |
| ADR template (D00-9) missing | Final formatting of ADR-0003 and ADR-0009 | ADR content, decisions and links |

<a id="ownership-and-requirements"></a>
## D. Ownership and engineering requirements

### D.1 Decisions and contracts owned by this step

| Decision ID | What is decided | Master's proposed starting point |
|---|---|---|
| D01-1 | `Money` API (construction, currency validation, arithmetic, currency-mismatch behavior) and overflow policy | [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts) (Money record, overflow bounds) |
| D01-2 | ISO 4217 minor-unit table: source edition, file format, checked-in location, regeneration procedure, handling of entries without numeric minor units | [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards), [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts) |
| D01-3 | `FeeCalculator` API, input domain and rounding | [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts) (rounding), [docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example) (fee derivation) |
| D01-4 | `FareSplitter` API, remainder rule, sign symmetry, and how adjustments are split | [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract) (rule 9), worked-example O1/O3 |
| D01-5 | `ZeroSumValidator` rule set: which validation rules the library implements, violation codes, evaluation order, limits constants, and which rules each consumer runs | [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract) (rules 1–9); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) C4 (repeated lines allowed) |
| D01-6 | `ChartOfAccounts` content and ADR-0003: sign convention, normal sides, presentation of contra balances | [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts), [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) |
| D01-7 | Currency allow-list: location, relation to the ISO table, override policy | [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract) (rule 4) |
| D01-8 | JSON Schemas for the stored money order and the payment event: dialect, file locations, identifiers, strictness, required fields per event type, versioning rule | [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract), [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts), [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) C1, C5, C6, C16 |
| D01-9 | Golden payloads O1–O8: fixture identities, paired payment events, expected-balance fixtures, manifest, loader | [docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) C6, C7, O6 |
| D01-10 | Seeded generator utilities: actual test-fixtures path, RNG algorithm, generator families, seed reporting and replay (ADR-0009) | [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits), [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions), [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) C16 (location) |
| D01-11 | ArchUnit money rules: package scope, `BigDecimal` allow-list, rounding-mode rule, JDK-only dependency rule, reuse by services | [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) (M1(c)), [docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) C16 (S01 covers only the `libs/money` rules) |

### D.2 Inherited decisions

| Decision | Owner | How this step must use it |
|---|---|---|
| v1.2 decomposition clarifications C1, C4, C5, C6, C7, C16, O6 | Master [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) | Settled inputs for D01-5, D01-8, D01-9, D01-10 and D01-11. Implement them; a disagreement is a master change request, not a local re-decision. |
| Pinned test and JSON libraries | D00-1, [docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs) | Use only catalog entries; the JSON Schema validator is pinned there (§0.3 C16). A missing library is a change request to D00-1, never an ad hoc version string. |
| Module layout and root package | D00-2 (S00 register) | Place code in the modules and package root it records |
| Build conventions and test tags | D00-10 (S00 register) | Tag generative tests as that decision specifies; use its source-set convention for test fixtures |
| CI job structure | D00-5 (S00 register) | Confirm the new tests run in the intended jobs; do not restructure CI |
| Environment variable naming | D00-8 (S00 register) | Name the seed override variable per this convention |
| ADR process | D00-9 (S00 register) | Author ADR-0003 and ADR-0009 with its template and status values |
| M1 acceptance criteria | Master [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) | Acceptance target; never weaken it |
| Validation rules 1–9 and their †/‡ markers | Master [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract) (PROPOSED) | Starting point for D01-5. Rule changes beyond selecting details need an ADR and a master change-log row. |
| Worked example O1–O8 | Master [docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example) | Source values for D01-9; a disagreement is a change request, never a silent fixture edit |
| No property-based testing library | Master [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits), ADR-0009 proposal | Generators are plain JUnit with seeded `RandomGenerator` loops |
| Ledger stores ISO minor units; adapters own provider mapping | Master [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards) | `libs/money` knows only ISO digits; no provider exceptions here |
| Single writer of money orders (ADR-0006) and ledger-only structural rejection (ADR-0004) | Master [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions); later D03-6 | The validator must be callable by both order-service and ledger-service with identical results |
| Event format: JSON with JSON Schema files in `libs/contracts` | Master [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack) | Schemas are files, not generated code |

### D.3 Engineering requirements

**Module and package boundaries**

- **`libs/money` main source set depends only on the JDK** ([docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure)).
  - No Spring, no Jackson, no logging framework, no annotations from a framework.
  - It contains `Money`, `CurrencyRules`, `FeeCalculator`, `FareSplitter`, `ChartOfAccounts`, `ZeroSumValidator` and the minimal order input type the validator accepts.
- **`libs/money` test fixtures** (location set by §0.3 C16; actual path in D01-10) contain the generators, the seed-reporting JUnit extension and the reusable ArchUnit rule class. Consumers opt in with a test-scoped dependency. `tools/simulator` may depend on them in S08.
- **`libs/contracts`** holds the schema files, golden payloads and manifest as classpath resources, plus a small helper to load goldens and validate JSON text against a schema.
  - It may depend on `libs/money` and on the catalog's JSON and JSON Schema libraries.
  - It must not depend on any service module.
- **No service types in either library.** This keeps the G2 alternative open: instrument-service may validate orders through the shared library (S05-C01).

**Interfaces (planned shapes; the selected API is recorded in the register, not here)**

- **`Money`:** an immutable value of integer minor units plus an ISO alphabetic code. It has exact addition, subtraction and negation, and a dedicated exception for currency mismatch. There is no decimal or floating constructor.
- **`CurrencyRules`:** `isKnown(code)` and `minorUnitDigits(code)` from the checked-in table, plus `isAllowed(code)` from the allow-list (D01-7).
- **`FeeCalculator`:** computes a fee from a base amount, a rate in basis points and a fixed minor-unit component, rounding HALF_EVEN.
- **`FareSplitter`:**
  - `split(fare, commissionBps)` returns a driver share and a platform commission.
  - An adjustment helper returns the component-wise difference of two splits (T02 explains why).
- **`ChartOfAccounts`:** entity kind from an entity ID, allowed accounts per kind, normal side per account, and normal-side presentation of a signed balance.
- **`ZeroSumValidator`:** `validate(order)` returns an ordered list of violations. Each violation has a stable code, the rule number, and the entry index or currency where relevant. An empty list means valid. The validator never throws on malformed input.
- **Golden and schema helper:** load a golden payload by ID; validate JSON text against a schema by identifier and return the errors.

**Data flows**

1. **ISO table.** ISO 4217 List One file → generator script (run by a developer, never by CI) → checked-in table resource → `CurrencyRules` → `Money` and the validator.
2. **Validation.** S03 maps a request DTO, and S02 maps a decoded Kafka record, into the library order input type. Both call `ZeroSumValidator`. S03 maps violation codes to its API error codes (D03-2). S02 stores them as quarantine error codes (D02-9).
3. **Contracts.** Schema files → producer contract tests in S03, S05 and S06, and golden-driven tests in S02, S03 and S06. The API request contract is S03's (D03-2) and is not a `libs/contracts` schema (§0.3 C1).
4. **Generators.** Generators → S01 bulk test, S02 apply-engine and stress tests, and S08 simulator workloads. Seeds printed by the extension → S08 results files (M13(c)).

**Lifecycle behavior**

- **Startup.** The libraries have no threads, connections or background work.
  - `CurrencyRules` loads its table and allow-list once, on first use.
  - Loading fails fast with a message naming the resource if the table or allow-list is missing, empty, malformed, contains duplicates, or allows a currency absent from the table.
  - Do not let this surface as an opaque class-initialization error; use an explicit loader that wraps the cause.
- **Concurrency.** Every public type is immutable or stateless and safe to share across threads. The validator is called once per order inside batched apply (SP1 / D02-10), so it must not allocate per call beyond its result list.
- **Crash and retry.** Validation and splitting are pure and deterministic. Retrying a call with the same input gives the same output; there is nothing to recover.
- **Shutdown.** Nothing to release.

**Security and trust boundaries**

- **The validator runs at TB1 on untrusted input** ([docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries)).
  - It must be total: nulls, empty lists, huge strings and huge entry lists produce violations, not exceptions.
  - It checks entry count before per-entry work.
  - It bounds string length before applying patterns.
  - It uses only anchored patterns without nested quantifiers, so there is no catastrophic backtracking.
- **The downloaded ISO XML is untrusted.** The generator parses it with DTDs and external entities disabled (no XXE).
- **No secrets, tokens or personal data** exist in this step. Golden payloads use synthetic identifiers only.

**Deployment constraints**

- Builds and tests never download the ISO list or any other network resource.
- The table and schemas ship inside the library jars as classpath resources.
- Nothing in S01 runs as a container.

### D.4 Configuration ownership

Every authoritative value below lives in exactly one artifact, with a trace comment such as `# decision: D01-2 — docs/step_01_domain_contracts.md#decisions-and-outputs`. In JSON Schema files, use `$comment` for the trace. Actual paths go in H.2.

| Value | Planned location | Traced to |
|---|---|---|
| ISO minor-unit digits per currency, plus source edition and source-file SHA-256 in the header | `libs/money/src/main/resources/<root package path>/iso4217-minor-units.csv` | D01-2 |
| Table regeneration script | `libs/money/scripts/GenerateMinorUnitTable.java` | D01-2 |
| Currency allow-list | `libs/money/src/main/resources/<root package path>/allowed-currencies.txt` (no runtime override in the MVP) | D01-7 |
| Validation limits: entry count bounds, per-entry magnitude cap, type set, reason and entity-ID patterns | One constants holder next to `ZeroSumValidator` | D01-5 (the S02/S03 DDL CHECK constraints must trace to the same ID) |
| Rounding mode and basis-point scale | `FeeCalculator` constants | D01-3 |
| Normal side per account, allowed accounts per kind | `ChartOfAccounts` | D01-6, ADR-0003 |
| Schema identifiers, strictness, event-type requirements | `libs/contracts/src/main/resources/schemas/*.schema.json` | D01-8 |
| Golden fixture identities and expected balances | `libs/contracts/src/main/resources/golden/` plus `manifest.json` | D01-9 |
| RNG algorithm name and seed override variable name | Seed-reporting extension in `libs/money` test fixtures | D01-10, ADR-0009 |
| ArchUnit money-package scope and `BigDecimal` allow-list | `MoneyArchRules` in `libs/money` test fixtures | D01-11 |

**Not owned here:**

- The FakeCard fee schedule and fault knobs (S05 D05-2; S06 D06-1).
- Commission basis points and adjustment probability for simulated trips (S08 D08-1).
- Idempotency-key length limits (S03 D03-3).

`FeeCalculator` and `FareSplitter` take all such values as parameters.

**Permitted alternatives that downstream work must handle** (from [docs/README.md#source-of-truth](README.md#source-of-truth)):

| Alternative | Effect on S01 outputs |
|---|---|
| SP4 option (b) or (c): shard hot platform entities into sub-entities (S07-T06, D07-6) | `ChartOfAccounts` keys allowed accounts by entity **kind**, never by full entity ID. Sub-entity IDs of an existing kind validate without a chart change. S07 must not add per-ID chart entries. |
| G2 alternative to ADR-0006: instrument-service writes orders through shared validation (S05-C01, D03-6) | The validator and order input type live in `libs/money` with no order-service dependency, so instrument-service can call them unchanged. |
| SP2: Debezium Outbox Event Router replaces the polling relay (S04-C01) | Schemas describe the event **payload** only. Envelope and header layout are not part of D01-8, so contract tests keep validating the payload. |
| SP1 outcome: batched apply becomes must-have (D02-10) | No API change. The validator is already stateless and cheap per order. |
| SP3 fallback (D00-7) | No effect on S01. |

<a id="phases-and-tasks"></a>
## E. Phases and tasks

| Phase | Tasks | Hours |
|---|---|---|
| 1 Money primitives | S01-T01 (3), S01-T02 (2) | 5 |
| 2 Zero-sum rules and chart of accounts | S01-T03 (2), S01-T04 (1) | 3 |
| 3 Contracts | S01-T05 (3) | 3 |
| 4 Generative testing and guardrails | S01-T06 (2), S01-T07 (1) | 3 |
| **Total** | | **14** |

**Schedule.** The first hour of S01-T01 is the Day-2 money-library task in [docs/zerosum_ledger_mvp_plan.md#first-two-days](zerosum_ledger_mvp_plan.md#first-two-days). The rest follows the week split in [docs/zerosum_ledger_mvp_plan.md#weekly-roadmap](zerosum_ledger_mvp_plan.md#weekly-roadmap).

<a id="phase-1"></a>
### Phase 1 — Money primitives

**Objective:** an exact, currency-aware amount type whose precision comes from the official ISO list, with fee and fare-split arithmetic that can never create or lose a minor unit.

**Exit checkpoint:**
- `libs/money` tests for T01 and T02 are green locally and in CI.
- The ISO table is checked in with its source hash recorded in D01-2.
- D01-1 to D01-4 are filled in H.1.
- Change detection has been re-run and recorded in I.2.

<a id="s01-t01"></a>
#### S01-T01 — Money, generated ISO 4217 minor-unit table and tests
- **Outcome:** a `Money` value type with exact arithmetic, and `CurrencyRules` backed by a checked-in table generated from ISO 4217 List One. Tests prove 2-, 0- and 3-digit currencies resolve correctly without consulting JDK currency data.
- **Estimate:** 3 h
- **Inputs:**
  - Master: [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts) (Money proposal, overflow bounds); [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards) (List One source, provider differences); [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) M1(b); [docs/zerosum_ledger_mvp_plan.md#step-01](zerosum_ledger_mvp_plan.md#step-01) (ISO-vs-JDK risk).
  - Decisions: D00-1, D00-2, D00-10.
  - Artifacts: `libs/money/`, `gradle/libs.versions.toml`.
- **Depends on:** S00-T02 ([docs/step_00_foundations.md#s00-t02](step_00_foundations.md#s00-t02))
- **Instructions:**
  1. Resolve the module path and root package from D00-2. Create the `Money` and currency-rules package in `libs/money`.
  2. **Generator script.** Write the table generator as a single-file, JDK-only Java program under `libs/money/scripts/`, outside every source set, run with `java <script> <path-to-xml>`.
     - Parse the XML with secure processing on, DOCTYPE declarations disallowed and external entities disabled.
  3. **Script output.** A text table sorted by alphabetic code, one `CODE,DIGITS` line per currency, preceded by comment lines containing:
     - the source name and the publication date read from the file itself;
     - the SHA-256 of the input file;
     - the script path;
     - the codes that were skipped;
     - the D01-2 trace comment.
  4. **Source quirks.**
     - Deduplicate by alphabetic code, because one currency appears under many countries.
     - Fail with a non-zero exit if one code appears with conflicting digits.
     - Skip entries with no currency or with non-numeric minor units, and list them in the header so the exclusion is reviewable.
     - Keep codes whose digits exceed three. The allow-list (D01-7), not the table, decides what orders may use.
  5. **Check in the table only.** Do not check in the raw XML unless its redistribution terms have been confirmed; record that choice in D01-2. The recorded hash lets anyone verify a regeneration.
  6. **`CurrencyRules`.**
     - Load the table once from the classpath through an explicit loader.
     - Reject a missing, empty, unsorted or duplicate-containing resource with a message that names the resource.
     - Expose `isKnown` and `minorUnitDigits`.
     - Never call `java.util.Currency`. JDK data can lag ISO amendments, which is the master's recorded step risk.
  7. **`Money`.** Start from the proposed record in [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts).
     - Validate at construction that the code is three uppercase ASCII letters and present in the table.
     - Provide `plus`, `minus` and `negate` with `Math.addExact`, `Math.subtractExact` and `Math.negateExact`.
     - Throw a dedicated `CurrencyMismatchException` when currencies differ.
     - Provide no decimal, `double` or string-parsing constructor, and no formatting to major units. Display in major units is not an MVP need, and it would pull `BigDecimal` into money code.
  8. **Overflow policy (D01-1).** Arithmetic never wraps and never saturates; an `ArithmeticException` propagates to the caller. The per-entry magnitude cap is a validation rule (D01-5), not a `Money` invariant, because balances accumulate beyond a single entry's cap. Record this rationale in H.1.
  9. Write `MoneyTest` and `CurrencyRulesTest`. Tag them as unit tests per D00-10.
- **Edge cases and failure behavior:**
  - `negate` of the minimum `long` value and `plus` at either `long` boundary throw `ArithmeticException`. Tests cover both ends.
  - Lower-case, two-letter, four-letter or unlisted codes are rejected at construction, with the offending code in the message.
  - A currency whose provider precision differs from ISO ([docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards)) is **not** special-cased. S05 adapters own that mapping.
  - **The ISO file cannot be downloaded.** Mark the table part Blocked in I.1 and H.5. Continue `Money` and `CurrencyRules` against a small test-only table resource. Never hand-type a production table.
  - **The publisher's file format changes** (element names or attributes). The script fails loudly and names the missing element. The previously checked-in table remains authoritative until a regeneration is reviewed.
  - **A regenerated table changes digits for a currency already in the allow-list.** Treat it as a contract change: change request per [docs/README.md#conflict-resolution](README.md#conflict-resolution), and mark S02/S03/S05 consumers "Needs review".
- **Outputs:** (planned)
  - `libs/money/src/main/java/<root>/money/Money.java`, `CurrencyMismatchException.java`, `CurrencyRules.java`
  - `libs/money/src/main/resources/<root>/money/iso4217-minor-units.csv`
  - `libs/money/scripts/GenerateMinorUnitTable.java`
  - `libs/money/src/test/java/<root>/money/MoneyTest.java`, `CurrencyRulesTest.java`
- **Verification and definition of done:**
  - `./gradlew :libs:money:test` passes; use the actual project path from D00-2.
  - `MoneyTest` covers: exact add, subtract and negate; overflow at both `long` boundaries; currency mismatch; each rejected code shape.
  - `CurrencyRulesTest` asserts:
    - the digits for the 2-, 0- and 3-digit currencies named in M1(b) match the table;
    - the table is sorted and has no duplicates;
    - a malformed test resource fails with a message naming the resource.
  - **Regeneration check.** Running the script on the file whose SHA-256 matches the table header gives a byte-identical table (`diff` prints nothing). Record the command and output path in H.4.
  - `grep -rn "java.util.Currency" libs/money/src/main` returns no matches. S01-T07 later enforces this in the build.

<a id="s01-t02"></a>
#### S01-T02 — FeeCalculator (HALF_EVEN) and FareSplitter
- **Outcome:** a basis-point fee calculator that rounds HALF_EVEN, and a fare splitter whose driver share and platform commission always sum exactly to the fare. The split is sign-symmetric and does not drift across repeated adjustments.
- **Estimate:** 2 h
- **Inputs:**
  - Master: [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts) (rounding rule); [docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example) (fee derivation, O1 and O3 splits); [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract) rule 9; M1(c) at [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have).
  - Decisions: D01-1 (from T01).
  - Artifacts: `libs/money/`.
- **Depends on:** S01-T01
- **Instructions:**
  1. **`FeeCalculator` shape.** It takes a `Money` base, a rate in basis points and a fixed component in the same currency, and returns a `Money`.
     - It is the only class in `libs/money` allowed to use `BigDecimal` (enforced by S01-T07).
     - Every `setScale` or `divide` call passes `RoundingMode.HALF_EVEN` explicitly.
     - Convert back with `longValueExact()` so an out-of-range result throws instead of truncating.
  2. **Schedules are parameters.** Do not embed the FakeCard schedule. It is a simulation parameter owned by S05/S06 ([docs/zerosum_ledger_mvp_plan.md#instrument-interface](zerosum_ledger_mvp_plan.md#instrument-interface)).
  3. **Input domain (D01-3).** Reject negative basis points and negative bases. Fees are computed on captures and are not refunded, so no caller needs a negative fee. A narrower domain is easier to reason about. Record the choice in H.1.
  4. **`FareSplitter.split(fare, commissionBps)`.**
     - Accept basis points from zero to 10 000 inclusive; 10 000 bps is the whole fare.
     - Commission is the HALF_EVEN-rounded share of the fare. Compute it with `FeeCalculator`'s rate rounding (fixed component zero), so `BigDecimal` stays in one class.
     - Driver share = fare − commission, with exact subtraction.
     - **Remainder rule (D01-4):** any rounding remainder goes to the driver share, so the two parts always sum to the fare.
  5. **Sign symmetry.** Negative fares are allowed: an adjustment can be negative. HALF_EVEN is symmetric, so `split(−f)` equals the negation of `split(f)`. Test this; do not assume it.
  6. **Adjustments.** Provide `adjustment(oldFare, newFare, commissionBps)`. It returns `split(newFare) − split(oldFare)` component-wise, **not** `split(newFare − oldFare)`. Record in D01-4 why: after any number of adjustments, the cumulative driver and platform amounts equal a direct split of the final fare. Splitting each delta can drift by one minor unit per adjustment.
  7. **Amounts only.** `FareSplitter` returns amounts. Turning them into signed entries per ADR-0003 is the job of the generators (T06) and the simulator (S08). The order-service never computes splits (rule 9).
  8. **No bug hooks.** Add no bug-injection switch or hook. The A4 fare-split bug seam is owned by S08 ([docs/step_08_fault_injection_ablation.md#s08-t01](step_08_fault_injection_ablation.md#s08-t01)).
- **Edge cases and failure behavior:**
  - Exact ties round to the even neighbor in both signs; include ties that round down and ties that round up.
  - Zero fare returns a zero split. A zero-amount entry is invalid under rule 2, so callers skip such entries; the splitter does not decide that.
  - 0-digit and 3-digit currencies behave identically, because arithmetic is on minor units. Tests use both to prove no hidden scale assumption.
  - A base near the `long` limits with a large rate: the result either fits or throws `ArithmeticException`; it never truncates silently.
  - Fixed-component currency different from the base currency → `CurrencyMismatchException`.
  - Basis points outside the accepted range → `IllegalArgumentException` naming the value.
- **Outputs:** (planned)
  - `libs/money/src/main/java/<root>/money/FeeCalculator.java`, `FareSplitter.java`
  - `libs/money/src/test/java/<root>/money/FeeCalculatorTest.java`, `FareSplitterTest.java`
- **Verification and definition of done:**
  - `FeeCalculatorTest` reproduces the capture fee derived in [docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example). The schedule and expected value appear as test data with a trace comment to that anchor. The test also covers tie cases and input rejection.
  - `FareSplitterTest`:
    - reproduces the O1 and O3 splits from the worked example;
    - checks, in a nested loop over a range of fares (negative, zero, positive) and a set of basis-point values, that driver + commission equals the fare and that `split(−f)` equals the negation of `split(f)`;
    - checks, over a sequence of adjustments, that the cumulative shares equal `split(finalFare)`.
  - `./gradlew :libs:money:test` passes, and the new tests carry the unit tag per D00-10.

<a id="phase-2"></a>
### Phase 2 — Zero-sum rules and chart of accounts

**Objective:** one executable chart of accounts and one validator that every service uses, with the sign convention recorded as an accepted ADR.

**Exit checkpoint:**
- T03 tests are green.
- ADR-0003 is accepted and linked from D01-6.
- D01-5, D01-6 and D01-7 are filled in H.1.
- Change detection has been re-run.

<a id="s01-t03"></a>
#### S01-T03 — ZeroSumValidator and ChartOfAccounts
- **Outcome:** a chart of accounts expressed as code, and a pure validator implementing the library-checkable money-order rules. It returns typed, deterministically ordered violations and never throws on bad input.
- **Estimate:** 2 h
- **Inputs:**
  - Master: [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts) (kinds, accounts, normal sides); [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract) (rules 1–9, † and ‡); [docs/zerosum_ledger_mvp_plan.md#assumptions](zerosum_ledger_mvp_plan.md#assumptions) A11; [docs/zerosum_ledger_mvp_plan.md#bottlenecks](zerosum_ledger_mvp_plan.md#bottlenecks) (sharding option); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) C4.
  - Decisions: D01-1, D01-2.
  - Artifacts: `libs/money/`.
- **Depends on:** S01-T01
- **Instructions:**
  1. **`ChartOfAccounts`** in plain Java (enums and records, no configuration file).
     - Model entity kinds, the allowed account codes per kind, and the normal side per account code, starting from the table in [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts).
     - Parse the kind from an entity ID using the rule-3 pattern.
     - Key allowed accounts by **kind only**. This covers SP4 sub-entity sharding and a new provider such as the H4 FakeWallet ([docs/zerosum_ledger_mvp_plan.md#human-eval](zerosum_ledger_mvp_plan.md#human-eval)) without chart changes.
  2. **Presentation helper.** Add a function that turns a signed balance into its normal-side presentation. Implement the contra-balance rule exactly as ADR-0003 decides (S01-T04). Draft the rule here and finalize it with the ADR in the same phase.
  3. **Order input type.** Define a minimal record in `libs/money`: type, reason and a list of entries (entity ID, account code, currency, amount in minor units). No JSON or framework annotations. S02 and S03 map their own DTOs into it.
  4. **Rules in the library.** Implement rules 1–5 of [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract): type and reason, entry count and amounts, entity ID and allowed account, currency allow-list, zero-sum per currency.
  5. **Rules left to S03.** Rules 6–9 need database state, the authenticated principal or caller policy, so S03 owns them (D03-1, D03-3, D03-4). Say so explicitly in D01-5.
  6. **Violations.**
     - Each carries a stable code (one per failure class, with a zero-sum code distinct from the structural codes), the rule number, and the entry index or currency where relevant.
     - Return **all** violations, ordered by rule number, then entry index, then currency code.
     - An empty list means valid.
     - Null order, null fields and null entries produce violations, never exceptions.
  7. **Evaluation order.**
     - Check entry count first.
     - If the count exceeds the maximum, report that violation and skip per-entry checks.
     - If an entry's entity ID is invalid, skip its account check to avoid cascading noise.
  8. **Overflow-safe zero-sum.** Sum per currency with `Math.addExact`. If an over-cap amount makes the sum overflow, report a violation rather than letting `ArithmeticException` escape.
  9. **Limits in one place.** Put the entry-count bounds, magnitude cap, type set and patterns in one constants holder with the D01-5 trace comment. Values come from the master rules. S02 and S03 DDL CHECK constraints must trace to D01-5 too, so a change is visible to both.
  10. **Allow-list (D01-7).** Load it from the `libs/money` resource. Loading fails if it lists a currency absent from the ISO table.
  11. **Consumer rule sets (D01-5).** Default: both order-service and ledger-service run the full library rule set, and the ‡ markers are the ledger's minimum. One rule set means an order accepted by S03 can't be quarantined by S02 for a rule S03 never ran. Record the choice; if S02 needs a narrower profile, it raises a change request.
- **Edge cases and failure behavior:**
  - A multi-currency order where each currency nets to zero is valid (A11). Two entries in two different currencies pass the count rule but fail zero-sum for each currency.
  - The same entity, account and currency on several lines of one order is allowed (§0.3 C4; each line is applied separately). The validator accepts it and D01-5 records it, so S02's apply engine handles each line.
  - **Kind-level limitation.** Kind-level allowed accounts let a bank provider carry the card clearing account. The validator can't catch that; invariant I9 ([docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants)) is the backstop. Record it in H.5.
  - An unknown kind prefix, empty suffix, over-long suffix or disallowed characters produce an entity violation.
  - A reason with uppercase letters, no dot or extra dots produces a reason violation.
  - Very long strings are rejected on length before pattern matching.
- **Outputs:** (planned)
  - `libs/money/src/main/java/<root>/money/ChartOfAccounts.java`, `EntityKind.java`, `NormalSide.java`
  - `libs/money/src/main/java/<root>/money/ZeroSumValidator.java`, `Violation.java`, `OrderCandidate.java` (name final in D01-5), `ValidationLimits.java`
  - `libs/money/src/main/resources/<root>/money/allowed-currencies.txt`
  - `libs/money/src/test/java/<root>/money/ChartOfAccountsTest.java`, `ZeroSumValidatorTest.java`
- **Verification and definition of done:**
  - `ChartOfAccountsTest` asserts:
    - every kind/account/normal-side row from [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts) is present;
    - a disallowed kind/account pair is rejected;
    - a sub-entity ID of an existing kind is accepted;
    - one contra-balance presentation case follows ADR-0003.
  - `ZeroSumValidatorTest` covers:
    - at least one accepting and one rejecting case for each of rules 1–5, with the expected code;
    - multi-violation ordering;
    - null-safety;
    - overflow reported as a violation;
    - the count guard skipping per-entry work;
    - an allow-list resource listing an unknown currency failing to load;
    - an order with repeated entity/account/currency lines accepted (§0.3 C4).
  - The O1–O8 entries, built directly in test code, all validate; the golden JSON versions follow in S01-T05.
  - `./gradlew :libs:money:test` passes.

<a id="s01-t04"></a>
#### S01-T04 — ADR-0003 sign convention
- **Outcome:** an accepted ADR-0003 recording the signed-amount convention, per-currency zero-sum, normal sides and contra-balance presentation, linked to the `ChartOfAccounts` code and tests.
- **Estimate:** 1 h
- **Inputs:**
  - Master: [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) (ADR-0003 proposal); [docs/zerosum_ledger_mvp_plan.md#chart-of-accounts](zerosum_ledger_mvp_plan.md#chart-of-accounts); [docs/zerosum_ledger_mvp_plan.md#industry-designs](zerosum_ledger_mvp_plan.md#industry-designs) (clearing accounts); [docs/zerosum_ledger_mvp_plan.md#products](zerosum_ledger_mvp_plan.md#products) (debit/credit pair models).
  - Decisions: D00-9, D01-6.
  - Artifacts: `docs/adr/0001-record-architecture-decisions.md` (template).
- **Depends on:** S01-T03; S00-T01 ([docs/step_00_foundations.md#s00-t01](step_00_foundations.md#s00-t01))
- **Instructions:**
  1. Create ADR-0003 with the D00-9 template, file-naming rule and status vocabulary.
  2. **Context:** the master proposal, and the need for one convention shared by validator, ledger balances, APIs and verifier.
  3. **Decision.**
     - Debits are positive and credits negative.
     - Each order's entries sum to zero per currency.
     - Every account code has one normal side.
     - A balance is presented on its normal side.
     - **Contra balances.** The master says balances display on the normal side but does not define contra balances such as driver debt or rider credit. Proposed rule: the presented value is the signed balance for debit-normal accounts and its negation for credit-normal accounts. A contra balance therefore appears as a negative amount on the normal side. Record the final rule.
  4. **Alternatives considered:**
     - an unsigned amount with a direction flag per entry;
     - separate debit and credit accounts per transfer, as in the two-account transfer model at [docs/zerosum_ledger_mvp_plan.md#products](zerosum_ledger_mvp_plan.md#products);
     - separate debit and credit columns;
     - a credit-positive convention.
  5. **Consequences:**
     - the ledger read API presents balances with this rule (D02-7);
     - clearing accounts returning to zero is the health signal (I9);
     - driver debt appears as a negative payable (the R1 metric at [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants)).
  6. **Links:** the `ChartOfAccounts` source path, `ChartOfAccountsTest`, and this register (D01-6). Set status Accepted.
  7. **Escalation.** If the accepted rule contradicts the master's intent (not just its detail), stop and raise a master change request per [docs/README.md#conflict-resolution](README.md#conflict-resolution).
- **Edge cases and failure behavior:**
  - The D00-9 template does not exist yet: write the ADR with a minimal context/decision/consequences layout, and record a limitation to reformat when D00-9 lands.
  - The presentation rule in code differs from the ADR text: the test fails or review finds the gap. Fix the code or the ADR before closing the task; never leave them inconsistent ([docs/README.md#conflict-resolution](README.md#conflict-resolution) rule 3).
- **Outputs:** (planned) `docs/adr/0003-sign-convention.md` (final name per D00-9)
- **Verification and definition of done:**
  - The ADR file exists with status Accepted, and every repository path it links resolves (`test -f <path>` for each).
  - `ChartOfAccountsTest` contains the contra-balance presentation case the ADR describes, and the test passes.
  - If D00-9 defines an ADR index, it lists ADR-0003.
  - H.1 row D01-6 references the ADR path.

<a id="phase-3"></a>
### Phase 3 — Contracts

**Objective:** machine-checkable message contracts and golden data that reproduce the worked example exactly, so S02, S03, S05 and S06 test against one shared truth.

**Exit checkpoint:**
- T05 tests are green.
- Every golden file validates against its schema and passes the validator.
- D01-8 and D01-9 are filled in H.1, including the required fields per event type (§0.3 C5) and the fixture identities that C6 and C7 leave open.
- Change detection has been re-run.

<a id="s01-t05"></a>
#### S01-T05 — JSON Schemas, golden O1–O8 payloads and schema validation tests
- **Outcome:**
  - versioned JSON Schemas for the money order and the payment event;
  - golden money orders O1–O8 with paired payment events and expected-balance fixtures;
  - a loader and validation helper;
  - tests proving that golden data, schemas, `FeeCalculator` and the validator agree with the worked example.
- **Estimate:** 3 h
- **Inputs:**
  - Master: [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract); [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts); [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order); [docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example); [docs/zerosum_ledger_mvp_plan.md#stack](zerosum_ledger_mvp_plan.md#stack); [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) (contract layer); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) C1, C5, C6, C7, C16, O6.
  - Decisions: D00-1 (JSON library and the JSON Schema validator pinned per C16), D00-2, D01-3, D01-5, D01-6.
  - Artifacts: `libs/contracts/`.
- **Depends on:** S01-T02, S01-T03
- **Instructions:**
  1. **Dialect and library.** Use the JSON Schema validator pinned in D00-1/ADR-0002 (§0.3 C16) and the dialect it fully supports; record the dialect in D01-8. If the actual catalog lacks the entry, raise a change request to D00-1; never add an unpinned dependency.
  2. **Money-order schema** (`zerosum.money_order.v1`). Describe the **stored** order, starting from the example in [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract). Per §0.3 C1 the published event is the stored order, and the API request is a separate contract owned by D03-2 (its fields and the `Idempotency-Key` rule are under rule 8 at that anchor). Do not add a request schema to `libs/contracts`.
     - Put reusable pieces under `$defs`: entry, entity ID, account code, currency code, signed minor-unit amount. S03's OpenAPI request contract (D03-2) can then reference them instead of copying them.
     - Make the `schema` property a constant.
     - Disallow unknown properties at the top level, in `source` and in entries. Keep `metadata` an open object.
     - Mirror the shape constraints of rules 1–4 using the D01-5 constants, and add a `$comment` trace to D01-5 wherever a value is mirrored.
  3. **Payment-event schema** (`zerosum.payment_event.v1`). Start from [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts).
     - The event-type enum is exactly the v1.2 list at that anchor (§0.3 C5), including the types that produce no order ([docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order)).
     - D01-8 owns the required fields per event type (C5). Express them with conditional subschemas and record the per-type table in D01-8.
     - `SETTLEMENT_RECEIVED` follows C6: one event per provider, report and currency, carrying the settlement fields named at [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts) and no attempt or entity fields.
     - The `event_id` composition (C5 for attempt events, C6 for settlement events) and the settlement rule that `order_group_id` equals `event_id` compare values across fields, which JSON Schema cannot express. Record in D01-8 that producers (D05-5, D06-3) assert them in their contract tests; `PaymentEventSchemaTest` asserts them on the goldens.
  4. **Versioning rule (D01-8).**
     - The major version appears in both the schema identifier and the file name.
     - **Before release v1.0.0,** any change to a v1 schema goes through a change request to D01-8, updates all golden payloads in the same commit, and marks consuming tasks "Needs review".
     - **After release,** a breaking change creates a v2 file side by side. Breaking changes are: removing or renaming a property, changing a type, adding a required property, or narrowing an enum or pattern.
     - Record whether adding an optional property is allowed within v1, given the strict unknown-property policy.
     - Topic version suffixes are owned by D04-1; the rule only notes the relationship.
  5. **Golden money orders O1–O8.** One file each, with entries, types, reasons and adjustment links exactly as in [docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example).
     - Choose and record in D01-9 the fixture identities the master leaves open: order IDs, trip and payout group IDs, attempt IDs, the settlement report ID, timestamps.
     - O3 references O1 and shares its group.
     - The payout orders share a payout group.
     - Mapper-created orders (O2, O4–O8) take `source.system` and `source.idempotency_key` from §0.3 C7 (rule 8 at [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract)); the key is the paired event's `event_id`.
     - O6's `order_group_id` is its settlement event's `event_id` (§0.3 C6).
     - Timestamps increase from O1 to O8.
  6. **Golden payment events.** One file for each mapper-produced order (O2, O4, O5, O6, O7, O8), such that applying the mapping row yields the paired order's type, reason, entries, source and idempotency key. The settlement event's amounts satisfy net + fee = gross. Add one valid example for each remaining §0.3 C5 event type, marked in the manifest as a schema-only fixture (and as producing no order where [docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order) says so), so every event type has a positive fixture.
  7. **Expected balances.** A fixture for the state after O1–O7 and for the O8 variant, transcribed from the worked example with a trace comment to that anchor.
  8. **Manifest** (`manifest.json`). It lists each golden file, its pairing, and the fields a service generates at write time (order ID, creation timestamp), so downstream comparisons know which fields to ignore.
  9. **Negative fixtures.** At least one per constraint class: missing required property, unknown property, wrong enum value, pattern violation, too few entries, zero amount, amount given as a string, a payment event missing a type-specific field, an unknown event type, and a settlement event carrying attempt or entity fields (§0.3 C6).
  10. **Helpers in `libs/contracts`.** `GoldenPayloads` loads a golden by ID (for example `"O6"`) as JSON text. `ContractSchemas` validates JSON text against a schema identifier and returns an error list. Producer contract tests in S03, S04 and S05 use them.
- **Edge cases and failure behavior:**
  - **Integers with a zero fraction.** Recent JSON Schema dialects treat numbers like `12.0` as integers, so the schema alone cannot reject decimal notation for amounts. Record in D01-8 that consumers' JSON parsers must reject non-integer lexical amounts (S02, S03). Add a test documenting what the schema accepts.
  - **Writer `source.system` for O1 and O3.** §0.3 C7 fixes only mapper-created orders; the writer principal's value comes from D03-4, which does not exist yet. Use the value in the example at [docs/zerosum_ledger_mvp_plan.md#money-order-contract](zerosum_ledger_mvp_plan.md#money-order-contract), record it in D01-9, and flag it in J. S03 adopts it or raises a change request.
  - **A golden breaks a cross-field rule** (`event_id` composition, settlement group equals `event_id`): fix the fixture to follow C5/C6; never relax the test.
  - **A worked-example value fails a test** (sum, balance, fee): do not edit the fixture to pass. Check the transcription. If the master itself is inconsistent, raise a change request with a row in [docs/zerosum_ledger_mvp_plan.md#changelog](zerosum_ledger_mvp_plan.md#changelog).
  - **Schema/validator disagreement** on a shared shape constraint: a defect in one of them. S01-T06 adds a corpus-wide agreement check.
  - **The validator library rejects the chosen dialect or keyword:** switch to a supported construct and record it; do not drop the constraint silently.
- **Outputs:** (planned)
  - `libs/contracts/src/main/resources/schemas/zerosum.money_order.v1.schema.json`
  - `libs/contracts/src/main/resources/schemas/zerosum.payment_event.v1.schema.json`
  - `libs/contracts/src/main/resources/golden/orders/o1.json` … `o8.json`
  - `libs/contracts/src/main/resources/golden/events/*.json`
  - `libs/contracts/src/main/resources/golden/expected-balances.json`
  - `libs/contracts/src/main/resources/golden/manifest.json`
  - `libs/contracts/src/test/resources/negative/*.json`
  - `libs/contracts/src/main/java/<root>/contracts/GoldenPayloads.java`, `ContractSchemas.java`
  - Tests: `MoneyOrderSchemaTest`, `PaymentEventSchemaTest`, `WorkedExampleGoldenTest`, `GoldenManifestTest`
- **Verification and definition of done:**
  - `./gradlew :libs:contracts:test` passes (actual project path from D00-2).
  - `MoneyOrderSchemaTest` and `PaymentEventSchemaTest`: every golden file is valid, and every negative fixture is invalid with the expected failing keyword or path. The event-type enum equals the §0.3 C5 list, every type has a positive fixture, and the golden `event_id` and settlement group rules (C5, C6) hold.
  - `WorkedExampleGoldenTest`:
    - every O1–O8 file, mapped into the order input type, passes `ZeroSumValidator` with no violations;
    - the per-currency sums are zero;
    - cumulative balances after O1–O7 and after the O8 variant equal `expected-balances.json` (the M1(d) scope set by §0.3 O6);
    - `FeeCalculator`, applied to O2's captured amount with the example schedule, equals O6's processing-fee entry;
    - each paired event's amounts match its order's entries, and each mapper-created order's source follows §0.3 C7.
  - `GoldenManifestTest`: every golden file is listed, every listed file exists, and no unlisted file is present.
  - The tests run in the contract layer per D00-10 and D00-5.

<a id="phase-4"></a>
### Phase 4 — Generative testing and guardrails

**Objective:** reproducible, seed-reported generative evidence for M1(a), and build-time rules that keep floating-point and unrounded decimal arithmetic out of money code.

**Exit checkpoint:**
- T06 and T07 tests are green in CI.
- ADR-0009 is accepted.
- The seed replay has been demonstrated and recorded in H.4.
- D01-10 and D01-11 are filled in H.1.
- The section G checklist has been evaluated.
- Change detection has been re-run before handoff.

<a id="s01-t06"></a>
#### S01-T06 — Seeded generators, M1(a) bulk validator test and seed reporting (ADR-0009)
- **Outcome:** deterministic seeded generators for valid orders, labeled invalid orders, trip order sequences and stream perturbation; a JUnit extension that reports seeds and supports replay; the M1(a) bulk test; and an accepted ADR-0009.
- **Estimate:** 2 h
- **Inputs:**
  - Master: [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits); [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) (ADR-0009); [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) (seeded generative layer); M1(a) at [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have); [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling); [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd) (pull-request time budget); [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) C4, C16.
  - Decisions: D00-5, D00-8, D00-9, D00-10, D01-4, D01-5, D01-6, D01-7, D01-8.
  - Artifacts: `libs/money/`, `libs/contracts/` schemas.
- **Depends on:** S01-T02, S01-T03, S01-T05
- **Instructions:**
  1. **Location (D01-10).** The Gradle test-fixtures source set of `libs/money`, as settled by §0.3 C16. Service tests and `tools/simulator` depend on the generators without them shipping in the `libs/money` runtime jar. Record the actual path; if D00-10's build conventions don't support test fixtures, raise a change request to D00-10 instead of moving the generators.
  2. **Random number generator.**
     - Create it with `RandomGenerator.of(<algorithm name>)`, with the algorithm named once in a traced constant.
     - Never use `RandomGenerator.getDefault()`: the default algorithm may change between JDK releases and break replay.
     - Never use `Math.random` or `ThreadLocalRandom`.
     - Generate only from ordered collections, never by iterating a hash-based set or map.
  3. **Seed extension (JUnit).**
     - Take the seed from an override environment variable named per D00-8 if it is set. Otherwise draw a fresh seed from a non-deterministic source.
     - At test start, print one greppable line containing the test ID and seed.
     - On failure, add the seed and the exact replay command to the failure message.
     - Record the line format in ADR-0009 so S08 results tooling can parse it.
  4. **Valid-order generator.**
     - Random type and reason, and an entry count within the D01-5 bounds.
     - Kinds and allowed accounts from `ChartOfAccounts`; currencies from the allow-list, including multi-currency orders.
     - Occasional repeated entity/account/currency lines (allowed by §0.3 C4).
     - Balance each currency by construction: its last entry closes the sum. If the closing amount would be zero or exceed the cap, redraw, up to a fixed bound, then fail loudly.
  5. **Invalid-order generator.**
     - Apply exactly one labeled mutation to a valid order, drawn from one mutation family per rule 1–5 failure class.
     - The label is the expected violation code, determined by the mutation itself; never obtain it by calling the validator.
     - A generator self-check confirms the mutation holds using independent arithmetic or pattern checks (for example, the per-currency sum really is non-zero).
  6. **Trip-sequence generator.** COMMERCE orders built with `FareSplitter.split`, and adjustment orders built with `FareSplitter.adjustment` that reference the original order. Commission basis points and adjustment probability are caller parameters, owned by S08 for workloads.
  7. **Stream perturbation helper.** Seeded duplication and bounded reordering of any list, with rates supplied by the caller. S02 uses it for duplicate and reorder tests ([docs/step_02_ledger_core.md#s02-t03](step_02_ledger_core.md#s02-t03)); S08 uses it for workloads.
  8. **`ValidatorGenerativeTest`.**
     - Generate the order count required by M1(a) with a mix of valid and invalid orders.
     - Assert every valid order has no violations, and every invalid order has a violation carrying its label code.
     - Also validate a sample of the corpus against the money-order schema from S01-T05, and assert the schema and validator agree on shared shape constraints.
  9. **`GeneratorDeterminismTest`.** The same seed twice gives an identical SHA-256 digest over a canonical rendering of the generated orders; two different seeds give different digests.
  10. **ADR-0009** (D00-9 template):
      - **Context:** the jqwik status and license clause.
      - **Decision:** seeded JUnit generators.
      - **Consequences:** no automatic shrinking. A failing seed becomes a committed regression test before the fix ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)).
      - **Also record:** the seed line format and the replay procedure.
- **Edge cases and failure behavior:**
  - **Redraw bound exhausted.** The generator throws with the seed rather than silently skipping, because silent skips would bias the corpus toward easy cases.
  - **A mutation that accidentally leaves the order valid** (for example, moving an amount between two entries of the same currency) is not allowed. The mutation families guarantee a violation by construction, and the self-check catches mistakes.
  - **The bulk test exceeds the pull-request CI budget** in [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd): make generation faster. Never lower the M1(a) count.
  - **CI accidentally sets the seed override variable,** so every run uses one corpus. The start-of-test seed line makes this visible. D00-5's workflow must not set it; if it does, raise a change request.
  - **A failing seed appears in CI.** Add a regression test pinned to that seed first, then fix the root cause. Never re-run until green ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)).
- **Outputs:** (planned)
  - `libs/money/src/testFixtures/java/<root>/money/generate/OrderGenerator.java`, `TripSequenceGenerator.java`, `StreamPerturbation.java`, `SeededExtension.java`
  - `libs/money/src/test/java/<root>/money/ValidatorGenerativeTest.java`, `GeneratorDeterminismTest.java`
  - `docs/adr/0009-seeded-generative-tests.md` (final name per D00-9)
- **Verification and definition of done:**
  - `./gradlew :libs:money:test` passes, including the generative tests under their D00-10 tag. The test log contains the seed line.
  - **Replay:** re-running the test with the override variable set to the printed seed prints an identical digest line. Record both log paths in H.4.
  - **Mutation sanity check** (manual, not committed): temporarily disable the zero-sum rule locally. `ValidatorGenerativeTest` fails and prints its seed. Revert, and record the observation in H.4.
  - ADR-0009 exists with status Accepted.
  - A dependency report (`./gradlew dependencies` for every project) contains no jqwik artifact.
  - The CI run on the commit shows the generative tests in the job D00-5 assigns.

<a id="s01-t07"></a>
#### S01-T07 — ArchUnit money rules
- **Outcome:** reusable ArchUnit rules that fail the build on any of these in money packages:
  - floating-point types;
  - `BigDecimal` outside `FeeCalculator`;
  - rounding modes other than HALF_EVEN, or decimal operations without an explicit rounding mode;
  - non-JDK dependencies in `libs/money`;
  - use of JDK currency data.

  Canary fixtures prove each rule actually fires.
- **Estimate:** 1 h
- **Inputs:**
  - Master: M1(c) at [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have); [docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure) (module boundaries); [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut); [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) R8; [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) C16.
  - Decisions: D00-1 (ArchUnit), D00-10, D01-3, D01-10 (fixtures location).
  - Artifacts: `libs/money/`.
- **Depends on:** S01-T02, S01-T03, S01-T06
- **Instructions:**
  1. **Rule class.** Put the rules in `MoneyArchRules`, in the same test-fixtures location as D01-10, so service modules can apply them to their own money-handling packages. Record in D01-11 the package scope that counts as "money packages" (at least all of `libs/money`, test fixtures included).
  2. **Rule A: no floating point.** No fields, parameters or return types of `float`, `double`, `Float` or `Double`, and no calls to members whose signature involves them. This catches `Math.round(double)`, `Double.parseDouble`, `BigDecimal.valueOf(double)`, `new BigDecimal(double)` and `doubleValue()`.
  3. **Rule B: `BigDecimal` allow-list.** Only `FeeCalculator` may depend on `BigDecimal`. Record the allow-list in D01-11.
  4. **Rule C: explicit HALF_EVEN.**
     - No access to any `RoundingMode` constant other than `HALF_EVEN`.
     - No use of `MathContext`.
     - No calls to `BigDecimal` overloads that round without an explicit rounding mode: `divide(BigDecimal)`, `setScale(int)`, and any others listed in D01-11.
  5. **Rule D: JDK only.** `libs/money` main classes depend only on `java..` packages ([docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure)).
  6. **Rule E: no JDK currency data.** No dependency on `java.util.Currency` in money packages.
  7. **Canary fixtures.** Put one deliberately violating class per rule in a test-only package that the main scan excludes. `MoneyArchitectureTest` imports each canary explicitly and asserts its rule reports exactly that violation.
  8. **Known blind spot (H.5).** Bytecode analysis does not see primitive local variables or arithmetic opcodes. A `double` local computed without calling any floating-point member escapes Rule A. Code review and the absence of floating-point members in the API are the mitigation.
  9. **Not in this task (§0.3 C16).** S01 covers only the `libs/money` rules; S03 adds the outbox rule (S03-T05) and S05 the provider rule (S05-T06). Under [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut), Rule A is the one that must survive.
- **Edge cases and failure behavior:**
  - **Record accessors and compiler-generated members** are analyzed like normal methods. If a false positive appears, fix the rule predicate rather than suppressing the class.
  - **ArchUnit can't read the pinned toolchain's class files** (unsupported class-file version): mark the task Blocked and raise a change request to D00-1 (risk R8). Do not lower the toolchain.
  - **Generators in test fixtures** are money code and are scanned too. Only the canary package is excluded.
  - **A service later applies the rules and finds violations:** the service fixes its code. The rules are not relaxed without a change request to D01-11.
- **Outputs:** (planned)
  - `libs/money/src/testFixtures/java/<root>/money/arch/MoneyArchRules.java`
  - `libs/money/src/test/java/<root>/money/arch/MoneyArchitectureTest.java`
  - `libs/money/src/test/java/<root>/archcanary/*.java`
- **Verification and definition of done:**
  - `./gradlew :libs:money:test` passes with all rules applied to the real `libs/money` classes.
  - `MoneyArchitectureTest` shows each of rules A–E reporting its canary violation.
  - **Manual check:** temporarily adding a `double` field to `Money` makes the build fail with Rule A. Revert, and record in H.4.
  - The CI run on the commit is green in the D00-5 job.

<a id="conditional-work"></a>
### Conditional and deferred work

**Conditional tasks.** None. The pack assigns S01 no conditional tasks ([docs/README.md#effort](README.md#effort)).

A defect later found in an S01-owned contract is handled as a change request against the owning decision in this register ([docs/README.md#conflict-resolution](README.md#conflict-resolution)). Its hours come from unallocated contingency, and it gets no new task ID here.

**Deferred improvements** (not to be implemented in S01):

| Item | Reference |
|---|---|
| FX conversion; the money model stays multi-currency with per-currency zero-sum only | [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred) |
| Authorization holds and pending/posted balances (would add account states to the chart) | [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred) |
| Protobuf/Avro contracts with a schema registry instead of JSON Schema files | [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred) |
| Chargebacks and disputes (new order types and event types) | [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred) |
| Should-have items S1–S5: none belongs to S01; the S1 hash canonical form is S02's (D02-6) | [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have) |
| Under the minimum cut, S01 drops the ArchUnit module rules and keeps the float/double rule | [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut) |

<a id="risks-and-recovery"></a>
## F. Risks and recovery

| Risk | Detection (signal or test) | Recovery / fallback |
|---|---|---|
| The ISO table disagrees with JDK currency data, or code silently uses JDK data (master step risk) | Rule E in `MoneyArchitectureTest`; `CurrencyRulesTest` asserts digits from the table | The checked-in table is authoritative. Remove the JDK usage; never patch the table by hand. |
| The ISO source is unavailable, or its format changes | The generator script exits non-zero and names the missing element; T01 is marked Blocked | Keep the last reviewed table. Adapt the script's element mapping and regenerate; the source hash in D01-2 proves provenance. |
| Schema and validator drift on shared constraints | Schema/validator agreement assertion in `ValidatorGenerativeTest` | Fix the side that is wrong. If the constraint itself changes, raise a change request to D01-5/D01-8 and review S02/S03 DDL checks. |
| The generator's oracle is tautological (labels derived from the validator), so M1(a) proves nothing | Code review of the generator; manual mutation sanity check recorded in H.4 | Rebuild labels from the mutation itself; re-run the sanity check. Related to [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) R6 (a harness that exercises nothing). |
| Seeds don't replay across machines or JDK updates | `GeneratorDeterminismTest`; replay check in H.4 | Pin the RNG algorithm by name; remove hash-ordered iteration; record the algorithm in D01-10 |
| Fare-split rounding drifts by a minor unit across adjustments | `FareSplitterTest` cumulative-adjustment loop | Use the difference-of-splits rule (D01-4); never split deltas |
| ArchUnit misses floating-point use in local variables | Known limitation (H.5); code review | Keep floating-point members out of the API and dependencies; rules A–C catch every call path through the JDK |
| A worked-example transcription error, or an inconsistency in the master itself | `WorkedExampleGoldenTest` balance, sum and fee assertions | Fix the transcription. If the master is inconsistent, raise a change request with a master change-log row; never edit expected values to pass. |
| The actual S00 catalog lacks a library the master assigns to D00-1 (the JSON Schema validator per §0.3 C16, or ArchUnit), or ArchUnit can't read the toolchain | Build failure on dependency resolution or class import | Change request to D00-1 per [docs/README.md#conflict-resolution](README.md#conflict-resolution); run independent preparation from C.4 meanwhile ([docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) R8) |
| Scope creep: FX, holds, major-unit formatting or provider precision creeping into `libs/money` | Review against section A; the JDK-only rule D blocks framework creep | Remove the addition; move a genuine need to a change request with hours from contingency ([docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) R1) |
| The writer `source.system` in golden O1/O3 (the only fixture value still tied to a later decision, D03-4) conflicts with S03's choice | S03-T03 golden-driven tests fail on that field | S03 raises a change request to D01-9; goldens are updated in one commit; consumers marked "Needs review". Mapper-order sources and the O6 group are fixed by §0.3 C6 and C7. |
| The step overruns its planned hours | Actual vs planned hours in I.1 | Apply [docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut) for S01 only if the master's cut trigger is met ([docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) R11) |

<a id="acceptance-checklist"></a>
## G. Acceptance checklist

Evaluate each item against evidence recorded in H.4. Thresholds and counts are those in the linked master sections; they are not restated here.

- [ ] **M1(a):** `ValidatorGenerativeTest` generates the order count required by M1(a) ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)). It accepts every balanced order, rejects every unbalanced one with its labeled code, prints its seed, and a replay with that seed reproduces the digest.
- [ ] **M1(b):** the 0-digit and 3-digit currencies named in M1(b) are validated against the checked-in table generated from the source cited in [docs/zerosum_ledger_mvp_plan.md#standards](zerosum_ledger_mvp_plan.md#standards). The table's source hash is recorded in D01-2, and no money code uses JDK currency data (Rule E).
- [ ] **M1(c):**
  - ArchUnit Rule A fails the build on floating-point types in money packages.
  - `BigDecimal` is confined to `FeeCalculator` with explicit HALF_EVEN (rules B and C).
  - Every rule's canary fires.
- [ ] **M1(d):** golden O1–O7 validate exactly and reproduce the balances in [docs/zerosum_ledger_mvp_plan.md#worked-example](zerosum_ledger_mvp_plan.md#worked-example), and the O8 variant passes. This is the M1(d) scope set by §0.3 O6, and it satisfies the worked-example item in [docs/zerosum_ledger_mvp_plan.md#release-checklist](zerosum_ledger_mvp_plan.md#release-checklist).
- [ ] The stored money-order schema (§0.3 C1) and the payment-event schema exist. Event types match §0.3 C5, settlement events follow C6, and mapper-created goldens follow C7. Every golden payload validates, every negative fixture is rejected, and the required fields per type and the versioning rule are recorded in D01-8.
- [ ] `FeeCalculator` reproduces the worked-example fee, and it equals O6's processing-fee entry. `FareSplitter` sums exactly, is sign-symmetric and doesn't drift across adjustments.
- [ ] The zero-sum rule definition that invariant I1 checks ([docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants)) is documented in D01-5, precisely enough for S06-T04 to mirror it in SQL.
- [ ] `libs/money` main classes depend only on the JDK (Rule D; [docs/zerosum_ledger_mvp_plan.md#repo-structure](zerosum_ledger_mvp_plan.md#repo-structure)).
- [ ] No jqwik or other property-based testing library appears in any dependency report ([docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits)).
- [ ] ADR-0003 and ADR-0009 exist with status Accepted under the D00-9 process.
- [ ] All new tests run green in CI in the jobs defined by D00-5, within the pull-request budget in [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd).
- [ ] Every authoritative value in D.4 carries its trace comment, and H.2 lists its actual path.
- [ ] Section H is complete (D01-1…D01-11 no longer Pending), I.1 shows every task Done with evidence, and I.2 reflects a change-detection run made immediately before handoff.

<a id="decisions-and-outputs"></a>
## H. Decisions and outputs register

### H.1 Decisions and rationale

| ID | Decision | Rationale | Alternatives considered | Status | Date |
|---|---|---|---|---|---|
| D01-1 | — | — | — | Pending | — |
| D01-2 | — | — | — | Pending | — |
| D01-3 | — | — | — | Pending | — |
| D01-4 | — | — | — | Pending | — |
| D01-5 | — | — | — | Pending | — |
| D01-6 | — | — | — | Pending | — |
| D01-7 | — | — | — | Pending | — |
| D01-8 | — | — | — | Pending | — |
| D01-9 | — | — | — | Pending | — |
| D01-10 | — | — | — | Pending | — |
| D01-11 | — | — | — | Pending | — |

### H.2 Implementation and configuration locations

| Item | Planned path | Actual path | Traced to |
|---|---|---|---|
| `Money`, `CurrencyMismatchException`, `CurrencyRules` | `libs/money/src/main/java/<root>/money/` | — | D01-1 |
| ISO 4217 minor-unit table | `libs/money/src/main/resources/<root>/money/iso4217-minor-units.csv` | — | D01-2 |
| Table regeneration script | `libs/money/scripts/GenerateMinorUnitTable.java` | — | D01-2 |
| `FeeCalculator` (rounding mode, bps scale) | `libs/money/src/main/java/<root>/money/FeeCalculator.java` | — | D01-3 |
| `FareSplitter` | `libs/money/src/main/java/<root>/money/FareSplitter.java` | — | D01-4 |
| `ZeroSumValidator`, violation codes, `ValidationLimits` | `libs/money/src/main/java/<root>/money/` | — | D01-5 |
| `ChartOfAccounts`, `EntityKind`, `NormalSide` | `libs/money/src/main/java/<root>/money/` | — | D01-6 |
| Currency allow-list | `libs/money/src/main/resources/<root>/money/allowed-currencies.txt` | — | D01-7 |
| JSON Schemas | `libs/contracts/src/main/resources/schemas/` | — | D01-8 |
| Golden payloads, expected balances, manifest | `libs/contracts/src/main/resources/golden/` | — | D01-9 |
| Generators, seed extension, RNG algorithm constant, seed variable name | `libs/money/src/testFixtures/java/<root>/money/generate/` | — | D01-10 |
| `MoneyArchRules` (scope, `BigDecimal` allow-list) | `libs/money/src/testFixtures/java/<root>/money/arch/` | — | D01-11 |

### H.3 Produced artifacts

| Artifact | Planned path | Actual path | Revision/hash |
|---|---|---|---|
| `libs/money` module (main, test fixtures, tests) | `libs/money/` | — | — |
| `libs/contracts` module (schemas, goldens, helpers, tests) | `libs/contracts/` | — | — |
| Checked-in ISO table | `libs/money/src/main/resources/<root>/money/iso4217-minor-units.csv` | — | — |
| Money-order schema v1 | `libs/contracts/src/main/resources/schemas/zerosum.money_order.v1.schema.json` | — | — |
| Payment-event schema v1 | `libs/contracts/src/main/resources/schemas/zerosum.payment_event.v1.schema.json` | — | — |
| Golden O1–O8, paired events, expected balances, manifest | `libs/contracts/src/main/resources/golden/` | — | — |
| ADR-0003 sign convention | `docs/adr/0003-sign-convention.md` | — | — |
| ADR-0009 seeded generative tests | `docs/adr/0009-seeded-generative-tests.md` | — | — |

### H.4 Validation results and evidence

| Check | Method | Result | Evidence path | Date |
|---|---|---|---|---|
| Money arithmetic, overflow, currency validation | `MoneyTest`, `CurrencyRulesTest` | Not run | — | — |
| ISO table regeneration reproduces checked-in file | Script run + `diff` against recorded source hash | Not run | — | — |
| Fee rounding and worked-example fee | `FeeCalculatorTest` | Not run | — | — |
| Fare split exactness, symmetry, adjustment drift | `FareSplitterTest` | Not run | — | — |
| Chart of accounts and validator rules 1–5 | `ChartOfAccountsTest`, `ZeroSumValidatorTest` | Not run | — | — |
| ADR-0003 accepted and linked | File review, path checks | Not run | — | — |
| Schemas accept goldens, reject negatives | `MoneyOrderSchemaTest`, `PaymentEventSchemaTest` | Not run | — | — |
| Worked example O1–O7 plus the O8 variant, and balances (M1(d), §0.3 O6) | `WorkedExampleGoldenTest`, `GoldenManifestTest` | Not run | — | — |
| M1(a) bulk validator test with seed | `ValidatorGenerativeTest` | Not run | — | — |
| Seed determinism and replay | `GeneratorDeterminismTest` + replay run | Not run | — | — |
| Mutation sanity check of the generative oracle | Manual local rule disablement | Not run | — | — |
| ArchUnit rules A–E and canaries (M1(c)) | `MoneyArchitectureTest` + manual `double` field check | Not run | — | — |
| No property-based testing library | Dependency report | Not run | — | — |
| CI placement and duration | CI run on the handoff commit | Not run | — | — |

### H.5 Known limitations and blockers

| Item | Type (limitation/blocker) | Impact | Owner/next action |
|---|---|---|---|
| No FX conversion; zero-sum is enforced per currency only (master A11, deferred list) | limitation | Cross-currency movements cannot be expressed as one balanced order | Post-MVP ([docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred)) |
| No automatic shrinking of failing generated cases (ADR-0009 consequence) | limitation | Failing seeds must be minimized by hand when debugging | S01 (ADR-0009); regression tests pin seeds |
| ISO precision can differ from provider precision | limitation | `libs/money` does not model provider exceptions | S05 adapters ([docs/step_05_instruments_fake_providers.md#s05-t05](step_05_instruments_fake_providers.md#s05-t05)) |
| Kind-level chart cannot stop a provider entity using another provider type's account | limitation | Misbooked clearing is caught only after the fact | I9 in the verifier ([docs/step_06_reconciliation_verifier.md#s06-t04](step_06_reconciliation_verifier.md#s06-t04)) |
| ArchUnit does not see primitive floating-point locals | limitation | Rule A can be bypassed by pure local arithmetic | Code review; D01-11 |

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
| S01-T01 | Planned | — | — | — |
| S01-T02 | Planned | — | — | — |
| S01-T03 | Planned | — | — | — |
| S01-T04 | Planned | — | — | — |
| S01-T05 | Planned | — | — | — |
| S01-T06 | Planned | — | — | — |
| S01-T07 | Planned | — | — | — |

<a id="change-record"></a>
### I.2 Consumed sources and change record

| Source/artifact path | Revision or hash | Recorded at | Affected tasks | Review outcome |
|---|---|---|---|---|
| `docs/zerosum_ledger_mvp_plan.md` | — | — | S01-T01 … S01-T07 | — |
| `docs/zerosum_ledger_mvp_plan.md` §0.3 C1 (request vs stored order) | v1.2 | 2026-09-15 (doc 1.1) | S01-T05 | Resolved in master v1.2 (§0.3 C1) |
| `docs/zerosum_ledger_mvp_plan.md` §0.3 C4 (repeated lines) | v1.2 | 2026-09-15 (doc 1.1) | S01-T03, S01-T06 | Resolved in master v1.2 (§0.3 C4) |
| `docs/zerosum_ledger_mvp_plan.md` §0.3 C5 (event types, `event_id`) | v1.2 | 2026-09-15 (doc 1.1) | S01-T05 | Resolved in master v1.2 (§0.3 C5) |
| `docs/zerosum_ledger_mvp_plan.md` §0.3 C6 (settlement events, O6 group) | v1.2 | 2026-09-15 (doc 1.1) | S01-T05 | Resolved in master v1.2 (§0.3 C6) |
| `docs/zerosum_ledger_mvp_plan.md` §0.3 C7 (mapper-order source) | v1.2 | 2026-09-15 (doc 1.1) | S01-T05 | Resolved in master v1.2 (§0.3 C7) |
| `docs/zerosum_ledger_mvp_plan.md` §0.3 C16 (generator home, ArchUnit split, JSON Schema validator pin) | v1.2 | 2026-09-15 (doc 1.1) | S01-T05, S01-T06, S01-T07 | Resolved in master v1.2 (§0.3 C16) |
| `docs/zerosum_ledger_mvp_plan.md` §0.3 O6 (M1(d) scope) | v1.2 | 2026-09-15 (doc 1.1) | S01-T05 | Resolved in master v1.2 (§0.3 O6) |
| `docs/README.md` | — | — | All (procedures) | — |
| `docs/step_00_foundations.md` (register and execution record) | — | — | All | — |
| `gradle/libs.versions.toml` | — | — | S01-T05, S01-T06, S01-T07 | — |
| `settings.gradle.kts`, `build.gradle.kts`, and any build-convention sources named by D00-10 | — | — | All | — |
| `libs/money/build.gradle.kts`, `libs/contracts/build.gradle.kts` (as created by S00) | — | — | All | — |
| `.github/workflows/ci.yml` | — | — | S01-T06, S01-T07 | — |
| `docs/adr/0001-record-architecture-decisions.md` (template) | — | — | S01-T04, S01-T06 | — |
| `docs/adr/0002-*.md` (pinned versions) | — | — | S01-T05, S01-T06, S01-T07 | — |
| `.env.example` | — | — | S01-T06 | — |
| ISO 4217 List One source file (external; SHA-256 when downloaded) | — | — | S01-T01 | — |

<a id="handoff"></a>
## J. Handoff

**Which outputs the next steps consume**

| Consumer | Consumes | Where known, the consuming tasks |
|---|---|---|
| S02 Ledger core | D01-5 validator (structural rejection feeding quarantine codes), D01-6 chart (normal side for auto-provisioned accounts and balance presentation), D01-1 overflow policy, D01-9 goldens and expected balances, D01-10 generators and stream perturbation | [docs/step_02_ledger_core.md#s02-t01](step_02_ledger_core.md#s02-t01) (CHECK limits traced to D01-5), [docs/step_02_ledger_core.md#s02-t02](step_02_ledger_core.md#s02-t02), [docs/step_02_ledger_core.md#s02-t03](step_02_ledger_core.md#s02-t03), [docs/step_02_ledger_core.md#s02-t04](step_02_ledger_core.md#s02-t04), [docs/step_02_ledger_core.md#s02-t05](step_02_ledger_core.md#s02-t05) |
| S03 Order service and outbox | D01-8 schemas and `$defs` (OpenAPI reuse, producer contract tests), D01-5 validator and D01-7 allow-list (request validation), D01-4 `FareSplitter` (test data), D01-9 paired events and orders (mapper tests; mapper-order sources per §0.3 C7), and the golden O1/O3 writer `source.system` to confirm against D03-4 | [docs/step_03_order_service_outbox.md#s03-t01](step_03_order_service_outbox.md#s03-t01), [docs/step_03_order_service_outbox.md#s03-t03](step_03_order_service_outbox.md#s03-t03), [docs/step_03_order_service_outbox.md#s03-t04](step_03_order_service_outbox.md#s03-t04), [docs/step_03_order_service_outbox.md#s03-t05](step_03_order_service_outbox.md#s03-t05), [docs/step_03_order_service_outbox.md#s03-t07](step_03_order_service_outbox.md#s03-t07) |
| S05 Instruments and fake providers | D01-1 `Money` in instrument commands, D01-2 table as the ISO side of adapter mapping, D01-8 payment-event schema for outbox events, including the required fields per event type (§0.3 C5) | [docs/step_05_instruments_fake_providers.md#s05-t04](step_05_instruments_fake_providers.md#s05-t04), [docs/step_05_instruments_fake_providers.md#s05-t05](step_05_instruments_fake_providers.md#s05-t05), [docs/step_05_instruments_fake_providers.md#s05-t07](step_05_instruments_fake_providers.md#s05-t07) |
| S06 Reconciliation and verifier | Golden O6 and its per-currency settlement event (§0.3 C6), D01-3 `FeeCalculator` for fee lines, D01-5 zero-sum definition for I1 | [docs/step_06_reconciliation_verifier.md#s06-t01](step_06_reconciliation_verifier.md#s06-t01), [docs/step_06_reconciliation_verifier.md#s06-t02](step_06_reconciliation_verifier.md#s06-t02), [docs/step_06_reconciliation_verifier.md#s06-t04](step_06_reconciliation_verifier.md#s06-t04) |
| S08 Fault injection and ablation | D01-10 generators, seed line format and replay procedure; D01-4 `FareSplitter`. The A4 bug-injection seam stays in S08. | [docs/step_08_fault_injection_ablation.md#s08-t01](step_08_fault_injection_ablation.md#s08-t01), [docs/step_08_fault_injection_ablation.md#s08-t04](step_08_fault_injection_ablation.md#s08-t04) |
| S09 Demo, docs, release | ADR-0003 and ADR-0009 in the ADR index; M1 rows of the traceability table | [docs/step_09_demo_docs_release.md#s09-t02](step_09_demo_docs_release.md#s09-t02) |

S04 and S07 have no direct handoff from S01. S04's poison handling receives validator codes through S02's quarantine shape (D02-9).

**Where consumers must resolve current definitions**

- Read the current row of the owning decision in [docs/step_01_domain_contracts.md#decisions-and-outputs](step_01_domain_contracts.md#decisions-and-outputs) (H.1). Then read the artifact at the actual path in H.2/H.3, and only then the master proposal, for context.
- Never copy limits, patterns, fixture identities, schema fields or expected balances into a downstream document. Link the decision ID and load the artifact: `GoldenPayloads`, `ContractSchemas`, `ValidationLimits`, `ChartOfAccounts`.
- **A change needed downstream** (for example, an event type beyond §0.3 C5, or a writer `source.system` value chosen by D03-4) is a change request to the owning D01 decision per [docs/README.md#conflict-resolution](README.md#conflict-resolution). Goldens and schemas are updated in one commit, and consumers mark affected tasks "Needs review".

**Handoff conditions**

1. Every item in section G is checked, with evidence in H.4. S01 has no gate; its completion checkpoint is M1.
2. H.1 has no Pending rows; H.2 and H.3 have actual paths and revisions.
3. I.1 shows every task Done, or Blocked with the exact dependency. A Blocked task stops handoff unless the blocked output has no S02 consumer and the blocker is recorded in H.5.
4. I.2 reflects a change-detection run made immediately before handoff, with a review outcome for every row.
5. H.6 is updated, and S02's agent records acceptance in its own I.2 when it starts.
