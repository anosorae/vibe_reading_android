package com.vibereading.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room 迁移回归测试：
 * - 5→6 手工建 v5 库 → 执行 MIGRATION_5_6 → 用 Room 打开（校验迁移后 schema 与实体一致，
 *   identity hash 不匹配会抛 IllegalStateException，测试失败）；
 * - 2→3→4→5→6 全链逐个执行迁移对象，验证数据保留、列变更与外键完整性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migrate5To6_dropsTranslatedColumn_andAddsRunId() {
        val dbName = "migrate-5-6"
        createV5Database(context, dbName)
        try {
            // 用 Room + MIGRATION_5_6 打开：Room 严格校验迁移结果与当前实体 schema 一致
            val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .addMigrations(AppDatabase.MIGRATION_5_6)
                .build()
            runBlocking {
                val book = db.bookDao().getBookById(1L)!!
                assertEquals("测试书", book.title)
                assertEquals(2, book.totalChapters)
                val chapter = db.chapterDao().getChapterById(1L, 1L)!!
                assertEquals("EN A", chapter.translatedContent)
                assertEquals(2, chapter.status)
                assertEquals(0L, chapter.translationRunId)
                assertEquals(1, db.bookDao().getBooksWithProgress().first().size)
            }
            // 冗余列已移除，新列已加入
            val sqlite = db.openHelper.writableDatabase
            assertFalse("translatedChapters 应被移除", "translatedChapters" in columns(sqlite, "books"))
            assertTrue("translationRunId 应存在", "translationRunId" in columns(sqlite, "chapters"))
            assertNoForeignKeyViolations(sqlite)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migrate2To6_fullChain_preservesData() {
        val dbName = "migrate-2-6"
        val db = createV2Database(context, dbName)

        AppDatabase.MIGRATION_2_3.migrate(db)
        AppDatabase.MIGRATION_3_4.migrate(db)
        AppDatabase.MIGRATION_4_5.migrate(db)
        AppDatabase.MIGRATION_5_6.migrate(db)

        try {
            // v5→v6 后：lastReadPage（v3 加入）被 lastReadOffset 替代，translatedChapters 被移除
            val bookColumns = columns(db, "books")
            assertFalse("lastReadPage 应被移除", "lastReadPage" in bookColumns)
            assertFalse("translatedChapters 应被移除", "translatedChapters" in bookColumns)
            assertTrue("lastReadOffset 应存在", "lastReadOffset" in bookColumns)
            val chapterColumns = columns(db, "chapters")
            assertTrue("errorMessage 应存在", "errorMessage" in chapterColumns)
            assertTrue("translationRunId 应存在", "translationRunId" in chapterColumns)

            // 数据保留：进度 offset 归零（旧页码不转换），译文/状态保留
            db.query("SELECT lastReadChapterId, lastReadOffset, lastReadAt FROM books WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals(0, c.getInt(1))
                assertEquals(1000L, c.getLong(2))
            }
            db.query("SELECT translatedContent, status, translationRunId, errorMessage FROM chapters WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("EN A", c.getString(0))
                assertEquals(2, c.getInt(1))
                assertEquals(0, c.getInt(2))
                assertNull(c.getString(3))
            }
            assertNoForeignKeyViolations(db)
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }

    /** 手工建 v5 库（结构对齐导出的 5.json；缺 room_master_table，Room 打开时执行迁移并校验）。 */
    private fun createV5Database(context: Context, name: String): SupportSQLiteDatabase {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE books (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "title TEXT NOT NULL, filePath TEXT NOT NULL, " +
                            "totalChapters INTEGER NOT NULL, translatedChapters INTEGER NOT NULL, " +
                            "lastReadChapterId INTEGER, lastReadOffset INTEGER NOT NULL DEFAULT 0, " +
                            "lastReadAt INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE chapters (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "bookId INTEGER NOT NULL, title TEXT NOT NULL, section TEXT, " +
                            "chapterIndex INTEGER NOT NULL, content TEXT NOT NULL, " +
                            "translatedContent TEXT, status INTEGER NOT NULL, errorMessage TEXT, " +
                            "FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE)"
                    )
                    db.execSQL("CREATE INDEX index_books_lastReadChapterId ON books(lastReadChapterId)")
                    db.execSQL("CREATE INDEX index_chapters_bookId ON chapters(bookId)")
                    db.execSQL(
                        "INSERT INTO books (id, title, filePath, totalChapters, translatedChapters, lastReadChapterId, lastReadOffset, lastReadAt, createdAt) " +
                            "VALUES (1, '测试书', '/sdcard/a.txt', 2, 1, 1, 42, 1000, 2000)"
                    )
                    db.execSQL(
                        "INSERT INTO chapters (id, bookId, title, section, chapterIndex, content, translatedContent, status, errorMessage) " +
                            "VALUES (1, 1, '第一章', NULL, 0, '正文A', 'EN A', 2, NULL)"
                    )
                    db.execSQL(
                        "INSERT INTO chapters (id, bookId, title, section, chapterIndex, content, translatedContent, status, errorMessage) " +
                            "VALUES (2, 1, '第二章', NULL, 1, '正文B', NULL, 0, NULL)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return factory.create(configuration).writableDatabase
    }

    private fun createV2Database(context: Context, name: String): SupportSQLiteDatabase {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // v2 结构：books 无 lastReadPage（v3 加入）；chapters 无 errorMessage（v4 加入）
                    db.execSQL(
                        "CREATE TABLE books (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "title TEXT NOT NULL, filePath TEXT NOT NULL, " +
                            "totalChapters INTEGER NOT NULL, translatedChapters INTEGER NOT NULL, " +
                            "lastReadChapterId INTEGER, lastReadAt INTEGER NOT NULL, " +
                            "createdAt INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE chapters (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "bookId INTEGER NOT NULL, title TEXT NOT NULL, section TEXT, " +
                            "chapterIndex INTEGER NOT NULL, content TEXT NOT NULL, " +
                            "translatedContent TEXT, status INTEGER NOT NULL, " +
                            "FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE)"
                    )
                    db.execSQL("CREATE INDEX index_chapters_bookId ON chapters(bookId)")
                    db.execSQL(
                        "INSERT INTO books (id, title, filePath, totalChapters, translatedChapters, lastReadChapterId, lastReadAt, createdAt) " +
                            "VALUES (1, '测试书', '/sdcard/a.txt', 2, 1, 1, 1000, 2000)"
                    )
                    db.execSQL(
                        "INSERT INTO chapters (id, bookId, title, section, chapterIndex, content, translatedContent, status) " +
                            "VALUES (1, 1, '第一章', NULL, 0, '正文A', 'EN A', 2)"
                    )
                    db.execSQL(
                        "INSERT INTO chapters (id, bookId, title, section, chapterIndex, content, translatedContent, status) " +
                            "VALUES (2, 1, '第二章', NULL, 1, '正文B', NULL, 0)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return factory.create(configuration).writableDatabase
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> {
        val result = mutableSetOf<String>()
        db.query("PRAGMA table_info($table)").use { c ->
            while (c.moveToNext()) result.add(c.getString(1))
        }
        return result
    }

    private fun assertNoForeignKeyViolations(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { c ->
            val violations = c.count
            assertTrue("存在外键约束违规 ($violations 条)", violations == 0)
        }
    }
}
