package com.vibereading.app.ui.reader.pagination

import android.content.Context
import android.net.Uri
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 内置字体条目；systemFamily 非空表示免下载的系统字体，直接映射到系统字体族。 */
data class ReaderFontInfo(
    val id: String,
    val zhName: String,
    val enSpec: String,
    val urls: List<String>, // 下载镜像源，按序尝试（GitHub + 国内可达镜像）；系统字体为空
    val localName: String, // 保存到 fonts 目录的文件名（含扩展名）；系统字体为空
    val systemFamily: FontFamily? = null, // 非空=免下载，直接返回该系统 FontFamily
    val hasChinese: Boolean = true, // 是否含中文字形（中文槽位按此过滤）
    val hasEnglish: Boolean = true  // 是否含英文字形（英文槽位按此过滤）
)

/** 内置字体目录（均 OFL 协议、含中英文/拉丁字形；系统字体免下载，其余点击即下载）。
 *  每个下载字体配多镜像：优先国内可达的 CDN/代理，GitHub 兜底，逐个尝试直到成功。 */
object ReaderFonts {

    private const val DOWNLOAD_BUFFER = 8192

    val fonts = listOf(
        ReaderFontInfo(
            id = "sys_song", zhName = "系统宋体", enSpec = "Noto Serif CJK",
            urls = emptyList(), localName = "", systemFamily = FontFamily.Serif
        ),
        ReaderFontInfo(
            id = "sys_hei", zhName = "系统黑体", enSpec = "Noto Sans CJK",
            urls = emptyList(), localName = "", systemFamily = FontFamily.SansSerif
        ),
        ReaderFontInfo(
            id = "serif_sc", zhName = "思源宋体", enSpec = "Source Han Serif SC",
            urls = listOf(
                "https://cdn.jsdelivr.net/gh/adobe-fonts/source-han-serif@release/SubsetOTF/CN/SourceHanSerifCN-Regular.otf",
                "https://github.com/adobe-fonts/source-han-serif/raw/release/SubsetOTF/CN/SourceHanSerifCN-Regular.otf"
            ),
            localName = "SourceHanSerifCN-Regular.otf"
        ),
        ReaderFontInfo(
            id = "wenkai", zhName = "霞鹜文楷", enSpec = "LXGW WenKai",
            urls = listOf(
                "https://ghfast.top/https://github.com/lxgw/LxgwWenKai/releases/download/v1.522/LXGWWenKai-Regular.ttf",
                "https://ghproxy.net/https://github.com/lxgw/LxgwWenKai/releases/download/v1.522/LXGWWenKai-Regular.ttf",
                "https://github.com/lxgw/LxgwWenKai/releases/download/v1.522/LXGWWenKai-Regular.ttf"
            ),
            localName = "LXGWWenKai-Regular.ttf"
        ),
        ReaderFontInfo(
            id = "tinos", zhName = "Times New Roman", enSpec = "Tinos · Times 兼容英文衬线",
            urls = listOf(
                "https://cdn.jsdelivr.net/gh/google/fonts@main/ofl/tinos/Tinos-Regular.ttf",
                "https://github.com/google/fonts/raw/main/ofl/tinos/Tinos-Regular.ttf"
            ),
            localName = "Tinos-Regular.ttf",
            hasChinese = false // 纯英文字体，不进入中文槽位
        )
    )

    fun byId(id: String): ReaderFontInfo? = fonts.find { it.id == id }

    private fun fontDir(context: Context): File =
        File(context.filesDir, "fonts").apply { mkdirs() }

    /** 内置字体本地文件；未下载（或下载中）返回 null。 */
    fun localFile(context: Context, info: ReaderFontInfo): File? {
        val f = File(fontDir(context), info.localName)
        return f.takeIf { it.exists() }
    }

