# Keep kotlinx.serialization generated code
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Media3
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Coil
-dontwarn coil.**

# Coroutines
-dontwarn kotlinx.coroutines.**

# Gson-free models (serializable)
-keepclassmembers class com.spotify.music.data.model.** { <fields>; }
-keepclassmembers enum com.spotify.music.data.model.** { *; }