package com.vibereading.app.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.ReadingPosition
import com.vibereading.app.newTextMeasurer
import com.vibereading.app.testPageStyle
import com.vibereading.app.ui.reader.pagination.BookWindow
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「冻结杀进程后重开回到章节首页」的 UI 会话层反馈回路：忠实还原
 * ReaderScreen 的 window/pagerSession 接线时序——
 * 冷启动空状态（含 savedInstanceState 恢复的非零 initialPage）→ VM 恢复落到
 * 深 offset → 全书章节列表到达 → 当前章译文完成（分页指纹变化触发窗口重建）→
 * 用户翻页。
 *
 * 症状的判定标准：恢复之后任何一次 updateProgress 上报若早于恢复 offset
 * 所在页（尤其章节开头 offset=0），都会经 enqueueProgress 落库，
 * 在下一次冷启动时表现为「回到章节首页」。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
class ReaderPagerSessionRestoreTest {

    @get:Rule
    val compose = createComposeRule()

    private val contentW = 400f
    private val contentH = 800f

    private fun chapter(id: Long, index: Int, translated: String? = null) = Chapter(
        id = id, bookId = 1L, title = "第${index + 1}章", chapterIndex = index,
        content = (0 until 60).joinToString("\n\n") { p -> "第${index}章第${p}段。" + "文字内容".repeat(30) },
        translatedContent = translated,
        status = if (translated != null) Chapter.STATUS_DONE else Chapter.STATUS_PENDING
    )

