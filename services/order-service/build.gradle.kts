plugins {
    id("zs.service-conventions")
}

dependencies {
    // decision: D01-1, D01-5, D01-6 — Money, the validator, the chart of accounts and the currency allow-list are
    // consumed from libs/money, never re-implemented here (S03-T01, S03-T03, S03-T07).
    implementation(project(":libs:money"))
    // decision: D01-8, D01-9 — the money-order JSON Schema and the golden payloads the API and mapper validate against.
    implementation(project(":libs:contracts"))
    testImplementation(testFixtures(project(":libs:money")))
    testImplementation(libs.testcontainers.postgresql)
}

tasks.withType<Test>().configureEach {
    // Order-service integration tests start PostgreSQL with the real infra/postgres init scripts and the Compose pin,
    // exactly as the ledger tests do (D00-3, D00-4).
    systemProperty("zs.rootDir", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("infra/postgres")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docker-compose.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
}
