plugins {
    id("zs.service-conventions")
}

dependencies {
    implementation(project(":libs:money"))
    implementation(project(":libs:contracts"))
    testImplementation(testFixtures(project(":libs:money")))
    testImplementation(libs.testcontainers.postgresql)
}

tasks.withType<Test>().configureEach {
    // Ledger integration tests start PostgreSQL with the real infra/postgres init scripts and the Compose-pinned image.
    systemProperty("zs.rootDir", rootDir.absolutePath)
    // decision: D02-11 — concurrency stress size; -Pzs.stress.size=full runs the master §8.2 size (CI nightly).
    systemProperty("zs.stress.size", providers.gradleProperty("zs.stress.size").getOrElse("pr"))
    inputs.dir(rootDir.resolve("infra/postgres")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docker-compose.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
}
