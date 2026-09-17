# M13 verifier — invariant detection against a real ledger database

<!--
decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness
Hand-written from executed runs, following docs/results/TEMPLATE.md. The tool writes its own report in this same
shape; this document records what was executed and, just as importantly, what was not.
-->

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | M13-VERIFIER |
| Type | verification |
| Owning step and task | M13 (a), M13 (c) — evidence harness (CR-S09-01) |
| Date (UTC) | 2026-09-17 |

## 2. Status

- **Measured**

This covers the verifier's **detection behaviour against a real PostgreSQL database**. It does **not** cover a run
against the live Compose stack's ledger; that is recorded as Not run in
[simulator.md](simulator.md#2-status) for the same reason.

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | `4e7d942b676417d8a25c9aa83926cc94bcc6beeb`; **working tree DIRTY at launch** — the harness being measured was itself the uncommitted change. The runs were re-executed unchanged before the commit that adds this file |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../../adr/0002-stack-and-pinned-versions.md) at that SHA. Runtime-reported by the tool: PostgreSQL `18.6 (Debian 18.6-1.pgdg13+2) on aarch64-unknown-linux-gnu`, JVM `OpenJDK 64-Bit Server VM 25.0.4.1 (Eclipse Adoptium)`, `java.runtime.version 25.0.4.1+1-LTS`. The container image is the one pinned in `docker-compose.yml`, read from that file by `ZsTestDatabase` rather than restated here |
| Seeds | none — the verifier is not generative. The tool prints `none (non-generative evidence)` in its own report |
| Hardware | `Mac16,12`, 10 cores, 24.0 GiB RAM (25,769,803,776 bytes), Mac OS X 26.5.2, aarch64. Captured by the tool, not typed in |

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | Docker engine 29.8.0. **Compose was not used**: the database is a Testcontainers container, because `docker compose` is a shared resource on this host |
| Docker VM CPUs / memory | 10 CPUs / 8,319,504,384 bytes (7.75 GiB) |
| Emulated images (non-native architecture) | none — the host is arm64 and the PostgreSQL image ran natively (`aarch64-unknown-linux-gnu`, per the server's own `version()` above) |
| Other load on the host during the run | an interactive development session. No timing is claimed here, so load does not distort the result |

## 5. Scenario, workload and seeds

`tools/verifier/src/test/java/dev/zerosum/verifier/VerifierIT.java` drives the **real CLI entry point**
(`VerifierMain.run`) against a PostgreSQL container initialised by the real `infra/postgres` scripts and migrated
with ledger-service's real migrations, connecting as the real read-only `verifier` role (D00-4, TB5).

The fixture ledger is two balanced COMMERCE orders in the W1 shape, inserted as SQL: rider receivable debits, driver
payable credits and platform revenue credits, summing to zero in USD. It is built by hand rather than through the
apply engine deliberately — what is under test is the verifier's *detection*, and hand-built rows let each corruption
be surgical. The apply engine's own correctness is ledger-service's business and is covered there.

Five states were verified, in order, against one database:

| # | State | Expected |
|---|---|---|
| 1 | empty ledger | exit 0, **and** an explicit warning that the invariants hold vacuously |
| 2 | two balanced orders | exit 0 |
| 3 | a write attempted as the `verifier` role | refused by PostgreSQL |
| 4 | **I2 broken alone** — a changelog row and its account balance moved together by +1, so the books no longer sum to zero while every per-account figure stays internally consistent | exit 1 naming **I2 only** |
| 5 | changelog corrupted — one delta edited, one row deleted, both by superuser with the append-only triggers disabled | exit 1 naming **I3 and I4** |

Seeds: none.

## 6. Exact commands

```sh
./gradlew :tools:verifier:integrationTest -i
```

The tool itself is a single command, and this is the form the evidence records:

```sh
./gradlew :tools:verifier:run --args="--jdbc-url jdbc:postgresql://127.0.0.1:5432/ledger --out docs/results/m13"
```

The password is never an argument. It comes from `ZS_VERIFIER_DB_PASSWORD` in the environment or `.env` (D00-8),
because anything on the command line is visible to `ps`.

## 7. Raw data

`tools/verifier/build/test-results/integrationTest/TEST-dev.zerosum.verifier.VerifierIT.xml` — the Gradle XML the
counts below are read from. It is not committed: `build/` is git-ignored, and the run reproduces with the command in
§6.

## 8. Results

**6 tests, 0 failures, 0 errors, 0 skipped, 1.777 s** — read from the XML, not from `BUILD SUCCESSFUL`. This build
sets `failOnNoDiscoveredTests = false`, so a green build is not by itself evidence that anything ran.

### 8.1 The clean ledger (MEASURED)

```
ZS-VERIFY start jdbc=jdbc:postgresql://localhost:58197/ledger user=verifier at=2026-09-17T07:57:55.183752Z
ZS-VERIFY session transaction_read_only=on server=PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on aarch64-unknown-linux-gnu, ...
ZS-VERIFY scope entities=4 accounts=4 changelog_rows=6 applied_orders=2
ZS-VERIFY I2 global per-currency sum of ledger balances is 0: OK
ZS-VERIFY I3 account balance = Σ changelog deltas, and balance_after_minor is a correct running sum: OK
ZS-VERIFY I4 changelog seq is gapless per entity: OK
ZS-VERIFY result PASS
```

`transaction_read_only=on` is reported by the tool from the session itself, so a run that had quietly connected as a
privileged role would be visible in the evidence rather than assumed away.

### 8.2 I2 broken alone (MEASURED)

```
ZS-VERIFY scope entities=4 accounts=4 changelog_rows=7 applied_orders=2
ZS-VERIFY I2 global per-currency sum of ledger balances is 0: VIOLATED, 1: USD
ZS-VERIFY I3 account balance = Σ changelog deltas, and balance_after_minor is a correct running sum: OK
ZS-VERIFY I4 changelog seq is gapless per entity: OK
ZS-VERIFY result FAIL violated=I2
```

**This is the case that makes the tool worth running.** The ledger is wrong by exactly one minor unit, every
per-account figure still agrees with its own changelog, and the verifier names `I2` and the currency — not "something
is wrong". Process exit code **1**.

### 8.3 Corrupted changelog (MEASURED)

```
ZS-VERIFY I2 global per-currency sum of ledger balances is 0: VIOLATED, 1: USD
ZS-VERIFY I3 account balance = Σ changelog deltas, and balance_after_minor is a correct running sum: VIOLATED, 2: driver:D1/payable/USD, driver:D1#2
ZS-VERIFY I4 changelog seq is gapless per entity: VIOLATED, 1: driver:D1
ZS-VERIFY result FAIL violated=I2,I3,I4
```

All three are listed, not just the first found: an operator fixing one fault per run is a worse outcome than a long
list. I2 is still violated here from §8.2, which is why it appears. Process exit code **1**.

### 8.4 Exit codes (MEASURED)

| Situation | Exit | Asserted by |
|---|---|---|
| every invariant holds | 0 | cases 1, 2 |
| an invariant is violated | 1 | cases 4, 5 |
| database unreachable | 2 | `anUnreachableDatabaseIsAHarnessFailureAndNeverAPass` |

The third is separated on purpose: **"could not connect" is not evidence that the books balance**, and a pipeline
that treated it as a pass would report a healthy ledger it never looked at.

### 8.5 The empty-ledger guard (MEASURED)

An empty ledger satisfies I2, I3 and I4 trivially. The tool exits 0 — that is correct — and prints
`ZS-VERIFY WARNING the ledger is empty: the invariants below hold vacuously and this run says nothing about a
populated ledger`, alongside the row counts it examined. Without that line, a verifier pointed at the wrong database
would report perfect books.

## 9. Gate or threshold compared against

The invariant catalog in [master §8.3](../../zerosum_ledger_mvp_plan.md#invariants), entries I2, I3 and I4, and the
M13 (a)/(c) criteria in [master §3.1](../../zerosum_ledger_mvp_plan.md#must-have).

**Pass** for I2, I3 and I4 detection and for the M13 (c) provenance block. **Not applicable** for I1 and I5–I12,
which this tool does not evaluate.

## 10. Deviations and limitations

- **Three invariants of twelve.** I1 and I6–I12 are cross-store checks against the orders and instruments databases,
  which S06 owns and which is not built. I5 is the hash chain, evaluated by ledger-service's own `verify` endpoint.
  M11 and the R1 report are untouched.
- **The ledger under test was a fixture, not a production of the system.** Rows were inserted as SQL. This measures
  detection, not the apply engine, and it is not a claim that a real run of the stack leaves a consistent ledger.
- **No live-stack run.** See [simulator.md](simulator.md#2-status).
- **The invariant SQL is a copy** of ledger-service's `InvariantQueries` (D02-8), so the verifier can audit a database
  whose service is not running. Nothing enforces that the two copies stay identical; they must be changed together.
- **The working tree was dirty at launch**, so this result is not reproducible from the SHA in §3 alone. It is
  reproducible from the commit that introduces the harness, with the command in §6.
