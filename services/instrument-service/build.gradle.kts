plugins {
    id("zs.service-conventions")
}

dependencies {
    // decision: D05-1 — commands carry Money, and ProviderId derives the `provider:<id>` entity id from EntityKind,
    // so the instrument layer agrees with the chart of accounts instead of inventing a second convention.
    implementation(project(":libs:money"))
}
