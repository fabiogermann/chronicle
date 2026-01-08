# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotations for reflection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# Keep Kotlin metadata for reflection (required for Moshi, Room, etc.)
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }

# ================================
# Retrofit & OkHttp
# ================================
# Retrofit does reflection on generic parameters and annotations
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Keep Retrofit interfaces
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Keep all Retrofit service interfaces
-keep interface local.oss.chronicle.data.sources.plex.PlexLoginService { *; }
-keep interface local.oss.chronicle.data.sources.plex.PlexMediaService { *; }

# Keep HTTP method annotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# OkHttp platform used only on JVM and Android
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ================================
# Moshi (using reflection adapters)
# ================================
# Keep all model classes with @JsonClass annotation
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Keep all Plex model classes (critical for API serialization)
-keep class local.oss.chronicle.data.sources.plex.model.** { *; }
-keepclassmembers class local.oss.chronicle.data.sources.plex.model.** { *; }

# Moshi reflection adapters
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}

# Enum support for Moshi
-keepclassmembers enum * { *; }

# ================================
# Data Models (Parcelable, Room, etc.)
# ================================
# Keep all Room entities
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Dao class * { *; }

# Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep all data classes in data.model package
-keep class local.oss.chronicle.data.model.** { *; }

# ================================
# Dagger 2
# ================================
-dontwarn com.google.errorprone.annotations.**

# ================================
# Coroutines
# ================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ================================
# Fresco
# ================================
-keep,allowobfuscation @interface com.facebook.common.internal.DoNotStrip
-keep,allowobfuscation @interface com.facebook.soloader.DoNotOptimize
-keep @com.facebook.common.internal.DoNotStrip class *
-keepclassmembers class * {
    @com.facebook.common.internal.DoNotStrip *;
}
-keep @com.facebook.soloader.DoNotOptimize class *
-keepclassmembers class * {
    @com.facebook.soloader.DoNotOptimize *;
}

# ================================
# ExoPlayer / Media3
# ================================
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ================================
# WorkManager
# ================================
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(...);
}
-keep class local.oss.chronicle.data.sources.plex.PlexSyncScrobbleWorker { *; }

# ================================
# General Android
# ================================
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ================================
# Remove Logging (optional optimization)
# ================================
# Uncomment to remove all logging in release builds
# -assumenosideeffects class android.util.Log {
#     public static *** d(...);
#     public static *** v(...);
#     public static *** i(...);
# }
