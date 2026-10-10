plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Backend base URLs. Override with -PabhaynayanDebugApiUrl=... / -PabhaynayanReleaseApiUrl=https://...
// Debug defaults to the Android emulator's alias for the development machine. Release has no default:
// the deployed HTTPS URL must be supplied explicitly or entered in Settings.
val debugApiUrl = (findProperty("abhaynayanDebugApiUrl") as String?) ?: "http://10.0.2.2:8000"
val releaseApiUrl = (findProperty("abhaynayanReleaseApiUrl") as String?) ?: ""
require(releaseApiUrl.isEmpty() || releaseApiUrl.startsWith("https://")) { "Release API URL must use HTTPS." }

android {
    namespace = "in.abhaynayan.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "in.abhaynayan.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-demo"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$debugApiUrl\"")
        }
        release {
            buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$releaseApiUrl\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("androidx.work:work-runtime-ktx:2.10.1")

    implementation("androidx.core:core-ktx:1.17.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Android's bundled org.json is a stub in local JVM tests; use the reference implementation there.
    testImplementation("org.json:json:20240303")
}
