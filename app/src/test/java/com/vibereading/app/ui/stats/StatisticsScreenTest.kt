package com.vibereading.app.ui.stats

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.newInMemoryDb
import com.vibereading.app.seedBookAndChapters
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 统计页冒烟：空库引导与有书概览（数据经真实 Room 内存库流出，断言关键文案）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatisticsScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `空书库显示引导文案`() {
        val db = newInMemoryDb()
        try {
            val vm = StatisticsViewModel(BookRepository(db.bookDao()))
            compose.setContent {
                MaterialTheme { StatisticsScreen(vm = vm, onOpenBook = {}) }
            }
            // Room 流在 IO 线程首发，等空库引导出现再断言，避免与加载态竞态
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("还没有可统计的内容").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("还没有可统计的内容").assertIsDisplayed()
        } finally {
            db.close()
        }
    }

    @Test
    fun `有书时显示标题与概览磁贴`() {
        runBlocking {
        val db = newInMemoryDb()
        try {
            seedBookAndChapters(db, bookId = 1L, chapterCount = 3)
            markAllChaptersDone(db)
            val vm = StatisticsViewModel(BookRepository(db.bookDao()))
            compose.setContent {
                MaterialTheme { StatisticsScreen(vm = vm, onOpenBook = {}) }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("藏书").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("统计").assertIsDisplayed()
            compose.onNodeWithText("藏书").assertIsDisplayed()
            // 3 章全部 DONE → 覆盖率 100%
            compose.onNodeWithText("100%").assertIsDisplayed()
        } finally {
            db.close()
        }
        }
    }

    private suspend fun markAllChaptersDone(db: AppDatabase) {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE chapters SET status = ${Chapter.STATUS_DONE}, translatedContent = '译文'"
        )
    }
}
