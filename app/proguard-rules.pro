# Project specific ProGuard rules

# Keep all DTO / models used for Gson serialization & MMKV storage
-keep class com.v2ray.ang.dto.** { *; }
-keep class com.v2ray.ang.enums.** { *; }

# Keep AppConfig and other constant key structures
-keep class com.v2ray.ang.AppConfig { *; }

# Gson specific rules
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }

# Native and JNI rules
-keepclasseswithmembernames class * {
    native <methods>;
}

# MMKV rules
-keep class com.tencent.mmkv.** { *; }
-keepclassmembers class com.tencent.mmkv.** { *; }

# Libsu rules (since we might use root shell)
-keep class com.topjohnwu.libsu.** { *; }

# Kotlin coroutines and reflection
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Support and AndroidX components
-keep class androidx.preference.** { *; }

# Retain Line numbers and source files for easy stack trace deobfuscation
-keepattributes SourceFile,LineNumberTable
