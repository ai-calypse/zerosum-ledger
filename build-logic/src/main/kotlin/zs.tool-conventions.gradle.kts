// decision: D00-10 — docs/step_00_foundations.md#decisions-and-outputs
// Command-line tools with a plain main class.
plugins {
    id("zs.java-conventions")
    application
}

// CLI defaults (.env and docs/results) are relative to the repository root, even when Gradle's
// application plugin runs a nested tools project. This matches the documented root-level commands.
tasks.named<JavaExec>("run") {
    workingDir(rootDir)
}
