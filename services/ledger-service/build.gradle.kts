plugins {
    id("zs.service-conventions")
}

dependencies {
    implementation(project(":libs:money"))
    implementation(project(":libs:contracts"))
    // decision: D03-4 — shared token auth. ledger-service enforces the 401/403 its OpenAPI already
    // declares (master 0.3 C9); order-service authenticates every money endpoint (TB1).
    implementation(project(":libs:auth"))
    // decision: D04-1, D04-2 — ledger-service consumes money orders (S04-T02) and provisions its own topics.
    implementation(libs.spring.boot.starter.kafka)
    testImplementation(testFixtures(project(":libs:money")))
    // decision: D07-5 — S07-T05 measures order-to-balance latency across the REAL outbox -> Kafka -> apply path
    // (docs/results/perf/). Test scope only: the study drives the real relay and writer rather than a stand-in, since
    // a hand-rolled relay would measure this harness instead of the pipeline. ledger-service's main code does not, and
    // must not, depend on the outbox — only libs/outbox may call KafkaTemplate.send (master module boundaries).
    testImplementation(project(":libs:outbox"))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    // decision: D00-1, D01-8 — the same pinned JSON Schema validator libs/contracts uses. LedgerOpenApiContractIT
    // validates real responses against openapi/ledger-service.yaml with it, rather than adding a second validator.
    testImplementation(libs.json.schema.validator)
}

tasks.withType<Test>().configureEach {
    // Ledger integration tests start PostgreSQL with the real infra/postgres init scripts and the Compose-pinned image.
    systemProperty("zs.rootDir", rootDir.absolutePath)
    // decision: D02-11 — concurrency stress size; -Pzs.stress.size=full runs the master §8.2 size (CI nightly).
    systemProperty("zs.stress.size", providers.gradleProperty("zs.stress.size").getOrElse("pr"))
    // decision: D02-10 — SP1 smoke overrides. A Gradle -D sets a property on the daemon, not on the forked test JVM,
    // so they must be forwarded explicitly or the study silently runs at full size.
    listOf("writers", "windowSeconds", "warmupSeconds", "repetitions").forEach { name ->
        providers.gradleProperty("zs.sp1.$name").orNull?.let { systemProperty("zs.sp1.$name", it) }
    }
    // decision: D07-5, D07-6 — S07 performance study smoke overrides, forwarded for the same reason as SP1's: a
    // Gradle -D reaches the daemon, not the forked test JVM, and the study asserts that a requested override arrived.
    listOf("windowSeconds", "warmupSeconds", "repetitions", "batchSizes", "batchWriters", "entityCounts", "e2eRates")
        .forEach { name ->
            providers.gradleProperty("zs.perf.$name").orNull?.let { systemProperty("zs.perf.$name", it) }
        }
    inputs.dir(rootDir.resolve("infra/postgres")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docker-compose.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
}
