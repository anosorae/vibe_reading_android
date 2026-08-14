package com.vibereading.app.ui.reader.pagination

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextMeasurer
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.ui.reader.components.parseBilingualParagraphs
import com.vibereading.app.ui.reader.components.splitParagraphs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 分页器索引空间内的一个页面：定位到（章, 章内页）。 */
data class WindowPage(val chapterId: Long, val pageInChapter: Int)

/**
 * 章窗口模型（ADR-001 D1）：持有当前章 ±1 的 [ChapterPaginator]，
 * 分页器索引空间 = 窗口内章节页面的扁平列表 [windowPages]（响应式，驱动 Pager pageCount）。
 *
 * - 阅读跨入新章时由调用方 `recenterSync` 滑动窗口（索引空间重映射，视觉页不变）；
 * - `preloadNeighbors` 在后台预载 center±2，滑动即时；
 * - 放弃「真全局页索引」：窗口内前缀和是诚实实现（远跳 O(1)，不铺全书）。
 */
class BookWindow(
    val chapters: List<Chapter>,
    private val style: PageStyle,
    private val mode: String,
    private val contentWidthPx: Float,
    private val contentHeightPx: Float,
    private val measurer: TextMeasurer,           // 主线程测量
    private val backgroundMeasurer: () -> TextMeasurer, // 后台预载测量（每章独立实例）
    private val displayDensity: Float = 1f        // 用于 dp→px 转换
) {

    // 已排版章节：chapterId -> paginator（窗口章 + 预载外缘章）
    private val paginators = HashMap<Long, ChapterPaginator>()
    private val lock = Any()

    /** 窗口中心章 id（最近一次 recenter 的目标）。 */
    var centerChapterId: Long? by mutableStateOf(null)
        private set

    /** 分页器索引空间 = 窗口 [center-1, center, center+1] 的扁平页列表。 */
    var windowPages by mutableStateOf<List<WindowPage>>(emptyList())
        private set

    val pageCount: Int get() = windowPages.size

    /** 窗口内章节 id（按书序去重），越界翻页的目标计算用。 */
    val windowChapterIds: List<Long> get() = windowPages.map { it.chapterId }.distinct()

    // ── 窗口维护 ──

    /** 同步把窗口中心移到 [chapterId]：补排窗口 [c-1, c, c+1] 缺失章节并重建索引空间。 */
    fun recenterSync(chapterId: Long) {
        val idx = chapters.indexOfFirst { it.id == chapterId }
        if (idx < 0) return
        centerChapterId = chapterId
        val from = (idx - 1).coerceAtLeast(0)
        val to = (idx + 1).coerceAtMost(chapters.size - 1)
        synchronized(lock) {
            for (i in from..to) {
                val cid = chapters[i].id
                if (cid !in paginators) paginators[cid] = buildPaginator(cid, measurer)
            }
        }
        rebuildWindow(from, to)
        // 驱逐窗口外缘（±2）之外的章节，保持内存有界
        synchronized(lock) {
            val keep = (idx - 2).coerceAtLeast(0)..(idx + 2).coerceAtMost(chapters.size - 1)
            val keepIds = keep.map { chapters[it].id }.toSet()
            paginators.keys.retainAll(keepIds)
        }
    }

    /** 后台预载 center±2 章节（排版不阻塞 UI；未完成时 recenter 会同步兜底）。 */
    suspend fun preloadNeighbors(chapterId: Long) = withContext(Dispatchers.Default) {
        val idx = chapters.indexOfFirst { it.id == chapterId }
        if (idx < 0) return@withContext
        val outer = listOf(idx - 2, idx + 2)
            .filter { it in chapters.indices }
            .map { chapters[it].id }
        synchronized(lock) {
            for (cid in outer) {
                if (cid !in paginators) paginators[cid] = buildPaginator(cid, backgroundMeasurer())
            }
        }
    }

    // ── 索引空间查询 ──

    fun chapterOfPage(index: Int): Long? = windowPages.getOrNull(index)?.chapterId

    fun pageInChapterOfPage(index: Int): Int = windowPages.getOrNull(index)?.pageInChapter ?: 0

    fun indexOf(chapterId: Long, pageInChapter: Int): Int? =
        windowPages.indexOfFirst { it.chapterId == chapterId && it.pageInChapter == pageInChapter }
            .takeIf { it >= 0 }

    /** 某章已排版时的页数（未排版返回 0）。 */
    fun pageCountInChapter(chapterId: Long): Int =
        synchronized(lock) { paginators[chapterId]?.pages?.size } ?: 0

    fun pageUnits(index: Int): List<PageUnit> {
        val wp = windowPages.getOrNull(index) ?: return emptyList()
        synchronized(lock) {
            return paginators[wp.chapterId]?.pageUnits(wp.pageInChapter) ?: emptyList()
        }
    }

    // ── 内部 ──

    private fun buildPaginator(chapterId: Long, m: TextMeasurer): ChapterPaginator {
        val chapter = chapters.first { it.id == chapterId }
        return ChapterPaginator(
            chapterId = chapterId,
            items = buildChapterItems(chapter),
            style = style,
            mode = mode,
            contentWidthPx = contentWidthPx,
            contentHeightPx = contentHeightPx,
            measurer = m,
            density = displayDensity
        )
    }

    private fun rebuildWindow(fromIdx: Int, toIdx: Int) {
        val pages = ArrayList<WindowPage>()
        synchronized(lock) {
            for (i in fromIdx..toIdx) {
                val cid = chapters[i].id
                val p = paginators[cid] ?: continue
                val n = p.pages.size.coerceAtLeast(1) // 空章也占一页（索引空间连续）
                for (pi in 0 until n) pages += WindowPage(cid, pi)
            }
        }
        windowPages = pages
    }

    companion object {
        /** 章节 → 章节内排版条目（标题 + 段落；en 模式配对英译）。
         *  以 parseBilingualParagraphs 为唯一数据源——cnText 和 enText 均来自配对结果，
         *  避免独立拆分中文段落导致索引不对齐（如整章无 \n\n 分隔时 cnText 为全章文本）。 */
        fun buildChapterItems(chapter: Chapter): List<FlowItem> {
            val list = mutableListOf<FlowItem>()
            list += FlowItem.Title(chapter.id, chapter.section, chapter.title, chapter.status)
            val pairs = chapter.translatedContent?.takeIf { it.isNotBlank() }
                ?.let { parseBilingualParagraphs(it, chapter.content) }
            if (pairs != null) {
                pairs.forEachIndexed { idx, (en, cn) ->
                    list += FlowItem.Para(
                        chapter.id, idx, cn,
                        en.takeIf { it.isNotBlank() }
                    )
                }
            } else {
                // 未翻译：纯中文模式，按原文段落拆分
                val paragraphs = splitParagraphs(chapter.content)
                paragraphs.forEachIndexed { idx, para ->
                    list += FlowItem.Para(chapter.id, idx, para, null)
                }
            }
            return list
        }
    }
}
