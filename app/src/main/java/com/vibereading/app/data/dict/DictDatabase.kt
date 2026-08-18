package com.vibereading.app.data.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.vibereading.app.domain.model.DictEntry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 内嵌 ECDICT 精简版词典（SQLite，只读）。
 *
 * 数据源为构建产物 `assets/dict/ecdict.db.gz`（tools/build_dict_db.py 从基础版 CSV
 * 生成，构建时按「词频存在或词长≤14」裁剪为约 50 万词条并统一小写存储；gzip 预压缩后
 * APK 以 noCompress 原样打包，约 19MB，避免 AAPT 默认 deflate 压缩膨胀）。
 * 首次查询时把 gz 资产解压到 `databases/ecdict.db` 再以只读方式打开，之后直接复用。
 * 打开是惰性的（`by lazy`）：构造实例不阻塞主线程，首次查词才解压+打开，
 * 调用方应保证在 IO 线程触发。
 *
 * 词条小写存储 + 查询时小写归一：BINARY 主键等代价覆盖任意大小写查询，无 NOCASE 索引。
 * 不经过 Room：asset 库无 Room 注解/迁移管理，直接 SQLiteDatabase 更简单。
 */
class DictDatabase private constructor(
    private val context: Context?,
    private val injected: SQLiteDatabase?
) {
    private var opened = false
    private val database: SQLiteDatabase by lazy {
        opened = true
        injected ?: openDb(requireNotNull(context) { "DictDatabase 缺少 Context" })
    }

    companion object {
        // AGP 会对 .gz 资产自动解压，故扩展名用 .dict（内容为 gzip + 自定义尺寸头）
        private const val ASSET_PATH = "dict/ecdict.dict"

        /** 从 asset 打开（惰性：首次 lookup 才解压+打开，应在 IO 线程调用）。 */
        fun open(context: Context): DictDatabase = DictDatabase(context, null)

        /** 单测注入：直接用测试库打开。 */
        internal fun forTesting(database: SQLiteDatabase): DictDatabase =
            DictDatabase(null, database)

        /** 选词可能带上的首尾标点（引号、括号、逗号、句号等）。 */
        private val TRAILING_PUNCTUATION = charArrayOf(
            ' ', '"', '\'', '`', '‘', '’', '「', '」',
            ',', '.', ';', ':', '!', '?', '…',
            '(', ')', '[', ']', '{', '}', '〈', '〉',
            '（', '）', '【', '】'
        )

        /**
         * 读取 gz 自定义头里携带的解压后字节数（构建脚本在 FEXTRA 字段写入 4 字节 LE）。
         * 只读 asset 前 ~16 字节；无该字段时返回 null（退化为「目标存在即复用」）。
         */
        private fun gzipExpectedSize(context: Context): Long? {
            return try {
                context.assets.open(ASSET_PATH).use { ins ->
                    val head = ByteArray(12)
                    if (!readFully(ins, head, 12)) return@use null
                    if (head[0] != 0x1f.toByte() || head[1] != 0x8b.toByte() ||
                        head[2] != 0x08.toByte()
                    ) return@use null
                    val flag = head[3].toInt() and 0xff
                    if (flag and 0x04 == 0) return@use null // 无 FEXTRA
                    val xlen = (head[10].toInt() and 0xff) or
                        ((head[11].toInt() and 0xff) shl 8)
                    if (xlen < 4) return@use null
                    val extra = ByteArray(4)
                    if (!readFully(ins, extra, 4)) return@use null
                    (extra[0].toInt() and 0xff).toLong() or
                        ((extra[1].toLong() and 0xff) shl 8) or
                        ((extra[2].toLong() and 0xff) shl 16) or
                        ((extra[3].toLong() and 0xff) shl 24)
                }
            } catch (_: IOException) {
                null
            }
        }

        private fun readFully(ins: InputStream, buf: ByteArray, len: Int): Boolean {
            var off = 0
            while (off < len) {
                val r = ins.read(buf, off, len - off)
                if (r < 0) return false
                off += r
            }
            return true
        }

        /**
         * 目标库缺失或与 gz 头声明的解压大小不一致时重新解压（原子替换，幂等）。
         * 大小一致说明资产未更新，直接复用磁盘上的库。
         */
        private fun extractAsset(context: Context, target: File) {
            val expected = gzipExpectedSize(context)
            if (expected != null && target.exists() && target.length() == expected) return
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { output ->
                GZIPInputStream(context.assets.open(ASSET_PATH)).use { input ->
                    input.copyTo(output)
                }
            }
            if (expected != null) {
                check(tmp.length() == expected) { "词典库解压大小与声明不一致" }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "词典库解压失败" }
            }
        }
    }

    /** 按词查词典：先做展示文本规范化（去首尾标点），再小写化，最后尝试撇号后缀。 */
    fun lookup(raw: String): DictEntry? {
        var word = raw.trim().lowercase()
        if (word.isEmpty()) return null
        word = word.trim(*TRAILING_PUNCTUATION)
        if (word.isEmpty()) return null
        query(word)?.let { return it }
        // 撇号所有格/缩写后缀：governments' → governments；dog's → dog
        if (word.endsWith("'s")) {
            query(word.dropLast(2))?.let { return it }
        }
        if (word.endsWith('\'')) {
            query(word.dropLast(1))?.let { return it }
        }
        return null
    }

    private fun openDb(context: Context): SQLiteDatabase {
        val target = context.getDatabasePath("ecdict.db")
        extractAsset(context, target)
        return SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun query(word: String): DictEntry? {
        // 词条以小写存储，BINARY 主键等代价覆盖任意大小写查询
        database.rawQuery(
            "SELECT word, phonetic, translation, pos FROM dict " +
                "WHERE word = ? LIMIT 1",
            arrayOf(word)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return DictEntry(
                word = cursor.getString(0),
                phonetic = cursor.getString(1)?.takeIf { it.isNotBlank() },
                translation = cursor.getString(2)?.takeIf { it.isNotBlank() },
                pos = cursor.getString(3)?.takeIf { it.isNotBlank() }
            )
        }
    }

    fun close() {
        if (opened) database.close()
    }
}