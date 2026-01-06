# Add project specific ProGuard rules here.

# ==================== Luma Camera Core ====================

# Keep Luma Imaging Engine
-keep class com.luma.camera.imaging.** { *; }
-keep class com.luma.camera.lut.** { *; }
-keep class com.luma.camera.render.** { *; }

# Keep Camera classes
-keep class com.luma.camera.camera.** { *; }

# Keep Mode processors
-keep class com.luma.camera.mode.** { *; }

# Keep LivePhoto classes
-keep class com.luma.camera.livephoto.** { *; }

# Keep Crash reporter
-keep class com.luma.camera.crash.** { *; }

# ==================== Hilt / Dagger ====================

-keepnames @dagger.hilt.android.HiltAndroidApp class *
-keepnames @dagger.hilt.android.AndroidEntryPoint class *
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }

# ==================== Compose ====================

-keep class androidx.compose.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ==================== OpenGL / Native ====================

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep OpenGL shaders
-keep class com.luma.camera.render.shader.** { *; }

# ==================== ML Kit ====================

-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ==================== Kotlin ====================

-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ==================== Serialization ====================

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}

# ==================== Data Classes ====================

# Keep data classes used in serialization
-keep class com.luma.camera.domain.model.** { *; }

# ==================== Debugging ====================

# Keep annotation for debugging
-keepattributes *Annotation*

# Keep line numbers for stack traces
-keepattributes SourceFile,LineNumberTable

# If keeping line numbers, hide the original source file name
-renamesourcefileattribute SourceFile

# ==================== Logging ====================

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Remove Timber debug logging in release
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ==================== Firebase ====================

# Firebase Crashlytics
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ==================== Miscellaneous ====================

# Keep parcelables
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep R files
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Don't warn about missing classes for optional dependencies
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
