plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:model"))

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
