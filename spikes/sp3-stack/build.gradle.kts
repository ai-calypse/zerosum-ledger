// SP3 spike (S00-T07) — throwaway; removed from the build once its SHA is recorded.
// Uses only catalog versions so it proves the pinned combination (D00-1).
plugins {
    id("zs.service-conventions")
}

val otelAgent = configurations.create("otelAgent")

dependencies {
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.micrometer.registry.otlp)
    implementation(libs.spring.boot.opentelemetry)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    otelAgent(libs.opentelemetry.javaagent)
}

tasks.register<Copy>("copyOtelAgent") {
    description = "Copies the pinned OpenTelemetry Java agent to build/agent/."
    from(otelAgent)
    into(layout.buildDirectory.dir("agent"))
    rename { "opentelemetry-javaagent.jar" }
}

tasks.withType<Test>().configureEach {
    systemProperty("zs.rootDir", rootDir.absolutePath)
}
