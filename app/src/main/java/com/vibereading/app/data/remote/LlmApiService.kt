package com.vibereading.app.data.remote

import com.google.gson.Gson
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.model.WordExplanation
import com.vibereading.app.domain.parser.IllustrationLink
import com.vibereading.app.domain.parser.ReadingContentParser.splitParagraphs
import com.vibereading.app.domain.parser.SourceLanguageDetector
import com.vibereading.app.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

sealed class TranslationEvent {
    data object Started : TranslationEvent()
    data class Thinking(val text: String) : TranslationEvent()
    data class Chunk(val text: String) : TranslationEvent()
    data class Progress(val chars: Int) : TranslationEvent()
    data class Done(val text: String) : TranslationEvent()
    data class Error(val reason: String) : TranslationEvent()
}

private data class ChatStreamDelta(
    val content: String? = null,
    val reasoning_content: String? = null,
    val reasoning: String? = null,
    val thinking: String? = null
)
private data class ChatStreamChoice(
    val delta: ChatStreamDelta? = null,
    val finish_reason: String? = null
)
private data class ChatStreamResponse(val choices: List<ChatStreamChoice>? = null)
private data class ChatCompletionChoice(val message: ChatCompletionMessage? = null)
private data class ChatCompletionMessage(
    val role: String,
    val content: String,
    val reasoning_content: String? = null
)
private data class ChatCompletionResponse(val choices: List<ChatCompletionChoice>? = null)

