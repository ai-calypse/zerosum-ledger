# ZeroSum Ledger

A zero-sum, immutable payments ledger modeled on the principles Uber published for its payments platform: immutable money orders whose entries sum to zero per currency, a ledger with an entity changelog, Kafka between services, and a pluggable payment-instrument interface exercised by two fake providers (FakeCard and FakeBank). It is a learning and portfolio project; no real money, cards or bank accounts are involved.

**Status:** under construction. Implementation follows the documentation pack step by step; nothing is complete until its step register records evidence. This quickstart is a skeleton: sections marked *Planned* are finalized in S09-T01, and no timing claims are made yet.

## Quickstart

### 1. Prerequisites

- Docker Engine with Compose v2 and a VM memory allocation per the note in [master §4.6 topology](docs/zerosum_ledger_mvp_plan.md#topology).
- A JDK 17–26 to run the Gradle wrapper. The build compiles with a pinned Java 25 toolchain, which Gradle provisions automatically if it isn't installed.
- `openssl` (used to generate local secrets).

### 2. Configure `.env`

```sh
tools/dev/generate-env.sh
```

This creates a git-ignored `.env` with generated local secrets. See [docs/secrets.md](docs/secrets.md).

### 3. Start and verify the stack

```sh
./gradlew assemble                 # service jars + OpenTelemetry agent for the images
docker compose up -d --build --wait
docker compose ps
```

`--wait` returns once every container reports healthy. *Planned — finalized in S09-T01.*

### 4. Run the tests

```sh
./gradlew build            # compile + fast untagged tests, no containers
./gradlew integrationTest  # @Tag("integration"), Testcontainers (needs Docker)
./gradlew e2eTest          # @Tag("e2e"), needs the running Compose stack
```

### 5. Observability

Grafana (otel-lgtm) is published on `http://127.0.0.1:3000`. *Planned — service telemetry wiring lands in S00-T08; dashboards in S07.*

### 6. Reset

```sh
docker compose down -v
```

This deletes all data. PostgreSQL init scripts (`infra/postgres/`) run only on an empty data volume, so run it after changing them or after regenerating `.env`.

## Further reading

- Documentation pack index: [docs/README.md](docs/README.md)
- MVP engineering report (master plan): [docs/zerosum_ledger_mvp_plan.md](docs/zerosum_ledger_mvp_plan.md)
- Architecture decision records: [docs/adr/](docs/adr/)
