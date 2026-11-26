import java.util.Properties

plugins {
    id("com.android.application")
}

// The release key lives outside this public repository, in ~/.android/cameo-release.properties
// (storeFile, storePassword, keyAlias, keyPassword). Without it, release builds come out unsigned.
val releaseKeyProps = File(System.getProperty("user.home"), ".android/cameo-release.properties")
val releaseKey = Properties().apply { if (releaseKeyProps.exists()) releaseKeyProps.inputStream().use { load(it) } }

android {
    namespace = "io.github.m0n0t0ne0ne.floatbar"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.m0n0t0ne0ne.floatbar"
        minSdk = 31
        targetSdk = 36
        versionCode = 19
        versionName = "0.8.1"
    }
    signingConfigs {
        if (releaseKeyProps.exists()) {
            create("release") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
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
