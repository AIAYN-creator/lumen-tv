plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.lumen.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.lumen.tv"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // Release is what runs on the TV: R8-optimised Compose is far smoother than a debug build.
        // Signed with the debug key so it installs over earlier builds; this app is never published.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    // Radio and Ambientes playback (with a media session, so the screensaver shows what's on).
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    // QR code for opening the phone remote.
    implementation("com.google.zxing:core:3.5.4")
    // Applies the baseline profiles that ship with Compose, so startup and scrolling are pre-compiled.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
