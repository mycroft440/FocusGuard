plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("androidx.baselineprofile")
}

android {
    namespace = "com.focusguard"
    compileSdk = 36

    val ciVersionCode = System.getenv("CI_VERSION_CODE")?.toIntOrNull()
    val ciVersionName = System.getenv("CI_VERSION_NAME")?.takeIf { it.isNotBlank() }

    defaultConfig {
        // Permanent Android update identity. Never change this applicationId:
        // every production APK must update the same installed Hard Block app.
        applicationId = "com.focusguard.v2"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 10
        versionName = ciVersionName ?: "2.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
        // English is the universal fallback in unqualified `values/`.
        // Portuguese lives in `values-pt/`; pt-rBR also keeps Portuguese
        // resources supplied by AndroidX/AppCompat libraries when filtering.
        resourceConfigurations += setOf("en", "pt", "pt-rBR")
    }

    // AGP generates the Android 13+ per-app language configuration from the
    // actual resource folders. With no manual override selected, AppCompat
    // follows the phone locale automatically.
    androidResources {
        generateLocaleConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Both locales must stay in exact parity. The default English fallback
        // and Portuguese translation are treated as a release invariant.
        error.add("MissingTranslation")
        error.add("ExtraTranslation")
    }

    val releaseKeystorePath = System.getenv("KEYSTORE_FILE")
    val releaseKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("KEY_ALIAS")
    val releaseKeyPassword = System.getenv("KEY_PASSWORD")
    val releaseSigningAvailable = !releaseKeystorePath.isNullOrBlank() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank() &&
        file(releaseKeystorePath).exists()

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Android 8+ is the minimum supported version. Modern schemes
                // provide stronger integrity while the same permanent key keeps
                // every production build update-compatible with prior releases.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Debug remains an explicitly separate development-only variant and
            // is never published as an update artifact by CI/Release workflows.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // There is deliberately no .ci/debug fallback here. A production
            // Release without the permanent signing key must remain unpublished
            // rather than creating a second Android package or incompatible app.
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
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
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Keep ordinary release builds deterministic and fast. The profile is refreshed
// explicitly by :app:generateBaselineProfile and committed under src/main/generated.
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
}

dependencies {
    implementation(libs.kotlin.stdlib)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.google.material)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.security.crypto)

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

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    implementation(libs.coil.compose)
    implementation(libs.mpandroidchart)

    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
