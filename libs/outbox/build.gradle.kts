plugins {
    id("zs.library-conventions")
    // The publish-path rule ships as a fixture so every producing service reuses it rather than copying it (M4(b)).
    `java-test-fixtures`
}

dependencies {
    // Every coordinate is already pinned in the catalog (D00-1), so this adds no version and needs no change request.
    implementation(platform(libs.spring.boot.dependencies))
    // The writer needs JDBC and transactions; the relay additionally needs the Kafka producer.
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.kafka)
    // decision: D03-5 — outbox meters are Micrometer, the single metrics path (D00-6).
    implementation(libs.micrometer.registry.otlp)
    // decision: D03-5, §0.3 C11 — the API only, to read the active trace context when a row is appended. The agent
    // (D00-6) remains the sole exporter; this adds no exporter and no version.
    implementation(libs.opentelemetry.api)

    testFixturesImplementation(libs.archunit.junit5)
    // The table-shape fixture asserts, so the fixtures source set needs JUnit on its own compile classpath.
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":libs:outbox")))
    testImplementation(libs.testcontainers.postgresql)
    // The fixture migrates order-service's real migration directory, so the library's own tests run against the same
    // outbox table a producing service owns (candidate A ownership, D03-5).
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.database.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testRuntimeOnly(libs.postgresql)
}

tasks.withType<Test>().configureEach {
    systemProperty("zs.rootDir", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("infra/postgres")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docker-compose.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
}
