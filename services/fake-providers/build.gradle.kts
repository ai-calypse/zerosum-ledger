plugins {
    id("zs.service-conventions")
}

dependencies {
    // decision: D05-2 — fake-providers deliberately depends on NO domain library. It simulates an external system,
    // so it speaks minor units and currency codes over HTTP. Sharing Money with it would let a change in our domain
    // silently change what the "provider" does, and the simulation would stop being independent evidence.
    testImplementation(libs.testcontainers.postgresql)
}

tasks.withType<Test>().configureEach {
    // Integration tests start PostgreSQL with the real infra/postgres init scripts and the Compose-pinned image.
    systemProperty("zs.rootDir", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("infra/postgres")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docker-compose.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
}
