# SP3 — Stack compatibility spike

<!-- decision: D00-7 — docs/step_00_foundations.md#decisions-and-outputs -->

## 1. Evidence ID and type

| Field | Value |
|---|---|
| Evidence ID | SP3 |
| Type | spike |
| Owning step and task | S00-T07 |
| Date (UTC) | 2026-09-15 |

## 2. Status

- **Measured**

## 3. Provenance (master §3.1 M13 c)

| Item | Value |
|---|---|
| Git commit SHA | `e87c4e76c44c906e242843b28fc1fbe0cdac56ef` (spike code and raw evidence; working tree clean apart from this results file and ADR-0002) |
| Versions | [docs/adr/0002-stack-and-pinned-versions.md](../adr/0002-stack-and-pinned-versions.md) at the SHA above. Resolved on the spike's test runtime classpath: Spring Boot 4.1.1, Spring Framework 7.0.9, spring-kafka 4.1.1, kafka-clients 4.2.1, Flyway 12.4.0, Testcontainers 2.0.5, JUnit 6.0.3, PostgreSQL JDBC 42.7.13, micrometer-registry-otlp 1.17.1, OpenTelemetry SDK 1.62.0. Runtime-reported: JVM Temurin 25.0.4.1+1, Kafka broker 4.3.1, PostgreSQL 18.6, OTel Java agent 2.31.1, Grafana 13.2.1 and otelcol-contrib 0.160.0 inside `grafana/otel-lgtm:0.33.0`. |
| Seeds | none (non-generative evidence) |
| Hardware | Apple M4, 10 cores, 24 GiB RAM, macOS 26.5.2 |

## 4. Host and Docker allocation

| Item | Value |
|---|---|
| Docker engine / Compose version | 29.8.0 / v5.5.1 (Docker Desktop 4.91.0) |
| Docker VM CPUs / memory | 10 CPUs / 7.75 GiB (8 GiB setting) |
| Emulated images (non-native architecture) | none; every image has a native `linux/arm64` manifest |
| Other load on the host during the run | Interactive development session only. Timing is not part of SP3's criterion. |

## 5. Scenario, workload and seeds

A throwaway Boot application (`spikes/sp3-stack` at the SHA above) implements HTTP `POST /spike/messages` → `JdbcClient` insert into `sp3_spike.inbound` (Flyway-created) → `KafkaTemplate.send` to the explicitly created topic `sp3.spike` → `@KafkaListener` → `JdbcClient` insert into `sp3_spike.consumed`, incrementing the Micrometer counter `sp3.spike.consumed`.

