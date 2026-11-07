import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.gms.google.services)
}

// K-Guard Live's AI model (see AiConfig / GeminiKeyRotationManager). Set these in local.properties, which is
// not committed: kguard.ai.url, kguard.ai.model, kguard.ai.key, kguard.gemini.keys (comma-separated).
private val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
private fun localProp(name: String): String =
    (localProps.getProperty(name) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Release signing lives outside git: a `keystore.properties` at the repo root (gitignored) or
 * `~/keystores/k-guard-release.properties` on the build machine. Without one, the release
 * build simply stays unsigned — never put the password in this file.
 */
private val keystoreProps = Properties().apply {
    listOfNotNull(rootProject.file("keystore.properties"),
        file("${System.getProperty("user.home")}/keystores/k-guard-release.properties"))
        .firstOrNull { it.isFile }
        ?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.tele.k_guard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kguard.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "KGUARD_AI_URL", "\"${localProp("kguard.ai.url")}\"")
        buildConfigField("String", "KGUARD_AI_MODEL", "\"${localProp("kguard.ai.model")}\"")
        buildConfigField("String", "KGUARD_AI_KEY", "\"${localProp("kguard.ai.key")}\"")
        buildConfigField("String", "KGUARD_GEMINI_KEYS", "\"${localProp("kguard.gemini.keys")}\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // ONNX Runtime maps the model from a file; keep it uncompressed in the APK.
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.googleid)

    // Networking & Serialization
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Image loading
    implementation(libs.coil.compose)
    // On-device screen model for K-Guard Live (see live/NsfwScreenModel.kt).
    implementation(libs.onnxruntime.android)
    implementation(libs.coil.network.okhttp)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}