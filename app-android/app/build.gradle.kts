import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

// Config de Ollama leída de local.properties (NO se versiona: está en .gitignore).
// Añade allí:
//   ollama.url=https://api.blatcode.com/api/generate
//   ollama.model=qwen2.5-coder:7b
//   ollama.user=ariel
//   ollama.password=TU_CONTRASEÑA
val ollamaProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun ollamaProp(key: String, def: String = ""): String =
    (ollamaProps.getProperty(key) ?: def)

android {
    namespace = "com.example.erikpy"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.erikpy"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Credenciales del VPS inyectadas en BuildConfig (desde local.properties).
        buildConfigField("String", "OLLAMA_URL", "\"${ollamaProp("ollama.url")}\"")
        buildConfigField("String", "OLLAMA_MODEL", "\"${ollamaProp("ollama.model", "qwen2.5-coder:7b")}\"")
        buildConfigField("String", "OLLAMA_USER", "\"${ollamaProp("ollama.user")}\"")
        buildConfigField("String", "OLLAMA_PASSWORD", "\"${ollamaProp("ollama.password")}\"")
        // Voz clonada (servidor TTS en el VPS). Reutiliza la auth de Ollama.
        buildConfigField("String", "VOZ_URL", "\"${ollamaProp("voz.url", "https://api.blatcode.com/tts")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Traducción offline + OCR de documentos + identificación de idioma (ML Kit).
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}