class LlmApiService : TranslationService {

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
......

严禁输出任何解释、标题、注释或额外内容。"""

        /** 英文原版书方向（英文→中文，ADR-003）：契约与 [SYSTEM_PROMPT] 完全一致。 */
        const val SYSTEM_PROMPT_EN_TO_ZH = """你是一位资深中英文学翻译。
将用户给定的整章英文翻译为中文, 保留原文语气、风格、文学性。

源文中的每个段落以 [1], [2], [3] 等标记开头。你必须在中文译文中保留完全相同的段落标记, 使得每个标记段落与原文一一对应。

输出格式:
[1] 第一段的中文译文
[2] 第二段的中文译文
......

严禁输出任何解释、标题、注释或额外内容。"""

        /** 按书籍原文语言选择翻译方向 prompt（ADR-003）。 */
        fun translationSystemPrompt(sourceLanguage: String): String =
            if (sourceLanguage == SourceLanguageDetector.EN) SYSTEM_PROMPT_EN_TO_ZH else SYSTEM_PROMPT

        const val EXPLAIN_SYSTEM_PROMPT = """你是一个面向语言学习者的词典助手。

## 目标
根据给定的词语/词组及其周围段落，生成简洁的词典条目，以 JSON 格式输出。

## 规则
1. 聚焦于最匹配所提供段落内容的含义。
2. 先判断所选词语是否属于上下文中的固定/半固定多词表达（短语动词、习语、固定介词短语等）。若属于，且不解释完整词组就无法给出正确含义，则 lemma 输出完整词组并保留原文语言（如上下文中 look after 里的 after → lemma 为 "look after"）；若用户选中了多个词，直接以整个选区为词条。其余情况 lemma 输出该词的基本/标准形式（如 run）。lemma 一律保留原文语言。
3. 保持释义精确且适合学习者。
4. phonetic 字段必须使用该语言的标准标注（英语用 IPA，中文用拼音，日语用罗马字）；词组过长或不确定时留空。
5. pos 字段使用英文（noun, verb, adjective, adverb, phrasal verb, idiom, prepositional phrase 等）。
6. inflections 字段列出词形变化，保留原文语言（如 run: runs, running, ran；look after: looks after, looking after, looked after；big: bigger, biggest），名词列出复数，动词列出时态，形容词列出比较级/最高级。
7. synonyms 字段列出 2–5 个近义词，逗号分隔；保留原文语言。
8. antonyms 字段列出 1–3 个反义词，逗号分隔；若无则留空；保留原文语言。
9. collocations 字段列出 2–4 个常见搭配或短语，分号分隔；搭配本身保留原文语言，并在每个搭配后紧跟括号给出简短中文译义（如 "take part in（参加）, run a business（经营生意）, look forward to（期待）"）。当 lemma 本身已是词组时，collocations 留空以免与词条重复。
10. difficulty 字段必须是 CEFR 等级（A1, A2, B1, B2, C1, 或 C2）。
11. 如果某个字段未知，返回空字符串而非猜测。
12. 语言分工：definition 一律用中文解释；lemma、inflections、synonyms、antonyms、collocations 等词语类字段一律保留所选词语的原文语言，不得翻译成中文（collocations 的中文译义只放在括号内）。

## 输出格式
严格输出以下 JSON，不要输出任何其他内容：
{"lemma":"","phonetic":"","pos":"","definition":"","inflections":"","synonyms":"","antonyms":"","collocations":"","difficulty":""}"""

        fun chatCompletionsUrl(apiBase: String): String {
            val base = apiBase.trim().trimEnd('/')
            require(base.isNotEmpty()) { "API Base URL 不能为空" }
            val parsed = base.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("API Base URL 无效，请使用 http:// 或 https:// 地址")
            require(parsed.scheme == "http" || parsed.scheme == "https") {
                "API Base URL 仅支持 http:// 或 https:// 地址"
            }
            require(parsed.host.isNotBlank()) { "API Base URL 缺少主机名" }
            return if (base.endsWith("/v1")) "$base/chat/completions"
            else "$base/v1/chat/completions"
        }

        fun buildExplainUserPrompt(word: String, paragraphContext: String): String {
            val escaped = paragraphContext.replace("\"", "\\\"").replace("\n", " ")
            return """Selection="$word", Paragraphs="$escaped", 释义语言=中文"""
        }
    }

    fun buildUserPrompt(
        chapterTitle: String,
        chapterContent: String,
        sourceLanguage: String = SourceLanguageDetector.ZH
    ): String {
        // 插图段（ADR-002 D4）保留编号但剔除内容：prompt 出现编号空洞，模型输出自然缺失
        // 该标记，由 parseBilingualParagraphs 的「缺失标记→保留原文」兜底接住，解析器零改动。
        val isEnSource = sourceLanguage == SourceLanguageDetector.EN
        val paragraphs = splitParagraphs(chapterContent)
            .mapIndexed { i, p -> (i + 1) to p }
            .filterNot { (_, p) -> IllustrationLink.parse(p.trim()) != null }
            .joinToString("\n") { (n, p) -> "[$n] ${p.trim()}" }

        val sb = StringBuilder()
        sb.appendLine("Chapter: $chapterTitle")
        sb.appendLine(
            if (isEnSource) "请将以下整章英文翻译为中文, 保留每个段落的 [N] 标记:"
            else "请将以下整章中文翻译为英文, 保留每个段落的 [N] 标记:"
        )
        sb.append(paragraphs)
        return sb.toString()
    }

    override fun translateStream(
        settings: LlmSettings,
        chapterTitle: String,
        chapterContent: String,
        sourceLanguage: String
    ): Flow<TranslationEvent> = flow {
        var call: okhttp3.Call? = null
        var sawDone = false
        var finishReason: String? = null
        try {
            emit(TranslationEvent.Started)
            val userPrompt = buildUserPrompt(chapterTitle, chapterContent, sourceLanguage)
            val requestMap = mutableMapOf<String, Any>(
                "model" to settings.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to translationSystemPrompt(sourceLanguage)),
                    mapOf("role" to "user", "content" to userPrompt)
                ),
                "temperature" to settings.temperature.coerceIn(0f, 2f),
                "top_p" to settings.topP.coerceIn(0f, 1f),
                "max_tokens" to settings.maxOutputTokens,
                "stream" to true
            )
            // 思考模式：同时发送 OpenAI 兼容格式和 Qwen chat_template_kwargs 格式，
            // 以兼容不同 API 后端（DashScope / vLLM / Ollama 等）。
            if (settings.enableThinking) {
                requestMap["thinking"] = mapOf("type" to "enabled")
                requestMap["chat_template_kwargs"] = mapOf("enable_thinking" to true)
            } else {
                requestMap["thinking"] = mapOf("type" to "disabled")
                requestMap["chat_template_kwargs"] = mapOf("enable_thinking" to false)
            }

            val request = Request.Builder()
                .url(chatCompletionsUrl(settings.apiBase))
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(gson.toJson(requestMap).toRequestBody(jsonMediaType))
                .build()

            call = client.newCall(request)
            currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { call?.cancel() }
            call.execute().use { response ->
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
                BufferedReader(InputStreamReader(body.byteStream())).use { reader ->
                    while (true) {
                        val currentLine = reader.readLine() ?: break
                        if (currentLine.isBlank() || !currentLine.startsWith("data:")) continue
                        val data = currentLine.removePrefix("data:").trim()
                        if (data == "[DONE]") {
                            sawDone = true
                            when {
                                finishReason == "length" -> emit(TranslationEvent.Error("模型输出达到长度上限，译文未完整生成"))
                                fullText.isEmpty() -> emit(TranslationEvent.Error("翻译结果为空"))
                                else -> emit(TranslationEvent.Done(fullText.toString()))
                            }
                            break
                        }
                        try {
                            val streamChoice = gson.fromJson(data, ChatStreamResponse::class.java)
                                .choices?.firstOrNull()
                            finishReason = streamChoice?.finish_reason ?: finishReason
                            val delta = streamChoice?.delta
                            val reasoning = delta?.reasoning_content ?: delta?.reasoning ?: delta?.thinking
                            if (!reasoning.isNullOrEmpty()) {
                                emit(TranslationEvent.Thinking(reasoning))
                            }
                            val content = delta?.content
                            if (!content.isNullOrEmpty()) {
                                fullText.append(content)
                                emit(TranslationEvent.Chunk(content))
                                chunkCount++
                                emit(TranslationEvent.Progress(fullText.length))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.put("解析流式响应失败", e)
                            emit(TranslationEvent.Error("解析流式响应失败: ${e.message ?: "格式无效"}"))
                            return@flow
                        }
                    }
                }
                if (!sawDone) {
                    AppLog.put("流式响应提前结束（未收到 [DONE]）")
                    emit(TranslationEvent.Error("流式响应提前结束"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            AppLog.put("翻译网络错误", e)
            emit(TranslationEvent.Error("网络错误: ${e.message}"))
        } catch (e: Exception) {
            AppLog.put("翻译请求失败", e)
            emit(TranslationEvent.Error(e.message ?: "翻译请求失败"))
        } finally {
            call?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(settings: LlmSettings): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestMap = mutableMapOf<String, Any>(
                "model" to settings.model,
                "messages" to listOf(mapOf("role" to "user", "content" to "Say hi in one word.")),
                "temperature" to settings.temperature.coerceIn(0f, 2f),
                "top_p" to settings.topP.coerceIn(0f, 1f),
                "max_tokens" to 10,
                "stream" to false
            )
            if (settings.enableThinking) {
                requestMap["thinking"] = mapOf("type" to "enabled")
                requestMap["chat_template_kwargs"] = mapOf("enable_thinking" to true)
            } else {
                requestMap["thinking"] = mapOf("type" to "disabled")
                requestMap["chat_template_kwargs"] = mapOf("enable_thinking" to false)
            }
            val request = Request.Builder()
                .url(chatCompletionsUrl(settings.apiBase))
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(requestMap).toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()?.take(200) ?: "Unknown"
                    return@withContext Result.failure<String>(Exception("API 错误 ${response.code}: $body"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure<String>(Exception("空响应"))
                try {
                    val resp = gson.fromJson(body, ChatCompletionResponse::class.java)
                    Result.success(resp.choices?.firstOrNull()?.message?.content ?: "连接成功")
                } catch (e: Exception) {
                    AppLog.put("解析连接测试响应失败", e)
                    Result.failure(Exception("解析响应失败: ${e.message}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            AppLog.put("连接测试网络错误", e)
            Result.failure(Exception("网络错误: ${e.message}"))
        } catch (e: Exception) {
            AppLog.put("连接测试请求失败", e)
            Result.failure(Exception(e.message ?: "请求配置无效"))
        }
    }

    /**
     * 调用 LLM 解释单词用法（非流式）。
     * 返回解析后的 [WordExplanation]，失败时返回带错误信息的 Result。
     */
    suspend fun explainWord(
        settings: LlmSettings,
        word: String,
        paragraphContext: String
    ): Result<WordExplanation> = withContext(Dispatchers.IO) {
        try {
            val requestMap = mutableMapOf<String, Any>(
                "model" to settings.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to EXPLAIN_SYSTEM_PROMPT),
                    mapOf("role" to "user", "content" to buildExplainUserPrompt(word, paragraphContext))
                ),
                "temperature" to 0.3f,
                "top_p" to 0.9f,
                "max_tokens" to 4096,
                "stream" to false
            )
            if (settings.enableExplainThinking) {
                requestMap["thinking"] = mapOf("type" to "enabled")
                requestMap["chat_template_kwargs"] = mapOf("enable_thinking" to true)
            } else {
                requestMap["thinking"] = mapOf("type" to "disabled")
                requestMap["chat_template_kwargs"] = mapOf("enable_thinking" to false)
            }
            val request = Request.Builder()
                .url(chatCompletionsUrl(settings.apiBase))
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(gson.toJson(requestMap).toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(200) ?: "Unknown"
                    return@withContext Result.failure<WordExplanation>(Exception("API 错误 ${response.code}: $errorBody"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("空响应"))
                try {
                    val resp = gson.fromJson(body, ChatCompletionResponse::class.java)
                    val message = resp.choices?.firstOrNull()?.message
                    // content 为空时回退 reasoning_content（思考模式下模型可能将答案放入 reasoning_content）
                    val content = if (!message?.content.isNullOrBlank()) message.content else message?.reasoning_content
                    if (content.isNullOrBlank()) {
                        AppLog.put("单词解释：模型返回内容为空，content=${message?.content?.length ?: 0}字, reasoning_content=${message?.reasoning_content?.length ?: 0}字")
                        return@withContext Result.failure(Exception("模型返回内容为空"))
                    }
                    // 提取 JSON：模型可能在外层包裹 markdown 代码块或思考过程
                    val json = extractJson(content)
                    val explanation = gson.fromJson(json, WordExplanation::class.java)
                    if (explanation == null) {
                        AppLog.put("单词解释：解析结果为 null，extractJson 前200字: ${json.take(200)}")
                    }
                    Result.success(explanation)
                } catch (e: Exception) {
                    AppLog.put("解析单词解释结果失败", e)
                    Result.failure(Exception("解析解释结果失败: ${e.message}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            AppLog.put("单词解释网络错误", e)
            Result.failure(Exception("网络错误: ${e.message}"))
        } catch (e: Exception) {
            AppLog.put("单词解释请求失败", e)
            Result.failure(Exception(e.message ?: "解释请求失败"))
        }
    }

    /** 从可能含 markdown 代码块的文本中提取 JSON 字符串。 */
    private fun extractJson(text: String): String {
        val trimmed = text.trim()
        // 尝试提取 ```json ... ``` 代码块
        val codeBlockMatch = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```").find(trimmed)
        if (codeBlockMatch != null) return codeBlockMatch.groupValues[1].trim()
        // 如果直接以 { 开头，整体当作 JSON
        if (trimmed.startsWith("{")) return trimmed
        // 尝试找第一个 { 到最后一个 } 之间的内容
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)
        return trimmed
    }
}