    /** 泵足组合帧时钟后轮询条件；返回是否在超时内满足。 */
    private fun seekDoneWithin(timeoutMs: Long, probe: () -> String): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            Thread.sleep(50)
            if (probe().isEmpty()) return true
        }
        return false
    }

    @Test
    fun coldOpenRestoreSequence_neverReportsChapterStartOffset() {
        val measurer = newTextMeasurer(Density(1f))
        val pageStyle = testPageStyle()
        val c1 = chapter(11L, 0)
        val c2 = chapter(12L, 1)
        val c3 = chapter(13L, 2)
        val deepOffset = c2.content.indexOf("第1章第40段")
        assertTrue(deepOffset > 0)

        val reported = CopyOnWriteArrayList<Pair<Long, Int>>()
        val navigated = CopyOnWriteArrayList<Pair<Long, Int>>()
        val stateFlow = MutableStateFlow(ReaderUiState())
        val pagerRef = arrayOfNulls<PagerState>(1)
        val windowRef = arrayOfNulls<BookWindow>(1)
        val sessionRef = arrayOfNulls<ReaderPagerSession>(1)
        val scopeRef = arrayOfNulls<kotlinx.coroutines.CoroutineScope>(1)

        compose.setContent {
            val state by stateFlow.collectAsState()
            // 与 ReaderScreen 相同的分页指纹：当前章 id + 译文长度
            val fingerprint = remember(state.chapters) {
                state.chapters.find { it.id == state.activeChapterId }
                    ?.let { "${it.id}:${it.translatedContent?.length ?: -1}" }.orEmpty()
            }
            val window = remember(measurer, state.mode, state.sourceLanguage, fingerprint, true) {
                BookWindow(
                    chapters = state.chapters, style = pageStyle, mode = state.mode,
                    sourceLanguage = state.sourceLanguage,
                    contentWidthPx = contentW, contentHeightPx = contentH,
                    measurer = measurer, backgroundMeasurer = { measurer },
                    displayDensity = 1f, displayFontScale = 1f
                )
            }
            windowRef[0] = window
            SideEffect { window.updateChapterSource(state.chapters) }
            val pagerState = rememberPagerState(initialPage = 19) { window.pageCount }
            pagerRef[0] = pagerState
            val scope = rememberCoroutineScope()
            scopeRef[0] = scope
            val session = rememberReaderPagerSession(
                pagerState, scope, window, pageStyle, contentW, contentH,
                true, state,
                { id, off -> reported += id to off },
                { id, off -> navigated += id to off }
            )
            sessionRef[0] = session
            // 挂真实 Pager：保证 scrollToPage / 翻页上报路径与生产一致（页面内容本身与本测试无关）
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                Box(Modifier.fillMaxSize())
            }
        }

        // ── Phase A：冷启动首帧（恢复未就绪，等价进程被杀后重开的第一组合）──
        compose.waitForIdle()
        assertEquals(null, stateFlow.value.activeChapterId)

        // ── Phase B：VM 恢复完成（首屏单章 + 深处 offset）──
        stateFlow.value = ReaderUiState(
            chapters = listOf(c2), activeChapterId = 12L, activeChapter = c2,
            position = ReadingPosition(12L, deepOffset),
            restoreReady = true, chaptersLoaded = false
        )
        val seekOk = seekDoneWithin(15_000) {
            if (sessionRef[0]?.initialSeekDone == true) "" else
                "seekDone=${sessionRef[0]?.initialSeekDone} sliding=${sessionRef[0]?.windowSliding} " +
                    "pages=${windowRef[0]?.pageCount} center=${windowRef[0]?.centerChapterId} cur=${pagerRef[0]?.currentPage}"
        }
        assertTrue("seek 未完成：$seekOk", seekOk)
        val pageAfterRestore = pagerRef[0]!!.currentPage
        val rangeAfterRestore = windowRef[0]!!.offsetOfPage(pageAfterRestore)
        assertNotNull("恢复后当前页应有原文范围", rangeAfterRestore)
        assertTrue(
            "恢复后应落在覆盖保存 offset 的页（offset=$deepOffset, page=$pageAfterRestore, range=$rangeAfterRestore）",
            deepOffset >= rangeAfterRestore!!.first && deepOffset < rangeAfterRestore.last
        )

        // ── Phase C：全书章节列表到达（窗口对象不变，索引空间扩展）──
        stateFlow.value = stateFlow.value.copy(
            chapters = listOf(c1, c2, c3), chaptersLoaded = true
        )
        compose.waitForIdle()
        Thread.sleep(200)
        compose.waitForIdle()
        val rangeAfterFullList = windowRef[0]!!.offsetOfPage(pagerRef[0]!!.currentPage)
        assertNotNull(rangeAfterFullList)
        assertTrue(
            "全书列表到达后不应离开恢复页（range=$rangeAfterFullList）",
            deepOffset >= rangeAfterFullList!!.first && deepOffset < rangeAfterFullList.last
        )

        // ── Phase D：当前章译文完成（指纹变化 → 窗口重建，会话存续）──
        val translatedC2 = c2.copy(
            translatedContent = (0 until 60).joinToString("\n\n") { p -> "[${p + 1}] Translation of paragraph $p with some length." },
            status = Chapter.STATUS_DONE
        )
        stateFlow.value = stateFlow.value.copy(
            chapters = listOf(c1, translatedC2, c3), activeChapter = translatedC2
        )
        val translationSeekOk = seekDoneWithin(15_000) {
            // 等待窗口重建后的会话稳定（sliding 结束）
            if (sessionRef[0]?.windowSliding == false &&
                windowRef[0]!!.centerChapterId == 12L
            ) "" else "sliding=${sessionRef[0]?.windowSliding} center=${windowRef[0]?.centerChapterId}"
        }
        assertTrue("译文完成后的窗口重建未稳定：$translationSeekOk", translationSeekOk)
        val rangeAfterTranslation = windowRef[0]!!.offsetOfPage(pagerRef[0]!!.currentPage)
        assertNotNull("译文完成后当前页应有原文范围", rangeAfterTranslation)

        // ── Phase E：等后台续排/重映射彻底稳定，再模拟用户翻页 ──
        val settleOk = seekDoneWithin(15_000) {
            val stable = run {
                val a = pagerRef[0]!!.currentPage
                Thread.sleep(150)
                val b = pagerRef[0]!!.currentPage
                a == b
            }
            val complete = windowRef[0]!!.isChapterLayoutComplete(12L)
            if (stable && complete && sessionRef[0]!!.windowSliding == false) "" else
                "stable=$stable complete=$complete sliding=${sessionRef[0]?.windowSliding} cur=${pagerRef[0]?.currentPage}"
        }
        assertTrue("窗口排版/重映射未稳定：$settleOk", settleOk)
        val before = pagerRef[0]!!.currentPage
        val pageCountNow = windowRef[0]!!.pageCount
        val target = if (before + 1 < pageCountNow) before + 1 else before - 1
        assertTrue("需要可翻的相邻页（before=$before, pageCount=$pageCountNow）", target >= 0 && target != before)
        reported.clear()
        compose.runOnUiThread {
            scopeRef[0]!!.launch { pagerRef[0]!!.scrollToPage(target) }
        }
        val turnOk = seekDoneWithin(20_000) { if (reported.isNotEmpty()) "" else "cur=${pagerRef[0]!!.currentPage}" }
        val turnDump = "cur=${pagerRef[0]!!.currentPage} target=$target sliding=${sessionRef[0]!!.windowSliding} " +
            "seekDone=${sessionRef[0]!!.initialSeekDone} chapterOfTarget=${windowRef[0]!!.chapterOfPage(target)} " +
            "offsetOfTarget=${windowRef[0]!!.offsetOfPage(target)} complete=${windowRef[0]!!.isChapterLayoutComplete(12L)} " +
            "pageCount=${windowRef[0]!!.pageCount}"
        assertTrue("翻页后应有进度上报（$turnDump）", turnOk)

        // ── 判定 2：整个时序中不允许出现「回到章节开头」的上报 ──
        // 恢复之后（B 之后）的任何 updateProgress 若上报 offset=0 或远小于恢复 offset，
        // 都会在下一次冷启动时表现为章节首页。
        val badReports = reported.filter { (id, off) -> id == 12L && off < rangeAfterRestore.first }
        assertEquals(
            "恢复后出现把进度打回章节开头的上报：$reported",
            emptyList<Pair<Long, Int>>(), badReports
        )
    }
}
