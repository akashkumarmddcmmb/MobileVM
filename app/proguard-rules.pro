# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ==============================================================================
# Mobile BM - Production R8 / ProGuard Obfuscation & Security Rules
# ==============================================================================

# Preserve line numbers for stack trace mapping while removing source file names
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Optimization passes
-repackageclasses ''
-allowaccessmodification

# Strip debug and verbose logging statements in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Preserve Room database entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Preserve JNI methods and NativeVMBinding from renaming or stripping
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.example.vm.nativebridge.** { *; }

# Preserve Moshi JSON serialization models (for Licensing API and VM config)
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
}
-keep class com.example.vm.licensing.** { *; }
-keep class com.example.vm.security.** { *; }

# Protect internal cryptographic and integrity checks from inlining or stripping
-keepclassmembers class com.example.vm.security.ProjectProtectionManager {
    public *;
}
-keepclassmembers class com.example.vm.licensing.LicenseManager {
    public *;
}
-keepclassmembers class com.example.vm.licensing.TimeTamperDetector {
    public *;
}

