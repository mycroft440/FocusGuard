// ============================================================================
// FocusGuard app module — Kotlin DSL build script
// ============================================================================
// Migração de Groovy → Kotlin DSL + version catalog + KSP + Compose Compiler
// Gradle Plugin (Kotlin 2.0+) + R8 em release.
// ============================================================================

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.focusguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.focusguard.v2"
        minSdk = 21
        targetSdk = 35
        versionCode = 9
        versionName = "2.4.0-toolchain"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room schema export directory — exportSchema=true em AppDatabase
        // exige que o KSP saiba onde escrever os JSONs de schema.
        // Os JSONs são commitados em app/schemas/ para auditoria.
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // MissingTranslation: warn (não abort) quando strings do default não têm
        // tradução em algum locale. Temos ~340 strings faltantes em 10 locales
        // — este PR adiciona as 26 strings críticas (botões/actions) traduzidas.
        // O warning coleta o gap restante sem quebrar o build.
        warning.add("MissingTranslation")
        // ExtraTranslation: erro quando um locale tem string que não existe no
        // default — provavelmente é erro de digitação de key.
        error.add("ExtraTranslation")
    }

    signingConfigs {
        create("release") {
            // Assinatura de release via env vars (CI) ou fallback para debug
            // (desenvolvimento local). No CI, configurar secrets:
            //   KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAliasEnv = System.getenv("KEY_ALIAS")
            val keyPasswordEnv = System.getenv("KEY_PASSWORD")

            if (!keystoreFile.isNullOrEmpty()) {
                val releaseKeystore = file(keystoreFile)
                if (releaseKeystore.exists()) {
                    storeFile = releaseKeystore
                    storePassword = keystorePassword
                    keyAlias = keyAliasEnv
                    keyPassword = keyPasswordEnv
                    enableV1Signing = true
                    enableV2Signing = true
                }
            }
        }
    }

    buildTypes {
        debug {
            // Usa signing config de debug default do AGP
        }
        release {
            // R8/ProGuard ativado — gera APK menor e ofuscado (importante para
            // app de segurança). Regras em proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (!keystoreFile.isNullOrEmpty() && file(keystoreFile).exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Fallback silencioso para debug-signed quando KEYSTORE_FILE
                // não está configurado (ex.: PRs de contribuidores externos).
                // O CI do PR #8 loga explicitamente quando isto acontece.
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // K2 compiler (Kotlin 2.0+): melhor performance e relatórios de
            // erro mais precisos.
            freeCompilerArgs.addAll(
                "-Xjsr305=strict"
            )
        }
    }

    // Permite testes unitários com classes Android (Context, SharedPreferences, etc)
    // via Robolectric
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    buildFeatures {
        compose = true
    }

    // Compose Compiler Gradle Plugin (substitui composeOptions.kotlinCompilerExtensionVersion
    // do Kotlin 1.9.x). Declarado via plugin `org.jetbrains.kotlin.plugin.compose`.
    // Não é mais necessário especificar a versão da extensão do compilador.
}

dependencies {
    // ─── Kotlin stdlib ───────────────────────────────────────────────────
    implementation(libs.kotlin.stdlib)

    // ─── AndroidX Core ───────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.biometric)
    implementation(libs.google.material)

    // ─── Lifecycle ───────────────────────────────────────────────────────
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ─── Coroutines ──────────────────────────────────────────────────────
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // ─── Room (KSP em vez de KAPT — 2x mais rápido) ──────────────────────
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ─── Hilt (DI) ──────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // ─── Lifecycle (ViewModel + runtime) ────────────────────────────────
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // ─── Security ────────────────────────────────────────────────────────
    implementation(libs.androidx.security.crypto)

    // ─── Jetpack Compose (BOM + modules) ─────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ─── CameraX (Intruder Selfie) ───────────────────────────────────────
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    // ─── Coil (image loading) ────────────────────────────────────────────
    implementation(libs.coil.compose)

    // ─── 3rd party (JitPack) ─────────────────────────────────────────────
    implementation(libs.mpandroidchart)

    // ─── Testing ─────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
