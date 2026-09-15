# ADR-0001 — Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-09-15
- **Decision owner:** S00 (D00-9) — [docs/step_00_foundations.md#decisions-and-outputs](../step_00_foundations.md#decisions-and-outputs)

## Context

ZeroSum Ledger is built step by step from a documentation pack. Each step document owns a register (section H) of selected decisions, but a register row is one line: it has no room for the forces, alternatives and consequences behind a decision that changes architecture. Those need a durable, reviewable record that does not drift when the code changes.

## Decision

We record architecturally significant decisions as Architecture Decision Records (ADRs) in `docs/adr/`.

**Format.** Each ADR has these sections: title line `ADR-NNNN — <title>`, a header with **Status**, **Date** and **Decision owner** (step and decision ID), then **Context**, **Decision** and **Consequences**. Optional sections (Alternatives, Evidence, SP-spike results) follow Consequences.

**Naming and numbering.** Files are named `NNNN-kebab-title.md`, where `NNNN` is a four-digit sequential number. Numbers are never reused, even for rejected ADRs. A new ADR takes the next number that is neither used nor reserved below.

**Status lifecycle.** `Proposed` → `Accepted` → `Superseded by NNNN` (or `Rejected` from Proposed). Once an ADR is Accepted its text is never rewritten. Only the status line and supersession links change; a changed decision is a new ADR that supersedes the old one.

**Relationship to registers and artifacts** ([docs/README.md#source-of-truth](../README.md#source-of-truth)):

- the owning step's **register row** records the decision and references the ADR;
- the **ADR** carries the rationale, alternatives and consequences;
- the **artifacts** (code, migrations, configuration) carry the concrete values, with trace comments back to the decision ID.

**Reserved numbers.** The master plan ([docs/zerosum_ledger_mvp_plan.md#decisions](../zerosum_ledger_mvp_plan.md#decisions), [#state-machines](../zerosum_ledger_mvp_plan.md#state-machines)) already reserves these numbers. Later steps use them for exactly these topics:

| ADR | Topic | Owning step (decision) |
|---|---|---|
| 0001 | Record architecture decisions (this ADR) | S00 (D00-9) |
| 0002 | Stack and pinned versions | S00 (D00-1, D00-7) |
| 0003 | Sign convention and normal sides | S01 (D01-6) |
| 0004 | Balance-dependent policy lives outside the ledger | S02 (ledger half, D02-12); S05 (policy half) |
| 0005 | Pessimistic, sorted entity locks for ledger apply | S02 (D02-4) |
| 0006 | Single writer of money orders | S03 (D03-6) |
| 0007 | Partition key is `order_group_id` | S04 (D04-1) |
| 0008 | Polling outbox, one relay instance per service | S03 (D03-5) |
| 0009 | No PBT library; seeded generative JUnit tests | S01 |
| 0010 | Attempt state machines and FakeBank quiet period | S05 |

The next free number is **0011**.

## Consequences

- Reviewers can find why a decision was made without reading the whole plan.
- Changing an accepted decision costs a new ADR plus a change request against the owning register ([docs/README.md#conflict-resolution](../README.md#conflict-resolution)); that friction is intended.
- ADRs hold no runtime values, so a version bump or tuning change does not require an ADR edit unless the decision itself changes. ADR-0002 is the exception by design: it is the dated record of pinned versions, and it must match the artifacts.
