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
            // 浏览器直接导航（地址栏、历史记录、旧书签）被拒时给可读说明页：
            // 裸 JSON 会让用户只看到一串 {} 而不知道要去手机重新复制地址。
            return if (prefersHtml(session)) unauthorizedHtml()
            else json(Response.Status.UNAUTHORIZED, CompanionResult.failure("缺少或错误的 Token"))
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
        if (uri.isEmpty()) {
            // 首次带 token 访问时种 Cookie：之后地址里没有 token（手输 IP、书签、
            // 从地址栏复制出来的地址）也能打开，这是「网站打不开」类问题的兜底。
            return assetResponse("web/index.html", MIME_HTML).apply {
                addHeader("Set-Cookie", "$COOKIE_NAME=$token; Path=/; SameSite=Lax; HttpOnly")
                addHeader("Cache-Control", "no-store")
            }
        }
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

    /** Token 校验（ADR-005 三通道）：query 参数、`X-Companion-Token`、Cookie 任一命中即可。 */
    private fun authorize(session: IHTTPSession): Boolean = tokenAccepted(
        queryToken = session.parameters["token"]?.firstOrNull(),
        headerToken = session.headers["x-companion-token"],
        cookieHeader = session.headers["cookie"],
        expected = token
    )

    /** 浏览器导航（Accept 首选 text/html）被拒时改用说明页，接口调用仍返回 JSON。 */
    private fun prefersHtml(session: IHTTPSession): Boolean =
        session.headers["accept"]?.contains("text/html", ignoreCase = true) == true

    /**
     * Token 失效时的说明页。这段 HTML 必须自包含：静态页 index.html 同样在 Token
     * 校验之后，拿不到 assets，也不能依赖任何接口。
     */
    private fun unauthorizedHtml(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>译读 · 请重新复制访问地址</title>
            </head>
            <body style="margin:0;padding:48px 20px;background:#faf7f2;color:#2c2825;line-height:1.8;
                         font-family:system-ui,-apple-system,'PingFang SC','Microsoft YaHei',sans-serif">
            <div style="max-width:34em;margin:0 auto">
            <h1 style="font-size:18px;margin:0 0 14px">这个地址的访问 Token 不对</h1>
            <p style="margin:0 0 12px">Token 每次在手机上开启 Web 伴读都会重新生成，旧书签、旧地址和
            从地址栏复制出来的地址都会失效。</p>
            <p style="margin:0">请在手机 App「设置 → Web 伴读」里点一下那条地址复制，粘贴到地址栏打开；
            手机通知栏里的地址也可以。</p>
            </div>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_HTML, html)
            .apply { addHeader("Cache-Control", "no-store") }
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
        internal const val COOKIE_NAME = "companion_token"

        /**
         * Token 三通道判定（ADR-005）。抽成纯函数便于单测：鉴权是安全相关的，
         * 并且「旧地址打不开」的排查成本很高，不能让它的正确性只靠人工核对。
         */
        internal fun tokenAccepted(
            queryToken: String?,
            headerToken: String?,
            cookieHeader: String?,
            expected: String
        ): Boolean {
            if (expected.isEmpty()) return false
            if (queryToken == expected) return true
            if (headerToken == expected) return true
            return cookieToken(cookieHeader) == expected
        }

        /** 从 Cookie 请求头里取出伴读 Token；无该 Cookie 返回 null。 */
        internal fun cookieToken(cookieHeader: String?): String? =
            cookieHeader?.split(';')
                ?.asSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("$COOKIE_NAME=") }
                ?.substringAfter('=')
    }
}
