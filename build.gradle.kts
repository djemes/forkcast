plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

// The project folder lives in OneDrive. Build output goes outside it so OneDrive
// doesn't sync or lock thousands of generated files (see docs/DECISIONS.md).
val externalBuildRoot: String? = providers.environmentVariable("LOCALAPPDATA").orNull
    ?.takeIf { rootDir.absolutePath.contains("OneDrive", ignoreCase = true) }

if (externalBuildRoot != null) {
    allprojects {
        val name = if (path == ":") "root" else path.removePrefix(":").replace(':', '/')
        layout.buildDirectory.set(file("$externalBuildRoot/forkcast-build/$name"))
    }
}

// CI runs `testDebugUnitTest`, which only Android modules have: without this, the pure-Kotlin
// modules' tests (:core:domain, :core:network, :lint-checks) never ran in CI.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.register("testDebugUnitTest") { dependsOn("test") }
    }
}
