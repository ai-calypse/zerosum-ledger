# ZeroSum Ledger

A zero-sum, immutable payments ledger modeled on the principles Uber published for its payments platform: immutable
money orders whose entries sum to zero per currency, a ledger with a per-entity changelog, a transactional outbox and
Kafka between services, and a pluggable payment-instrument interface exercised by two deliberately mismatched fake
providers.

It is a learning and portfolio project. No real money, cards or bank accounts are involved.

## What it demonstrates

- **Money that cannot silently go missing.** Every order's entries sum to zero per currency, enforced by application
  validation *and* by a deferred database constraint, so an insert that bypasses the application still fails at
  `COMMIT`.
- **An append-only audit trail.** `UPDATE`, `DELETE` and `TRUNCATE` fail on the order and ledger tables for the
  application role *and* for the owner role; corrections are compensating orders, never edits.
- **Effectively-once processing.** Publishing every order three times produces balances identical to publishing once.
- **A transactional outbox.** The API accepts orders while the broker is down; the relay publishes afterwards, and
  marks a row published only after the broker acknowledges it.
- **Uncertain outcomes as a first-class result.** A provider timeout is neither success nor failure, and the design
  refuses to guess: it is resolved by lookup, and for a provider without idempotency keys, only after a quiet period.
- **Observability that can be alerted on**, and **CI** that runs the unit, integration and container layers.

## Measured evidence

Every number here comes from an executed run, not from reading the code. Test counts below were force-executed with
`--rerun-tasks` on 2026-09-16, because a Gradle up-to-date pass proves only that nothing changed.

| What | Result | Where |
|---|---|---|
| Unit tests, 7 modules | 223 passed, 0 failed, 4 skipped | `./gradlew test` |
| Integration tests, 6 modules (Testcontainers) | 180 passed, 0 failed, 7m54s | `./gradlew integrationTest` |
| Ledger lock contention study (SP1) | [docs/results/sp1-lock-study.md](docs/results/sp1-lock-study.md) | measured |
| Stack version compatibility spike (SP3) | [docs/results/sp3-stack-compat.md](docs/results/sp3-stack-compat.md) | measured |
| Fake providers and adapters | [docs/results/s05/providers.md](docs/results/s05/providers.md) | measured |
| Trace across API → outbox → Kafka → apply | [docs/results/s04-trace-propagation.md](docs/results/s04-trace-propagation.md) | measured |
| End-to-end money path, live stack | 1 test, 0 failed | `./gradlew e2eTest` (needs the stack up) |

The four skipped tests are deliberate. The shared adapter contract suite runs against **both** providers, and on
each one its settlement-report and webhook-parsing cases abort on a JUnit assumption naming the capability that is
not implemented — two cases times two providers. They are reported as skipped rather than quietly passing, which is
the whole reason the cases exist instead of being omitted.

## What is *not* built

Kept explicit on purpose — see [docs/scope-decisions.md](docs/scope-decisions.md) for each decision and its cost.

- **End-to-end coverage is one test, the money path only.** It asserts that an accepted order reaches the ledger,
  that its entries sum to zero, that the changelog links back to the order, and that the ledger's invariants hold.
  Nothing else is covered end to end: no provider path, no crash or restart, no fault injection, and no reconciliation.
- **instrument-service has no persistence and no API.** It is an adapter layer. Nothing records a payment attempt, so
  nothing resolves an `UNKNOWN` outcome yet, and the quiet-period rule is specified but unimplemented.
- **No reconciliation, no webhook delivery, no fault injection.** Settlement reports are declared in the interface and
  implemented by nobody.
- **Acceptance criteria M8–M11, M13 and M14 are unmet.** M7 is not claimed: both adapters pass one shared contract
  suite, but only against a stub, never against the running fake-providers service.

## Quickstart

Everything below has a `make` target; run `make` on its own to list them.

```sh
make env      # generate .env with local throwaway secrets (once)
make up       # build and start the stack, with the fake providers reachable
make demo     # move real money through it, then show the books
make explorer # open the Ledger Explorer
make down     # stop everything and delete all data
```

The long form follows, for when you want to see what those do.

### 1. Prerequisites

- Docker Engine with Compose v2, with the VM memory noted in
  [master §4.6 topology](docs/zerosum_ledger_mvp_plan.md#topology).
- A JDK 17–26 to run the Gradle wrapper. The build compiles with a pinned Java 25 toolchain, which Gradle provisions
  automatically if it is not installed.
- `openssl`, used to generate local secrets.

### 2. Configure `.env`

```sh
tools/dev/generate-env.sh
```

Creates a git-ignored `.env` with generated local secrets. See [docs/secrets.md](docs/secrets.md).

### 3. Start the stack

```sh
./gradlew assemble                 # service jars + the OpenTelemetry agent for the images
docker compose up -d --build --wait
docker compose ps
```

`--wait` returns once every container reports healthy.

> **Timing note:** M14(a) asks for a timed fresh-clone run to a completed W1 scenario in under 10 minutes. That has
> **not been run**, so no timing is claimed here.

### 4. Run the tests

```sh
./gradlew build            # compile + fast untagged tests, no containers
./gradlew integrationTest  # @Tag("integration"), Testcontainers (needs Docker)
./gradlew e2eTest          # @Tag("e2e") — needs the Compose stack running (step 3)
```

### 5. Observability

Grafana (otel-lgtm) is published on `http://127.0.0.1:3000` with provisioned dashboards for flow and money
invariants, and alert rules for invariant violations, outbox backlog, consumer lag and quarantined records. The
signal registry is [infra/otel/registry.yaml](infra/otel/registry.yaml); the alert runbook is
[docs/runbook.md](docs/runbook.md).

### 6. Reset

```sh
docker compose down -v
```

Deletes all data. The PostgreSQL init scripts (`infra/postgres/`) run only on an empty data volume, so reset after
changing them or after regenerating `.env`.

## Further reading

- Architecture decision records: [docs/adr/](docs/adr/) — 10 accepted, including the
  [sign convention](docs/adr/0003-sign-convention.md), the [partition key](docs/adr/0007-partition-key.md) and the
  [payment-instrument abstraction](docs/adr/0010-payment-instrument-abstraction.md).
- Scope decisions and known gaps: [docs/scope-decisions.md](docs/scope-decisions.md)
- Documentation pack index: [docs/README.md](docs/README.md)
- MVP engineering report (the master plan this implements): [docs/zerosum_ledger_mvp_plan.md](docs/zerosum_ledger_mvp_plan.md)
