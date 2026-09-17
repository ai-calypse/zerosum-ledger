// decision: D00-2, D00-10 — docs/step_00_foundations.md#decisions-and-outputs
pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // decision: D00-1 — provisions the pinned JDK when the host JDK differs (master A7)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "zerosum-ledger"

include(
    "libs:money",
    "libs:testsupport",
    // decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness (M13 (c) provenance, shared by both tools)
    "libs:evidence",
    "libs:contracts",
    "libs:outbox",
    "libs:auth",
    "services:order-service",
    "services:ledger-service",
    "services:instrument-service",
    "services:fake-providers",
    "tools:simulator",
    "tools:verifier",
    "infra:tests",
)
