import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firma de release: android/keystore.properties (no versionado) o variables de entorno (CI).
// Sin keystore se firma con la clave de depuración: sirve para probar, no para instalar en la familia.
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

// Tu servidor (el reloj se vincula contra él y el teléfono lo trae escrito al iniciar sesión).
// Cámbialo con KAIRO_SERVER_URL=https://kairo.tudominio.com o kairo.serverUrl en gradle.properties.
val kairoServerUrl: String = System.getenv("KAIRO_SERVER_URL")
    ?: (findProperty("kairo.serverUrl") as String?)
    ?: "https://kairo.dvxlabs.online"

android {
    namespace = "app.kairo.wear"
    compileSdk = 37

    defaultConfig {
        // Mismo applicationId que el teléfono: Wear OS los reconoce como la misma app
        applicationId = "app.kairo.android"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "DEFAULT_SERVER", "\"$kairoServerUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
