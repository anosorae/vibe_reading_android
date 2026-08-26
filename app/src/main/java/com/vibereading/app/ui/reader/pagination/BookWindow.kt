package com.vibereading.app.ui.reader.pagination

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextMeasurer
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.ui.reader.content.ReadingContent
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
    private val sourceLanguage: String = "zh",   // 书籍原文语言（ADR-003）：决定段落插槽方向
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

    /**
     * 同步把窗口中心移到 [chapterId]：补排窗口 [c-1, c, c+1] 缺失章节并重建索引空间。
     * [includeNeighbors]=false 时只保证中心章排版（打开书籍首帧提速），
     * 邻居章由 [paginateNeighbors] 后台排版后再次调用本方法幂等扩展。
     */
    fun recenterSync(chapterId: Long, includeNeighbors: Boolean = true) {
        val idx = chapters.indexOfFirst { it.id == chapterId }
        if (idx < 0) return
        centerChapterId = chapterId
        val from = if (includeNeighbors) (idx - 1).coerceAtLeast(0) else idx
        val to = if (includeNeighbors) (idx + 1).coerceAtMost(chapters.size - 1) else idx
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

    /** 窗口中心章 ±1 是否都已排版（后台扩展前的幂等检查）。 */
    fun hasNeighbors(chapterId: Long): Boolean {
        val idx = chapters.indexOfFirst { it.id == chapterId }
        if (idx < 0) return false
        val from = (idx - 1).coerceAtLeast(0)
        val to = (idx + 1).coerceAtMost(chapters.size - 1)
        synchronized(lock) {
            for (i in from..to) {
                if (chapters[i].id !in paginators) return false
            }
        }
        return true
    }

    /** 后台排版中心章 ±1（不重建索引空间；完成后主线程 [recenterSync] 幂等扩展）。 */
    suspend fun paginateNeighbors(chapterId: Long) = withContext(Dispatchers.Default) {
        val idx = chapters.indexOfFirst { it.id == chapterId }
        if (idx < 0) return@withContext
        val from = (idx - 1).coerceAtLeast(0)
        val to = (idx + 1).coerceAtMost(chapters.size - 1)
        synchronized(lock) {
            for (i in from..to) {
                val cid = chapters[i].id
                if (cid !in paginators) paginators[cid] = buildPaginator(cid, backgroundMeasurer())
            }
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

    /** 按章节原文偏移定位到窗口内页；Long 重载避免破坏既有 Int 页索引调用。 */
    fun indexOf(chapterId: Long, sourceOffset: Long): Int? {
        val paginator = synchronized(lock) { paginators[chapterId] } ?: return null
        val page = paginator.pageForOffset(sourceOffset.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) ?: return null
        return indexOf(chapterId, page)
    }

    /** Int 形式的显式别名，适合来源偏移由字符串/编辑器 API 提供的调用点。 */
    fun indexOfOffset(chapterId: Long, sourceOffset: Int): Int? =
        indexOf(chapterId, sourceOffset.toLong())

    /**
     * 返回窗口页对应的章节原文范围（start inclusive，end exclusive）。
     * 页不存在或未排版时返回 null。
     */
    fun offsetOfPage(index: Int): IntRange? {
        val wp = windowPages.getOrNull(index) ?: return null
        val page = synchronized(lock) { paginators[wp.chapterId]?.pages?.getOrNull(wp.pageInChapter) }
            ?: return null
        val start = page.sourceStartOffset?.takeIf { it >= 0 } ?: return null
        val end = page.sourceEndOffset?.takeIf { it >= start } ?: return null
        return start until end
    }

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
            items = buildChapterItems(chapter, sourceLanguage),
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
        /**
         * 章节 → 章节内排版条目（标题 + 段落；en 模式配对双语）。
         * 以 parseBilingualParagraphs 为唯一数据源——cnText/enText 均来自配对结果，
         * 避免独立拆分中文段落导致索引不对齐（如整章无 \n\n 分隔时 cnText 为全章文本）。
         * cnText/enText 是「中文侧/英文侧」插槽（ADR-003）：中文书 cnText=原文、enText=译文；
         * 英文书互换。offset 恒指向章节原文范围，不随插槽方向变化。
         */
        fun buildChapterItems(chapter: Chapter, sourceLanguage: String): List<FlowItem> {
            val content = ReadingContent.fromChapter(chapter)
            return buildList {
                add(FlowItem.Title(
                    chapterId = content.chapterId,
                    section = content.section,
                    title = content.title,
                    status = content.status,
                    errorMessage = content.errorMessage
                ))
                content.paragraphs.forEach { paragraph ->
                    val illustration = paragraph.illustration
                    if (illustration != null) {
                        add(FlowItem.Image(
                            chapterId = content.chapterId,
                            paraIndex = paragraph.index,
                            path = illustration.path,
                            imageWidthPx = illustration.widthPx,
                            imageHeightPx = illustration.heightPx,
                            sourceStartOffset = paragraph.sourceStartOffset,
                            sourceEndOffset = paragraph.sourceEndOffset
                        ))
                    } else {
                        add(FlowItem.Para(
                            chapterId = content.chapterId,
                            paraIndex = paragraph.index,
                            cnText = paragraph.chineseSide(sourceLanguage).orEmpty(),
                            enText = paragraph.englishSide(sourceLanguage),
                            sourceStartOffset = paragraph.sourceStartOffset,
                            sourceEndOffset = paragraph.sourceEndOffset
                        ))
                    }
                }
            }
        }
    }
}
