plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.forkcast.hcseed"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.forkcast.hcseed"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.health.connect)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
}
