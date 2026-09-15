plugins {
    id("zs.service-conventions")
}

dependencies {
    testImplementation(libs.testcontainers.postgresql)
}

tasks.withType<Test>().configureEach {
    // Ledger integration tests start PostgreSQL with the real infra/postgres init scripts and the Compose-pinned image.
    systemProperty("zs.rootDir", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("infra/postgres")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docker-compose.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
}
