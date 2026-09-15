// decision: D00-10 — docs/step_00_foundations.md#decisions-and-outputs
// Spring Boot services. Only services apply the Boot plugin; the BOM is imported as a Gradle platform.
plugins {
    id("zs.java-conventions")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(platform(libs.findLibrary("spring-boot-dependencies").get()))
    implementation(libs.findLibrary("spring-boot-starter-webmvc").get())
    implementation(libs.findLibrary("spring-boot-starter-actuator").get())
    testImplementation(libs.findLibrary("spring-boot-starter-test").get())
}

dependencies {
    // decision: D00-4 — every service owns one PostgreSQL database migrated by Flyway at startup
    implementation(libs.findLibrary("spring-boot-starter-jdbc").get())
    implementation(libs.findLibrary("spring-boot-starter-flyway").get())
    implementation(libs.findLibrary("flyway-database-postgresql").get())
    runtimeOnly(libs.findLibrary("postgresql").get())
}
