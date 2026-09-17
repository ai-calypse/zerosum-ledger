plugins {
    id("zs.service-conventions")
}

dependencies {
    // decision: D01-1, D01-5, D01-6 — Money, the validator, the chart of accounts and the currency allow-list are
    // consumed from libs/money, never re-implemented here (S03-T01, S03-T03, S03-T07).
    implementation(project(":libs:money"))
    // decision: D01-8, D01-9 — the money-order JSON Schema and the golden payloads the API and mapper validate against.
    implementation(project(":libs:contracts"))
    // decision: D03-4 — shared token auth. ledger-service enforces the 401/403 its OpenAPI already
    // declares (master 0.3 C9); order-service authenticates every money endpoint (TB1).
    implementation(project(":libs:auth"))
    // decision: D03-5 — the transactional outbox library. Only these classes may call the Kafka producer (M4(b)).
    implementation(project(":libs:outbox"))
    implementation(libs.spring.boot.starter.kafka)
    testImplementation(testFixtures(project(":libs:money")))
    testImplementation(libs.testcontainers.postgresql)
    // decision: D03-5 — the M4(b) publish-path rule ships as a libs/outbox fixture rather than being copied here.
    testImplementation(testFixtures(project(":libs:outbox")))
    testImplementation(libs.archunit.junit5)
}

tasks.withType<Test>().configureEach {
    // Order-service integration tests start PostgreSQL with the real infra/postgres init scripts and the Compose pin,
    // exactly as the ledger tests do (D00-3, D00-4).
    systemProperty("zs.rootDir", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("infra/postgres")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docker-compose.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
}
