plugins {
    id("zs.service-conventions")
}

dependencies {
    // decision: D05-1 — commands carry Money, and ProviderId derives the `provider:<id>` entity id from EntityKind,
    // so the instrument layer agrees with the chart of accounts instead of inventing a second convention.
    implementation(project(":libs:money"))

    // decision: D05-1 — the S05-T06 boundary rule (core must never name an adapter). No HTTP stub library is added:
    // the classification tests drive a JDK com.sun.net.httpserver stub, which is enough to hold a socket open past a
    // read timeout and is one fewer dependency to pin.
    testImplementation(libs.archunit.junit5)
}
