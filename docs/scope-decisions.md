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
