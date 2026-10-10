import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: android/keystore.properties (not versioned) or environment variables (CI).
// Without a keystore the build is signed with the debug key: fine for testing, not for real installs.
val releaseSigning: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    fun value(key: String, env: String): String? = props.getProperty(key) ?: System.getenv(env)
    val store = value("storeFile", "KAIRO_KEYSTORE_FILE") ?: return@run null
    val storePassword = value("storePassword", "KAIRO_KEYSTORE_PASSWORD") ?: return@run null
    mapOf(
        "storeFile" to store,
        "storePassword" to storePassword,
        "keyAlias" to (value("keyAlias", "KAIRO_KEY_ALIAS") ?: "kairo"),
        "keyPassword" to (value("keyPassword", "KAIRO_KEY_PASSWORD") ?: storePassword),
    )
}

// Your server (the watch pairs against it and the phone prefills it on the sign-in screen).
// Change it with KAIRO_SERVER_URL=https://kairo.example.com or kairo.serverUrl in gradle.properties.
val kairoServerUrl: String = System.getenv("KAIRO_SERVER_URL")
    ?: (findProperty("kairo.serverUrl") as String?)
    ?: "https://kairo.dvxlabs.online"

android {
    namespace = "app.kairo.wear"
    compileSdk = 37

    defaultConfig {
        // Same applicationId as the phone: Wear OS treats them as the same app
        applicationId = "app.kairo.android"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        buildConfigField("String", "DEFAULT_SERVER", "\"$kairoServerUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // English (default) and Spanish
    androidResources {
        localeFilters += listOf("en", "es")
    }

    signingConfigs {
        releaseSigning?.let { s ->
            create("release") {
                storeFile = rootProject.file(s.getValue("storeFile"))
                storePassword = s.getValue("storePassword")
                keyAlias = s.getValue("keyAlias")
                keyPassword = s.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation("androidx.wear:wear-input:1.2.0")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.material.icons)
}
