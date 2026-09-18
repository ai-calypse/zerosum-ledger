# M13 simulator — seeded W1 scenario through the order API

<!--
decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness
Hand-written, following docs/results/TEMPLATE.md. The headline result is "Not run", and the numbers that ARE here
are labelled with what produced them. Nothing in this file describes a run against the money path.
-->

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | M13-SIM-W1 |
| Type | verification (seeded scenario run) |
| Owning step and task | M13 (a), M13 (c) — evidence harness (CR-S09-01) |
| Date (UTC) | 2026-09-17 |

## 2. Status

- **Not run — the live Compose stack was never started.** `docker compose` is a shared resource on this host and
  other work was using it, so the stack was deliberately not brought up. `docker ps` was empty at the time of
  writing: there was no running stack to point the simulator at either.

**No scenario run against the money path exists.** The table in §8.1 is therefore empty, and no throughput, latency
or applied-order figure is claimed anywhere in this document.

What *was* executed is the CLI itself, against a stub of the two service APIs (§8.2). **A stub run is not evidence
about the money path**: nothing in it crosses an outbox, a broker or the apply engine. It is evidence that the
command works, that a seed reproduces its orders, and that the command fails when orders are not applied.

## 3. Provenance (master §3.1 M13 c)

Provenance for the stub run in §8.2. There is no provenance for a money-path run, because there was no money-path run.

| Item | Value |
|---|---|
| Git commit SHA | `0eac552e038d04bccfd365c670075f434f22586f`; **working tree DIRTY at launch** — the harness being measured was itself the uncommitted change |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at that SHA. Runtime-reported by the tool: JVM `OpenJDK 64-Bit Server VM 25.0.4.1 (Eclipse Adoptium)`, `java.runtime.version 25.0.4.1+1-LTS`. No broker or database version is reported, because the stub run reached neither |
| Seeds | base seed `4242`; per-run seeds `4273` and `4304` (run *r* uses `seed + 31*r`). Every run records its own seed in the tool's JSON output |
| Hardware | `Mac16,12`, 10 cores, 24.0 GiB RAM (25,769,803,776 bytes), Mac OS X 26.5.2, aarch64. Captured by the tool, not typed in |

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | n/a — **no container was started for this evidence**. The stub is an in-process JDK `HttpServer` |
| Docker VM CPUs / memory | n/a — as above |
| Emulated images (non-native architecture) | n/a — as above |
| Other load on the host during the run | an interactive development session. No latency figure is claimed, so load does not distort anything here |

## 5. Scenario, workload and seeds

