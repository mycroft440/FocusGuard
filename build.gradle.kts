// Top-level build file: plugins declarados com `apply false` para que cada
// módulo aplique a versão que precisa. Versões centralizadas em libs.versions.toml.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
