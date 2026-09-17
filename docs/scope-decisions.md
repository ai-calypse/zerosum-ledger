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
