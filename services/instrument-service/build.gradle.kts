plugins {
    id("zs.service-conventions")
}

dependencies {
    // decision: D05-1 — commands carry Money, and ProviderId derives the `provider:<id>` entity id from EntityKind,
    // so the instrument layer agrees with the chart of accounts instead of inventing a second convention.
    implementation(project(":libs:money"))
    // decision: D05-4 — every status change appends a payment event in the same transaction (D03-5, ADR-0008),
    // and the payload is validated against the D01-8 schema that libs/contracts owns.
    implementation(project(":libs:outbox"))
    implementation(project(":libs:contracts"))

    // decision: D05-1 — the S05-T06 boundary rule (core must never name an adapter). No HTTP stub library is added:
    // the classification tests drive a JDK com.sun.net.httpserver stub, which is enough to hold a socket open past a
    // read timeout and is one fewer dependency to pin.
    testImplementation(libs.archunit.junit5)

    // decision: CR-S05-01 — the shared container fixture, rather than a fourth copy of the same wiring.
    testImplementation(project(":libs:testsupport"))
}

tasks.withType<Test>().configureEach {
    // Schema tests start PostgreSQL with the real infra/postgres init scripts and the Compose-pinned image.
    systemProperty("zs.rootDir", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("infra/postgres")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docker-compose.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/resources/db/migration").withPathSensitivity(PathSensitivity.RELATIVE)
}
