package com.vibereading.app.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.collection.LruCache
import androidx.exifinterface.media.ExifInterface
import com.vibereading.app.log.AppLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 书籍图片资源单一数据源（ADR-002 D3）：
 *
 * - 插图：导入期解压到 `filesDir/books/{bookId}/images/{md5}.{ext}`，
 *   链接键为 `{bookId}/{fileName}`，运行时经 [imageFile] 解析；
 * - 封面：`filesDir/covers/`，路径存 books.coverPath，两种来源两种命名：
 *   - `{bookId}_embedded.jpg` 导入期内嵌封面（[saveEmbeddedCover]），导入后不再改写，
 *     充当「恢复原封面」的备份；
 *   - `{bookId}_{内容哈希}.jpg` 用户上传封面（[saveUserCover]），文件名随内容变化，
 *     使书架 `BookCover` 以 path 为 key 的 produceState 能重新解码（同名覆盖不会刷新）。
 * - 内存 LRU 位图缓存（按目标宽度降采样解码），删书时同步清理磁盘。
 */
object BookImageStore {

    private lateinit var baseDir: File
    private val bitmapCache = object : LruCache<String, Bitmap>(48) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
        // 逐出不 recycle：UI 可能仍持有引用（LazyColumn 复用/卷页快照），交给 GC 回收
    }
    private val decoding = ConcurrentHashMap<String, Any>()

    /** 用户上传封面的最长边上限（px）：书架封面最大渲染约 300px，1600 足够清晰且避开大图 OOM。 */
    private const val MAX_USER_COVER_DIMENSION = 1600

    fun init(context: Context) {
        if (::baseDir.isInitialized) return
        baseDir = File(context.applicationContext.filesDir, "books")
    }

    private fun requireInit(): File =
        if (::baseDir.isInitialized) baseDir
        else throw IllegalStateException("BookImageStore 未初始化（应在 Application.onCreate 调用 init）")

    // ── 写入 ──

    /**
     * 保存一批插图字节，返回 href → 文件名映射。文件名 = 内容 md5 + 扩展名
     * （[extOf] 由 href 后缀推导；同内容自动去重）。写入用 `.tmp` + rename 原子落盘
     * （对齐 ReaderFonts 惯例）。
     */
    fun saveImages(
        bookId: Long,
        images: Map<String, ByteArray>,
        extOf: (href: String) -> String
    ): Map<String, String> {
        val dir = imageDir(bookId).apply { mkdirs() }
        val result = HashMap<String, String>()
        images.forEach { (href, bytes) ->
            val name = "${md5(bytes)}${extOf(href)}"
            val target = File(dir, name)
            if (!target.exists()) {
                val tmp = File(dir, "$name.tmp")
                FileOutputStream(tmp).use { it.write(bytes) }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    AppLog.put("插图落盘失败: $target")
                    return@forEach
                }
            }
            result[href] = name
        }
        return result
    }

    /**
     * 保存 EPUB 内嵌封面字节，返回可持久化到 books.coverPath 的相对路径；失败返回 null。
     * 文件名固定为 `{bookId}_embedded.jpg`，导入后不再改写，供「恢复原封面」回退。
     */
    fun saveEmbeddedCover(bookId: Long, bytes: ByteArray): String? {
        try {
            val dir = coversDir()
            // 统一转 jpg 存储（EPUB 封面多为 jpg/png；png 透明通道在 JPEG 中铺黑底可接受）
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                AppLog.put("封面解码失败 bookId=$bookId")
                return null
            }
            val name = "${bookId}_embedded.jpg"
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            FileOutputStream(tmp).use { decoded.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            decoded.recycle()
            return if (tmp.renameTo(target)) "covers/$name" else { tmp.delete(); null }
        } catch (e: Exception) {
            AppLog.put("封面保存失败 bookId=$bookId", e)
            return null
        }
    }

    /**
     * 保存用户上传的封面（[source] 为调用方落好的临时文件，避免整张原图进堆）：
     * 按最长边 [MAX_USER_COVER_DIMENSION] 降采样（防大图 OOM）、按 EXIF 方向旋正
     * （相册照片常见，BitmapFactory 自身不处理），再转 JPEG 90。
     * 文件名带内容哈希，返回可持久化的相对路径；解码失败返回 null。
     */
    fun saveUserCover(bookId: Long, source: File): String? {
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        return try {
            val dir = coversDir()
            decoded = decodeDownsampled(source, MAX_USER_COVER_DIMENSION) ?: run {
                AppLog.put("用户封面解码失败 bookId=$bookId")
                return null
            }
            oriented = applyExifOrientation(decoded, readExifOrientation(source))
            val jpeg = ByteArrayOutputStream().use { out ->
                oriented.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }
            val name = "${bookId}_${md5(jpeg)}.jpg"
            val target = File(dir, name)
            // 内容哈希命名天然幂等：同内容已落盘就跳过重写
            // （Windows 上 renameTo 目标存在会失败，不能依赖 POSIX 覆盖语义）
            if (!target.exists()) {
                val tmp = File(dir, "$name.tmp")
                FileOutputStream(tmp).use { it.write(jpeg) }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return null
                }
            }
            "covers/$name"
        } catch (e: Exception) {
            AppLog.put("用户封面保存失败 bookId=$bookId", e)
            null
        } finally {
            oriented?.takeIf { !it.isRecycled }?.recycle()
            decoded?.takeIf { it !== oriented && !it.isRecycled }?.recycle()
        }
    }

    /** 内嵌封面备份的相对路径（不保证文件存在，用 [coverExists] 判断）。 */
    fun embeddedCoverPath(bookId: Long): String = "covers/${bookId}_embedded.jpg"

    /** 该路径是否为导入期内嵌封面（区分「移除封面」与「恢复原封面」的唯一判据）。 */
    fun isEmbeddedCover(coverPath: String): Boolean = coverPath.endsWith("_embedded.jpg")

    /** 封面相对路径对应的文件是否存在。 */
    fun coverExists(coverPath: String): Boolean =
        try { coverFile(coverPath).exists() } catch (e: Exception) { AppLog.put("封面文件检查失败: $coverPath", e); false }

    /**
     * 当前封面能否回退到内嵌原封面（「恢复原封面」与「移除封面」的唯一判据）：
     * 当前必须是用户上传的封面，且内嵌备份文件确实存在。
     */
    fun canRestoreEmbeddedCover(bookId: Long, currentCoverPath: String?): Boolean =
        currentCoverPath != null && !isEmbeddedCover(currentCoverPath) &&
            coverExists(embeddedCoverPath(bookId))

    /** 删除单个封面文件（替换/移除封面时清理旧图，调用方负责不删 [isEmbeddedCover] 的文件）。 */
    fun deleteCover(coverPath: String) {
        try {
            coverFile(coverPath).takeIf { it.exists() }?.delete()
        } catch (e: Exception) {
            AppLog.put("封面文件删除失败: $coverPath", e)
        }
    }

    /** `filesDir/covers` 目录，不存在则创建。 */
    private fun coversDir(): File = File(requireInit().parentFile, "covers").apply { mkdirs() }

    /** 按最长边降采样解码：先 inSampleSize 控峰值内存，再精确缩放到 ≤ [maxDimension]。 */
    private fun decodeDownsampled(source: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val longest = max(decoded.width, decoded.height)
        if (longest <= maxDimension) return decoded
        val scale = maxDimension.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** 读取 EXIF 方向；读不到按正常方向处理（不阻断保存）。 */
    private fun readExifOrientation(source: File): Int = try {
        ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        AppLog.put("封面 EXIF 方向读取失败: ${source.name}", e)
        ExifInterface.ORIENTATION_NORMAL
    }

    /** 按 EXIF 方向旋正/镜像；无变换时原样返回，有变换时回收原图。 */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }

    // ── 读取 ──

    /**
     * 链接键 `{bookId}/{fileName}` → 磁盘文件。
     * 落盘布局是 `files/books/{bookId}/images/{fileName}`（与 [saveImages] 一致）。
     */
    fun imageFile(path: String): File {
        val safe = path.replace("..", "").replace('\\', '/')
        val bookId = safe.substringBefore('/')
        val fileName = safe.substringAfter('/', "")
        require(bookId.isNotEmpty() && fileName.isNotEmpty()) { "插图链接键非法: $path" }
        return File(requireInit(), "$bookId/images/$fileName")
    }

    /** 封面相对路径（如 "covers/7_ab12cd.jpg"）→ 磁盘文件；基准是 filesDir，与写入端一致。 */
    fun coverFile(coverPath: String): File {
        val safe = coverPath.replace("..", "").replace('\\', '/')
        return File(requireInit().parentFile, safe)
    }

    /**
     * 同步解码插图为位图，带内存缓存。 [targetWidth] ≤ 0 时按原图尺寸；
     * 否则按宽降采样控制内存。并发调用同一 key 只解码一次。
     */
    fun loadBitmap(path: String, targetWidth: Int = 0): Bitmap? {
        val key = "$path@$targetWidth"
        bitmapCache.get(key)?.let { return it }
        val lock = decoding.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            bitmapCache.get(key)?.let { return it }
            val file = imageFile(path)
            if (!file.exists()) return null
            val bitmap = try {
                if (targetWidth <= 0) {
                    BitmapFactory.decodeFile(file.absolutePath)
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
                    BitmapFactory.decodeFile(
                        file.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                }
            } catch (e: Exception) {
                AppLog.put("插图解码失败: $path", e)
                null
            } ?: return null
            bitmapCache.put(key, bitmap)
            return bitmap
        }
    }

    // ── 清理（ADR-002：删书清理图片）──

    private fun imageDir(bookId: Long): File = File(requireInit(), "$bookId/images")

    fun deleteBookFiles(bookId: Long) {
        try {
            // 删整本书目录（含 images 子目录），避免留下空壳
            File(requireInit(), bookId.toString()).deleteRecursively()
            // 封面按「{bookId}.xxx / {bookId}_xxx」全名前缀匹配：覆盖老式 {id}.jpg、
            // 内嵌备份 {id}_embedded.jpg、用户封面 {id}_{hash}.jpg 与残留 .tmp；
            // 用全名而非 nameWithoutExtension，否则 {id}_{hash} 会被漏删（用分隔符避免误删 70.jpg）
            coverDir()?.listFiles()
                ?.filter { it.name.startsWith("$bookId.") || it.name.startsWith("${bookId}_") }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            AppLog.put("删书图片清理失败 bookId=$bookId", e)
        }
    }

    // ── 内部 ──

    private fun coverDir(): File? = requireInit().parentFile?.let { File(it, "covers") }

    internal fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
