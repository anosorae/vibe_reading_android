package com.vibereading.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vibereading.app.data.local.dao.BookDao
import com.vibereading.app.data.local.dao.ChapterDao
import com.vibereading.app.data.local.dao.LlmProfileDao
import com.vibereading.app.data.local.entity.BookEntity
import com.vibereading.app.data.local.entity.ChapterEntity
import com.vibereading.app.data.local.entity.LlmProfileEntity

@Database(
    entities = [BookEntity::class, ChapterEntity::class, LlmProfileEntity::class],
    version = 15,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun llmProfileDao(): LlmProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v2→v3：分页模式进度持久化到「章内页」（滚动模式恒 0）。 */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN lastReadPage INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3→v4：章节翻译失败原因持久化（errorMessage）。 */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN errorMessage TEXT DEFAULT NULL")
            }
        }

        /**
         * v4→v5：把阅读进度语义切换为原文字符 offset。
         * 旧页码没有可靠的字符映射，按决策不转换；章节 ID 保留，offset 归零。
         * 迁移期间临时关闭 SQLite 外键检查，重建 books 表以移除旧页码列。
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys=OFF")
                db.execSQL("""
                    CREATE TABLE books_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        totalChapters INTEGER NOT NULL,
                        translatedChapters INTEGER NOT NULL,
                        lastReadChapterId INTEGER,
                        lastReadOffset INTEGER NOT NULL DEFAULT 0,
                        lastReadAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO books_new (
                        id, title, filePath, totalChapters, translatedChapters,
                        lastReadChapterId, lastReadOffset, lastReadAt, createdAt
                    )
                    SELECT id, title, filePath, totalChapters, translatedChapters,
                        lastReadChapterId, 0, lastReadAt, createdAt
                    FROM books
                """.trimIndent())
                db.execSQL("DROP TABLE books")
                db.execSQL("ALTER TABLE books_new RENAME TO books")
                db.execSQL("CREATE INDEX index_books_lastReadChapterId ON books(lastReadChapterId)")
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        /**
         * v5→v6：已翻译章节数改为实时派生，移除 books.translatedChapters 冗余列；
         * 同时为 chapters 增加 translationRunId，用于翻译任务的数据库级 stale 防护。
         */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys=OFF")
                db.execSQL("""
                    CREATE TABLE books_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        totalChapters INTEGER NOT NULL,
                        lastReadChapterId INTEGER,
                        lastReadOffset INTEGER NOT NULL DEFAULT 0,
                        lastReadAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO books_new (
                        id, title, filePath, totalChapters,
                        lastReadChapterId, lastReadOffset, lastReadAt, createdAt
                    )
                    SELECT id, title, filePath, totalChapters,
                        lastReadChapterId, lastReadOffset, lastReadAt, createdAt
                    FROM books
                """.trimIndent())
                db.execSQL("DROP TABLE books")
                db.execSQL("ALTER TABLE books_new RENAME TO books")
                db.execSQL("CREATE INDEX index_books_lastReadChapterId ON books(lastReadChapterId)")
                db.execSQL("ALTER TABLE chapters ADD COLUMN translationRunId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        /** v6→v7：新增 llm_profiles 表，支持多 LLM 配置切换。 */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS llm_profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL DEFAULT '',
                        apiKey TEXT NOT NULL DEFAULT '',
                        apiBase TEXT NOT NULL DEFAULT 'https://api.deepseek.com',
                        model TEXT NOT NULL DEFAULT 'deepseek-v4-flash',
                        chapterMaxChars INTEGER NOT NULL DEFAULT 60000,
                        enableContextBoost INTEGER NOT NULL DEFAULT 0,
                        contextChapters INTEGER NOT NULL DEFAULT 1,
                        contextMaxChars INTEGER NOT NULL DEFAULT 30000,
                        enableThinking INTEGER NOT NULL DEFAULT 0,
                        isActive INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /** v7→v8：llm_profiles 增加 temperature / topP 列。 */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_profiles ADD COLUMN temperature REAL NOT NULL DEFAULT 0.6")
                db.execSQL("ALTER TABLE llm_profiles ADD COLUMN topP REAL NOT NULL DEFAULT 1.0")
            }
        }

        /** v8→v9：llm_profiles 增加 enableExplainThinking 列，独立控制选词解释的思考模式。 */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_profiles ADD COLUMN enableExplainThinking INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v9→v10：阅读语言模式改为按书绑定，books 新增 languageMode 列，默认中文。 */
        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN languageMode TEXT NOT NULL DEFAULT 'zh'")
            }
        }

        /** v10→v11：llm_profiles 增加 autoTranslateNext 列，控制英文阅读时预译下一章。 */
        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_profiles ADD COLUMN autoTranslateNext INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v11→v12：books 增加 format（txt/epub）与 coverPath 列，支持 EPUB 导入（ADR-002）。 */
        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'txt'")
                db.execSQL("ALTER TABLE books ADD COLUMN coverPath TEXT DEFAULT NULL")
            }
        }

        /** v12→v13：books 增加 sourceLanguage（书籍原文语言）列，存量书默认中文（ADR-003）。 */
        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN sourceLanguage TEXT NOT NULL DEFAULT 'zh'")
            }
        }

        /** v13→v14：llm_profiles 增加 maxOutputTokens 列，翻译请求的最大输出 token 可配置。 */
        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE llm_profiles ADD COLUMN maxOutputTokens INTEGER NOT NULL DEFAULT 32768")
            }
        }

        /** v14→v15：移除「上下文增强翻译」三列（enableContextBoost/contextChapters/contextMaxChars），重建 llm_profiles 表。 */
        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys=OFF")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS llm_profiles_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        apiKey TEXT NOT NULL,
                        apiBase TEXT NOT NULL,
                        model TEXT NOT NULL,
                        chapterMaxChars INTEGER NOT NULL,
                        maxOutputTokens INTEGER NOT NULL,
                        enableThinking INTEGER NOT NULL,
                        enableExplainThinking INTEGER NOT NULL,
                        autoTranslateNext INTEGER NOT NULL,
                        temperature REAL NOT NULL,
                        topP REAL NOT NULL,
                        isActive INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO llm_profiles_new (
                        id, name, apiKey, apiBase, model, chapterMaxChars, maxOutputTokens,
                        enableThinking, enableExplainThinking, autoTranslateNext, temperature, topP, isActive
                    )
                    SELECT id, name, apiKey, apiBase, model, chapterMaxChars, maxOutputTokens,
                        enableThinking, enableExplainThinking, autoTranslateNext, temperature, topP, isActive
                    FROM llm_profiles
                """.trimIndent())
                db.execSQL("DROP TABLE llm_profiles")
                db.execSQL("ALTER TABLE llm_profiles_new RENAME TO llm_profiles")
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vibe_reading"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
