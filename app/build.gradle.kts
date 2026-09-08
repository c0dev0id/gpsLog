plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is driven entirely by environment variables so that the
// keystore never has to live in the repository. CI exports them from secrets;
// locally they are simply absent and the release build stays unsigned.
val signingKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "de.codevoid.gpslog"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.codevoid.gpslog"
        minSdk = 26
        targetSdk = 36
        // The release workflow derives both from the release tag; the
        // defaults are what a local or debug build gets.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.0.1"
    }

    signingConfigs {
        if (!signingKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(signingKeystorePath)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // The Compose BOM only manages the androidx.compose.* groups, so
    // activity-compose needs an explicit version.
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
