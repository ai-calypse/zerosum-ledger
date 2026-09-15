# <Evidence ID> — <short title>

<!--
decision: D00-9 — docs/step_00_foundations.md#decisions-and-outputs
Results template for every measured evidence file in docs/results/. Copy it, fill every section, and delete
nothing: write "n/a — <why>" where a section doesn't apply. Estimates, extrapolations and invented values are
never allowed (master §8.10, docs/README.md#status-legend). S07 and S08 may specialize this template.
-->

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | <e.g. SP3, SP1, P2-500, A0-F3> |
| Type | <spike · performance · fault injection · ablation · human evaluation · verification> |
| Owning step and task | <e.g. S00-T07> |
| Date (UTC) | <YYYY-MM-DD> |

## 2. Status

<!-- Exactly one of the two lines below. -->
- **Measured**
- **Not run — <reason>** (for example: participants unavailable, machine time exhausted, blocked by <dependency>)

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | <full SHA; working tree clean? yes/no> |
| Versions | <link to docs/adr/0002-stack-and-pinned-versions.md at the SHA above, plus any runtime-reported versions (JVM, broker, PostgreSQL, OTel agent)> |
| Seeds | <every seed used, one per run; "none" only for non-generative evidence> |
| Hardware | <host model, CPU cores, RAM, OS version> |

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | |
| Docker VM CPUs / memory | |
| Emulated images (non-native architecture) | <none, or list them: emulation distorts measurements> |
| Other load on the host during the run | |

## 5. Scenario, workload and seeds

<What ran: scenario or workload name, rate or size, duration, warm-up, fault profile, variant. Link the scenario file.>

## 6. Exact commands

```sh
# every command, in order, exactly as run
```

## 7. Raw data

<Links to raw files committed next to this document (k6 summary JSON, Prometheus range exports, test reports,
logs, screenshots). Results below must be reproducible from these files.>

## 8. Results

<!-- Report every repetition, then the median of repetitions and the min–max range across them (master §8.6). -->

| Repetition | <metric 1> | <metric 2> |
|---|---|---|
| 1 | | |
| … | | |
| **Median** | | |
| **Range (min–max)** | | |

## 9. Gate or threshold compared against

<Link to the master section or step register that owns the threshold, e.g.
docs/zerosum_ledger_mvp_plan.md#go-no-go. Never copy the number here; state only pass / miss / not applicable.>

## 10. Deviations and limitations

<Anything that differs from the planned method, and what the result can't show.>
