import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.aboutlibraries.android)
}

// ---- Version: versionCode = commit count, so PC builds and CI releases share one rising counter.
// CI checks out full history (fetch-depth: 0). See docs/DECISIONS.md.
val gitCommitCount: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().toIntOrNull() ?: 1

val gitDescribe: String = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

// Release workflow passes -PversionName=<tag without v>. Local builds: "0.1.0-3-gabc1234" style.
val appVersionName: String = providers.gradleProperty("versionName").orNull
    ?: when {
        gitDescribe.startsWith("v") -> gitDescribe.removePrefix("v")
        gitDescribe.isNotEmpty() -> "0.0.0-dev+$gitDescribe"
        else -> "0.0.0-dev"
    }

// ---- Signing. One key for every build that can reach the phone (debug from this PC and
// releases from CI), so updates always install over existing data. The key never enters
// the repo: locally it's read from the git-ignored keys/ folder, in CI from env secrets.
val localSigning = Properties().apply {
    val f = rootProject.file("keys/forkcast-release-credentials.txt")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    localSigning.getProperty(propKey)?.takeIf { it.isNotBlank() } ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile: File? = signingValue("storeFile", "FORKCAST_KEYSTORE_FILE")?.let { File(it) }?.takeIf { it.isFile }
val hasReleaseKey = releaseStoreFile != null

// ---- Distribution (roadmap 2.0, task 1.2): `github` is the only channel that ships. `play` is
// prepared for a later Google Play build (no in-app updater, no in-app donation link) and its
// variants exist only with -Pforkcast.play, so every build, test and lint command stays one variant.
val playEnabled = providers.gradleProperty("forkcast.play").isPresent

android {
    // No hardcoded user-facing text (roadmap 2.0, task 1.6): XML via HardcodedText, Kotlin via :lint-checks.
    lint {
        error += "HardcodedText"
        // Every shipped language complete and well-formed, and no strings left behind (REVIEW-2 S8).
        error += listOf("MissingTranslation", "ExtraTranslation", "MissingQuantity", "ImpliedQuantity", "StringFormatMatches", "StringFormatInvalid", "StringFormatCount", "UnusedResources")
    }

    namespace = "app.forkcast"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.forkcast"
        minSdk = 29
        targetSdk = 37
        versionCode = gitCommitCount
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("String", "DISTRIBUTION", "\"github\"")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("storePassword", "FORKCAST_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "FORKCAST_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "FORKCAST_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // en-XA (long, accented) and ar-XB (right-to-left) to check layouts (roadmap 2.0, task 1.6).
            isPseudoLocalesEnabled = true
            // With the release key present (owner's PC), debug builds share its signature.
            // Without it (CI, other machines), the standard debug key is used.
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ML Kit ships native libs per ABI; the phone is arm64, so release ships only that
            // (debug keeps every ABI for the x86_64 emulator).
            ndk { abiFilters += "arm64-v8a" }
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric's Android 16 runtime reads JDK internals.
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // Only the app's languages: library strings in 80 others made resources.arsc 1 MB and could
    // show a system dialog in a language the app doesn't speak.
    androidResources {
        localeFilters += listOf("en", "pt-rBR", "en-rXA", "ar-rXB")
    }
}

// Forkcast's own license, readable offline in About › Licenses: the repo-root files are the one
// source, copied into the assets at build time (roadmap 2.0 task 4.5).
abstract class CopyAppLicense : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val licenseFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("licenses")
        dir.deleteRecursively()
        dir.mkdirs()
        licenseFiles.forEach { it.copyTo(dir.resolve(it.name)) }
    }
}

val copyAppLicense = tasks.register<CopyAppLicense>("copyAppLicense") {
    licenseFiles.from(rootProject.file("LICENSE"), rootProject.file("LICENSE-ADDITIONAL-PERMISSION.md"))
}

androidComponents {
    beforeVariants { if (it.productFlavors.any { (_, flavor) -> flavor == "play" } && !playEnabled) it.enable = false }
    onVariants { it.sources.assets?.addGeneratedSourceDirectory(copyAppLicense, CopyAppLicense::outputDir) }
}

// The one shipped flavor keeps the flavor-less task names that CLAUDE.md, CI and the memory notes use
// (without these, `./gradlew testDebugUnitTest lintDebug` would quietly skip :app).
mapOf(
    "testDebugUnitTest" to "testGithubDebugUnitTest",
    "lintDebug" to "lintGithubDebug",
    "installDebug" to "installGithubDebug",
    "connectedDebugAndroidTest" to "connectedGithubDebugAndroidTest",
).forEach { (alias, task) -> tasks.register(alias) { dependsOn(task) } }

// Baseline Profile (SPEC §9): generated on the emulator on demand and committed, so release
// builds (CI included) use it without a device.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    dexLayoutOptimization = true
}

dependencies {
    lintChecks(project(":lint-checks"))
    implementation(project(":core:model"))
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.compose)
    // Bundled models: barcode and text recognition work offline, no Play Services download.
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    // Home-screen widgets (Phase 5)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.coil.compose)
    implementation(libs.reorderable)
    // Settings › About › Licenses, generated from the dependencies (roadmap 2.0 task 4.5)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)

    testImplementation(libs.glance.appwidget.testing)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(libs.tink.android)
}

// The third-party licenses screen (roadmap 2.0 task 4.5): generated from the dependencies at build
// time, so it stays accurate by itself. The Manrope font isn't a dependency: config/aboutlibraries.
aboutLibraries {
    collect {
        configPath = rootProject.file("config/aboutlibraries")
    }
}
