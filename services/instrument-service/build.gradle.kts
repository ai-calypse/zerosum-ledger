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
    // decision: D03-4 — the attempt read and cancel endpoints are this service's first authenticated ones
    // (S05-T08, §0.3 C9). Same module as order-service and ledger-service: three services authenticating three
    // ways is how one of them ends up weaker.
    implementation(project(":libs:auth"))
    // decision: D04-1, D04-2 — instrument-service consumes money orders through the instrument-policy consumer
    // (S05-T09), provisions its own topics, and runs the outbox relay that publishes payment events.
    implementation(libs.spring.boot.starter.kafka)

    // decision: D05-1 — the S05-T06 boundary rule (core must never name an adapter). No HTTP stub library is added:
    // the classification tests drive a JDK com.sun.net.httpserver stub, which is enough to hold a socket open past a
    // read timeout and is one fewer dependency to pin.
    testImplementation(libs.archunit.junit5)

    // decision: CR-S05-01 — the shared container fixture, rather than a fourth copy of the same wiring.
    testImplementation(project(":libs:testsupport"))

    // decision: D00-1, D05-13 — the same pinned JSON Schema validator libs/contracts and ledger-service use.
    // AttemptEndpointsIT validates real responses against openapi/instrument-service.yaml with it, rather than
    // adding a second validator.
    testImplementation(libs.json.schema.validator)

    // decision: D05-6 — CollectionPolicyIT needs a real broker: a mocked listener cannot prove that an order
    // delivered three times charges the rider once, because the uniqueness that makes it true is in the database
    // and the ordering that makes it safe is in the broker.
    testImplementation(libs.testcontainers.kafka)
    // decision: D03-5 — the M4(b) publish-path rule ships as a libs/outbox fixture rather than being copied here.
    testImplementation(testFixtures(project(":libs:outbox")))
}

tasks.withType<Test>().configureEach {
    // Schema tests start PostgreSQL with the real infra/postgres init scripts and the Compose-pinned image.
    systemProperty("zs.rootDir", rootDir.absolutePath)
    inputs.dir(rootDir.resolve("infra/postgres")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("docker-compose.yml")).withPathSensitivity(PathSensitivity.RELATIVE)
    // ComposeEnvTest compares these two files, so an edit to either must re-run it. Without this the guard passes
    // from cache while the thing it guards is broken — which is exactly how it behaved when first written.
    inputs.file(rootDir.resolve(".env.example")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/resources/db/migration").withPathSensitivity(PathSensitivity.RELATIVE)
}
