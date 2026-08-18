package com.vibereading.app.data.dict

import android.database.sqlite.SQLiteDatabase
import com.vibereading.app.domain.model.DictEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 内嵌词典查询单测：用内存 SQLite 按一致 schema（word 小写主键 + 四列）注入，
 * 验证大小写归一、标点清理、撇号后缀与未收录处理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictDatabaseTest {

    private lateinit var db: SQLiteDatabase
    private lateinit var dict: DictDatabase

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null) // 内存库
        db.execSQL(
            "CREATE TABLE dict (word TEXT PRIMARY KEY, phonetic TEXT, " +
                "translation TEXT, pos TEXT)"
        )
        // 词条以小写存储（与构建脚本一致）
        val rows = listOf(
            arrayOf("abandon", "ə'bændən", "vt. 放弃, 抛弃\nn. 放任", null),
            arrayOf("abandoned", null, "a. 被抛弃的", null),
            arrayOf("dog", null, "n. 狗", null),
            arrayOf("dogs", null, "pl. 狗", null),
            arrayOf("atm", null, "abbr. 自动取款机", null),
            arrayOf("alfred", null, "n. 阿尔弗雷德", null),
            arrayOf("well-known", null, "a. 著名的", "j:100"),
            arrayOf("as soon as", null, "一…就…", null),
            arrayOf("n't", null, "", null)
        )
        rows.forEach { db.execSQL("INSERT INTO dict VALUES (?,?,?,?)", it) }
        dict = DictDatabase.forTesting(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `exact word lookup returns entry`() {
        val entry = dict.lookup("abandon")
        assertNotNull(entry)
        assertEquals("abandon", entry!!.word)
        assertEquals("ə'bændən", entry.phonetic)
        assertEquals("vt. 放弃, 抛弃\nn. 放任", entry.translation)
    }

    @Test
    fun `case insensitive lookup via lowercase normalization`() {
        assertEquals("abandon", dict.lookup("Abandon")!!.word)
        assertEquals("abandon", dict.lookup("ABANDON")!!.word)
        assertEquals("abandon", dict.lookup("aBaNdOn")!!.word)
    }

    @Test
    fun `uppercase stored words are reachable in any case`() {
        // 构建期已小写存储：atm / alfred 任意大小写均可命中
        assertEquals("atm", dict.lookup("ATM")!!.word)
        assertEquals("atm", dict.lookup("atm")!!.word)
        assertEquals("alfred", dict.lookup("Alfred")!!.word)
    }

    @Test
    fun `trailing punctuation is trimmed before lookup`() {
        assertEquals("abandon", dict.lookup("abandon,")!!.word)
        assertEquals("abandon", dict.lookup("abandon.")!!.word)
        assertEquals("abandon", dict.lookup("\"abandon\"")!!.word)
        assertEquals("abandon", dict.lookup("（abandon）")!!.word)
    }

    @Test
    fun `apostrophe suffix falls back to root`() {
        assertEquals("dog", dict.lookup("dog's")!!.word)
        assertEquals("dogs", dict.lookup("dogs'")!!.word)
    }

    @Test
    fun `multi word phrase lookup works`() {
        assertEquals("as soon as", dict.lookup("as soon as")!!.word)
        assertEquals("well-known", dict.lookup("Well-Known")!!.word)
    }

    @Test
    fun `unknown and empty words return null`() {
        assertNull(dict.lookup("zzzznope"))
        assertNull(dict.lookup(""))
        assertNull(dict.lookup("   "))
        assertNull(dict.lookup("..."))
        // 词条有值但释义为空时仍返回条目（对应"未收录"只在无词条时出现）
        assertNotNull(dict.lookup("n't"))
    }

    @Test
    fun `chinese word returns null`() {
        assertNull(dict.lookup("世界"))
    }
}