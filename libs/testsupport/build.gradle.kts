// decision: CR-S05-01 — docs/scope-decisions.md
// Test-only support shared by service test suites. Extracted when a fourth service needed the Testcontainers
// fixture, as CR-S05-01 said it must be: three near-identical copies were already one change away from drifting.
plugins {
    id("zs.java-conventions")
    `java-library`
}

dependencies {
    // The catalog carries no versions for Boot-managed libraries; they resolve through this platform. zs.java-conventions
    // adds it to testImplementation only, and this module's classes live in the MAIN source set, so without importing it
    // here the same coordinates resolve to a blank version ("Could not find org.flywaydb:flyway-core:").
    api(platform(libs.spring.boot.dependencies))

    // api, not implementation: consumers call flyway() and hold Connections, so these belong on their compile paths.
    api(libs.testcontainers.postgresql)
    api(libs.flyway.core)
    api(libs.postgresql)
    runtimeOnly(libs.flyway.database.postgresql)
}
