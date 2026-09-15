# ADR-0002 — Stack and pinned versions

- **Status:** Proposed (becomes Accepted when S00-T07 records the SP3 result)
- **Date:** 2026-09-15
- **Decision owner:** S00 (D00-1, D00-7) — [docs/step_00_foundations.md#decisions-and-outputs](../step_00_foundations.md#decisions-and-outputs)

## Context

The master plan ([§4.1 stack](../zerosum_ledger_mvp_plan.md#stack), [§4.6 topology](../zerosum_ledger_mvp_plan.md#topology)) proposes the stack with versions checked on 2026-09-15 and asks Step 00 to recheck and pin exact versions, preferring the versions Spring Boot manages over the latest upstream releases. Floating tags (`postgres:18`, an untagged `grafana/otel-lgtm`) would make builds and measurements irreproducible.

## Decision

Pin every component below. The artifacts hold the values; this table is the dated record and must match them exactly (checked by the S00-T06 comparison script). All rows were rechecked at the listed primary source on **2026-09-15**. No pre-releases are used.

### Build and toolchain

| Component | Version | Managed in | Source | Deviation from master |
|---|---|---|---|---|
| Java toolchain | 25 (provisioned: Eclipse Temurin 25.0.4.1+1) | `gradle/libs.versions.toml` (`java`), `build-logic` | https://www.oracle.com/java/technologies/java-se-support-roadmap.html | — |
| Gradle wrapper | 9.7.1, `distributionSha256Sum` acd53f1e…d20a | `gradle/wrapper/gradle-wrapper.properties` | https://services.gradle.org/versions/current | Not named in master; latest release, supports Java 17–26 for the daemon and 25 as toolchain |
| Foojay toolchain resolver | 1.0.0 | `settings.gradle.kts` | https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention | Not named in master; provisions JDK 25 on hosts with another JDK (A7) |

### Framework and libraries

Boot-managed rows carry no version in the catalog; they resolve through the `spring-boot-dependencies` platform.

| Component | Version | Managed in | Source | Deviation from master |
|---|---|---|---|---|
| Spring Boot (BOM and Gradle plugin) | 4.1.1 | catalog (`spring-boot`) | https://api.spring.io/projects/spring-boot/releases | — |
| Spring Framework | 7.0.9 | Boot BOM | https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html | — |
| spring-kafka | 4.1.1 | Boot BOM | same | — |
| kafka-clients | 4.2.1 | Boot BOM | same | — |
| Flyway (`flyway-core`, `flyway-database-postgresql`) | 12.4.0 | Boot BOM | same | — |
| Testcontainers (`testcontainers-postgresql`, `-kafka`, `-toxiproxy`) | 2.0.5 | Boot BOM | same | — |
| JUnit | 6.0.3 | Boot BOM | same | — |
| PostgreSQL JDBC driver | 42.7.13 | Boot BOM | same | Not named in master |
| Micrometer (incl. `micrometer-registry-otlp`) | 1.17.1 | Boot BOM | same | — |
| HikariCP | 7.0.2 | Boot BOM | same | Not named in master |
| Jackson | 3.1.5 (`tools.jackson`) | Boot BOM | same | Not named in master |
| ArchUnit (`archunit-junit5`) | 1.5.0 | catalog (`archunit`) | https://repo1.maven.org/maven2/com/tngtech/archunit/archunit-junit5/maven-metadata.xml | Named by master §0.3 C16; first used in S01 |
| JSON Schema validator (`com.networknt:json-schema-validator`) | 3.0.7; schema dialect **draft 2020-12** | catalog (`json-schema-validator`) | https://repo1.maven.org/maven2/com/networknt/json-schema-validator/maven-metadata.xml | Library chosen here (master §0.3 C16); S01 confirms it works with the Boot-managed Jackson line before relying on it |
| OpenTelemetry Java agent | 2.31.1 | catalog (`opentelemetry-javaagent`) | https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases | — |

### Container images

Images are pinned by tag **and** multi-arch index digest. Every image has a native `linux/arm64` manifest, so nothing runs emulated on the arm64 reference host.

| Component | Tag and digest | Managed in | Source | Deviation from master |
|---|---|---|---|---|
| PostgreSQL | `postgres:18.6@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280` | `docker-compose.yml` (read by `DatabaseIsolationIT`) | https://hub.docker.com/_/postgres | Master listed major-only `postgres:18`; pinned to 18.6 |
| Apache Kafka (KRaft) | `apache/kafka:4.3.1@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837` | `docker-compose.yml` | https://hub.docker.com/r/apache/kafka | — |
| grafana/otel-lgtm | `grafana/otel-lgtm:0.33.0@sha256:475319e883b66594d1a2f22ef168c2459802bb94548e6f25d9782bd5f5c19a3a` | `docker-compose.yml` | https://github.com/grafana/docker-otel-lgtm/releases | Master listed no tag; pinned to 0.33.0 (2026-09-11) |
| Toxiproxy | `ghcr.io/shopify/toxiproxy:2.12.0@sha256:9378ed52a28bc50edc1350f936f518f31fa95f0d15917d6eb40b8e376d1a214e` | `docker-compose.yml` (`chaos` profile) | https://github.com/Shopify/toxiproxy/releases | — |

### Tools and CI

| Component | Version | Managed in | Source | Deviation from master |
|---|---|---|---|---|
| Grafana k6 | v2.2.0 | host binary; pinned in `tools/k6` by S07 | https://github.com/grafana/k6/releases | — |
| `actions/checkout` | v7.0.1 → `3d3c42e5aac5ba805825da76410c181273ba90b1` | `.github/workflows/ci.yml` | https://github.com/actions/checkout/releases | Not named in master |
| `actions/setup-java` | v6.0.1 → `de7274f081f381c8f8158605e0321c36c376e2e6` | `.github/workflows/ci.yml` | https://github.com/actions/setup-java/releases | Not named in master |
| `gradle/actions/setup-gradle` | v6.3.0 → `9c971963bec38e04b3d30dcc455b5382be2fdbfb` | `.github/workflows/ci.yml` | https://github.com/gradle/actions/releases | Not named in master |
| `actions/upload-artifact` | v7.0.1 → `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` | `.github/workflows/ci.yml` | https://github.com/actions/upload-artifact/releases | Not named in master |

Host tools used on the reference laptop but not pinned by the repository: Docker Engine 29.8.0, Docker Compose v5.5.1, host JDK 26.0.1 (runs the Gradle daemon only).

### Pin-update policy (D00-1)

Later steps may add catalog entries for tools already pinned here, recording the addition in their own register. Changing a pinned version, or leaving the Boot-managed line other than through SP3 fallback B, is a change request against D00-1 ([docs/README.md#conflict-resolution](../README.md#conflict-resolution)).

## Consequences

- Builds and container starts are reproducible from a clone; a version bump is a visible diff in one artifact plus this table.
- Digest pins survive upstream tag re-pushes but must be refreshed deliberately when a tag is rechecked.
- **Licenses.** k6 (AGPL-3.0) and the Grafana LGTM stack are used as unmodified tools, never linked or redistributed. No jqwik (maintenance status and AI-usage clause, master §2.5). Flyway (Apache-2.0) rather than Liquibase (FSL).
- **MVP limitations, not production properties.** Kafka runs as a single broker without replication or authentication on the private Compose network; losing its volume loses unconsumed messages ([master §3.6](../zerosum_ledger_mvp_plan.md#mvp-vs-production)).
- The client–broker combination (kafka-clients 4.2.1 against a 4.3.1 broker), the OTel agent on Boot 4.1 and Micrometer OTLP export are unproven until SP3 records its result below.

## SP3 result

*Pending S00-T07.*
