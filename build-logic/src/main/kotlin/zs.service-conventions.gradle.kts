// decision: D00-10 — docs/step_00_foundations.md#decisions-and-outputs
// Spring Boot services. Only services apply the Boot plugin; the BOM is imported as a Gradle platform.
plugins {
    id("zs.java-conventions")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

val otelAgent = configurations.create("otelAgent")

dependencies {
    implementation(platform(libs.findLibrary("spring-boot-dependencies").get()))
    implementation(libs.findLibrary("spring-boot-starter-webmvc").get())
    implementation(libs.findLibrary("spring-boot-starter-actuator").get())
    testImplementation(libs.findLibrary("spring-boot-starter-test").get())

    // decision: D00-4 — every service owns one PostgreSQL database migrated by Flyway at startup
    implementation(libs.findLibrary("spring-boot-starter-jdbc").get())
    implementation(libs.findLibrary("spring-boot-starter-flyway").get())
    implementation(libs.findLibrary("flyway-database-postgresql").get())
    runtimeOnly(libs.findLibrary("postgresql").get())

    // decision: D00-6, D00-7 — Micrometer is the only metrics exporter (OTLP). The module, not the starter:
    // Boot 4.1 needs it for OTLP metrics export, and the starter's tracing bridge would duplicate agent traces.
    implementation(libs.findLibrary("micrometer-registry-otlp").get())
    implementation(libs.findLibrary("spring-boot-opentelemetry").get())

    // decision: D00-6 — OTel Java agent (traces), copied next to the boot jar for the service image
    otelAgent(libs.findLibrary("opentelemetry-javaagent").get())
}

// The service image copies exactly one jar under a fixed name, so stale or plain jars in build/libs can never be
// picked up by services/Dockerfile. The plain (non-executable) jar is not built.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "app.jar"
}

val copyOtelAgent = tasks.register<Copy>("copyOtelAgent") {
    description = "Copies the pinned OpenTelemetry Java agent to build/otel/ for the service image."
    from(otelAgent)
    into(layout.buildDirectory.dir("otel"))
    rename { "opentelemetry-javaagent.jar" }
}

tasks.named("assemble") {
    dependsOn(copyOtelAgent)
}

tasks.withType<Test>().configureEach {
    // decision: D00-6 — no telemetry backend in tests: the agent is never attached and metrics export is off.
    systemProperty("management.otlp.metrics.export.enabled", "false")
}
