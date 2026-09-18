// decision: D00-10 — docs/step_00_foundations.md#decisions-and-outputs
// Applied by every Gradle project: toolchain, compiler flags, reproducible archives, test tasks by JUnit tag.
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {
    // Test scope only. The platform contributes version constraints, never jars, so main classpaths
    // (notably libs/money, which must depend on nothing but the JDK) stay untouched.
    testImplementation(platform(libs.findLibrary("spring-boot-dependencies").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

// Test layers share one src/test source set and are selected by @Tag:
//   test            untagged fast layer, no containers (build depends on it)
//   integrationTest @Tag("integration"), Testcontainers
//   e2eTest         @Tag("e2e"), requires the running Compose stack
//   studyTest       @Tag("study"), timeboxed measurement runs (CR-S02-05 to D00-10). Never part of CI: a study window
//                   is minutes long by design, so it is run deliberately and its output is committed as evidence.
//   chaosTest       @Tag("chaos"), failure-mode runs against the RUNNING stack (S08): they kill -9 services, pause the
//                   broker and delete outbox rows on purpose. Never part of e2eTest, which `make demo` runs.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration", "e2e", "study", "chaos")
    }
}

mapOf("integrationTest" to "integration", "e2eTest" to "e2e", "studyTest" to "study", "chaosTest" to "chaos").forEach { (taskName, tag) ->
    tasks.register<Test>(taskName) {
        description = "Runs tests tagged '$tag'."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform {
            includeTags(tag)
        }
        shouldRunAfter(tasks.named("test"))
    }
}

tasks.withType<Test>().configureEach {
    // An empty tag selection is reported, never failed or passed silently. Projects without test
    // sources are reported by Gradle itself as NO-SOURCE.
    failOnNoDiscoveredTests = false
    val taskPath = path
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null && result.testCount == 0L) {
                logger.lifecycle("$taskPath: 0 tests selected by the tag filter")
            }
        }
    })
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
