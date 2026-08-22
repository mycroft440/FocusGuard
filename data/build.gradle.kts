plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.focusguard.persistence"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        ksp {
            arg("room.schemaLocation", "${rootProject.projectDir}/app/schemas")
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
}

dependencies {
    api(project(":domain"))
    implementation(libs.kotlin.stdlib)
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
