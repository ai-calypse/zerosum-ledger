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
}

tasks.withType<Test>().configureEach {
    systemProperty("zs.rootDir", rootDir.absolutePath)
    // Files the tests read at runtime: without these inputs Gradle would skip the tests as up-to-date
    // after an init script, image pin or migration change.
    inputs.dir(rootDir.resolve("infra/postgres")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docker-compose.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(rootDir.resolve("services")) { include("*/src/main/resources/db/migration/**") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
