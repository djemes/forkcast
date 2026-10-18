import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
// the repo: locally it's read from %USERPROFILE%\Documents\keys, in CI from env secrets.
val localSigning = Properties().apply {
    val f = File(System.getProperty("user.home"), "Documents/keys/forkcast-release-credentials.txt")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    localSigning.getProperty(propKey)?.takeIf { it.isNotBlank() } ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile: File? = signingValue("storeFile", "FORKCAST_KEYSTORE_FILE")?.let { File(it) }?.takeIf { it.isFile }
val hasReleaseKey = releaseStoreFile != null

android {
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
            // With the release key present (owner's PC), debug builds share its signature.
            // Without it (CI, other machines), the standard debug key is used.
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))

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

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
