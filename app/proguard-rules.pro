# Keep data models used by Gson
-keepattributes Signature
-keepattributes *Annotation*

# Gson model classes (LlmApiService 内的 private data class，R8 混淆后字段名不匹配 JSON key)
-keep class com.vibereading.app.data.remote.** { *; }

# WordExplanation — 选词「解释」功能用 Gson 反序列化 LLM 返回的 JSON
-keep class com.vibereading.app.domain.model.WordExplanation { *; }

# Room
-keep class com.vibereading.app.data.local.entity.** { *; }

# OkHttp SSE
-dontwarn okhttp3.**
-dontwarn okio.**
