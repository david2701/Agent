import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
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
    ?: ""

android {
    namespace = "app.kairo.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.kairo.android"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"
        // Server URL suggested on the sign-in screen (it can be changed in the app)
        buildConfigField("String", "DEFAULT_SERVER", "\"$kairoServerUrl\"")
        // Host of the server: the app handles its /join?code=... invite links (see AndroidManifest.xml)
        manifestPlaceholders["kairoHost"] = runCatching { URI(kairoServerUrl).host }.getOrNull() ?: "localhost"
        // Foldables and current phones are arm64; x86_64 only for the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // English (default) and Spanish; the Android 13+ per-app language picker is fed by res/xml/locales_config.xml
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
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("kairo.screenshots", rootProject.file("../docs/screenshots/android").absolutePath)
                // Language of the screenshots: en (default) or es (KAIRO_SCREENSHOT_LOCALE=es)
                it.systemProperty("kairo.screenshot.locale", System.getenv("KAIRO_SCREENSHOT_LOCALE") ?: "en")
                // Screenshots show times as the sample family sees them (Medellin), not in UTC
                it.systemProperty("user.timezone", System.getenv("KAIRO_TEST_TZ") ?: "America/Bogota")
                System.getenv("ROBOLECTRIC_REPO")?.let { repo -> it.systemProperty("robolectric.dependency.repo.url", repo) }
                it.maxHeapSize = "3g"
                it.jvmArgs(
                    "--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens", "java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens", "java.base/java.io=ALL-UNNAMED",
                )
            }
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
        )
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.window)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.layout)
    implementation(libs.compose.material3.adaptive.navigation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)

    implementation(libs.vosk.android)
    implementation("${libs.jna.get().module}:${libs.versions.jna.get()}@aar")

    // Own push without Firebase (UnifiedPush; decrypts Web Push RFC 8291 with Tink)
    implementation(libs.unifiedpush.connector)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
