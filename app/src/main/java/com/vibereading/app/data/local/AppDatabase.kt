package com.vibereading.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.vibereading.app.data.local.dao.BookDao
import com.vibereading.app.data.local.dao.ChapterDao
import com.vibereading.app.data.local.entity.BookEntity
import com.vibereading.app.data.local.entity.ChapterEntity

@Database(
    entities = [BookEntity::class, ChapterEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao

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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vibe_reading"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
