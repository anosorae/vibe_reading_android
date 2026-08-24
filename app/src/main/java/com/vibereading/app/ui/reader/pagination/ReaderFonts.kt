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

/** 内置开源字体条目。 */
data class ReaderFontInfo(
    val id: String,
    val zhName: String,
    val enSpec: String,
    val urls: List<String>, // 多个镜像源，按序尝试（GitHub + 国内可达镜像）
    val localName: String // 保存到 fonts 目录的文件名（含扩展名）
)

/** 内置开源字体目录（均 OFL 协议、含中英文/拉丁字形，点击即下载）。
 *  每个字体配多镜像：优先国内可达的 CDN/代理，GitHub 兜底，逐个尝试直到成功。 */
object ReaderFonts {

    val fonts = listOf(
        ReaderFontInfo(
            id = "sans_sc", zhName = "思源黑体", enSpec = "Source Han Sans SC",
            urls = listOf(
                "https://cdn.jsdelivr.net/gh/adobe-fonts/source-han-sans@release/SubsetOTF/CN/SourceHanSansCN-Regular.otf",
                "https://github.com/adobe-fonts/source-han-sans/raw/release/SubsetOTF/CN/SourceHanSansCN-Regular.otf"
            ),
            localName = "SourceHanSansCN-Regular.otf"
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

    private val client = OkHttpClient()

    /** 下载内置字体到 filesDir/fonts。多个镜像源按序尝试直到成功（先写 .tmp 再 rename 原子落盘）；
     *  已存在则直接返回缓存文件；全部失败回退 Result.failure 并落日志。 */
    suspend fun downloadFont(context: Context, info: ReaderFontInfo): Result<File> =
        withContext(Dispatchers.IO) {
            val dir = fontDir(context)
            val target = File(dir, info.localName)
            if (target.exists()) return@withContext Result.success(target)
            val failures = mutableListOf<String>()
            for (url in info.urls) {
                try {
                    val tmp = File(dir, "${info.localName}.tmp")
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        resp.body?.byteStream()?.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IOException("空响应体")
                    }
                    if (!tmp.renameTo(target)) throw IOException("字体落地失败")
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

    /** 将内置字体 id 解析为 FontFamily；未下载/文件缺失返回 null。 */
    fun builtinFontFamily(context: Context, fontId: String?): FontFamily? {
        val info = fontId?.let { byId(it) } ?: return null
        val file = localFile(context, info) ?: return null
        return try {
            FontFamily(Font(file))
        } catch (e: Exception) {
            AppLog.put("加载内置字体失败：${info.zhName}", e)
            null
        }
    }

    /** 统一解析当前阅读字体：customFontUri > 内置 fontId > null（系统默认）。 */
    fun readerFontFamily(context: Context, settings: ReadingSettings): FontFamily? = when {
        !settings.customFontUri.isNullOrBlank() -> customFontFamily(context, settings.customFontUri)
        settings.fontId != null -> builtinFontFamily(context, settings.fontId)
        else -> null
    }
}
