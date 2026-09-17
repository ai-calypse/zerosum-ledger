// decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness
plugins {
    id("zs.tool-conventions")
}

application {
    mainClass = "dev.zerosum.verifier.VerifierMain"
}

dependencies {
    // The M13 (c) provenance block and the .env reader.
    implementation(project(":libs:evidence"))

    // decision: D00-1 — the catalog carries no version for the Boot-managed JDBC driver, so the platform must be
    // imported for it to resolve. The main source set compiles against java.sql only; the driver registers itself
    // through the JDBC service loader, which is why it is runtimeOnly and no code here names org.postgresql.
    implementation(platform(libs.spring.boot.dependencies))
    runtimeOnly(libs.postgresql)

    // decision: CR-S05-01 — the shared container fixture. The verifier's own tests migrate the REAL ledger migration
    // directory and connect as the REAL verifier role, so a missing grant fails here rather than in a live run.
    testImplementation(project(":libs:testsupport"))
    testRuntimeOnly(libs.postgresql)
}

tasks.withType<Test>().configureEach {
    // ZsTestDatabase starts PostgreSQL with the real infra/postgres init scripts and the Compose-pinned image.
    systemProperty("zs.rootDir", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("infra/postgres")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docker-compose.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(rootDir.resolve("services/ledger-service/src/main/resources/db/migration")))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
