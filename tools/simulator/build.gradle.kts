// decision: CR-S09-01 — docs/scope-decisions.md#m13-evidence-harness
plugins {
    id("zs.tool-conventions")
}

application {
    mainClass = "dev.zerosum.simulator.SimulatorMain"
}

dependencies {
    // The M13 (c) provenance block and the .env reader.
    implementation(project(":libs:evidence"))

    // The domain itself: FareSplitter builds the W1 split, and ZeroSumValidator re-checks every generated body
    // before it is posted, so a simulator bug fails here instead of being recorded as a service rejection.
    implementation(project(":libs:money"))

    // decision: D01-10, ADR-0009 — the seeded generators. They ship as test fixtures of libs/money (S01-T06) and this
    // is a main source set, which is unusual and deliberate: S08-T01 says "never maintain two generators", and the
    // trip shape the simulator drives is exactly the one TripSequenceGenerator already produces. The cost is that the
    // fixtures' own test-scoped dependencies land on this tool's runtime classpath; the alternative was a second,
    // silently diverging copy of the randomness that every seeded claim in this repository rests on.
    implementation(testFixtures(project(":libs:money")))

    // decision: D00-1 — Boot-managed, so the platform must be imported for the versionless coordinate to resolve.
    // The JDK's own HttpClient does the talking, exactly as MoneyPathE2ETest does; this only reads the responses.
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.jackson.databind)
}
