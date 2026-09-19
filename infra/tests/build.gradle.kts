// decision: D00-2 — docs/step_00_foundations.md#decisions-and-outputs
// Test-only project for infrastructure tests (database roles and isolation); it has no main code.
plugins {
    id("zs.java-conventions")
}

dependencies {
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.database.postgresql)
    testRuntimeOnly(libs.postgresql)

    // decision: S09 — the e2e tests speak to the running Compose stack over HTTP. The JDK's own HttpClient does the
    // talking, so the only thing added here is a JSON reader for the responses.
    testImplementation(libs.jackson.databind)

    // decision: S08 — the failure-mode tests record M13 (c) provenance with the same code the tools use.
    testImplementation(project(":libs:evidence"))

    // M11(c) — the reconciliation chaos run ends by calling the real verifier entry point in-process, so the gate is
    // the same code the CLI runs, not a restatement of its checks.
    testImplementation(project(":tools:verifier"))
}

tasks.withType<Test>().configureEach {
    systemProperty("zs.rootDir", rootDir.absolutePath)
    // decision: S08 — run-size knobs for the failure-mode tests (-Dzs.volume.charges=..., -Dzs.crash.repetitions=...).
    System.getProperties().stringPropertyNames().filter { it.startsWith("zs.") && it != "zs.rootDir" }
        .forEach { systemProperty(it, System.getProperty(it)) }
    // Files the tests read at runtime: without these inputs Gradle would skip the tests as up-to-date
    // after an init script, image pin or migration change.
    inputs.dir(rootDir.resolve("infra/postgres")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docker-compose.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(rootDir.resolve("services")) { include("*/src/main/resources/db/migration/**") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