Client settings from master [#platform-limits](../zerosum_ledger_mvp_plan.md#platform-limits) were set explicitly so the pinned broker had to accept them: producer `acks=all` and `enable.idempotence=true`, consumer `group.protocol=consumer`. Their final values belong to S04 (D04-2).

- **A, B, D:** the application ran on the host against the Compose stack of the S00 branch, with the OTel Java agent attached and exporting to otel-lgtm through the local-only OTLP port (D00-3). Three messages were posted per run.
- **C:** `Sp3FlowIT` (tag `integration`) ran the same flow against Testcontainers PostgreSQL and Kafka, using the Compose-pinned images, without the agent.

## 6. Exact commands

```sh
# build, agent copy, evidence C
./gradlew :spikes:sp3-stack:build :spikes:sp3-stack:copyOtelAgent :spikes:sp3-stack:integrationTest

# evidence A, B, D (final run); secrets come from .env
set -a; source ./.env; set +a
export OTEL_SERVICE_NAME=sp3-stack OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318 OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
java -javaagent:spikes/sp3-stack/build/agent/opentelemetry-javaagent.jar \
     -Dotel.metrics.exporter=none -Dotel.logs.exporter=none \
     -jar spikes/sp3-stack/build/libs/sp3-stack.jar --management.endpoints.web.exposure.include=health,conditions
curl -X POST -H 'Content-Type: text/plain' --data sp3-final2-a http://127.0.0.1:8099/spike/messages   # ×3 (a, b, c)

# after ≥ 3 Micrometer steps (10 s each)
curl -X POST http://127.0.0.1:3000/api/ds/query -H 'Content-Type: application/json' \
     -d '{"queries":[{"refId":"B","datasource":{"uid":"prometheus"},"expr":"sp3_spike_consumed_total{job=\"sp3-stack\"}","instant":true}],"from":"<now-5m>","to":"<now>"}'
curl -G http://127.0.0.1:3000/api/datasources/proxy/uid/tempo/api/search --data-urlencode 'tags=service.name=sp3-stack' ...
curl http://127.0.0.1:3000/api/datasources/proxy/uid/tempo/api/traces/<traceID>

# trace header on the Kafka record
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 \
     --topic sp3.spike --from-beginning --max-messages 1 --property print.headers=true
```

## 7. Raw data

All in [docs/results/sp3-raw/](sp3-raw/):

- **Final run:** `final-run.txt` (conditions report excerpt, publish log line, posted IDs, summarized evidence), `grafana-ds-query-sp3-consumed.json`, `prometheus-sp3-consumed-range.json`, `prometheus-jvm-memory-used.json`, `tempo-search-final.json`, `trace-final-2aedbef9dae17723334c6406cfa68e46.json`.
- **Evidence C:** `evidence-c-testcontainers.txt` and `TEST-dev.zerosum.spike.sp3.Sp3FlowIT.xml`.
- **Earlier runs, kept for the B investigation:** `host-run.txt`, `host-run-2.txt`, `app-log-excerpt.txt`, `trace-229e13336e3b26b5843a866bca0d38ec.json`, `trace-final-97b5cef9ef54fc60d527fbd3208f7c59.json`, `tempo-search.json`, `prometheus-*.json`, `db-rows.txt`.
- **Trace propagation header:** `kafka-record-headers.txt`.

## 8. Results

SP3 is a pass/fail spike with one run per criterion. Repetitions and ranges don't apply.

| Criterion | Result | Evidence |
|---|---|---|
| **A — trace** | **Passed.** Trace `2aedbef9dae17723334c6406cfa68e46` (service `sp3-stack`) has 5 spans: `POST /spike/messages` (SERVER, root) → `INSERT sp3_spike.inbound` (CLIENT) and `sp3.spike publish` (PRODUCER) → `sp3.spike process` (CONSUMER, **child of the producer span, no span link**) → `INSERT sp3_spike.consumed` (CLIENT). Every run produced the same shape (also `229e1333…`, `97b5cef9…`). The record carries a W3C `traceparent` header. | `final-run.txt`, `trace-final-2aedbef9….json`, `kafka-record-headers.txt` |
| **B — metric** | **Passed** (after two wiring findings, §10). Grafana's `/api/ds/query` returns `sp3_spike_consumed_total{job="sp3-stack"} = 3`, with samples 0 at 21:49:15Z and 3 at 21:49:25Z, one export step apart. `OtlpMetricsExportAutoConfiguration` matched, and the log shows `Publishing metrics for OtlpMeterRegistry every 10s to http://127.0.0.1:4318/v1/metrics`. Duplicate check: only one JVM memory metric family (`jvm_memory_used_bytes`, 8 series) exists for the job, so the agent exported no metrics of its own. | `final-run.txt`, `grafana-ds-query-sp3-consumed.json`, `prometheus-sp3-consumed-range.json`, `prometheus-jvm-memory-used.json` |
| **C — Testcontainers** | **Passed.** `Sp3FlowIT.httpToKafkaToDatabaseFlowWorks()` passed in 9.4 s against `postgres:18.6@sha256:4ef4…` and `apache/kafka:4.3.1@sha256:77e3…`, without the agent. | `evidence-c-testcontainers.txt`, `TEST-…Sp3FlowIT.xml` |
| **D — Flyway** | **Passed.** At startup, Boot's Flyway starter applied `V1__spike_tables` to schema `sp3_spike` (`installed_by=orders_owner`), both on the Compose database and in the Testcontainers run. | `app-log-excerpt.txt`, `db-rows.txt`, `evidence-c-testcontainers.txt` |

**Decision (D00-7):** A–D pass, so we proceed with the proposed wiring: OTel Java agent for traces, Micrometer OTLP export for metrics. Fallback: **none**.

**Timebox:** started about 21:30Z, decided at 21:50Z, about 0.4 h against the 2 h timebox. No contingency hours were used (master §0.3 O1).

## 9. Gate or threshold compared against

- SP3 criterion: [docs/zerosum_ledger_mvp_plan.md#spikes](../zerosum_ledger_mvp_plan.md#spikes). **Pass.**
- Gate G0: [docs/zerosum_ledger_mvp_plan.md#decision-gates](../zerosum_ledger_mvp_plan.md#decision-gates). **Proceed.** Recorded in [docs/step_00_foundations.md](../step_00_foundations.md) H.6 before S00-T08 starts (master §0.3 O2).

## 10. Deviations and limitations

- **The first B attempts failed because of wiring, not incompatibility.** Both causes were found and fixed within the timebox:
  1. `micrometer-registry-otlp` alone creates no registry in Boot 4.1. `OtlpMetricsExportAutoConfiguration` is conditional on `OpenTelemetryProperties` from the `spring-boot-opentelemetry` module, so that module was added. The starter was not used, because its Micrometer Tracing bridge would export traces a second time next to the agent.
  2. The runs set `OTEL_METRICS_EXPORTER=none` as an environment variable for the agent. That module's `OpenTelemetryEnvironmentVariableEnvironmentPostProcessor` maps it onto `management.otlp.metrics.export.enabled=false`, and its property source outranks command-line arguments, so Micrometer export stayed off (conditions report in `host-run-2.txt` and the investigation logs). The agent's exporters are now disabled with `-Dotel.metrics.exporter=none -Dotel.logs.exporter=none`.

  Both findings bind D00-6 and S00-T08.
- **`@ServiceConnection` with digest-pinned images** needs an explicit `name`. Boot can't parse `repo:tag@sha256:…` into a connection name.
- **Tempo TraceQL query form.** The first search used a TraceQL query that returned no results; tag search (`tags=service.name=sp3-stack`) works and is used.
- **API responses instead of screenshots.** Grafana and Tempo evidence is recorded as JSON responses from Grafana's query and datasource-proxy APIs. They are what the UI panels render and are reproducible from the raw files.
- **Host-run spike.** A–D ran the application on the host, not as a container, and exported through the local-only OTLP port in D00-3. Container wiring is proven separately in S00-T08.
- **Docker Desktop instability** on this host (containers stuck in `Created` until `docker desktop restart`) interrupted work before the spike. It did not affect any SP3 run, but it is recorded in the S00 register (H.5).
