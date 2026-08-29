package com.vibereading.app.web

import android.content.res.AssetManager
import com.google.gson.Gson
import com.vibereading.app.data.image.BookImageStore
import com.vibereading.app.log.AppLog
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking

/**
 * Web 伴读服务的内嵌 HTTP 服务器（ADR-005）：NanoHTTPD 阻塞式线程模型，
 * 路由在 [serve] 分发。所有请求（含静态页）必须带 Token（query `?token=` 或
 * header `X-Companion-Token`），否则 401。
 *
 * 业务处理走 [CompanionApi] 的 suspend 函数，此处 runBlocking 桥接——
 * 单用户低并发场景，默认线程池足够。
 */
class CompanionServer(
    port: Int,
    private val token: String,
    private val api: CompanionApi,
    private val assets: AssetManager
) : NanoHTTPD(port) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        if (!authorize(session)) {
            return json(Response.Status.UNAUTHORIZED, CompanionResult.failure("缺少或错误的 Token"))
        }
        val uri = session.uri.trimEnd('/')
        return try {
            // 阻塞线程模型：业务层是 suspend 函数，此处 runBlocking 桥接（单用户低并发）
            runBlocking {
                when (session.method) {
                    Method.GET -> serveGet(session, uri)
                    Method.POST -> servePost(session, uri)
                    else -> json(Response.Status.METHOD_NOT_ALLOWED, CompanionResult.failure("不支持的方法"))
                }
            }
        } catch (e: Exception) {
            AppLog.put("伴读服务请求处理失败: ${session.method} $uri", e)
            json(Response.Status.INTERNAL_ERROR, CompanionResult.failure(e.message ?: "服务器内部错误"))
        }
    }

    // ── 路由（suspend：已在 runBlocking 内） ──

    private suspend fun serveGet(session: IHTTPSession, uri: String): Response {
        // /img/{bookId}/{fileName}：EPUB 插图（BookImageStore 键格式）
        if (uri.startsWith("/img/")) {
            return fileResponse(BookImageStore.imageFile(uri.removePrefix("/img/")))
        }
        if (uri.startsWith("/cover/")) {
            val bookId = uri.removePrefix("/cover/").toLongOrNull()
                ?: return json(Response.Status.BAD_REQUEST, CompanionResult.failure("非法书籍 ID"))
            return coverResponse(bookId)
        }
        if (uri.isEmpty()) return assetResponse("web/index.html", MIME_HTML)
        if (uri == "/api/books") return jsonOk(api.books())

        URI_CHAPTERS.matchEntire(uri)?.let { m ->
            val bookId = m.groupValues[1].toLongOrNull()
                ?: return json(Response.Status.BAD_REQUEST, CompanionResult.failure("非法书籍 ID"))
            val list = api.chapterList(bookId)
            return if (list == null) json(Response.Status.NOT_FOUND, CompanionResult.failure("书籍不存在"))
            else jsonOk(list)
        }
        URI_CHAPTER_CONTENT.matchEntire(uri)?.let { m ->
            val chapterId = m.groupValues[1].toLongOrNull()
            val bookId = session.parameters["bookId"]?.firstOrNull()?.toLongOrNull()
            if (chapterId == null || bookId == null) {
                return json(Response.Status.BAD_REQUEST, CompanionResult.failure("缺少 bookId 参数"))
            }
            val content = api.chapterContent(bookId, chapterId)
            return if (content == null) json(Response.Status.NOT_FOUND, CompanionResult.failure("章节不存在"))
            else jsonOk(content)
        }
        URI_CHAPTER_STATUS.matchEntire(uri)?.let { m ->
            val chapterId = m.groupValues[1].toLongOrNull()
            val bookId = session.parameters["bookId"]?.firstOrNull()?.toLongOrNull()
            if (chapterId == null || bookId == null) {
                return json(Response.Status.BAD_REQUEST, CompanionResult.failure("缺少 bookId 参数"))
            }
            val status = api.chapterStatus(bookId, chapterId)
            return if (status == null) json(Response.Status.NOT_FOUND, CompanionResult.failure("章节不存在"))
            else jsonOk(status)
        }
        return json(Response.Status.NOT_FOUND, CompanionResult.failure("未知路径"))
    }

    private suspend fun servePost(session: IHTTPSession, uri: String): Response {
        val body = readBody(session)
        return when {
            URI_PROGRESS.matchEntire(uri) != null -> {
                val req = parseBody(body, ProgressRequest()) ?: return badJson()
                if (req.bookId <= 0 || req.chapterId <= 0) {
                    return json(Response.Status.BAD_REQUEST, CompanionResult.failure("缺少 bookId/chapterId"))
                }
                jsonOk(api.saveProgress(req.bookId, req.chapterId, req.offset))
            }
            URI_MODE.matchEntire(uri) != null -> {
                val bookId = URI_MODE.matchEntire(uri)!!.groupValues[1].toLongOrNull()
                    ?: return json(Response.Status.BAD_REQUEST, CompanionResult.failure("非法书籍 ID"))
                val req = parseBody(body, ModeRequest()) ?: return badJson()
                jsonOk(api.setLanguageMode(bookId, req.mode))
            }
            URI_TRANSLATE.matchEntire(uri) != null -> {
                val chapterId = URI_TRANSLATE.matchEntire(uri)!!.groupValues[1].toLongOrNull()
                val bookId = session.parameters["bookId"]?.firstOrNull()?.toLongOrNull()
                if (chapterId == null || bookId == null) {
                    return json(Response.Status.BAD_REQUEST, CompanionResult.failure("缺少 bookId 参数"))
                }
                jsonOk(api.startTranslation(bookId, chapterId))
            }
            else -> json(Response.Status.NOT_FOUND, CompanionResult.failure("未知路径"))
        }
    }

    // ── 响应构造 ──

    private fun jsonOk(data: Any?): Response = json(Response.Status.OK, CompanionResult.success(data))

    private fun json(status: Response.Status, payload: CompanionResult): Response =
        newFixedLengthResponse(status, "application/json", gson.toJson(payload))

    private fun badJson(): Response =
        json(Response.Status.BAD_REQUEST, CompanionResult.failure("请求体不是合法 JSON"))

    private suspend fun coverResponse(bookId: Long): Response {
        val book = api.book(bookId)
        val path = book?.coverPath
        val file = path?.let {
            runCatching { BookImageStore.coverFile(it) }
                .onFailure { e -> AppLog.put("封面路径解析失败: $it", e) }
                .getOrNull()
        }
        if (file != null && file.exists()) {
            return fileResponse(file)
        }
        return json(Response.Status.NOT_FOUND, CompanionResult.failure("无封面"))
    }

    private fun fileResponse(file: java.io.File): Response {
        if (!file.exists() || !file.isFile) {
            return json(Response.Status.NOT_FOUND, CompanionResult.failure("文件不存在"))
        }
        return newChunkedResponse(Response.Status.OK, mimeOf(file.name), file.inputStream())
    }

    private fun assetResponse(path: String, mime: String): Response = try {
        assets.open(path).use { input ->
            val bytes = input.readBytes()
            newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
        }
    } catch (e: Exception) {
        AppLog.put("伴读静态资源缺失: $path", e)
        json(Response.Status.NOT_FOUND, CompanionResult.failure("静态资源缺失: $path"))
    }

    // ── 工具 ──

    /** Token 校验：query 参数或 header 任一命中即可（伴读地址自带 ?token=）。 */
    private fun authorize(session: IHTTPSession): Boolean {
        if (session.parameters["token"]?.firstOrNull() == token) return true
        return session.headers["x-companion-token"] == token
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    private inline fun <reified T : Any> parseBody(body: String, fallback: T): T? {
        if (body.isBlank()) return fallback
        return try {
            gson.fromJson(body, T::class.java)
        } catch (e: Exception) {
            AppLog.put("伴读请求体 JSON 解析失败", e)
            null
        }
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".svg", true) -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    data class ProgressRequest(val bookId: Long = 0, val chapterId: Long = 0, val offset: Int = 0)
    data class ModeRequest(val mode: String = "")

    companion object {
        private val URI_CHAPTERS = Regex("^/api/books/(\\d+)/chapters$")
        private val URI_CHAPTER_CONTENT = Regex("^/api/chapters/(\\d+)$")
        private val URI_CHAPTER_STATUS = Regex("^/api/chapters/(\\d+)/status$")
        private val URI_PROGRESS = Regex("^/api/progress$")
        private val URI_MODE = Regex("^/api/books/(\\d+)/mode$")
        private val URI_TRANSLATE = Regex("^/api/chapters/(\\d+)/translate$")

        private const val MIME_HTML = "text/html; charset=utf-8"
    }
}
