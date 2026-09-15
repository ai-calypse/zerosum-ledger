// decision: D00-10 — docs/step_00_foundations.md#decisions-and-outputs
// Plain JAR libraries. Adds no main dependencies (libs/money must depend on nothing but the JDK).
plugins {
    id("zs.java-conventions")
    `java-library`
}
