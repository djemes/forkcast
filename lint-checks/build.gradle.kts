import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Forkcast's own lint checks (roadmap 2.0, task 1.6), run by `lintDebug` in :app and :core:data.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Lint loads checks with its own bundled Kotlin; keep the jar readable by it.
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)

    testImplementation(libs.lint.api)
    testImplementation(libs.lint.checks)
    testImplementation(libs.lint.tests)
    testImplementation(libs.junit4)
}
