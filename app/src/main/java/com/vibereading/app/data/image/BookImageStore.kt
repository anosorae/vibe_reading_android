package com.vibereading.app.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import com.vibereading.app.log.AppLog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 书籍图片资源单一数据源（ADR-002 D3）：
 *
 * - 插图：导入期解压到 `filesDir/books/{bookId}/images/{md5}.{ext}`，
 *   链接键为 `{bookId}/{fileName}`，运行时经 [imageFile] 解析；
 * - 封面：`filesDir/covers/{bookId}.jpg`，路径存 books.coverPath；
 * - 内存 LRU 位图缓存（按目标宽度降采样解码），删书时同步清理磁盘。
 */
object BookImageStore {

    private lateinit var baseDir: File
    private val bitmapCache = object : LruCache<String, Bitmap>(48) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
        // 逐出不 recycle：UI 可能仍持有引用（LazyColumn 复用/卷页快照），交给 GC 回收
    }
    private val decoding = ConcurrentHashMap<String, Any>()

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

    /** 保存封面字节，返回可持久化到 books.coverPath 的相对路径；失败返回 null。 */
    fun saveCover(bookId: Long, bytes: ByteArray): String? {
        try {
            val coversDir = File(requireInit().parentFile, "covers").apply { mkdirs() }
            // 统一转 jpg 存储（EPUB 封面多为 jpg/png；png 透明通道在 JPEG 中铺黑底可接受）
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                AppLog.put("封面解码失败 bookId=$bookId")
                return null
            }
            val target = File(coversDir, "$bookId.jpg")
            val tmp = File(coversDir, "$bookId.tmp")
            FileOutputStream(tmp).use { decoded.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            decoded.recycle()
            return if (tmp.renameTo(target)) "covers/$bookId.jpg" else { tmp.delete(); null }
        } catch (e: Exception) {
            AppLog.put("封面保存失败 bookId=$bookId", e)
            return null
        }
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

    /** 封面相对路径（如 "covers/7.jpg"）→ 磁盘文件；基准是 filesDir，与 [saveCover] 一致。 */
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
            coverDir()?.listFiles()
                ?.filter { it.nameWithoutExtension == bookId.toString() }
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
