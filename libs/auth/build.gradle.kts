plugins {
    id("zs.library-conventions")
}

dependencies {
    // decision: D03-4 — a plain servlet filter, not a security framework: none is pinned in D00-1, and static bearer
    // tokens per role need nothing more. The Boot platform supplies the servlet API version.
    implementation(platform(libs.spring.boot.dependencies))
    compileOnly(libs.jakarta.servlet.api)
    testImplementation(libs.jakarta.servlet.api)
}