**Scenario `w1-trip-completed`** — W1 from [master §1.4](../../zerosum_ledger_mvp_plan.md#workflows). Each run
completes trips through order-service's real HTTP API. Every order is a COMMERCE `trip.completed` whose entries are a
rider receivable (debit), a driver payable (credit) and platform revenue (credit), summing to zero in USD.
Defaults: 3 trips per run, 2,000 bps commission.

Orders come from `TripSequenceGenerator` (D01-10, ADR-0009) — the generators S01 already built, not new randomness,
as S08-T01 requires. Riders and drivers are namespaced with the run's seed so repeated runs never collide;
`platform:main` is deliberately left shared, because it is the hot entity every trip books revenue to.

Two details make a seeded rerun a genuine rerun rather than a duplicate:

- **Idempotency keys are derived from the seed**, as `sim-<scenario>-<seed-hex>-<index>`.
- **`effective_at` is derived from the seed, not from the clock.** A body carrying `Instant.now()` would differ on
  every invocation, so re-running a seed would collide with its own earlier orders and be rejected 422
  `idempotency_key_reused` instead of replayed. This is the one design choice that makes M13 (a)'s "N seeded runs"
  reproducible in practice.

## 6. Exact commands

The single command M13 (a) asks for:

```sh
./gradlew :tools:simulator:run --args="--scenario w1-trip-completed --runs 5 --seed 4242"
```

It defaults to `http://127.0.0.1:8081` (order-service) and `http://127.0.0.1:8082` (ledger-service), reads its tokens
from `.env` (D00-8), and writes `docs/results/m13/w1-trip-completed.json` and `.md`. **This command has not been run
against the stack.** Running it requires `docker compose up -d --wait` first.

What was actually executed:

```sh
./gradlew :tools:simulator:test -i
```

## 7. Raw data

`tools/simulator/build/test-results/test/TEST-dev.zerosum.simulator.*.xml` — the Gradle XML the counts in §8.2 are
read from. Not committed: `build/` is git-ignored, and the run reproduces with the command above.

No `w1-trip-completed.json` is committed under `docs/results/m13/`, because the only ones produced so far describe a
stub.

## 8. Results

### 8.1 Money-path scenario run

| Run | Seed | Orders | Created | Replayed | Applied | Observed apply |
|---|---|---|---|---|---|---|
| — | — | — | — | — | — | **Not run** |

### 8.2 CLI verification against a stub (MEASURED — not the money path)

**12 tests, 0 failures, 0 errors, 0 skipped** — read from the XML, not from `BUILD SUCCESSFUL`, because this build
sets `failOnNoDiscoveredTests = false`.

| Suite | Tests | Failures | Skipped | Time |
|---|---|---|---|---|
| `ScenarioDeterminismTest` | 6 | 0 | 0 | 0.037 s |
| `SimulatorStubRunTest` | 6 | 0 | 0 | 9.886 s |

What those tests establish:

| Claim | How it is shown |
|---|---|
| The same seed produces identical orders | Byte-identical request bodies and identical idempotency keys for one seed; different for `seed + 1` |
| A rerun is a replay, not a duplicate | A second invocation of seed 77 created **0** new orders and was answered with **3** replays, with **0** body mismatches. The stub answers a reused key with a different body as 422, exactly as order-service does |
| Every generated order is valid and balanced | Each body is checked with the shared `ZeroSumValidator` (D01-5) before posting, so a generator bug fails with the seed printed instead of being recorded as a service rejection |
| The W1 shape is right | Rider receivable debit, driver payable credit, `platform:main` revenue credit, summing to zero |
| Each run records its own seed | The written JSON carries `seed` per run and the full seed list in the provenance block |
| **The command can fail** | With a stub that accepts orders but never applies them, the run exits **1** and prints `NOT APPLIED`; the written evidence records `passed: false` |
| A refused token is not a result | A stub returning 401 makes the run exit **2**, and **nothing is written as evidence** |

Against the stub, a full run reported:

```
ZS-SIM run=1 seed=4273 orders=3 created=3 replayed=0 applied=3 maxApplyMs=257 driverBalancesMatch=true passed=true
ZS-SIM run=2 seed=4304 orders=3 created=3 replayed=0 applied=3 maxApplyMs=257 driverBalancesMatch=true passed=true
ZS-SIM result PASS
```

`maxApplyMs=257` is an artefact of the stub's deliberate "404 on first poll" behaviour and the 250 ms poll interval.
**It is not a latency measurement of anything.**

## 9. Gate or threshold compared against

M13 (a) in [master §3.1](../../zerosum_ledger_mvp_plan.md#must-have): *a single command runs a named scenario for N
seeded runs and writes JSON + Markdown to `docs/results/`.*

- The command, the named scenario, the N seeded runs and both output files: **implemented and exercised**.
- The same criterion **against the running system**: **Not run**.

M13 (b) (ablations A1–A4) is untouched and remains **NOT MET**; S08 is not built.

## 10. Deviations and limitations

- **The headline evidence is absent.** No scenario has been run against the money path. Every number in §8.2 comes
  from a stub that answers HTTP and nothing else.
- **The stub is not order-service.** It reproduces only the four behaviours the simulator depends on: create,
  idempotent replay, reject a reused key with a different body, and serve balances. It does not validate bodies, so
  it cannot catch a malformed order the real API would reject — the shared `ZeroSumValidator` check inside the
  simulator is what covers that, and it is the same validator the service uses.
- **`platform:main` is never balance-checked** by the simulator: it is shared across runs and has no expected value.
  The global per-currency sum covers it, and that is `tools/verifier`'s I2.
- **One scenario.** No adjustments, refunds, payouts, provider path or injected faults. An adjustment needs
  `adjusts_order_id` wiring that is not built, and the scenario asserts a 0 % adjustment rate rather than quietly
  posting unlinked orders.
- **Apply times are polling observations**, quantised to the poll interval, and are not a latency benchmark even when
  the tool is pointed at a real stack. P2 and T1 in S07 are the latency evidence.
