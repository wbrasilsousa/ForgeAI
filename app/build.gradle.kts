plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.forgeai"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.forgeai"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.0.8"
        multiDexEnabled = true
        ndk {
            // The native library is only built for 64-bit ARM devices.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("forgeDebug") {
            val debugKs = rootProject.file("keystore/debug.keystore")
            if (debugKs.exists()) {
                storeFile = debugKs
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            // Keystore fixa versionada no repo: garante a MESMA assinatura em
            // todo build (update por cima sem desinstalar). É uma chave de
            // debug — pública por definição, nunca use para release.
            val debugKs = rootProject.file("keystore/debug.keystore")
            if (debugKs.exists()) {
                signingConfig = signingConfigs.getByName("forgeDebug")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Testes de lógica pura (parsers, URLs, shards) rodam na JVM. As
            // chamadas ao android.jar viram no-op em vez de lançar.
            isReturnDefaultValues = true
        }
    }

    packaging {
        jniLibs {
            // Build com FORGE_ALL_VARIANTS (backend CPU dinâmico): o ggml
            // precisa ENUMERAR o diretório (std::filesystem) e dar dlopen nos
            // libggml-cpu-*.so — só funciona com libs extraídas de verdade.
            // Build normal (estático) pode ficar compactado dentro do APK.
            useLegacyPackaging = (project.findProperty("forgeAllVariants") == "true")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Testes de unidade puros (JVM). org.json real: os stubs do android.jar não
    // parseiam nada, e ToolSupport/HfResolver dependem de JSONObject.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
