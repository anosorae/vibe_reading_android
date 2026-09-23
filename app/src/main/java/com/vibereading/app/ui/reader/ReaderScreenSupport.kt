package com.vibereading.app.ui.reader

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.reader.components.CatalogGroup
import com.vibereading.app.ui.reader.pagination.PageStyle

internal data class ReaderGestureLayoutKey(
    val paddingH: Int,
    val paddingV: Int,
    val statusBarPx: Int,
    val navBarPx: Int,
    val viewportSize: IntSize
)

/**
 * 进程代际（每进程一次）：作用域 key，令 `rememberPagerState`/`rememberLazyListState` 的
 * saveable 恢复在**跨进程**重启后失效。进程被系统杀死重启时，savedInstanceState 会把
 * 上一个窗口的扁平页索引恢复进 PagerState；该索引来自已销毁的窗口（邻居章节数不同则
 * 数值越界），HorizontalPager 首次布局遇到越界索引会回落到第 0 页，覆盖恢复定位
 * （chapterId+offset 的 seek），再被翻页上报链落库——表现为「回到章节首页」。
 * 阅读位置的唯一事实源是 DB 的 chapterId+offset（ADR-001），页码只是派生状态，
 * 不应参与跨进程恢复；同进程内（如旋转重建）该 key 不变，恢复行为保持原样。
 */
internal val readerProcessGeneration: Long = System.nanoTime()

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberReaderLayoutSpec(
    settings: ReadingSettings,
    pageStyle: PageStyle,
    palette: ReaderPalette,
    viewportSize: IntSize
): ReaderLayoutSpec {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val rawStatus = WindowInsets.systemBarsIgnoringVisibility.getTop(density)
    val rawNav = WindowInsets.systemBarsIgnoringVisibility.getBottom(density)
    val rawCutoutTop = WindowInsets.displayCutout.getTop(density)
    val rawCutoutLeft = WindowInsets.displayCutout.getLeft(density, direction)
    val rawCutoutRight = WindowInsets.displayCutout.getRight(density, direction)
    val statusCache = remember { mutableIntStateOf(rawStatus) }
    val navCache = remember { mutableIntStateOf(rawNav) }
    val cutoutTopCache = remember { mutableIntStateOf(rawCutoutTop) }
    val cutoutLeftCache = remember { mutableIntStateOf(rawCutoutLeft) }
    val cutoutRightCache = remember { mutableIntStateOf(rawCutoutRight) }
    if (rawStatus > statusCache.intValue) statusCache.intValue = rawStatus
    if (rawNav > navCache.intValue) navCache.intValue = rawNav
    if (rawCutoutTop > cutoutTopCache.intValue) cutoutTopCache.intValue = rawCutoutTop
    if (rawCutoutLeft > cutoutLeftCache.intValue) cutoutLeftCache.intValue = rawCutoutLeft
    if (rawCutoutRight > cutoutRightCache.intValue) cutoutRightCache.intValue = rawCutoutRight
    val status = maxOf(statusCache.intValue, cutoutTopCache.intValue)
    val padH = with(density) { settings.paddingH.dp.roundToPx() }
    val padV = with(density) { settings.paddingV.dp.roundToPx() }
    val geometry = ReaderPageGeometry.of(
        viewportSize.width, viewportSize.height, status, navCache.intValue, padH, padV
    )
    return remember(
        pageStyle, palette, geometry, settings.paddingH, settings.paddingV,
        settings.headerContentGap, settings.footerContentGap,
        cutoutLeftCache.intValue, cutoutRightCache.intValue
    ) {
        ReaderLayoutSpec(
            pageStyle, palette, geometry, settings.paddingH, settings.paddingV,
            settings.headerContentGap, settings.footerContentGap,
            cutoutLeftCache.intValue, cutoutRightCache.intValue
        )
    }
}

internal fun buildCatalogGroups(chapters: List<Chapter>): List<CatalogGroup> {
    val groups = mutableListOf<CatalogGroup>()
    var section: String? = null
    val current = mutableListOf<Chapter>()
    chapters.forEach { chapter ->
        if (chapter.section != null && chapter.section != section) {
            if (current.isNotEmpty()) groups += CatalogGroup(section, current.toList())
            section = chapter.section
            current.clear()
        }
        current += chapter
    }
    if (current.isNotEmpty()) groups += CatalogGroup(section, current.toList())
    return groups
}

internal class ReaderScreenActions(private val vm: ReaderViewModel) :
    ReaderGestureActions, ReaderOverlayActions {
    private var leave: () -> Unit = {}
    private var page: (Int) -> Unit = {}
    private var jump: (Long) -> Unit = {}
    private var jumpDelta: (Int) -> Unit = {}

    fun update(leaveReader: () -> Unit, goPage: (Int) -> Unit, jumpTo: (Long) -> Unit, jumpBy: (Int) -> Unit) {
        leave = leaveReader
        page = goPage
        jump = jumpTo
        jumpDelta = jumpBy
    }

    override fun goPage(page: Int) = this.page(page)
    override fun toggleToolbar() = vm.toggleToolbar()
    override fun dismissOverlays() = vm.dismissAllOverlays()
    override fun dismissPopups() { vm.dismissDictPopup(); vm.dismissExplainPopup() }
    override fun leaveReader() = leave()
    override fun switchMode(mode: String) = vm.switchMode(mode)
    override fun jumpChapterBy(delta: Int) = jumpDelta(delta)
    override fun jumpToChapter(id: Long) = jump(id)
    override fun toggleCatalog() = vm.toggleCatalog()
    override fun openLlmSettings() { vm.initLlmEditFields(); vm.toggleLlmSettings() }
    override fun retryTranslation(id: Long) = vm.retryTranslation(id)
    override fun toggleSettings() = vm.toggleSettings()
    override fun dismissCatalog() = vm.dismissCatalog()
    override fun updateReadingSettings(settings: ReadingSettings) = vm.updateReadingSettings { settings }
    override fun dismissSettings() = vm.dismissSettings()
    override fun lookupWord(word: String) = vm.lookupDictWord(word)
    override fun explainWord(word: String, paragraph: String) = vm.explainWord(word, paragraph)
    override fun dismissDictPopup() = vm.dismissDictPopup()
    override fun dismissExplainPopup() = vm.dismissExplainPopup()
    override fun updateApiKey(value: String) = vm.updateEditApiKey(value)
    override fun updateApiBase(value: String) = vm.updateEditApiBase(value)
    override fun updateModel(value: String) = vm.updateEditModel(value)
    override fun updateChapterMaxChars(value: Int) = vm.updateLlmChapterMaxChars(value)
    override fun updateMaxOutputTokens(value: Int) = vm.updateLlmMaxOutputTokens(value)
    override fun toggleThinking(enabled: Boolean) = vm.updateLlmThinking(enabled)
    override fun toggleExplainThinking(enabled: Boolean) = vm.updateLlmExplainThinking(enabled)
    override fun toggleAutoTranslateNext(enabled: Boolean) = vm.updateLlmAutoTranslateNext(enabled)
    override fun updateTemperature(value: Float) = vm.updateLlmTemperature(value)
    override fun updateTopP(value: Float) = vm.updateLlmTopP(value)
    override fun switchProfile(id: Long) = vm.switchProfile(id)
    override fun editProfile(id: Long) = vm.editProfileInSheet(id)
    override fun cancelEdit() = vm.cancelProfileEditInSheet()
    override fun save() = vm.saveLlmSettings()
    override fun test() = vm.testLlmConnection()
    override fun dismiss() = vm.dismissLlmSettings()
}
