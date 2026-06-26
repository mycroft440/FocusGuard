# ============================================================================
# FocusGuard - ProGuard / R8 rules
# ============================================================================
# Regras para preservar código que o R8 não deve remover/renomear quando
# minifyEnabled true em release. Originalmente criado no PR #8 (CI improvements)
# e ampliado no PR #10 (Phase 2 — R8 ativado em release).
# ============================================================================

# ----------------------------------------------------------------------------
# General Android / Kotlin
# ----------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Exceptions

# Kotlin metadata — necessário para reflection em bibliotecas Kotlin
-keep class kotlin.Metadata { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# ----------------------------------------------------------------------------
# Room
# ----------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Keep entities (Room referencia via reflection)
-keep class com.focusguard.database.** { *; }

# Keep DAO interfaces
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Entity class * { *; }

# ----------------------------------------------------------------------------
# Jetpack Compose
# ----------------------------------------------------------------------------
# Compose gera código que o R8 precisa preservar
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# ----------------------------------------------------------------------------
# Java libraries usadas transitivamente
# ----------------------------------------------------------------------------
# Trove (usada por Kotlin compiler)
-dontwarn gnu.trove.**

# kotlinx-coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ----------------------------------------------------------------------------
# CameraX
# ----------------------------------------------------------------------------
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ----------------------------------------------------------------------------
# Coil (image loading)
# ----------------------------------------------------------------------------
-keepnames class coil.Coil { *; }
-dontwarn coil.**

# ----------------------------------------------------------------------------
# MPAndroidChart (via JitPack)
# ----------------------------------------------------------------------------
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# ----------------------------------------------------------------------------
# Biometric
# ----------------------------------------------------------------------------
-keep class androidx.biometric.** { *; }

# ----------------------------------------------------------------------------
# EncryptedSharedPreferences / Security-crypto
# ----------------------------------------------------------------------------
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ----------------------------------------------------------------------------
# FocusGuard-specific
# ----------------------------------------------------------------------------
# Classes referenciadas via reflection do AndroidManifest
-keep class com.focusguard.FocusGuardApplication { *; }
-keep class com.focusguard.MainActivity { *; }
-keep class com.focusguard.service.** { *; }
-keep class com.focusguard.receiver.** { *; }
-keep class com.focusguard.admin.** { *; }
-keep class com.focusguard.ui.**Activity { *; }

# Loggers — evitar remover para manter stack traces
-keep class com.focusguard.utils.FocusGuardLogger { *; }

# ----------------------------------------------------------------------------
# Hilt ( Dagger ) —.generated classes usam reflection
# ----------------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }
-keepclassmembers class * { @javax.inject.Inject *; }
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking interface kotlin.coroutines.Continuation

# ----------------------------------------------------------------------------
# Debug logs — remover em release builds para reduzir APK
# ----------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
# Mantém warnings (w) e errors (e) — úteis para diagnóstico de produção
