package com.vibereading.app.data.remote

import com.google.gson.Gson
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.ui.reader.components.splitParagraphs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// ── SSE event types (mirrors Python translator.py) ──

sealed class TranslationEvent {
    data class Status(val status: String, val charCount: Int = 0) : TranslationEvent()
    data class Chunk(val text: String) : TranslationEvent()
    data class Progress(val chars: Int) : TranslationEvent()
    data class Done(val text: String) : TranslationEvent()
    data class Error(val reason: String) : TranslationEvent()
}

// ── LLM API request/response models ──

private data class ChatStreamDelta(val content: String? = null)
private data class ChatStreamChoice(val delta: ChatStreamDelta? = null)
private data class ChatStreamResponse(val choices: List<ChatStreamChoice>? = null)
private data class ChatCompletionChoice(val message: ChatCompletionMessage? = null)
private data class ChatCompletionMessage(val role: String, val content: String)
private data class ChatCompletionResponse(val choices: List<ChatCompletionChoice>? = null)

class LlmApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        const val SYSTEM_PROMPT = """你是一位资深中英文学翻译。
将用户给定的整章中文翻译为英文, 保留原文语气、风格、文学性。

源文中的每个段落以 [1], [2], [3] 等标记开头。你必须在英文译文中保留完全相同的段落标记, 使得每个标记段落与原文一一对应。

输出格式:
[1] 第一段的英文译文
[2] 第二段的英文译文
...

严禁输出任何解释、标题、注释或额外内容。"""

        /**
         * Build the chat completions URL from a user-provided base URL.
         * Tolerates bases with or without a trailing "/v1" (e.g.
         * "http://host:port/v1" or "https://api.deepseek.com").
         */
        fun chatCompletionsUrl(apiBase: String): String {
            val base = apiBase.trimEnd('/')
            return if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
        }
    }

    /**
     * Build the user prompt for translation, mirroring Python _build_user_prompt.
     */
    fun buildUserPrompt(
        chapterTitle: String,
        chapterContent: String,
        prevChapterEnglish: String? = null
    ): String {
        val paragraphs = splitParagraphs(chapterContent)
            .mapIndexed { i, p -> "[${i + 1}] ${p.trim()}" }
            .joinToString("\n")

        val sb = StringBuilder()
        if (prevChapterEnglish != null) {
            sb.appendLine("上一章英译 (供术语 / 风格衔接参考):")
            sb.appendLine(prevChapterEnglish)
            sb.appendLine("---")
        }
        sb.appendLine("Chapter: $chapterTitle")
        sb.appendLine("请将以下整章中文翻译为英文, 保留每个段落的 [N] 标记:")
        sb.append(paragraphs)
        return sb.toString()
    }

    /**
     * Truncate middle of text, mirroring Python _truncate_middle.
     */
    fun truncateMiddle(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val half = maxLen / 2
        return text.take(half) + "\n[... middle truncated ...]\n" + text.takeLast(half)
    }

    /**
     * Streaming translation via SSE, returns Flow<TranslationEvent>.
     * Mirrors Python translate_chapter_stream.
     * Uses flow {} + flowOn(Dispatchers.IO) for proper threading.
     */
    fun translateStream(
        settings: LlmSettings,
        chapterTitle: String,
        chapterContent: String,
        prevChapterEnglish: String? = null
    ): Flow<TranslationEvent> = flow {

        val userPrompt = buildUserPrompt(chapterTitle, chapterContent, prevChapterEnglish)

        val requestMap = mutableMapOf<String, Any>(
            "model" to settings.model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                mapOf("role" to "user", "content" to userPrompt)
            ),
            "temperature" to 0.3,
            "max_tokens" to 16000,
            "stream" to true
        )

        if (settings.enableThinking) {
            requestMap["extra_body"] = mapOf("thinking" to mapOf("type" to "enabled"))
        }

        val requestBody = gson.toJson(requestMap).toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(chatCompletionsUrl(settings.apiBase))
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        val call = client.newCall(request)

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                emit(TranslationEvent.Error("API 错误 ${response.code}: ${errorBody.take(200)}"))
                return@flow
            }

            val body = response.body ?: run {
                emit(TranslationEvent.Error("空响应"))
                return@flow
            }

            val fullText = StringBuilder()
            var chunkCount = 0

            try {
                val reader = BufferedReader(InputStreamReader(body.byteStream()))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (!currentLine.startsWith("data: ")) continue
                    val data = currentLine.removePrefix("data: ").trim()
                    if (data == "[DONE]") {
                        if (fullText.isEmpty()) {
                            emit(TranslationEvent.Error("翻译结果为空"))
                        } else {
                            emit(TranslationEvent.Done(fullText.toString()))
                        }
                        break
                    }

                    try {
                        val streamResp = gson.fromJson(data, ChatStreamResponse::class.java)
                        val content = streamResp.choices?.firstOrNull()?.delta?.content
                        if (content != null) {
                            fullText.append(content)
                            emit(TranslationEvent.Chunk(content))
                            chunkCount++
                            if (chunkCount % 20 == 0) {
                                emit(TranslationEvent.Progress(fullText.length))
                            }
                        }
                    } catch (_: Exception) {
                        // Skip malformed SSE lines
                    }
                }
                if (fullText.isEmpty()) {
                    emit(TranslationEvent.Error("翻译结果为空"))
                }
            } finally {
                response.close()
            }
        } catch (e: java.io.IOException) {
            emit(TranslationEvent.Error("网络错误: ${e.message}"))
        } catch (e: Exception) {
            emit(TranslationEvent.Error("翻译异常: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Non-streaming test connection call.
     */
    suspend fun testConnection(settings: LlmSettings): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestMap = mapOf(
                "model" to settings.model,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to "Say hi in one word.")
                ),
                "temperature" to 0.1,
                "max_tokens" to 10,
                "stream" to false
            )

            val requestBody = gson.toJson(requestMap).toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(chatCompletionsUrl(settings.apiBase))
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val call = client.newCall(request)

            val response = call.execute()
            if (!response.isSuccessful) {
                val body = response.body?.string()?.take(200) ?: "Unknown"
                return@withContext Result.failure<String>(Exception("API 错误 ${response.code}: $body"))
            }
            val body = response.body?.string() ?: return@withContext Result.failure<String>(Exception("空响应"))
            try {
                val resp = gson.fromJson(body, ChatCompletionResponse::class.java)
                val content = resp.choices?.firstOrNull()?.message?.content ?: "连接成功"
                return@withContext Result.success(content)
            } catch (e: Exception) {
                return@withContext Result.failure<String>(Exception("解析响应失败: ${e.message}"))
            }
        } catch (e: java.io.IOException) {
            return@withContext Result.failure<String>(Exception("网络错误: ${e.message}"))
        }
    }
}
