# ── Hilt ──────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel

# ── LiteRT-LM (Google AI Edge) ────────────────────────────────────────
# The SDK uses JNI + reflection on its native Engine/Conversation classes.
# Stripping or renaming any of these breaks model loading at runtime.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.mediapipe.**

# ── Kotlin Serialization ──────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.offlinetranslator.app.**$$serializer { *; }
-keepclassmembers class com.offlinetranslator.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.offlinetranslator.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Compose ───────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── Coroutines ────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── Room (paranoid, KSP-generated DAOs occasionally need this) ────────
-keep class androidx.room.RoomDatabase { *; }
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# ── App data classes used in StateFlow / Room ─────────────────────────
-keep class com.offlinetranslator.app.core.data.model.** { *; }
-keep class com.offlinetranslator.app.feature.**.*Ui { *; }
-keep class com.offlinetranslator.app.feature.**.*UiState { *; }
