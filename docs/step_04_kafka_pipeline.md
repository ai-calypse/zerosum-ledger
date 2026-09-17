# Step 04 — Kafka pipeline

> **Pack:** ZeroSum Ledger implementation docs · **Master:** [docs/zerosum_ledger_mvp_plan.md#step-04](zerosum_ledger_mvp_plan.md#step-04) (v1.2)
> **Doc version:** 1.1 · **Date:** 2026-09-15 · **Step status:** Planned (no implementation exists)
> **Planned effort:** 16 h (master schedule; see [docs/zerosum_ledger_mvp_plan.md#schedule-overview](zerosum_ledger_mvp_plan.md#schedule-overview)) · **Gate:** —
> **Shared procedures:** [docs/README.md#change-detection](README.md#change-detection) · [docs/README.md#source-of-truth](README.md#source-of-truth) · [docs/README.md#status-legend](README.md#status-legend)

<a id="purpose-and-outcome"></a>
## A. Purpose and outcome

**The problem.** When S02 and S03 are done, both halves of the money path exist, but nothing connects them. order-service commits money orders together with outbox rows, and its relay can publish them. ledger-service can apply orders, but only when a test driver calls it. Kafka sits between the two, and it brings failure modes that neither step has had to handle:

- Kafka delivers at least once. Its exactly-once guarantees don't reach PostgreSQL ([docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits)).
- The relay can publish the same order twice, and the ledger consumer can crash after its database commit but before its offset commit ([docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows), crash-point table).
- A malformed record could block a partition forever. Worse, a wrong error policy could skip a valid money order without anyone noticing.
- The S05 payout run needs a trustworthy measure of how stale ledger balances are before it pays anyone.

**The deliverable.** An effectively-once order pipeline with these parts:

1. Topics provisioned from a single definition source, using the partition key from ADR-0007, with explicit producer and consumer client configuration.
2. A ledger batch listener. It hands each poll batch to the S02 apply entrypoint and acknowledges offsets only after the database commit.
3. An error policy. Transient failures get bounded retries, then a listener pause with an observable signal and automatic resume. Poison records get a quarantine row and a DLQ record, and the partition keeps moving.
4. A freshness endpoint in ledger-service that fails closed.
5. A Testcontainers pipeline e2e suite (API → Kafka → ledger) that covers repeated delivery (M5 a), a crash before ack, and a relay crash after send.
6. A recorded trace-propagation result, and a relay-lag measurement with the SP2 trigger decision.

**Contribution to the MVP.**

- It delivers M5 (a) end to end, and the downstream half of M4 (c) ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)). It also verifies M6 (c), the audit walk from a balance to its source order, now that order-service exists (E11 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)).
- It supplies the freshness input for M10 (c), which S05 enforces.
- It emits the lag, pause, DLQ and quarantine signals behind the M12 (c) alerts, which S07 turns into rules.
- It gives S08 the crash-point seam and DLQ behavior it needs for faults F2, F4, F5 and F9.
- In the master schedule, this step completes the "M5 end-to-end" checkpoint. It has no decision gate.

**In scope**

- Topic definitions, provisioning, the partition-key ADR, and client configuration for order-service and ledger-service.
- The ledger listener, the poison-message policy and DLQ publishing for all consumers (C18 in the master's §0.3), the manual re-publish runbook step (C14), and the freshness endpoint.
- The pipeline e2e suite, the trace-propagation check and the relay-lag measurement.
- The conditional SP2 spike (S04-C01), only if evidence triggers it.

**Explicitly out of scope**

| Excluded | Owner |
|---|---|
| Apply-engine internals: dedupe, sorted locks, retry classification, the quarantine table and the quarantine-row write (§0.3 C18) | S02 (D02-3, D02-4, D02-9) |
| Outbox table, relay loop, cleanup job; relay tuning outside S04-C01 | S03 (D03-5) |
| Payment-event → order mapper logic and order-service's own quarantine table (§0.3 C8) | S03 (D03-1, D03-6) |
| The instrument-policy consumer, payment-event production, and the payout-run freshness check | S05 ([docs/step_05_instruments_fake_providers.md#s05-t09](step_05_instruments_fake_providers.md#s05-t09), [docs/step_05_instruments_fake_providers.md#s05-t10](step_05_instruments_fake_providers.md#s05-t10)) |
| Metric name registry, dashboards, alert rules, formal perf runs, SP4 | S07 |
| Chaos scripts, ablation flags (including the A1 dedupe seam) | S08 |
| Debezium CDC as the default publisher, Protobuf/Avro and schema registry | Master [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred) (Debezium only via S04-C01) |
| A ledger-updates topic | Master [docs/zerosum_ledger_mvp_plan.md#components](zerosum_ledger_mvp_plan.md#components) (no consumer exists) |
| Multi-broker replication, Kafka authentication | Master [docs/zerosum_ledger_mvp_plan.md#mvp-vs-production](zerosum_ledger_mvp_plan.md#mvp-vs-production) |
| The ledger replay tool | Master [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery) (conditional, unassigned; [docs/README.md#known-gaps](README.md#known-gaps)) |
| The admin quarantine-retry operation | Deferred (C14 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)); S04-T03 writes the manual re-publish runbook step instead |

<a id="agent-prompt"></a>
## B. Step-specific agent prompt

```text
You are the implementation agent for Step 04 (Kafka pipeline) of the ZeroSum Ledger project.
Repository root: zerosum-ledger/     Step document: docs/step_04_kafka_pipeline.md

1. Read docs/README.md (source-of-truth, conflict-resolution, change-detection, status-legend),
   then this document in full, then every source in section C at the linked anchors.
2. Inspect the current repository. Read the registers (section H) and execution records
   (section I) of S00, S01, S02 and S03. Resolve topic, schema, apply-engine, quarantine,
   outbox and auth definitions from those registers and the artifacts they reference,
   never from copies in documents (including this one).
3. Run the change-detection procedure in docs/README.md#change-detection before touching code.
   Record revisions/hashes for every consumed document and artifact in section I.2.
4. Complete only the remaining authorized S04 tasks, in dependency order:
   T01 -> T02 -> T03 -> T04 -> T05 -> T06 -> T07. S04-C01 runs only if its trigger is met.
5. Verify each task exactly as its "Verification and definition of done" field says.
   Every fault test must also prove the fault actually happened.
6. Record decisions D04-1..D04-8 (rationale, alternatives), actual paths, artifacts,
   evidence and blockers in sections H and I. Add decision trace comments next to
   authoritative configuration values.
7. Never invent measurements or test results. Mark unexecuted evidence "Not run" and
   blocked work "Blocked" with the exact missing dependency. Never mark blocked evidence as
   passed. Do not implement S05-S09 scope, deferred items, or non-goals.
8. Re-run change detection at every phase boundary and before handoff.
9. If you need to change something owned by the master or an upstream step (apply engine,
   quarantine shape, outbox relay, ledger OpenAPI file, compose topology, ArchUnit rules),
   stop and follow docs/README.md#conflict-resolution. Never override an owner silently and
   never weaken an acceptance gate.
```

<a id="required-reading"></a>
## C. Required reading and prerequisites

### C.1 Master sections

| Section | Why this step needs it |
|---|---|
| [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) | v1.2 resolutions that change this step: C8, C10, C11, C12, C14, C18, E11, O9 |
| [docs/zerosum_ledger_mvp_plan.md#step-04](zerosum_ledger_mvp_plan.md#step-04) | Objective, task list, exit criteria and named risks for this step |
| [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts) | Proposed topics, keys, partitions, consumer groups, retention, DLQ row and common client settings |
| [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits) | Why the outbox plus idempotent consumers are required; KIP-848 opt-in; silent idempotence disabling |
| [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm) | Proposed apply pseudocode: quarantine, dedupe, transient retry, pause, ack after commit; relay pseudocode |
| [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows) | W1 sequence and the crash-point/recovery table the e2e suite must reproduce |
| [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded) | Required behavior for Kafka down, a stuck consumer, and poison messages |
| [docs/zerosum_ledger_mvp_plan.md#stage-budgets](zerosum_ledger_mvp_plan.md#stage-budgets) | P2 stage budget (the outbox wait and relay send stages frame T07) |
| [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes) | SP2 question, timebox and decision criterion; SP3 (trace propagation) |
| [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions) | ADR-0007 partition key and ADR-0008 polling outbox |
| [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have) | M5 (a)–(d), M4 (c), M6 (c), M10 (c), M12 (a)/(c) acceptance criteria |
| [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers) | Kafka integration layer content; "never mock PostgreSQL or Kafka" |
| [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) | Retry, cancellation and graceful shutdown, stale-state (freshness) and recovery contracts |
| [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis) | Proposed freshness endpoint, role and error conventions; the quarantine-retry endpoint marked deferred |
| [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation) | Proposed metric and span names, including the v1.2 consumer-lag and listener-paused gauges; the single-clock assumption |
| [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring) | Alerts that consume this step's signals (quarantine, consumer lag or paused, DLQ messages) |
| [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) | TB1 (reader role on freshness), TB3 (Kafka without auth), TB4 (guarding test and chaos hooks) |
| [docs/zerosum_ledger_mvp_plan.md#scaling-triggers](zerosum_ledger_mvp_plan.md#scaling-triggers) | The Debezium upgrade trigger |
| [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling) | Flaky money-path tests are bugs |
| [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register) | R2, R3, R4 as they apply to the pipeline |

### C.2 Earlier step documents and their registers

| Register | Decision IDs consumed |
|---|---|
| [docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs) | D00-1 pinned versions (broker image, Kafka clients, spring-kafka, Testcontainers); D00-2 module layout; D00-3 compose topology (Kafka service, health checks, broker settings, memory limits); D00-5 CI job split; D00-6 OTel agent and metrics export; D00-7 SP3 result and fallback; D00-8 `ZS_*` environment conventions; D00-9 results template; D00-10 test tags and source sets |
| [docs/step_01_domain_contracts.md#decisions-and-outputs](step_01_domain_contracts.md#decisions-and-outputs) | D01-8 JSON Schemas and versioning rule; D01-9 golden payloads O1–O8; D01-10 seeded generators and seed reporting |
| [docs/step_02_ledger_core.md#decisions-and-outputs](step_02_ledger_core.md#decisions-and-outputs) | D02-1 ledger schema (applied-orders Kafka coordinates); D02-3 apply engine entrypoint; D02-4 lock strategy and retry classification (ADR-0005); D02-7 ledger read API contract and OpenAPI file; D02-8 invariant queries I2–I5; D02-9 quarantine table shape and the engine's quarantine-row write (§0.3 C8, C18); D02-10 SP1 result and batch-apply decision |
| [docs/step_03_order_service_outbox.md#decisions-and-outputs](step_03_order_service_outbox.md#decisions-and-outputs) | D03-1 orders schema, including order-service's own quarantine table (§0.3 C8); D03-2 money-order API contract; D03-3 idempotency semantics; D03-4 auth module and roles; D03-5 `libs/outbox` design (ADR-0008), including trace context captured in outbox headers (§0.3 C11); D03-6 payment-event consumer; D03-7 outbox stats endpoint |

### C.3 Artifacts that must already exist

The paths below are the paths *planned* in upstream documents. **Resolve every actual path from the owning register (H.2/H.3 of that step), not from this list.**

| Artifact | Planned path | Owner |
|---|---|---|
| Version catalog and compose image tags | `gradle/libs.versions.toml`, `docker-compose.yml` | D00-1, D00-3 |
| CI workflow with integration and e2e jobs | `.github/workflows/ci.yml` | D00-5 |
| SP3 decision record | `docs/adr/0002-*.md` | D00-7 |
| Results template | `docs/results/TEMPLATE.md` | D00-9 |
| JSON Schemas, golden payloads, generators | `libs/contracts/` (generators per D01-10) | D01-8, D01-9, D01-10 |
| Ledger migrations, apply engine, invariant queries | `services/ledger-service/` | D02-1, D02-3, D02-8, D02-9 |
| Lock and retry ADR | `docs/adr/0005-*.md` | D02-4 |
| Ledger OpenAPI | `openapi/ledger-service.yaml` | D02-7 |
| SP1 lock study | `docs/results/sp1-lock-study.md` | D02-10 |
| Money-order API, auth module, mapper consumer, outbox stats | `services/order-service/`, `openapi/order-service.yaml` | D03-2, D03-4, D03-6, D03-7 |
| Outbox library and relay ADR | `libs/outbox/`, `docs/adr/0008-*.md` | D03-5 |

### C.4 Blocking vs independent work

| Missing input | Blocks | Independent preparation that can proceed |
|---|---|---|
| D02-3 apply entrypoint | S04-T02, S04-T03, S04-T05 | S04-T01; the T04 lag calculation (it only needs the consumer group); test scaffolding against a fake engine |
| D02-9 quarantine shape and engine quarantine write (§0.3 C18) | Poison-path verification in S04-T03 | T03 classification, pause/resume, DLQ publishing and the runbook step |
| D02-10 SP1 decision | Final batch-size setting in S04-T02 | A batch-capable listener with the mode read from configuration |
| D03-5 relay and its injection point | Case D of S04-T05; S04-T06; S04-T07 | S04-T01–T04; cases A–C of T05 |
| D03-4 auth module | Endpoint security in S04-T04; API calls in S04-T05 | Freshness calculation and its unit tests |
| D02-7 ledger OpenAPI file | Contract publication in S04-T04 | Endpoint implementation; draft of the operation for the change request |
| D00-7 SP3 outcome | S04-T06 | Everything else |
| D01-9 / D01-10 fixtures | Workload in S04-T05, poison fixtures in S04-T03 | Harness wiring. Never hand-invent replacement fixtures. |
| Reference laptop time for measurement | Result of S04-T07 (stays `Not run`) | The measurement driver and results-file skeleton |

<a id="ownership-and-requirements"></a>
## D. Ownership and engineering requirements

### D.1 Decisions and contracts owned by this step

| Decision ID | What is decided | Master's proposed starting point |
|---|---|---|
| D04-1 | Topic definitions: names, partition counts, retention, DLQ topics and DLQ name derivation. Also the single definition source, the provisioning mechanism, behavior when an existing topic differs from its definition, and the partition key (ADR-0007). | [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts); ADR-0007 in [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions); `NewTopic` beans in [docs/zerosum_ledger_mvp_plan.md#step-04](zerosum_ledger_mvp_plan.md#step-04) |
| D04-2 | Producer and consumer client configuration for every service: explicitly set properties, consumer group IDs, listener concurrency, the configuration location, and a drift test; how trace context captured in outbox headers is carried into Kafka record headers | Common settings in [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts); [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits); pool sizing in [docs/zerosum_ledger_mvp_plan.md#bottlenecks](zerosum_ledger_mvp_plan.md#bottlenecks); C11 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) |
| D04-3 | Ledger listener design: batch listener, how a poll batch maps to apply-engine calls, ack mode and timing, shutdown and rebalance behavior | [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm); cancellation row in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting) |
| D04-4 | Poison-message and error-handling policy for all consumers: transient, poison and unclassified classes; the single retry layer; pause, alert signal and resume; DLQ naming, partitioning and headers; ordering of the engine's quarantine write, the DLQ publish and the ack; undecodable records; the manual re-publish runbook step | [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm); retries row in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting); [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded); C8, C10, C14 and C18 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) |
| D04-5 | Freshness endpoint contract and computation: fields, units, fail-closed semantics, OpenAPI operation (the timestamp basis is fixed by §0.3 C12) | [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis); stale-state row in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting); C12 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications) |
| D04-6 | Pipeline e2e test suite: harness type, cases (including the M6 (c) audit walk, §0.3 E11), fault-exercised assertions, CI job and tag | Kafka integration row in [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers); [docs/zerosum_ledger_mvp_plan.md#step-04](zerosum_ledger_mvp_plan.md#step-04) |
| D04-7 | Relay lag measurement method and result; SP2 trigger decision | SP2 in [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes); P2 in [docs/zerosum_ledger_mvp_plan.md#stage-budgets](zerosum_ledger_mvp_plan.md#stage-budgets) |
| D04-8 | *(appended)* Crash-point test seam at "after DB commit, before offset ack" in ledger-service: interface, one-shot behavior, activation guard, and reuse by S08 | Crash points in [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows); TB4 in [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries) |

D04-8 is appended because the brief's handoff says S08 consumes "crash-point seams", and no other decision ID covers them.

### D.2 Inherited decisions

| Decision | Owner | How this step must use it |
|---|---|---|
| Pinned broker image, Kafka client, spring-kafka and Testcontainers versions | D00-1 ([docs/step_00_foundations.md#decisions-and-outputs](step_00_foundations.md#decisions-and-outputs)) | Use only the pinned versions. Check library behavior (ack modes, error handlers, admin client) against the docs for *those* versions. |
| Module layout | D00-2 | Place the topic-definition source, listener and tests where the layout says; the planned paths below are proposals |
| Compose Kafka service, broker settings, health checks, `chaos` profile | D00-3 | T01 checks broker auto-topic-creation. Any compose change goes through a change request. |
| CI job split and test tags | D00-5, D00-10 | Tag T01–T04 tests as Kafka integration and T05 as e2e, per those conventions |
| OTel agent attach and metrics export; SP3 outcome | D00-6, D00-7 | New metrics use the selected export path. T06 checks propagation under the SP3 outcome, including any fallback. |
| Environment variables | D00-8 | Any new variable (e.g., the D04-8 activation flag) follows the `ZS_*` convention and appears in `.env.example` only if operators need it |
| Schema identifiers and versioning rule | D01-8 | The listener decodes and version-checks payloads by this rule, and never by ad-hoc field sniffing |
| Golden payloads and seeded generators | D01-9, D01-10 | T03/T05 workloads. Seeds are printed on failure (ADR-0009). |
| Applied-orders Kafka coordinates and record timestamps | D02-1 | T02 passes partition and offset into the engine exactly as the schema expects |
| Apply entrypoint (batch-capable) | D02-3 ([docs/step_02_ledger_core.md#s02-t02](step_02_ledger_core.md#s02-t02)) | The only apply path. The listener never writes ledger tables directly. |
| Retry classification (ADR-0005) | D02-4 | T03 reuses the classifier. The listener does not maintain its own SQLSTATE list. |
| Read API contract and OpenAPI file | D02-7 | T04 adds the freshness operation additively, through the change request procedure |
| Invariant queries I2–I5 | D02-8 | T02/T05 assert money state with these queries |
| Quarantine table shape (surrogate key, nullable order ID; §0.3 C8) and the engine's quarantine-row write (§0.3 C18) | D02-9, D02-3 | The engine writes quarantine rows. T03 routes engine-reported poison and undecodable records through that path, then publishes to the DLQ. No change request on the shape is needed. |
| Batch apply must-have decision | D02-10 ([docs/step_02_ledger_core.md#s02-t07](step_02_ledger_core.md#s02-t07)) | Sets the T02 batch mode |
| G1 alternative (account-level optimistic locking) | D02-4 via [docs/step_02_ledger_core.md#s02-c01](step_02_ledger_core.md#s02-c01) | If adopted, optimistic-conflict errors join the transient class through D02-4's classifier; the listener design does not change |
| Money-order API and idempotency semantics | D03-2, D03-3 | T05 posts orders and retries with the same keys |
| Auth module and roles | D03-4 | T04 protects freshness with the reader role; T05 uses writer tokens |
| `libs/outbox` relay, producer usage, metrics, cleanup (ADR-0008) | D03-5 ([docs/step_03_order_service_outbox.md#s03-t05](step_03_order_service_outbox.md#s03-t05)) | T01 applies D04-2 producer properties to the relay. T06 verifies the trace context it captures in outbox headers (§0.3 C11). T05 case D uses its injection point. T07 reads its lag metric. |
| Payment-event consumer | D03-6 ([docs/step_03_order_service_outbox.md#s03-t07](step_03_order_service_outbox.md#s03-t07)) | T01 applies D04-2 consumer properties and topic provisioning to it, without changing its mapping or idempotency behavior. T03 applies the D04-4 poison policy and DLQ publishing to it; its quarantine rows go to order-service's own table (D03-1, §0.3 C8). |
| Outbox stats | D03-7 | T05 quiesce condition; T07 cross-check; the composite freshness context for S05 |
| Metric and span names | Future D07-1 (S07) | Expose the underlying signals for the gauges added in v1.2 (O9 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications): consumer lag in records and in seconds, listener paused), plus the counters proposed in [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation). D07-1 owns the names; record the emitted names in H.2. |

### D.3 Engineering requirements

**Module and package boundaries**

- **Topic definitions.** Topic definitions live in one plain-Java source with no Spring dependency, so every service and test reads the same names and partition counts (D04-1). Services build their `NewTopic` beans from it. No topic name string appears anywhere else.
- **Where Kafka code lives in ledger-service.** Kafka-specific code (container factory, listener, error handler, DLQ recoverer, freshness calculator, D04-8 seam) lives in its own package, for example `…ledger.kafka` and `…ledger.freshness`. The apply engine (D02-3) receives decoded orders plus their Kafka coordinates, never `ConsumerRecord` or `Acknowledgment` types. This keeps S07's SP4 changes to the engine independent of the transport.
- **DLQ publishing.** Consuming services publish to Kafka only through the framework dead-letter recoverer. The relay-only ArchUnit rule covers application code and exempts that recoverer (C10 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications); rule owned by D03-5). Don't write application-level DLQ senders.

**Interfaces**

| Interface | Direction | Contract owner |
|---|---|---|
| Money-orders topic record (key, value, headers) | relay → listener | D04-1 (topic, key); D01-8 (payload) |
| Apply entrypoint call and result (applied, duplicates skipped, quarantined) | listener → engine | D02-3 |
| Retry classification | engine/listener → error handler | D02-4 |
| DLQ record (original key, value and headers plus error headers) | recoverer → DLQ topic | D04-4 |
| Freshness read API | clients (S05 payout run, S06/S08 quiesce checks) → ledger-service | D04-5 |
| Crash-point seam | listener → test or chaos hook | D04-8 |

**Data flow and order of operations.** This ordering is the core of effectively-once. The snippet is illustrative; the contract is D04-3/D04-4.

```text
// ILLUSTRATIVE ONLY — not a contract
onPollBatch(records):
  decoded, undecodable = decode(records)             // D01-8 rules; bad bytes never crash the poll loop
  result = applyEntrypoint(decoded, undecodable)    // D02-3: applies orders AND writes quarantine rows (§0.3 C18); COMMITTED on return
  crashPoint(AFTER_COMMIT_BEFORE_ACK)                // D04-8; no-op unless activated under the guard
  publishToDlqAndAwait(result.quarantined)         // includes undecodable records; failure => throw, no ack
  acknowledge(records)                               // offsets for the whole poll batch, last
```

**Lifecycle behavior**

- **Startup.**
  1. Flyway migrations (D02-1) finish.
  2. Topic definitions are verified or created.
  3. Only then does the listener container start.

  A topic whose partition count differs from D04-1 fails startup.
- **Normal operation.** Offsets are committed only after the apply transaction commits and any DLQ publish is confirmed.
- **Graceful shutdown.** Follow the cancellation contract in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting): stop polling, finish the in-flight batch, commit its offsets, within the selected shutdown timeout (traced to D04-3).
- **Crash.** Uncommitted offsets are redelivered, and the engine's dedupe drops orders that were already applied. The pipeline never compensates for a crash by editing ledger rows.
- **Retry and pause.** There is exactly one retry layer. After retries are exhausted, the container pauses, keeps its position, and resumes automatically after a health probe succeeds (D04-4).
- **Rebalance.** Revoked partitions may be redelivered to another consumer, and dedupe makes that harmless. A failed offset commit is logged and counted, never "fixed" by rolling back the database.

**Security and trust boundaries**

- **TB3.** Kafka has no authentication in the MVP. The DLQ carries the original payload, which contains no secrets. Logs contain classification, topic, partition and offset, but not payloads or tokens (D00-8 redaction guideline).
- **TB1.** The freshness endpoint requires the reader role through the D03-4 module.
- **TB4.** The D04-8 seam defaults to a no-op implementation. It activates only when a test or the `chaos` profile explicitly enables it under the existing chaos guard (`ZS_ALLOW_CHAOS`, per [docs/zerosum_ledger_mvp_plan.md#trust-boundaries](zerosum_ledger_mvp_plan.md#trust-boundaries)). It must be impossible to enable in the `demo-public` profile.

**Deployment constraints**

- One broker with no replication (D00-3), so the RF=1 data-loss window in [docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery) applies.
- One ledger-service instance. Listener concurrency is a D04-2 setting, not a replica count.
- Container memory limits from D00-3.
- Hikari pool size must cover listener concurrency plus HTTP concurrency ([docs/zerosum_ledger_mvp_plan.md#bottlenecks](zerosum_ledger_mvp_plan.md#bottlenecks)).

### D.4 Configuration ownership

| Runtime value | Authoritative location (planned; actual path recorded in H.2) | Trace |
|---|---|---|
| Topic names, partition counts, retention, DLQ derivation | Topic-definition source, proposed `libs/contracts/src/main/java/…/kafka/TopicDefinitions.java` | `// decision: D04-1 — docs/step_04_kafka_pipeline.md#decisions-and-outputs` |
| Partition key rule | ADR-0007, proposed `docs/adr/0007-partition-key.md`; the relay uses the outbox message key (D03-5) | ADR reference |
| Producer and consumer properties, group IDs, listener concurrency | `spring.kafka` section of each service's `application.yaml` (order-service, ledger-service; instrument-service in S05) | `# decision: D04-2 — docs/step_04_kafka_pipeline.md#decisions-and-outputs` |
| Trace-context header propagation from outbox headers to Kafka headers (§0.3 C11) | Relay producer configuration per D03-5, plus the order-service `application.yaml` | `# decision: D04-2 — …` |
| Ack mode, batch mode, shutdown timeout | ledger-service `application.yaml` plus the container factory configuration class | `# decision: D04-3 — …` |
| Retry schedule (only if the listener owns retry), pause probe schedule, DLQ header set | ledger-service `application.yaml` plus the error-handling configuration class | `# decision: D04-4 — …` |
| Manual re-publish runbook step (§0.3 C14) | Quarantine section of `docs/runbook.md` (created if absent; S07-T03 and S09 extend it) | `<!-- decision: D04-4 — docs/step_04_kafka_pipeline.md#decisions-and-outputs -->` |
| Freshness admin-call timeouts | ledger-service `application.yaml` | `# decision: D04-5 — …` |
| Freshness operation schema | `openapi/ledger-service.yaml` (file owned by D02-7; operation owned by D04-5) | `# decision: D04-5 — …` |
| Crash-seam activation flag | Environment variable per D00-8, read only under the guard | `# decision: D04-8 — …` |

**Permitted alternatives that downstream work must handle**

- **SP2 / Debezium (S04-C01).** The Outbox Event Router may replace the polling relay (D04-7 → D03-5, ADR-0008). Topic names and keys (D04-1) and the listener (D04-3) must not change. Relay-lag metrics and the F1/F4 fault semantics change for S07 and S08.
- **SP1 outcome (D02-10).** If batched apply becomes must-have, the listener passes whole poll batches to the engine. Otherwise it works in per-order mode, still acknowledging after commit.
- **G1 alternative (S02-C01).** With account-level optimistic locking, retry classification changes through D02-4 only.
- **SP3 fallback (D00-7).** Prometheus scraping instead of OTLP metrics, or the older Boot line, changes how S04 metrics are exported, not what is emitted. T06 records the effect.
- **SP4 options (S07-T06).** Batching, hot-entity sharding, or both may change the batch mode, listener concurrency or partition counts, through change requests to D04-1, D04-2 and D04-3.
- **G2 alternative (S05-C01).** If instrument-service writes orders through shared validation, a second producer writes to the money-orders topic. It must use the D04-1 key and the D04-2 producer properties. The listener is unaffected, because ADR-0007 relies on commutative balance arithmetic rather than cross-producer ordering.

<a id="phases-and-tasks"></a>
## E. Phases and tasks

| Phase | Tasks | Hours |
|---|---|---|
| Phase 1 — Topics and listener | S04-T01 (1), S04-T02 (3) | 4 |
| Phase 2 — Error handling | S04-T03 (4) | 4 |
| Phase 3 — Freshness and e2e | S04-T04 (2), S04-T05 (4) | 6 |
| Phase 4 — Trace and measurement | S04-T06 (1), S04-T07 (1) | 2 |
| **Total** | | **16** |

<a id="phase-1"></a>
### Phase 1 — Topics and listener

**Objective:** Orders published by the S03 relay reach the S02 apply engine through explicitly configured topics and clients, and offsets are acknowledged only after the ledger commit.

**Exit checkpoint:** T01 and T02 verification passes. D04-1, D04-2 and D04-3 rows are filled in H.1/H.2. Change detection has been re-run and recorded in I.2.

<a id="s04-t01"></a>
#### S04-T01 — Topic provisioning and explicit client configuration
- **Outcome:** Every topic the pipeline uses is created from one definition source by the services that use it, and every producer and consumer in order-service and ledger-service runs with the client properties selected in D04-2, set explicitly.
- **Estimate:** 1 h
- **Inputs:** C11 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications); [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts) (topic table, common settings); [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits) (KIP-848 opt-in, silent idempotence disabling); ADR-0007 in [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions); D00-1, D00-2, D00-3, D03-5, D03-6; `docker-compose.yml`, `libs/outbox/`, the order-mapper consumer from D03-6.
- **Depends on:** S00-T03, S03-T05, S03-T07
- **Instructions:**
  1. Run change detection ([docs/README.md#change-detection](README.md#change-detection)) and record revisions in I.2.
  2. Create the topic-definition source (D04-1), starting from the master's proposed table. For the money-orders topic, the payment-events topic and each DLQ, it holds the name, partition count, retention and key semantics, plus one function that derives a DLQ name from a source topic. Plain Java, no Spring dependency.
  3. In each service, declare `NewTopic` beans built from that source for every topic the service produces to, consumes from or dead-letters to. For order-service: money-orders, payment-events, and the payment-events DLQ. For ledger-service: money-orders and its DLQ. Declarations for instrument-service belong to S05. Identical declarations from several services are intentional: creation is idempotent, and no service depends on another's startup order.
  4. Add a startup check that fails with a clear message when an existing topic's partition count differs from its definition. Don't let the admin client add partitions silently, because that remaps keys to partitions and breaks per-group ordering (ADR-0007). Confirm the admin client's default behavior on the pinned spring-kafka version.
  5. Check whether the D00-3 broker configuration allows automatic topic creation. If it does, raise a change request to S00: a misspelled topic name would otherwise be created silently with broker defaults.
  6. Write D04-2. Start from the master's common settings and set idempotence and acks explicitly on every producer (including the `libs/outbox` relay), even where they match defaults. On every consumer, set the group protocol, auto-commit, isolation level, max poll records and group ID. Put the values in each service's `spring.kafka` section with D04-2 trace comments. Don't change relay or mapper behavior. Record in D04-2 how the trace context captured in outbox headers (§0.3 C11) is copied into Kafka record headers, so T06 can verify it.
  7. Add a configuration test per service. It reads the effective properties from the built producer and consumer factories and asserts every D04-2 property, so a later edit or framework default can't drop one silently.
  8. Write ADR-0007 (partition key), taking the master's decision as the starting point, and link it from D04-1.
- **Edge cases and failure behavior:**
  - A topic left over from an earlier run (persistent compose volume) with a different partition count fails startup. The local reset procedure is documented in the ADR; nothing is repartitioned automatically.
  - Two services creating the same topic at once: "topic already exists" counts as success.
  - Consumer properties that only the classic group protocol accepts are rejected once the new protocol is enabled. Remove them; never revert the protocol to make startup pass.
  - A producer setting that conflicts with explicit idempotence makes producer construction fail. That is intended: it replaces silent disabling with a loud failure.
  - Broker unreachable at startup: the service reports unhealthy through its health check within the admin timeout selected in D04-1, instead of hanging or crash-looping without a log line.
- **Outputs:** Planned: the topic-definition source (proposed `libs/contracts/src/main/java/…/kafka/TopicDefinitions.java`); `KafkaTopicsConfig` classes in `services/order-service/` and `services/ledger-service/`; `spring.kafka` sections in both `application.yaml` files; `docs/adr/0007-partition-key.md`; configuration tests and `TopicProvisioningIT` (Kafka integration tag per D00-10).
- **Verification and definition of done:**
  - `TopicProvisioningIT` starts both service contexts against an empty Testcontainers broker. An admin-client describe shows every D04-1 topic with its defined partition count and retention configuration.
  - `TopicMismatchIT` pre-creates a money-orders topic with a different partition count. Service startup fails with the documented message.
  - Per-service configuration tests pass for every D04-2 property.
  - After the first poll, the admin-client consumer-group description reports the new consumer protocol group type for each consumer group.
  - `PartitionKeyIT`: two relay-published orders with the same order group land on the same partition, and orders from different groups are not forced onto one.
  - ADR-0007 is committed. D04-1 and D04-2 are recorded in H.1 and H.2.

<a id="s04-t02"></a>
#### S04-T02 — Ledger batch listener with manual ack after DB commit
- **Outcome:** ledger-service consumes the money-orders topic with a batch listener that hands each poll batch to the D02-3 entrypoint and commits offsets only after the apply transaction(s) commit. Redelivered orders drop out through the engine's dedupe.
- **Estimate:** 3 h
- **Inputs:** [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm) (ack after commit, offset order); crash points in [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows); cancellation and concurrency rows in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting); pool sizing in [docs/zerosum_ledger_mvp_plan.md#bottlenecks](zerosum_ledger_mvp_plan.md#bottlenecks); D01-8, D01-9, D02-1, D02-3, D02-8, D02-10, D04-1, D04-2; the apply engine module referenced by D02-3; `docs/results/sp1-lock-study.md`.
- **Depends on:** S04-T01, S02-T02, S02-T07
- **Instructions:**
  1. Configure the listener container factory for batch listening and manual acknowledgment. In D04-3, choose between queued and immediate offset commits and record why. Take listener concurrency from D04-2, and size the Hikari pool to cover it.
  2. Decode with an error-handling deserializer, so undecodable bytes reach the listener as a failed record instead of killing the poll loop. Check the schema identifier and version per D01-8. Pass each decoded order with its topic, partition, offset and record timestamp to D02-3, in the shape D02-1 expects.
  3. Apply the batch mode from D02-10:
     - **Batching must-have, or a batch size above one configured:** pass the poll batch (or chunks of the configured size) to one engine call, keeping offset order within each partition.
     - **Per-order mode:** call the engine once per record.
     
     In both modes, acknowledge the whole poll batch once, after the last commit.
  4. Never acknowledge inside a transaction, before the engine returns, or after an exception. Until T03 lands, any failed or undecodable record fails the batch without acknowledgment.
  5. If the offset commit fails after the database commit (rebalance, broker unavailable), log and count it and continue. Never roll back or compensate ledger state; redelivery plus dedupe covers it.
  6. Implement graceful shutdown per the cancellation contract: stopping the container lets the in-flight batch commit and acknowledge within the shutdown timeout selected in D04-3. The listener must not start before Flyway and the T01 topic check complete.
  7. Add the D04-8 seam: an interface called between engine return and acknowledgment, with a no-op production implementation. Test implementations may halt the JVM or close the context. Activation follows the guard in D.3 and is one-shot.
  8. Emit per-batch counts of records received, orders applied and duplicates skipped, taken from the engine result, following the naming style proposed in [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation). Record the actual names in H.2 for D07-1.
- **Edge cases and failure behavior:**
  - A poll batch spans several partitions: build engine input so each partition's offset order is preserved. Cross-partition order doesn't matter (ADR-0007).
  - A batch made up entirely of duplicates (after a crash): the engine applies nothing, and the listener still acknowledges.
  - An empty poll: no engine call.
  - The listener thread is interrupted during shutdown mid-transaction: the transaction rolls back, nothing is acknowledged, and the records are redelivered after restart.
  - A batch outlives the consumer's poll interval (long lock waits): the assignment is lost, the late commit fails, and redelivery is deduped. Count the event, because it is an early SP4 signal for S07.
  - In per-order mode, processing time grows linearly with batch size. Watch it against the poll interval.
- **Outputs:** Planned: `services/ledger-service/src/main/java/…/ledger/kafka/MoneyOrderListener.java`, the container factory configuration, `CrashPoint` (D04-8), `LedgerListenerIT`, `DuplicateDeliveryIT`, `GracefulShutdownIT`, `AckOrderingTest`.
- **Verification and definition of done:**
  - `AckOrderingTest` (unit): with an engine test double that throws, `acknowledge` is never called; with one that succeeds, it is called exactly once, after the engine returns.
  - `LedgerListenerIT`: golden orders O1–O7 (D01-9) published with ADR-0007 keys produce the golden balances, read through the D02-7 API or the D02-8 queries.
  - `DuplicateDeliveryIT`: every record is produced the number of times M5 (a) specifies, with copies interleaved so they span different polls. The D02-8 I2–I4 queries pass, the applied-orders table has exactly one row per order, and the duplicate-skip count is above zero, which proves duplicates reached the engine.
  - `GracefulShutdownIT`: the context stops during a load of seeded orders (D01-10, seed printed) and restarts. I3 and I4 pass, and the ledger balances equal the sum of entries over the produced orders.
  - D04-3 is recorded, with the choices from instructions 1, 3 and 6.

<a id="phase-2"></a>
### Phase 2 — Error handling

**Objective:** Every listener failure is classified. Transient failures never skip money, and poison records never block a partition.

**Exit checkpoint:** T03 verification passes. D04-4 and D04-8 are recorded. The runbook step is written. Any blocker on D03-6 is logged in H.5 and I.2.

<a id="s04-t03"></a>
#### S04-T03 — Transient retry to pause and alert signal; poison to DLQ and quarantine
- **Outcome:** Transient failures are retried in exactly one bounded layer, then pause the listener with an observable signal and resume automatically. Poison records get a quarantine row written by the apply engine and a confirmed DLQ record, and the partition continues. The same policy covers every consumer (§0.3 C18). Unclassified failures pause instead of being dead-lettered.
- **Estimate:** 4 h
- **Inputs:** C8, C10, C14, C18 and O9 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications); [docs/zerosum_ledger_mvp_plan.md#apply-algorithm](zerosum_ledger_mvp_plan.md#apply-algorithm) (quarantine in the same transaction, retry then pause); retries row in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting); [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded); DLQ row in [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts); [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring) (consumer lag or paused, quarantine and DLQ alerts); I6 in [docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants); D01-8, D01-9, D01-10, D02-3, D02-4, D02-9, D04-1, D04-3, D04-8; ADR-0005 (path from D02-4).
- **Depends on:** S04-T02, S02-T01, S02-T02, S03-T01, S03-T07
- **Instructions:**
  1. Write the D04-4 classification table:
     - **Transient:** exactly the classes D02-4's classifier marks retryable, plus failure to acquire a database connection.
     - **Poison:** undecodable bytes; an unknown schema identifier or version; orders the engine reports as structurally invalid; order-specific deterministic apply errors that D02-3 reports (for example, overflow).
     - **Unclassified:** everything else, including errors thrown by the error handler itself. Unclassified is handled like exhausted transient failure: pause and alert, never DLQ. A dead-lettered valid order is skipped money.
  2. **Single retry layer.** Read D02-3. If the engine already retries transient errors with backoff, the container must not add a second loop; it reacts to the engine's "retries exhausted" outcome. Otherwise, configure the container error handler's backoff, starting from the master's proposed schedule, with values traced to D04-4. Record which layer owns retry.
  3. Keep every retry sleep below the consumer's poll-interval limit. If the schedule can exceed it, retry by seeking and pausing instead of sleeping on the consumer thread. Check on the pinned spring-kafka version.
  4. **Pause.** Pause the ledger listener container (D04-4 records whether it pauses the whole container or single partitions; the default is the whole container, because database faults affect all partitions). Don't acknowledge, don't dead-letter, and seek back to the first unacknowledged record.
  5. **Alert signal.** Expose the listener-paused signal added in v1.2 (§0.3 O9; name owned by D07-1), a pause-event counter tagged with the cause class, and one WARN log line per pause without payload. S07-T03 turns these into the "consumer lag or paused" alert.
  6. **Automatic resume.** While paused, a scheduled probe runs a trivial query on the service's own pool. On success, resume and reprocess from the saved position. On renewed failure, pause again and lengthen the probe interval up to a cap. Record the probe schedule in D04-4. No manual step may be needed, because S08 faults F5 and F9 expect unattended recovery.
  7. **Poison order of operations:**
     1. The apply engine writes the quarantine row in the D02-9 shape, in the same transaction as the batch's valid orders (§0.3 C18). The listener supplies the record bytes and Kafka coordinates and never writes quarantine rows itself. Because the Kafka coordinates are unique together, a redelivered record inserts with do-nothing-on-conflict semantics and never gets a second row. Resolved in master v1.2 (§0.3 C25); verify that D02-9's insert behaves this way.
     2. After the commit, publish the DLQ record and wait for broker confirmation.
     3. Acknowledge.
     
     A crash between steps 2 and 3 produces a duplicate DLQ record, which is tolerated and documented. It must not produce a second quarantine row (see step 1).
  8. **DLQ record.** Keep the original key, value bytes and headers (including trace context). Add error headers: original topic, partition and offset, classification, error code, exception class. Derive the destination name through D04-1. Configure partition selection explicitly by key or partitioner, because the recoverer's default reuses the source partition number, which may not exist in a DLQ with fewer partitions.
  9. **Undecodable records.** Pass them to the engine's quarantine path with a null order ID, which the surrogate-key shape allows (§0.3 C8), then publish them to the DLQ like any other poison record. Don't fabricate an order ID.
  10. **Isolation in batch mode.** When a multi-record engine call fails with an order-specific, non-transient error that the engine didn't quarantine itself, re-run that batch one record at a time to isolate the record, then have the engine quarantine it. Reuse D02-3's own isolation if it has one.
  11. A DLQ publish failure (topic missing, broker down) is transient: no acknowledgment, retry, then pause.
  12. Expose the quarantine counter behind the quarantine alert, and a DLQ-published counter behind the v1.2 DLQ alert ([docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring)).
  13. **All consumers (§0.3 C18).** D04-4 is the poison policy for every consumer. Apply the same classification, DLQ naming and partitioning to the order-mapper consumer (D03-6), whose quarantine rows go to order-service's own table (D03-1, §0.3 C8). If D03-6 lacks that quarantine write, record a blocker for the S03 owner in H.5; don't write order-service's table from S04 code. S05-T09 applies D04-4 to the instrument-policy consumer.
  14. **Manual re-publish runbook step (§0.3 C14).** The admin retry endpoint is deferred, so write the D04-4 runbook step in the quarantine section of `docs/runbook.md`. The steps: find the unresolved quarantine row; fix the cause; re-publish the original bytes (from the DLQ record or the quarantine payload) to the source topic with the original key and headers, using the stock Kafka CLI tools; confirm the order applied (dedupe makes a repeat harmless); then mark the row resolved under the D02-9 rules. Don't build a tool for it.
- **Edge cases and failure behavior:**
  - PostgreSQL restarts: connection errors, retries, pause, probe, resume. No DLQ records, no skipped offsets.
  - Lock-timeout storms under hot-entity load are transient, so pauses may fire under load. S07 tunes this through SP4, not by reclassifying.
  - A poison record between valid records on one partition: the valid records apply, the poison record is quarantined, and all offsets are acknowledged only after DLQ confirmation.
  - A null key on a poison record: the DLQ partitioner handles it; confirm in a test.
  - Shutdown requested while paused: container stop wins, and the probe must not block shutdown.
  - Resolving a quarantined record after a fix uses the instruction 14 runbook step, because the admin retry endpoint is deferred (§0.3 C14).
- **Outputs:** Planned: error-handling and DLQ recoverer configuration and the resume probe in `services/ledger-service/src/main/java/…/ledger/kafka/`; `PoisonRecordIT`, `TransientRetryIT`, `PauseResumeIT`, `UnclassifiedErrorIT`, `DlqPartitioningIT`, `QuarantineIdempotencyIT`, `MapperPoisonIT`; the quarantine re-publish runbook step in `docs/runbook.md`.
- **Verification and definition of done:**
  - `PoisonRecordIT`: one partition receives, in order, a valid order, a structurally invalid order (from D01-9 or D01-10's invalid mode), undecodable bytes, and a valid order. Both valid orders apply. There are two quarantine rows: one for the invalid order, and one with a null order ID for the undecodable record. The DLQ holds two records with the original keys and the D04-4 error headers. The committed offset is past all four records, and the quarantine counter has increased.
  - `TransientRetryIT`: a test connection holds a lock on an entity row for less than the retry budget. The order applies exactly once, with no DLQ record and no quarantine row.
  - `PauseResumeIT`: the lock is held (or the PostgreSQL container stopped) beyond the retry budget. The paused gauge reads paused, the committed offset doesn't move, lag is above zero, and the DLQ stays empty. After release, the listener resumes without manual action, the order applies exactly once, and the gauge clears.
  - `UnclassifiedErrorIT`: an engine test double throws an unexpected runtime exception. The listener pauses, and no DLQ record exists.
  - `DlqPartitioningIT`: poison records from source partitions numbered beyond the DLQ's partition range are dead-lettered successfully.
  - `QuarantineIdempotencyIT`: the D04-8 seam halts after commit, before the DLQ publish. After restart there is exactly one quarantine row and at least one DLQ record.
  - `MapperPoisonIT` (order-service): a payment event that fails its mapping check is quarantined in order-service's own table and dead-lettered per D04-4. If D03-6 lacks the write, the H.5 blocker is recorded instead.
  - **Runbook dry run:** following the instruction 14 step against a quarantined record whose cause the test has fixed, the order applies exactly once and the row is marked resolved.

<a id="phase-3"></a>
### Phase 3 — Freshness and e2e

**Objective:** Downstream steps can read how stale the ledger is, and a repeatable e2e suite proves effectively-once application across the real API, relay, broker and ledger.

**Exit checkpoint:** T04 and T05 verification passes in the D00-5 CI jobs. D04-5 and D04-6 are recorded. The OpenAPI change request is logged in I.2.

<a id="s04-t04"></a>
#### S04-T04 — Freshness endpoint
- **Outcome:** ledger-service serves the D04-5 freshness read API: ledger apply consumer lag and the age of the oldest unapplied record. It fails closed when it can't compute them, and the operation is published in the ledger OpenAPI file.
- **Estimate:** 2 h
- **Inputs:** C12 and O9 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications); [docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis) (freshness row, conventions); stale-state row in [docs/zerosum_ledger_mvp_plan.md#cross-cutting](zerosum_ledger_mvp_plan.md#cross-cutting); M10 (c) in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have); payout eligibility in [docs/zerosum_ledger_mvp_plan.md#policies](zerosum_ledger_mvp_plan.md#policies); single clock in [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation); D02-7, D03-4, D03-7, D04-1, D04-2, D04-4; `openapi/ledger-service.yaml`.
- **Depends on:** S04-T02, S04-T03, S02-T04, S03-T03
- **Instructions:**
  1. Write the D04-5 contract, starting from the master's proposal: total lag and per-partition lag in records, oldest unapplied age, computation timestamp, listener paused state (from T03), a status value, the reader role, and problem-details errors.
  2. **Lag.** Read the group's committed offsets and the partitions' latest offsets through the admin client, consistent with the D04-2 isolation level. A partition with no committed offset counts from its earliest available offset.
  3. **Oldest unapplied age.** For each partition with lag, read the timestamp of the record at the committed offset, using a group-less consumer assigned to that partition. Age is now minus the earliest such timestamp. Zero lag means zero age. Expose this age and the record lag as the consumer-lag gauges added in v1.2 (§0.3 O9; names owned by D07-1). Compute the gauges on their own schedule, so alerts work without HTTP traffic.
  4. **Timestamp basis.** Use the Kafka record timestamp of the oldest unapplied record, as fixed by §0.3 C12. Outbox ages come from their own created-at timestamps (D03-7), so the composite freshness that S05-T10 assembles counts no stage twice.
  5. **Fail closed.** If any admin or fetch call errors or exceeds its D04-5 timeout, return an error status. Never return zero lag or zero age for an unknown value.
  6. No cache. If one is added later, the reported age must include the cache age.
  7. Add the operation to the D02-7 OpenAPI file additively, with a D04-5 trace comment. Follow [docs/README.md#conflict-resolution](README.md#conflict-resolution): record it in I.2 and note the addition in the S02 register.
  8. Secure the endpoint with the D03-4 reader role.
- **Edge cases and failure behavior:**
  - Quarantined records are acknowledged, so they don't count as lag. Unresolved quarantine is reported by the invariants API (D02-7, I6), not here.
  - The committed offset is below the log start offset (records removed by retention before they were consumed). That is lost money data under RF=1 ([docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery)). Return an error status that names the condition; never report fresh.
  - The partition count doesn't match D04-1: error status.
  - A record timestamp in the future (clock anomaly): clamp age at zero and count the anomaly.
  - A rebalance in progress: committed offsets stay readable, so compute normally.
- **Outputs:** Planned: `services/ledger-service/src/main/java/…/ledger/freshness/FreshnessController.java` and `FreshnessCalculator.java`; the OpenAPI operation; `FreshnessCalculatorTest`, `FreshnessIT`, `FreshnessContractTest`.
- **Verification and definition of done:**
  - `FreshnessCalculatorTest` (unit) covers: no committed offset, zero lag, clamped negative age, offset below log start (error), partition mismatch (error).
  - `FreshnessIT`:
    - An empty topic reports zero lag and zero age.
    - Records produced while the listener is stopped report lag equal to the record count, and an age of at least the time since the first record's timestamp.
    - After the listener starts, lag returns to zero within the test timeout.
    - With the Kafka container stopped, the endpoint returns the error status.
    - The consumer-lag gauges follow the endpoint's lag and age within one gauge interval, and never report zero while the broker is unreachable.
  - `FreshnessContractTest`: responses validate against the OpenAPI operation, and requests without the reader role are rejected per D03-4.
  - D04-5 is recorded, and the change request appears in I.2.

<a id="s04-t05"></a>
#### S04-T05 — Testcontainers pipeline e2e
- **Outcome:** The D04-6 suite proves effectively-once application from API through outbox relay and Kafka to the ledger. Delivering every order as many times as M5 (a) specifies gives the same balances as delivering once. A crash after DB commit and before ack loses and duplicates nothing. A relay crash after send leaves no downstream duplicates. Every case asserts that its fault actually occurred. The suite also verifies the M6 (c) audit walk (§0.3 E11).
- **Estimate:** 4 h
- **Inputs:** M4 (c), M5 and M6 (c) in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have); E11 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications); Kafka integration row in [docs/zerosum_ledger_mvp_plan.md#test-layers](zerosum_ledger_mvp_plan.md#test-layers); crash points in [docs/zerosum_ledger_mvp_plan.md#data-flows](zerosum_ledger_mvp_plan.md#data-flows); [docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling); ADR-0009 in [docs/zerosum_ledger_mvp_plan.md#decisions](zerosum_ledger_mvp_plan.md#decisions); D00-3, D00-5, D00-10, D01-9, D01-10, D02-7, D02-8, D03-2, D03-3, D03-4, D03-5, D03-7, D04-3, D04-4, D04-5, D04-8.
- **Depends on:** S04-T02, S04-T03, S04-T04, S03-T03, S03-T05, S02-T04, S02-T05
- **Instructions:**
  1. Decide the harness in D04-6. The preferred option runs order-service and ledger-service as containers built from project images, alongside Testcontainers PostgreSQL and Kafka on one network, because a real process halt gives faithful crash semantics. The alternative is in-process Spring contexts, which are faster but less faithful. Record the CI job, the tag (D00-5, D00-10) and the observed duration against [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd).
  2. **Workload.** Use seeded orders from the D01-10 generators plus golden O1–O8 (D01-9), posted through the D03-2 API with a writer token (D03-4). Print the seed on failure (ADR-0009).
  3. **Quiesce condition.** Outbox stats report empty (D03-7) and freshness reports zero lag (D04-5), within a timeout. A timeout is a failure that dumps diagnostics (lag, paused state, quarantine rows), never a skip.
  4. **Case A: single delivery (control).** Post the workload, wait for quiesce, and snapshot all balances.
  5. **Case B: repeated delivery (M5 a).** Use fresh databases and the same seed. After the relay publishes, a test producer re-sends every record until each has been delivered the number of times M5 (a) specifies, with identical key, value and headers, interleaved in a seeded shuffled order. Assert: balances equal Case A; the D02-8 I2–I4 queries pass; the set of applied order IDs equals the order IDs in the orders database; and the topic holds that multiple of the order count (fault exercised).
  6. **Case C: crash before ack.** Activate the D04-8 seam once, on a seeded batch number, to halt the ledger-service JVM. Restart the container with the same database and broker. Assert that, before restart, the group's committed offset was behind the last applied record (fault exercised), that the duplicate-skip count after restart is above zero, that balances equal Case A, that I2–I4 pass, and that no quarantine rows exist.
  7. **Case D: relay crash after send (M4 c, downstream half).** Use the D03-5 injection point to halt order-service after broker confirmation and before outbox rows are marked published. Restart. Assert that the topic holds more records than orders (fault exercised), that the applied set equals the order set, and that balances equal Case A. If D03-5 provides no injection point, raise a change request to S03, mark Case D Blocked in I.1 with that exact dependency, and don't fork `libs/outbox`.
  8. **Client retries.** During Cases C and D, the client retries unanswered POSTs with the same idempotency key (D03-3). Assert one order per key.
  9. **Case E: audit walk (M6 c).** After Case A quiesces, pick a seeded driver entity that has at least one balance change. Starting from its balance, use only the public read APIs. The changelog (D02-7) gives the money-order ID and source idempotency key, and the order API (D03-2) returns that order. Assert that the walk stays within the call bound in M6 (c), and that the key and order match what the workload posted.
  10. Assert money state (balances, invariant queries, set equality), not HTTP success codes.
  11. Don't add retry-until-pass annotations. A flaky money-path test is a product bug until root cause proves otherwise.
- **Edge cases and failure behavior:**
  - Restarting a halted container must reuse the same PostgreSQL and Kafka containers and data. Recreating them would hide duplicates.
  - The seam must not fire again after restart. It is one-shot (for example, a marker the test removes, or an activation flag the restart omits).
  - Seeded workloads touching hot entities can trigger lock timeouts. The transient path then runs as well, and the case must still converge.
  - An upstream relay backoff after the halt lengthens quiesce. Size the timeout from observed CI durations, not from a guessed constant.
- **Outputs:** Planned: e2e source set per D00-10 (proposed `services/ledger-service/src/e2eTest/java/…/pipeline/PipelineE2ETest.java`), with Cases A–E and helper assertions; CI job wiring per D00-5 if not already present.
- **Verification and definition of done:**
  - Cases A–E pass in the D00-5 job on a clean checkout, in three consecutive runs, with seeds recorded.
  - Every fault-exercised assertion in Cases B–D is present and passing. A case whose fault didn't occur counts as failed, not passed.
  - H.4 records the run IDs and evidence paths. If Case D is Blocked, I.1 names the missing D03-5 injection point.

<a id="phase-4"></a>
### Phase 4 — Trace and measurement

**Objective:** Record, with evidence, whether traces cross the pipeline, and whether the polling relay needs the SP2 spike.

**Exit checkpoint:** Results files for T06 and T07 exist (or are marked `Not run` with the reason). D04-7 is recorded. The S04-C01 status is set. Change detection has been re-run before handoff.

<a id="s04-t06"></a>
#### S04-T06 — Trace propagation check
- **Outcome:** Verified evidence that the HTTP request, relay send, Kafka consume and ledger apply transaction form the single trace M12 (a) requires, using the trace context captured in outbox headers at write time (§0.3 C11). If they don't, the break or span-link behavior is recorded, and each gap becomes a change request or handoff item.
- **Estimate:** 1 h
- **Inputs:** M12 (a) in [docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have); C11 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications); [docs/zerosum_ledger_mvp_plan.md#instrumentation](zerosum_ledger_mvp_plan.md#instrumentation); SP3 in [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes); headers in [docs/zerosum_ledger_mvp_plan.md#event-contracts](zerosum_ledger_mvp_plan.md#event-contracts); D00-3, D00-6, D00-7, D00-9, D03-5, D04-3; `docs/results/TEMPLATE.md`.
- **Depends on:** S04-T05, S00-T07, S00-T08
- **Instructions:**
  1. Read the D00-7 outcome and note what SP3 proved about Kafka context propagation, including any fallback.
  2. Start the compose stack (D00-3) with the agent attached per D00-6. Post one order with a writer token, and find its spans in the tracing backend by order ID or log correlation.
  3. **Outbox hop.** The outbox headers carry the W3C trace context captured when the row is written (§0.3 C11, D03-5). Verify that the relay's send restores it, so the Kafka record belongs to the request's trace. Record what you observe.
  4. **Batch-listener hop.** Batch consumption commonly produces a processing span linked to the producer contexts rather than parented by them. Record whether you see links or parent-child.
  5. Classify the result as "connected (parent-child)", "connected (links)" or "broken at <hop>":
     - **Broken at the outbox:** that is a defect against D03-5, because C11 requires the context to survive. Raise a change request to S03; don't patch the relay here.
     - **Links:** hand off to [docs/step_07_observability_performance.md#s07-t01](step_07_observability_performance.md#s07-t01), so custom apply spans and M12 (a) evidence account for links.
  6. Write `docs/results/s04-trace-propagation.md` from the D00-9 template, with trace IDs, a screenshot, versions (D00-1) and the git SHA.
- **Edge cases and failure behavior:**
  - Sampling drops the trace: confirm the local sampler records everything before concluding anything is broken.
  - A DLQ record must keep the original trace context header. Check one poison record while the stack is up.
  - If the agent can't attach (SP3 fallback path), record `Not run` with the D00-7 reference. Never record an assumed result.
- **Outputs:** Planned: `docs/results/s04-trace-propagation.md`; screenshot under `docs/results/`; any change request in I.2.
- **Verification and definition of done:**
  - The results file exists, names one of the three classifications, and lists trace IDs that resolved in the backend during the check.
  - Every gap has a matching change request (I.2) or handoff entry (section J).

<a id="s04-t07"></a>
#### S04-T07 — Relay lag measurement and SP2 trigger decision
- **Outcome:** Outbox publish lag is measured informally on the reference laptop at the rate named in the SP2 question and compared with the SP2 criterion. The SP2 trigger decision is recorded in D04-7.
- **Estimate:** 1 h
- **Inputs:** SP2 in [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes); P2 outbox stages in [docs/zerosum_ledger_mvp_plan.md#stage-budgets](zerosum_ledger_mvp_plan.md#stage-budgets); item 3 in [docs/zerosum_ledger_mvp_plan.md#bottlenecks](zerosum_ledger_mvp_plan.md#bottlenecks); [docs/zerosum_ledger_mvp_plan.md#perf-tests](zerosum_ledger_mvp_plan.md#perf-tests) (method conventions); G3 in [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates); exit criteria in [docs/zerosum_ledger_mvp_plan.md#step-04](zerosum_ledger_mvp_plan.md#step-04); D00-9, D02-1, D03-5, D03-7, D04-6.
- **Depends on:** S04-T05, S03-T05, S03-T06
- **Instructions:**
  1. **Load driver.** Use an open-model driver that issues money-order requests at a fixed arrival rate, independent of response times, with a seeded workload. It may reuse T05 harness code. Don't build k6 scripts; that is D07-4.
  2. Run a warm-up, then one measurement window. Record both durations, taking the conventions in [docs/zerosum_ledger_mvp_plan.md#perf-tests](zerosum_ledger_mvp_plan.md#perf-tests) as guidance. This is not a formal perf run.
  3. **Lag source.** Prefer the D03-5 publish-lag histogram. Otherwise compute it from outbox created and published timestamps, sampled before the D03-5 cleanup job deletes rows. Don't disable cleanup without recording it.
  4. Also record informal order-to-apply latency from the applied-orders timestamps (D02-1), as the step exit criteria allow.
  5. Record the achieved arrival rate, p50 and p95 lag, window, hardware, versions, SHA and seed in `docs/results/s04-relay-lag.md` (D00-9 template). Label it "informal, single repetition".
  6. **Decide D04-7:**
     - Criterion met at the SP2 rate: SP2 is not triggered.
     - Criterion missed: trigger S04-C01.
     - Achieved rate below the SP2 rate (for example, the API became the bottleneck): record "inconclusive" and hand the question to S07-T05.
     - No reference laptop available: mark the result `Not run`, set T07 to Blocked, and name the dependency.
- **Edge cases and failure behavior:**
  - Docker Desktop noise ([docs/zerosum_ledger_mvp_plan.md#performance](zerosum_ledger_mvp_plan.md#performance)): close other workloads, and note anything that couldn't be controlled.
  - A broker hiccup during the window triggers relay backoff and skews p95. Rerun once and report both runs, never only the better one.
  - Ledger lock contention doesn't affect publish lag directly, but shared database CPU does. Record database CPU if it is available.
- **Outputs:** Planned: `docs/results/s04-relay-lag.md`; the load-driver source next to the T05 e2e code; the D04-7 register entry.
- **Verification and definition of done:**
  - The results file states the method, achieved rate, lag percentiles and window, with the SP2 criterion referenced by link, not copied.
  - D04-7 has a decision, rationale and date, or is `Not run`/Blocked with the dependency.
  - I.1 shows the S04-C01 status as either triggered or not triggered.

<a id="conditional-work"></a>
### Conditional and deferred work

<a id="s04-c01"></a>
#### S04-C01 — SP2 relay tuning and Debezium Outbox Event Router spike
- **Outcome:** Either tuning the polling relay meets the SP2 criterion, or a timeboxed Debezium spike shows whether CDC publication meets it, with adoption decided through the change procedure.
- **Estimate:** 0 h
- **Inputs:** SP2 in [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes); [docs/zerosum_ledger_mvp_plan.md#scaling-triggers](zerosum_ledger_mvp_plan.md#scaling-triggers); G3 in [docs/zerosum_ledger_mvp_plan.md#decision-gates](zerosum_ledger_mvp_plan.md#decision-gates); [docs/README.md#effort](README.md#effort); D03-5 (ADR-0008), D00-1, D00-3, D04-1, D04-6, D04-7; `docs/results/s04-relay-lag.md`.
- **Depends on:** S04-T07
- **Instructions:**
  1. **Trigger (evidence).** T07, or later S07-T05 evidence under G3, shows p95 publish lag missing the SP2 criterion at an achieved SP2 rate. **Funding:** unallocated contingency ([docs/README.md#effort](README.md#effort)). **Conditional hours:** 3 h, the master's SP2 timebox. Record the start in I.1.
  2. Tune first. The relay poll interval and batch size are D03-5 values, so change them through a change request on the S03 register, then re-measure with the T07 method. If the criterion is met, stop and record.
  3. Start the Debezium spike only if tuning fails *and* the remaining-contingency condition in [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes) holds. Check remaining contingency against the pack's effort record before starting.
  4. **Spike.** Run the Outbox Event Router against the outbox table in compose. The connector container and PostgreSQL logical-replication settings are a D00-3 change request, and its version is pinned via D00-1. Verify that the record key follows ADR-0007, that headers and trace context survive, and that downstream dedupe is unchanged.
  5. **Adoption** updates D03-5 and ADR-0008 through [docs/README.md#conflict-resolution](README.md#conflict-resolution), with an impact assessment covering: the S03 relay code and the M4 (b) ArchUnit rule; S07 relay-lag metrics; the S08 F1/F4 fault semantics ([docs/zerosum_ledger_mvp_plan.md#fault-matrix](zerosum_ledger_mvp_plan.md#fault-matrix)); and compose memory ([docs/zerosum_ledger_mvp_plan.md#topology](zerosum_ledger_mvp_plan.md#topology)). Mark affected completed tasks Needs review.
- **Edge cases and failure behavior:**
  - CDC also publishes at least once, so consumer dedupe stays mandatory.
  - Outbox cleanup deletes rows: CDC must ignore delete events.
  - A stopped connector keeps its replication slot and retains WAL, so disk grows. The spike must observe this and record a mitigation.
  - Per-key ordering must hold with the connector's task configuration.
- **Outputs:** Planned: `docs/results/sp2-outbox-lag.md`; D04-7 updated; D03-5 and ADR-0008 updated only if adopted.
- **Verification and definition of done:**
  - Lag re-measured with the T07 method is recorded against the SP2 criterion.
  - If Debezium is adopted, the T05 suite passes unchanged against the new publisher.
  - D04-7 and D03-5 carry the decision and date.

**Deferred improvements** (not planned in this step)

- **Debezium CDC as the standard publisher.** [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred). Only S04-C01 may pull it forward. The minimum cut ([docs/zerosum_ledger_mvp_plan.md#minimum-cut](zerosum_ledger_mvp_plan.md#minimum-cut)) removes SP2 entirely.
- **Protobuf/Avro with a schema registry.** [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred). JSON with D01-8 versioning stays.
- **Kubernetes/Helm, with multi-instance consumers and relays.** [docs/zerosum_ledger_mvp_plan.md#deferred](zerosum_ledger_mvp_plan.md#deferred).
- **S3 batched apply.** [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have). The listener is batch-capable. Promotion is D02-10 or S07-T06.
- **S4 hot-entity sharding.** [docs/zerosum_ledger_mvp_plan.md#should-have](zerosum_ledger_mvp_plan.md#should-have). It may change partition counts or concurrency through change requests on D04-1 and D04-2.

<a id="risks-and-recovery"></a>
## F. Risks and recovery

Related master risks: R2 (correctness under redelivery), R3 (hot-entity ceiling) and R4 (tooling time) in [docs/zerosum_ledger_mvp_plan.md#risk-register](zerosum_ledger_mvp_plan.md#risk-register), plus the two S04 risks named in [docs/zerosum_ledger_mvp_plan.md#step-04](zerosum_ledger_mvp_plan.md#step-04).

| Risk | Detection (signal or test) | Recovery / fallback |
|---|---|---|
| Offsets acknowledged before the DB commit, so a crash loses an order (master S04 risk; R2) | `AckOrderingTest`; T05 Case C with its fault-exercised assertion | Move the acknowledgment after engine return and DLQ confirmation; rerun Case C. Never compensate inside the ledger. |
| Group protocol not actually enabled, or classic-only properties break startup (master S04 risk) | T01 configuration tests; consumer-group type check; startup failure log | Remove incompatible properties, keeping the new protocol. If the pinned client can't run it, raise a change request on D00-1/D00-7; never fall back silently. |
| A valid order is dead-lettered because an error was misclassified, which skips money (R2) | `UnclassifiedErrorIT`; DLQ counter; later, the S06 verifier's I6 set difference | Unclassified errors pause by default. Fix D04-4, then follow the D04-4 manual re-publish runbook step (§0.3 C14). Dedupe makes it safe. |
| DLQ publish fails because the recoverer reuses a source partition number the DLQ doesn't have | `DlqPartitioningIT` | Explicit partition selection by key or partitioner (D04-4) |
| Nested retries or long sleeps exceed the poll interval, causing group churn and repeated redelivery | Lost-assignment counter (T02); pause counter; `TransientRetryIT` duration | One retry layer; pause and seek instead of sleeping (D04-4) |
| Listener stays paused after the database recovers, so unattended recovery (F5, F9) fails | `PauseResumeIT`; paused gauge; the S07 alert | Automatic resume probe. Until it is fixed, a service restart resumes from committed offsets; hand the runbook note to S07-T03. |
| Redelivery after a crash quarantines the same record twice | `QuarantineIdempotencyIT` | Resolved in master v1.2 (§0.3 C25, [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)): a uniqueness rule on Kafka coordinates plus a do-nothing-on-conflict insert (D02-9, D04-4). `QuarantineIdempotencyIT` guards against regressions. |
| Freshness reports "fresh" when it doesn't know, so payouts run on stale balances (M10) | `FreshnessIT` broker-stopped case; unit test for offset below log start | Fail-closed contract (D04-5). S05 treats an error as stale. |
| Topic drift: an auto-created topic or changed partition count remaps keys (ADR-0007) | `TopicMismatchIT`; T01 broker-configuration check | Fail startup; change request on D00-3; documented local reset |
| Trace context lost despite outbox capture (§0.3 C11), or only span links across the batch listener (M12 a) | T06 results file | Change request on D03-5; hand off to S07-T01 |
| Polling relay misses the SP2 criterion ([docs/zerosum_ledger_mvp_plan.md#scaling-triggers](zerosum_ledger_mvp_plan.md#scaling-triggers)) | T07 results; later S07-T05 | S04-C01: tune first, then Debezium only under the contingency condition |
| Hot-entity lock timeouts cause frequent pauses under load (R3) | Pause counter tagged with cause; S07 perf runs | SP4 via S07-T06. Never reclassify lock timeouts as poison to keep throughput up. |
| E2E suite flaky or too slow for the CI budget (R4) | Three consecutive runs; job duration against [docs/zerosum_ledger_mvp_plan.md#cicd](zerosum_ledger_mvp_plan.md#cicd) | Root-cause money-path flakes ([docs/zerosum_ledger_mvp_plan.md#test-failure-handling](zerosum_ledger_mvp_plan.md#test-failure-handling)). Move slow cases to the nightly job through a D00-5 change request; never delete them. |
| Crash seam enabled outside tests | Guard test: activation refused without the chaos guard, and impossible under `demo-public` | TB4 guard; S08-T03 consolidates it under D08-3 |

<a id="acceptance-checklist"></a>
## G. Acceptance checklist

- [ ] **M5 (a)** ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have)): T05 Case B passes. Repeated delivery yields balances identical to single delivery, and the fault-exercised assertion holds.
- [ ] **M5 (b)–(d)** hold end to end after Cases B and C, checked with the D02-8 queries for I2–I4 ([docs/zerosum_ledger_mvp_plan.md#invariants](zerosum_ledger_mvp_plan.md#invariants)).
- [ ] **M6 (c)** ([docs/zerosum_ledger_mvp_plan.md#must-have](zerosum_ledger_mvp_plan.md#must-have); E11 in [docs/zerosum_ledger_mvp_plan.md#decomposition-clarifications](zerosum_ledger_mvp_plan.md#decomposition-clarifications)): T05 Case E completes the audit walk within the M6 (c) call bound.
- [ ] **M4 (c), downstream half:** T05 Case D passes, or is recorded as Blocked with the named D03-5 dependency. Never mark it passed without running it.
- [ ] **Crash before ack:** Case C passes, and the committed offset was proven to be behind the applied records at the moment of the crash.
- [ ] **Poison messages** behave as in [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded): `PoisonRecordIT`, `DlqPartitioningIT`, `QuarantineIdempotencyIT` and `MapperPoisonIT` pass (or the D03-6 blocker is recorded), the partition continues, and the D04-4 manual re-publish runbook step is written (§0.3 C14).
- [ ] **Stuck consumer** behaves as in [docs/zerosum_ledger_mvp_plan.md#degraded](zerosum_ledger_mvp_plan.md#degraded): `TransientRetryIT`, `PauseResumeIT` and `UnclassifiedErrorIT` pass. No record is skipped, and resume is automatic.
- [ ] **Explicit client configuration** per [docs/zerosum_ledger_mvp_plan.md#platform-limits](zerosum_ledger_mvp_plan.md#platform-limits): the per-service configuration tests pass, and the consumer-group protocol type is verified.
- [ ] **Topics:** every topic matches D04-1, a mismatch fails startup, and ADR-0007 is committed.
- [ ] **Freshness:** the endpoint meets D04-5 with the §0.3 C12 timestamp basis, fails closed, and is published in the ledger OpenAPI file, ready for S05 to enforce M10 (c).
- [ ] **M12 (c) signals:** the quarantine and DLQ counters, and the v1.2 listener-paused and consumer-lag signals (records and seconds; §0.3 O9), are exposed, with their emitted names recorded in H.2 for the alerts in [docs/zerosum_ledger_mvp_plan.md#monitoring](zerosum_ledger_mvp_plan.md#monitoring).
- [ ] **M12 (a) input:** the single trace from API to ledger apply is verified (§0.3 C11) and recorded in `docs/results/s04-trace-propagation.md`, and any gap has a change request or handoff item.
- [ ] **Measurement:** relay lag and informal P2 are recorded as the exit criteria in [docs/zerosum_ledger_mvp_plan.md#step-04](zerosum_ledger_mvp_plan.md#step-04) require. The SP2 decision is evaluated against [docs/zerosum_ledger_mvp_plan.md#spikes](zerosum_ledger_mvp_plan.md#spikes) and recorded in D04-7 (or marked `Not run` with the reason).
- [ ] **CI:** every S04 test is tagged per D00-10 and green in the D00-5 jobs.
- [ ] **Registers:** H.1–H.6 and I.1–I.2 are current, every change request raised is listed with its outcome, and change detection was re-run before handoff.

<a id="decisions-and-outputs"></a>
## H. Decisions and outputs register

### H.1 Decisions and rationale

| ID | Decision | Rationale | Alternatives considered | Status | Date |
|---|---|---|---|---|---|
| D04-1 | **Topic definitions live in one place**, `libs/contracts/src/main/java/dev/zerosum/contracts/kafka/TopicDefinitions.java` (plain Java, no Spring): `payments.money-orders.v1` and `payments.payment-events.v1`, both 12 partitions, 7 days, keyed by `order_group_id`; `dlqFor(source)` derives `<topic>.dlq` at 3 partitions and 30 days keeping the original key. Consumer group ids (`ledger-apply`, `order-mapper`, `instrument-policy`) are named here too, so a service and the T04 freshness query cannot disagree. **Provisioning:** each service declares `NewTopic` beans for the topics it produces to, consumes from or dead-letters to; both services declare money-orders on purpose, since creation is idempotent and start-up order must not be part of the contract. **Partition count is contract, not configuration** — the key is the order group (ADR-0007), so changing it remaps groups to partitions. **Partial:** the startup mismatch check and `TopicMismatchIT` are **not implemented** (deferred, [docs/scope-decisions.md](scope-decisions.md)); a leftover topic with a different partition count is not yet detected | One definition source is what stops a consumer dead-lettering into a name nobody provisioned, and derived DLQ names remove the second place to misspell | A YAML topic list per service (two places to drift); a shared provisioning service (start-up ordering becomes a dependency); letting the admin client add partitions to match (silently breaks per-group ordering) | Accepted | 2026-09-16 |
| D04-2 | **Every client property is set explicitly**, in each service's `spring.kafka` section, even where it matches a framework default. Producers: `acks=all` and `enable.idempotence=true`. Consumers: `enable.auto.commit=false`, `isolation.level=read_committed`, `max.poll.records=500`, `auto.offset.reset=earliest`, explicit String deserializers, and the group id from D04-1. Ledger-service additionally sets `listener.ack-mode=manual` and `listener.type=batch`, which S04-T02 depends on. **Verified by reading the effective built properties** (`KafkaProperties.buildConsumerProperties()`) in a per-service configuration test, not by reading the file | A default that changes between versions would change pipeline behaviour silently; and in S03 a misplaced YAML block bound to a key nothing read, producing a listener that consumed nothing while looking healthy — asserting effective values is the cheap defence against both | Relying on framework defaults (invisible when they change); asserting the YAML text (proves the file's content, not what the client received) | Accepted | 2026-09-16 |
| D04-3 | — | — | — | Pending | — |
| D04-4 | — | — | — | Pending | — |
| D04-5 | — | — | — | Pending | — |
| D04-6 | — | — | — | Pending | — |
| D04-7 | — | — | — | Pending | — |
| D04-8 | — | — | — | Pending | — |

### H.2 Implementation and configuration locations

| Item | Planned path | Actual path | Traced to |
|---|---|---|---|
| Topic-definition source | `libs/contracts/src/main/java/…/kafka/TopicDefinitions.java` | — | D04-1 |
| Topic declarations, order-service | `services/order-service/src/main/java/…/config/KafkaTopicsConfig.java` | — | D04-1 |
| Topic declarations, ledger-service | `services/ledger-service/src/main/java/…/config/KafkaTopicsConfig.java` | — | D04-1 |
| Client properties, order-service (relay producer, mapper consumer) | `services/order-service/src/main/resources/application.yaml` | — | D04-2 |
| Client properties, ledger-service | `services/ledger-service/src/main/resources/application.yaml` | — | D04-2 |
| Listener and container factory | `services/ledger-service/src/main/java/…/ledger/kafka/` | — | D04-3 |
| Error handling, DLQ recoverer, resume probe | `services/ledger-service/src/main/java/…/ledger/kafka/` | — | D04-4 |
| Poison-policy wiring, order-mapper consumer | `services/order-service/src/main/java/…/config/` | — | D04-4 |
| Manual re-publish runbook step | `docs/runbook.md` (quarantine section) | — | D04-4 (§0.3 C14) |
| Freshness controller and calculator | `services/ledger-service/src/main/java/…/ledger/freshness/` | — | D04-5 |
| Freshness OpenAPI operation | `openapi/ledger-service.yaml` | — | D04-5 (file owned by D02-7) |
| Crash-point seam | `services/ledger-service/src/main/java/…/ledger/kafka/CrashPoint.java` | — | D04-8 |
| Emitted metric names (input to D07-1) | Listener and error-handling classes above | — | D04-3, D04-4 |

### H.3 Produced artifacts

| Artifact | Planned path | Actual path | Revision/hash |
|---|---|---|---|
| ADR-0007 partition key | `docs/adr/0007-partition-key.md` | — | — |
| Kafka integration tests (T01–T04) | `services/ledger-service/` and `services/order-service/` integration source sets per D00-10 | — | — |
| Pipeline e2e suite (D04-6) | `services/ledger-service/src/e2eTest/java/…/pipeline/` | — | — |
| Trace propagation result | `docs/results/s04-trace-propagation.md` | — | — |
| Relay lag result | `docs/results/s04-relay-lag.md` | — | — |
| SP2 spike result (conditional) | `docs/results/sp2-outbox-lag.md` | — | — |

### H.4 Validation results and evidence

| Check | Method | Result | Evidence path | Date |
|---|---|---|---|---|
| Topic provisioning and mismatch behavior | `TopicProvisioningIT`, `TopicMismatchIT` | **Partial.** `TopicProvisioningIT` **passed** (3 tests): every defined topic is created with its contracted partition count and retention, a second declaration of an existing topic raises exactly `TopicExistsException` and leaves the topic unaltered, and DLQ names are derived rather than spelled twice. **`TopicMismatchIT` and the startup partition-count check are not implemented** — deferred per [docs/scope-decisions.md](scope-decisions.md), so a leftover topic with a different partition count is still undetected. Not claimed as passing | [docs/results/s04/s04-t01-topics-clients.txt](results/s04/s04-t01-topics-clients.txt) | 2026-09-16 |
| Explicit client configuration and group protocol | Per-service configuration tests; group type check | **Partial.** `KafkaClientConfigTest` **passed** (3 tests) against the *effective* built properties: consumer group, `auto.offset.reset`, auto-commit off, `read_committed`, `max.poll.records=500`; producer `acks=all` with idempotence; and `ack-mode=MANUAL` with `type=BATCH`. **The KIP-848 group-protocol assertion after first poll is not implemented** (deferred). Order-service's equivalent configuration test is not yet written | [docs/results/s04/s04-t01-topics-clients.txt](results/s04/s04-t01-topics-clients.txt) | 2026-09-16 |
| Partition key | `PartitionKeyIT` | Not run | — | — |
| Ack only after commit | `AckOrderingTest` | Not run | — | — |
| Listener correctness under duplicates | `LedgerListenerIT`, `DuplicateDeliveryIT` | Not run | — | — |
| Graceful shutdown | `GracefulShutdownIT` | Not run | — | — |
| Poison → quarantine + DLQ | `PoisonRecordIT`, `DlqPartitioningIT`, `QuarantineIdempotencyIT` | Not run | — | — |
| Transient → retry → pause → resume | `TransientRetryIT`, `PauseResumeIT`, `UnclassifiedErrorIT` | Not run | — | — |
| Freshness contract | `FreshnessCalculatorTest`, `FreshnessIT`, `FreshnessContractTest` | Not run | — | — |
| M5 (a)–(d) end to end | `PipelineE2ETest` Cases A–C | Not run | — | — |
| M4 (c) downstream half | `PipelineE2ETest` Case D | Not run | — | — |
| M6 (c) audit walk | `PipelineE2ETest` Case E | Not run | — | — |
| Mapper consumer poison policy | `MapperPoisonIT` | Not run | — | — |
| Manual re-publish runbook step | Runbook dry run (T03) | Not run | — | — |
| Trace propagation | T06 check against the compose stack | Not run | — | — |
| Relay lag and SP2 decision | T07 informal measurement | Not run | — | — |

### H.5 Known limitations and blockers

| Item | Type (limitation/blocker) | Impact | Owner/next action |
|---|---|---|---|
| Kafka without authentication (TB3) | limitation | Any container on the compose network can produce to pipeline topics | Production concern ([docs/zerosum_ledger_mvp_plan.md#mvp-vs-production](zerosum_ledger_mvp_plan.md#mvp-vs-production)); no MVP action |
| Single-broker data-loss window ([docs/zerosum_ledger_mvp_plan.md#recovery](zerosum_ledger_mvp_plan.md#recovery)) | limitation | Losing the broker volume loses published but unconsumed orders; freshness reports an error | Conditional replay tool, unassigned ([docs/README.md#known-gaps](README.md#known-gaps)) |
| Admin quarantine-retry operation ([docs/zerosum_ledger_mvp_plan.md#rest-apis](zerosum_ledger_mvp_plan.md#rest-apis)) | limitation | Quarantined records are re-applied only through the manual runbook step | Resolved in master v1.2 (§0.3 C14): endpoint deferred; runbook step owned by D04-4 |
| Mapper-side quarantine ([docs/zerosum_ledger_mvp_plan.md#event-to-order](zerosum_ledger_mvp_plan.md#event-to-order)) | limitation | — | Resolved in master v1.2 (§0.3 C8, C18): order-service quarantines in its own database (D03-1); S04 owns the poison policy and DLQ publishing (D04-4) |
| Undecodable records vs the quarantine shape | limitation | — | Resolved in master v1.2 (§0.3 C8): surrogate key with nullable order ID; no change request to D02-9 |
| Relay-only ArchUnit rule vs the dead-letter recoverer | limitation | — | Resolved in master v1.2 (§0.3 C10): the framework recoverer is exempt |
| Freshness timestamp basis | limitation | — | Resolved in master v1.2 (§0.3 C12) |
| Quarantine idempotency under redelivery | limitation | — | Resolved in master v1.2 (§0.3 C25): quarantine rows are unique on Kafka coordinates, and T03 uses a do-nothing-on-conflict insert (D02-9, D04-4) |

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
| S04-T01 | Done | `libs/contracts/src/main/java/dev/zerosum/contracts/kafka/TopicDefinitions.java`, `services/{ledger,order}-service/src/main/java/…/KafkaTopicsConfig.java`, both `application.yml` `spring.kafka` sections, `services/ledger-service/build.gradle.kts` (Kafka starter + Testcontainers), [docs/adr/0007-partition-key.md](adr/0007-partition-key.md), `…/kafka/{KafkaClientConfigTest,TopicProvisioningIT}.java` | [docs/results/s04/s04-t01-topics-clients.txt](results/s04/s04-t01-topics-clients.txt): 6 tests green | **Partial by choice:** no startup partition-mismatch check or `TopicMismatchIT`, no KIP-848 group-protocol assertion, no order-service config test; `PartitionKeyIT` moves to T02 where the relay exists. All recorded as deferred, none reported as passing |
| S04-T02 | Planned | — | — | — |
| S04-T03 | Planned | — | — | — |
| S04-T04 | Planned | — | — | — |
| S04-T05 | Planned | — | — | — |
| S04-T06 | Planned | — | — | — |
| S04-T07 | Planned | — | — | — |
| S04-C01 | Planned | — | — | — |

<a id="change-record"></a>
### I.2 Consumed sources and change record

| Source/artifact path | Revision or hash | Recorded at | Affected tasks | Review outcome |
|---|---|---|---|---|
| `docs/zerosum_ledger_mvp_plan.md` (v1.2) | — | — | S04-T01, S04-T03–S04-T06 | Resolved in master v1.2 (§0.3 C8, C10, C11, C12, C14, C18, C25, E11, O9); tasks updated in doc version 1.1 |
| `docs/README.md` | — | — | All | — |
| `docs/step_00_foundations.md` | — | — | S04-T01, S04-T05, S04-T06 | — |
| `docs/step_01_domain_contracts.md` | — | — | S04-T02, S04-T03, S04-T05 | — |
| `docs/step_02_ledger_core.md` | — | — | S04-T02–S04-T05 | — |
| `docs/step_03_order_service_outbox.md` | — | — | S04-T01, S04-T04–S04-T07, S04-C01 | — |
| `gradle/libs.versions.toml` (D00-1) | — | — | S04-T01–S04-T06 | — |
| `docker-compose.yml` (D00-3) | — | — | S04-T01, S04-T05, S04-T06, S04-C01 | — |
| `.github/workflows/ci.yml` (D00-5) | — | — | S04-T05 | — |
| `docs/adr/` (ADR-0002, ADR-0005, ADR-0008) | — | — | S04-T02, S04-T03, S04-T06, S04-C01 | — |
| `docs/results/TEMPLATE.md` (D00-9) | — | — | S04-T06, S04-T07 | — |
| `libs/contracts/` (schemas, golden payloads; generators per D01-10) | — | — | S04-T02, S04-T03, S04-T05 | — |
| ledger-service migrations and apply engine (paths from D02-1, D02-3) | — | — | S04-T02, S04-T03 | — |
| `openapi/ledger-service.yaml` (D02-7) | — | — | S04-T04 | — |
| `docs/results/sp1-lock-study.md` (D02-10) | — | — | S04-T02 | — |
| `libs/outbox/` (D03-5) | — | — | S04-T01, S04-T05, S04-T07, S04-C01 | — |
| order-service API, auth module, mapper consumer, outbox stats (paths from D03-2, D03-4, D03-6, D03-7) | — | — | S04-T01, S04-T04, S04-T05, S04-T07 | — |
| `openapi/order-service.yaml` (D03-2) | — | — | S04-T05 | — |

<a id="handoff"></a>
## J. Handoff

**What downstream steps consume**

| Output | Decision / artifact | Consumer and tasks |
|---|---|---|
| Topic definitions and DLQ name derivation | D04-1; topic-definition source (H.2) | S05: instrument outbox publishing ([docs/step_05_instruments_fake_providers.md#s05-t07](step_05_instruments_fake_providers.md#s05-t07)) and the instrument-policy consumer ([docs/step_05_instruments_fake_providers.md#s05-t09](step_05_instruments_fake_providers.md#s05-t09)) |
| Client configuration and consumer pattern (batch or record listener, ack after commit) | D04-2, D04-3 | S05: [docs/step_05_instruments_fake_providers.md#s05-t09](step_05_instruments_fake_providers.md#s05-t09) |
| Poison-message and error-handling policy for all consumers (classification, pause and resume, DLQ publishing, quarantine ordering, manual re-publish runbook step; §0.3 C14, C18) | D04-4 | S05: [docs/step_05_instruments_fake_providers.md#s05-t09](step_05_instruments_fake_providers.md#s05-t09); S08: [docs/step_08_fault_injection_ablation.md#s08-t02](step_08_fault_injection_ablation.md#s08-t02), [docs/step_08_fault_injection_ablation.md#s08-t04](step_08_fault_injection_ablation.md#s08-t04) |
| Freshness endpoint (Kafka record-timestamp basis, §0.3 C12) | D04-5; `openapi/ledger-service.yaml` | S05 payout run ([docs/step_05_instruments_fake_providers.md#s05-t10](step_05_instruments_fake_providers.md#s05-t10)); quiesce detection in S06 ([docs/step_06_reconciliation_verifier.md#s06-t04](step_06_reconciliation_verifier.md#s06-t04)) and S08 ([docs/step_08_fault_injection_ablation.md#s08-t04](step_08_fault_injection_ablation.md#s08-t04)) |
| Consumer lag (records and seconds), listener paused, DLQ and quarantine signals (§0.3 O9) | Emitted names in H.2; D07-1 owns the registry | S07: [docs/step_07_observability_performance.md#s07-t01](step_07_observability_performance.md#s07-t01) (registry), [docs/step_07_observability_performance.md#s07-t02](step_07_observability_performance.md#s07-t02) (dashboards), [docs/step_07_observability_performance.md#s07-t03](step_07_observability_performance.md#s07-t03) (alerts) |
| Relay lag result and SP2 decision | D04-7; `docs/results/s04-relay-lag.md` | S07: [docs/step_07_observability_performance.md#s07-t05](step_07_observability_performance.md#s07-t05), [docs/step_07_observability_performance.md#s07-t06](step_07_observability_performance.md#s07-t06) (G3 relay-lag path) |
| Single-trace verification result (§0.3 C11) | `docs/results/s04-trace-propagation.md` | S07: [docs/step_07_observability_performance.md#s07-t01](step_07_observability_performance.md#s07-t01) |
| Crash-point seam | D04-8 | S08: F2 in [docs/step_08_fault_injection_ablation.md#s08-t02](step_08_fault_injection_ablation.md#s08-t02); guard consolidation in [docs/step_08_fault_injection_ablation.md#s08-t03](step_08_fault_injection_ablation.md#s08-t03) |
| Pipeline e2e harness | D04-6 | S05 scenario runner ([docs/step_05_instruments_fake_providers.md#s05-t13](step_05_instruments_fake_providers.md#s05-t13)); S08 regression tests with failing seeds ([docs/step_08_fault_injection_ablation.md#s08-c01](step_08_fault_injection_ablation.md#s08-c01)) |
| M6 (c) audit-walk evidence (§0.3 E11) | `PipelineE2ETest` Case E (H.4) | S09 traceability table: [docs/step_09_demo_docs_release.md#s09-t02](step_09_demo_docs_release.md#s09-t02) |

**Where to resolve current definitions.** Downstream agents read the current D04-n entry in H.1 of [docs/step_04_kafka_pipeline.md#decisions-and-outputs](step_04_kafka_pipeline.md#decisions-and-outputs), then the actual paths in H.2/H.3, then the artifact itself (topic-definition source, `application.yaml`, `openapi/ledger-service.yaml`, ADR-0007). Topic names, partition counts, client property values, retry and probe schedules, and freshness fields are never copied from this document. If S04-C01 adopted Debezium, publication details come from D03-5 and ADR-0008, not from the polling-relay description.

**Alternatives downstream must still handle:** the permitted alternatives listed at the end of D.4 ([docs/step_04_kafka_pipeline.md#ownership-and-requirements](step_04_kafka_pipeline.md#ownership-and-requirements)).

**Handoff conditions**

1. There is no decision gate. The "M5 end-to-end" checkpoint is met when every section G item is checked, or explicitly recorded as `Not run`/Blocked with the missing dependency.
2. H.1 has a status and date for D04-1 through D04-8. H.2 and H.3 have actual paths. H.4 has results with evidence paths.
3. I.1 shows each task as Done or Blocked (with the dependency), and S04-C01 as triggered or not triggered.
4. I.2 is current after a final change-detection run. Every change request raised (the D02-7 OpenAPI addition, and any on D02-9, D03-5, D03-6 or D00-3) is listed with its outcome.
5. The S05 agent fills "Handoff accepted by next step" in H.6 after running its own change detection.
