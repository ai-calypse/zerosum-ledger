// decision: D00-2 (module), D01-8 (schema validator) — docs/step_01_domain_contracts.md#decisions-and-outputs
plugins {
    id("zs.library-conventions")
}

dependencies {
    implementation(project(":libs:money"))
    // Pinned in D00-1 / ADR-0002 (master §0.3 C16); JSON Schema draft 2020-12.
    implementation(libs.json.schema.validator)
}
