// decision: D00-2 (module), D01-10 (test fixtures) — docs/step_01_domain_contracts.md#decisions-and-outputs
plugins {
    id("zs.library-conventions")
    `java-test-fixtures`
}

dependencies {
    // Main stays JDK-only (ArchUnit Rule D). Generators, the seed extension and the ArchUnit rules ship as test
    // fixtures, which consumers opt into with testImplementation(testFixtures(project(":libs:money"))).
    testFixturesApi(platform(libs.spring.boot.dependencies))
    testFixturesApi(libs.junit.jupiter)

    // Schema/validator agreement check in ValidatorGenerativeTest (S01-T06).
    testImplementation(project(":libs:contracts"))
}

tasks.withType<Test>().configureEach {
    // Lets SeededExtension print an exact replay command for this project's test task.
    systemProperty("zs.gradleTestTask", path)
}
