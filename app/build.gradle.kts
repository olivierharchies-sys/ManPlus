plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.olivier.manplus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.olivier.manplus"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.2"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("manplus.keystore")
            storePassword = "manplus"
            keyAlias = "manplus"
            keyPassword = "manplus"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug { signingConfig = signingConfigs.getByName("fixed") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Extraction texte PDF
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Gemini Nano (on-device, ML Kit GenAI Prompt API)
    implementation("com.google.mlkit:genai-prompt:1.0.0-alpha1")
    // Scan QR / code-barres (Google Code Scanner, sans permission caméra)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
