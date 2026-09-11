package com.vibereading.app.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 封面落盘的命名约定、降采样、EXIF 旋正与删书清理。
 *
 * 必须用 NATIVE 图形模式：LEGACY 模式下 BitmapFactory 走 Robolectric 的位图影子缓存，
 * 解码结果会被其他测试污染（实测全量跑时返回默认 100×100 位图）。
 *
 * 注意：BookImageStore 是 object 单例且 [BookImageStore.init] 只生效一次，
 * 而 Robolectric 每个测试方法会重建 Application（filesDir 随之变化），
 * 所以全部断言放在同一个测试方法里，且读写一律经 [BookImageStore.coverFile]
 * 解析真实路径，不假设 filesDir。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookImageStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 封面目录：用真实的封面相对路径解析，不假设 filesDir 布局（coverFile 以 filesDir 为基准）。 */
    private val coversDir: File get() = BookImageStore.coverFile("covers/probe.jpg").parentFile!!

    @Before
    fun setUp() {
        BookImageStore.init(context)
    }

    @Test
    fun `cover naming downsampling exif and delete cleanup`() {
        // ── 1. 用户封面：降采样到最长边 1600，文件名带内容哈希 ──
        val big = writeJpeg(File(context.cacheDir, "big.jpg"), 3200, 2400)
        val userPath = BookImageStore.saveUserCover(1L, big)
        assertNotNull("用户封面应保存成功", userPath)
        assertTrue(
            "用户封面路径应为 covers/{bookId}_{16位内容哈希}.jpg，实际=$userPath",
            Regex("""^covers/1_[0-9a-f]{16}\.jpg$""").matches(userPath!!)
        )
        val userBitmap = decode(userPath)
        assertEquals("最长边应精确降到 1600", 1600, max(userBitmap.width, userBitmap.height))
        assertEquals("应保持原始宽高比 4:3", 1200, userBitmap.height)
        userBitmap.recycle()

        // 同内容重复保存 → 同一文件名（内容哈希天然去重，书架靠路径变化触发重新解码）
        assertEquals("同内容应幂等到同一路径", userPath, BookImageStore.saveUserCover(1L, big))

        // ── 2. 内嵌封面：固定 _embedded 命名，供「恢复原封面」回退 ──
        val small = writeJpeg(File(context.cacheDir, "small.jpg"), 200, 300)
        val embeddedPath = BookImageStore.saveEmbeddedCover(2L, small.readBytes())
        assertEquals("covers/2_embedded.jpg", embeddedPath)
        assertEquals(BookImageStore.embeddedCoverPath(2L), embeddedPath)
        assertTrue("应被识别为内嵌封面", BookImageStore.isEmbeddedCover(embeddedPath!!))

        // ── 3. 「恢复原封面」判据：当前是用户封面 且 内嵌备份存在 ──
        assertFalse("无封面时不可恢复", BookImageStore.canRestoreEmbeddedCover(2L, null))
        assertFalse("当前就是内嵌封面时不可恢复", BookImageStore.canRestoreEmbeddedCover(2L, embeddedPath))
        assertFalse("内嵌备份不存在时不可恢复", BookImageStore.canRestoreEmbeddedCover(99L, "covers/99_x.jpg"))

        // ── 4. 替换用户封面不得触碰内嵌备份 ──
        BookImageStore.saveEmbeddedCover(3L, small.readBytes())
        val userPath3 = BookImageStore.saveUserCover(3L, big)!!
        val embedded3 = BookImageStore.embeddedCoverPath(3L)
        assertTrue("内嵌备份应仍在（替换只写不删）", BookImageStore.coverFile(embedded3).exists())
        assertTrue("此时可恢复原封面", BookImageStore.canRestoreEmbeddedCover(3L, userPath3))

        // ── 5. EXIF 方向：竖拍照片（orientation=6）应被旋正 ──
        val rotated = writeJpeg(File(context.cacheDir, "rotated.jpg"), 200, 100, orientation = 6)
        assertEquals(
            "测试前置：EXIF 方向应已写入",
            6,
            ExifInterface(rotated.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        )
        val rotatedPath = BookImageStore.saveUserCover(4L, rotated)!!
        val rotatedBitmap = decode(rotatedPath)
        assertEquals("orientation=6 应旋转 90°，宽高互换", 100, rotatedBitmap.width)
        assertEquals("orientation=6 应旋转 90°，宽高互换", 200, rotatedBitmap.height)
        rotatedBitmap.recycle()

        // ── 6. 删书清理：按 {bookId}. / {bookId}_ 全名前缀匹配 ──
        File(coversDir, "7.jpg").writeBytes(byteArrayOf(1))          // 老版本遗留命名
        File(coversDir, "7_embedded.jpg").writeBytes(byteArrayOf(1))
        File(coversDir, "7_abcdef0123456789.jpg").writeBytes(byteArrayOf(1))
        File(coversDir, "7.jpg.tmp").writeBytes(byteArrayOf(1))      // 残留临时文件
        File(coversDir, "70.jpg").writeBytes(byteArrayOf(1))         // bookId=7 不得误删 70
        File(coversDir, "8_abcdef0123456789.jpg").writeBytes(byteArrayOf(1))

        BookImageStore.deleteBookFiles(7L)

        listOf("7.jpg", "7_embedded.jpg", "7_abcdef0123456789.jpg", "7.jpg.tmp").forEach {
            assertFalse("删书应清理 $it", File(coversDir, it).exists())
        }
        assertTrue("不得误删 70.jpg", File(coversDir, "70.jpg").exists())
        assertTrue("不得误删其他书的封面", File(coversDir, "8_abcdef0123456789.jpg").exists())

        // ── 7. 单文件删除（替换/恢复时清理旧用户封面）──
        assertTrue(BookImageStore.coverExists(userPath))
        BookImageStore.deleteCover(userPath)
        assertFalse("deleteCover 应删除目标文件", BookImageStore.coverExists(userPath))
    }

    private fun decode(coverPath: String): Bitmap =
        BitmapFactory.decodeFile(BookImageStore.coverFile(coverPath).absolutePath)
            ?: error("封面解码失败: $coverPath")

    /** 生成一张纯色 JPEG；[orientation] 非 0 时写入 EXIF 方向标签。 */
    private fun writeJpeg(target: File, width: Int, height: Int, orientation: Int = 0): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        if (orientation != 0) {
            ExifInterface(target.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return target
    }
}
