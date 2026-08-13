# Keep data models used by Gson
-keepattributes Signature
-keepattributes *Annotation*

# Room
-keep class com.vibereading.app.data.local.entity.** { *; }

# OkHttp SSE
-dontwarn okhttp3.**
-dontwarn okio.**
