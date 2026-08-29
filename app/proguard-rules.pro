# Keep data models used by Gson
-keepattributes Signature
-keepattributes *Annotation*

# Gson model classes (LlmApiService 内的 private data class，R8 混淆后字段名不匹配 JSON key)
-keep class com.vibereading.app.data.remote.** { *; }

# WordExplanation — 选词「解释」功能用 Gson 反序列化 LLM 返回的 JSON
-keep class com.vibereading.app.domain.model.WordExplanation { *; }

# Web 伴读服务 DTO — 伴读 HTTP 接口用 Gson 序列化，字段名即 JSON key（ADR-005）
-keep class com.vibereading.app.web.** { *; }

# Room
-keep class com.vibereading.app.data.local.entity.** { *; }

# OkHttp SSE
-dontwarn okhttp3.**
-dontwarn okio.**