    // 字体体积大（数 MB~25MB），镜像可能走慢速代理：放宽连接/读取超时避免误判失败
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 下载内置字体到 filesDir/fonts。多个镜像源按序尝试直到成功（先写 .tmp 再 rename 原子落盘）；
     *  已存在则直接返回缓存文件；全部失败回退 Result.failure 并落日志。 */
    suspend fun downloadFont(
        context: Context,
        info: ReaderFontInfo,
        onProgress: (Float) -> Unit = {}
    ): Result<File> =
        withContext(Dispatchers.IO) {
            val dir = fontDir(context)
            val target = File(dir, info.localName)
            if (target.exists()) return@withContext Result.success(target)
            val failures = mutableListOf<String>()
            for (url in info.urls) {
                try {
                    val tmp = File(dir, "${info.localName}.tmp")
                    val request = Request.Builder().url(url).build()
                    // 必须在 resp.use 块内读完 body：use 退出会关闭 response（连带 body 流），
                    // 若在 .let 里再读会抛 IOException: closed
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IOException("空响应体")
                        val total = body.contentLength()
                        var readBytes = 0L
                        val buf = ByteArray(DOWNLOAD_BUFFER)
                        body.byteStream().use { input ->
                            tmp.outputStream().use { output ->
                                while (true) {
                                    val n = input.read(buf)
                                    if (n == -1) break
                                    output.write(buf, 0, n)
                                    readBytes += n
                                    if (total > 0) onProgress(readBytes.toFloat() / total)
                                }
                            }
                        }
                    }
                    if (!tmp.renameTo(target)) throw IOException("字体落地失败")
                    onProgress(1f)
                    return@withContext Result.success(target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += url
                    AppLog.put("下载内置字体镜像失败：${info.zhName} $url", e)
                }
            }
            val err = IOException("内置字体「${info.zhName}」所有镜像源（${failures.joinToString()}）均下载失败")
            Result.failure(err)
        }

    /** 将 SAF 导入的自定义字体 content:// URI 解析为 FontFamily（缓存到 cacheDir 后按 File 加载）。 */
    fun customFontFamily(context: Context, uriString: String?): FontFamily? {
        if (uriString.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(uriString)
            val file = File(context.cacheDir, "custom_font_${uriString.hashCode()}.ttf")
            if (!file.exists()) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { out -> input.copyTo(out) }
                } ?: return null
            }
            FontFamily(Font(file))
        } catch (e: Exception) {
            AppLog.put("解析自定义字体失败", e)
            null
        }
    }

    /** 将内置字体 id 解析为 FontFamily；系统字体直接返回，下载字体需文件存在。 */
    fun builtinFontFamily(context: Context, fontId: String?): FontFamily? {
        val info = fontId?.let { byId(it) } ?: return null
        info.systemFamily?.let { return it }
        val file = localFile(context, info) ?: return null
        return try {
            FontFamily(Font(file))
        } catch (e: Exception) {
            AppLog.put("加载内置字体失败：${info.zhName}", e)
            null
        }
    }

    /**
     * 统一解析当前阅读的中英字体：返回 (中文字体, 英文字体)。
     * 中文字体优先级 customFontUri > fontId > null（系统默认）；
     * 英文字体优先 enFontId，未设则跟随中文字体。
     */
    fun readerFontFamilies(context: Context, settings: ReadingSettings): Pair<FontFamily?, FontFamily?> {
        val cn = when {
            !settings.customFontUri.isNullOrBlank() -> customFontFamily(context, settings.customFontUri)
            settings.fontId != null -> builtinFontFamily(context, settings.fontId)
            else -> null
        }
        // 英文字体：自定义导入 > 内置 fontId > 跟随中文字体
        val en = when {
            !settings.enCustomFontUri.isNullOrBlank() -> customFontFamily(context, settings.enCustomFontUri)
            settings.enFontId != null -> builtinFontFamily(context, settings.enFontId)
            else -> cn
        }
        return cn to en
    }
}
