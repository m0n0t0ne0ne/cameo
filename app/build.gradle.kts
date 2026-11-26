plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.m0n0t0ne0ne.floatbar"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.m0n0t0ne0ne.floatbar"
        minSdk = 31
        targetSdk = 36
        versionCode = 18
        versionName = "0.8.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
