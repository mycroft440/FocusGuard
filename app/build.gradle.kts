import com.android.build.api.variant.BuildConfigField
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("androidx.baselineprofile")
}

val admobTestAppId = "ca-app-pub-3940256099942544~3347511713"
val admobTestInterstitialId = "ca-app-pub-3940256099942544/1033173712"
val admobTestRewardedId = "ca-app-pub-3940256099942544/5224354917"
val admobTestBannerId = "ca-app-pub-3940256099942544/9214589741"
val admobTestNativeId = "ca-app-pub-3940256099942544/2247696110"

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
        // Keep exactly the app-supported language resources in packaged variants.
        // pt-rBR stays as a regional override alongside the 20 language options.
        resourceConfigurations += setOf(
            "en", "pt", "pt-rBR", "b+zh+Hans", "hi", "es", "ar", "fr", "bn",
            "id", "ur", "ru", "de", "ja", "b+pcm", "b+arz", "mr", "vi", "te",
            "sw", "ha"
        )
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
        // Every published locale must stay in resource-key parity with the English
        // fallback. Missing and extra translations remain release errors.
        error.add("MissingTranslation")
        error.add("ExtraTranslation")

        // Compose 1.11 introduces stricter configuration-observability checks.
        // Keep these findings visible as warnings during the UI migration instead
        // of failing unrelated security/release work. They are static-analysis
        // severity changes only and add no runtime work to blocking hot paths.
        warning.add("NonObservableLocale")
        warning.add("LocalContextGetResourceValueCall")
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
    val canonicalReleaseSignerSha256 =
        "e8da4209d0012052b052b280fa64becb788bf6929563ce54a0707cb8c3385157"

    val verifyCanonicalReleaseSigningIdentity =
        tasks.register("verifyCanonicalReleaseSigningIdentity") {
            group = "verification"
            description = "Verifies that production release packaging uses the canonical update key."
            // This task intentionally reads a production keystore only when a release is
            // packaged. Keeping it out of the configuration cache avoids serializing the
            // build-script closure (and, more importantly, avoids persisting signing state).
            notCompatibleWithConfigurationCache(
                "Reads the production signing certificate at execution time."
            )
            doLast {
                check(releaseSigningAvailable) {
                    "Production release packaging requires KEYSTORE_FILE, KEYSTORE_PASSWORD, " +
                        "KEY_ALIAS and KEY_PASSWORD for the canonical signing key."
                }

                val keyStore = KeyStore.getInstance(
                    file(requireNotNull(releaseKeystorePath)),
                    requireNotNull(releaseKeystorePassword).toCharArray()
                )
                val certificate = requireNotNull(
                    keyStore.getCertificate(requireNotNull(releaseKeyAlias))
                ) {
                    "Release keystore does not contain the configured KEY_ALIAS."
                }
                val actualSignerSha256 = MessageDigest.getInstance("SHA-256")
                    .digest(certificate.encoded)
                    .joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }

                check(actualSignerSha256.equals(canonicalReleaseSignerSha256, ignoreCase = true)) {
                    "Release signing key does not match the canonical Hard Block update identity. " +
                        "Refusing to package an incompatible production update."
                }
            }
        }

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

    // Compilation, lint and release unit tests remain usable without production
    // secrets. Only tasks that actually package the canonical production app are
    // gated, preventing an unsigned or differently signed com.focusguard.v2 from
    // being generated and mistaken for an installable update.
    val protectedReleasePackagingTasks = setOf(
        "assembleRelease",
        "bundleRelease",
        "packageRelease"
    )
    tasks.matching { it.name in protectedReleasePackagingTasks }.configureEach {
        dependsOn(verifyCanonicalReleaseSigningIdentity)
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

androidComponents {
    onVariants(selector().all()) { variant ->
        // Every variant, release included, uses Google's official AdMob test IDs.
        // Production IDs are intentionally absent from the build so no APK/AAB
        // can serve (or be credited for) real ads until they are reintroduced.
        val appId = admobTestAppId
        val interstitialId = admobTestInterstitialId
        val rewardedId = admobTestRewardedId
        val bannerId = admobTestBannerId

        val buildConfigFields = requireNotNull(variant.buildConfigFields) {
            "BuildConfig fields must be enabled for ${variant.name}"
        }

        buildConfigFields.put(
            "ADMOB_APP_ID",
            BuildConfigField("String", "\"$appId\"", "AdMob application ID")
        )
        buildConfigFields.put(
            "ADMOB_INTERSTITIAL_AD_UNIT_ID",
            BuildConfigField("String", "\"$interstitialId\"", "AdMob interstitial unit ID")
        )
        buildConfigFields.put(
            "ADMOB_REWARDED_AD_UNIT_ID",
            BuildConfigField("String", "\"$rewardedId\"", "AdMob rewarded unit ID")
        )
        buildConfigFields.put(
            "ADMOB_BANNER_AD_UNIT_ID",
            BuildConfigField("String", "\"$bannerId\"", "AdMob banner unit ID")
        )
        buildConfigFields.put(
            "ADMOB_NATIVE_AD_UNIT_ID",
            BuildConfigField("String", "\"$admobTestNativeId\"", "AdMob native test unit ID")
        )
        variant.manifestPlaceholders.put("admobAppId", appId)
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
    implementation(libs.google.mobile.ads)
    implementation(libs.play.billing)
    implementation(libs.google.user.messaging.platform)
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
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

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
