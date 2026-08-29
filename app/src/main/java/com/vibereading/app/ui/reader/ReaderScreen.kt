package com.vibereading.app.ui.reader

import android.app.Activity
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.log.AppLog
import com.vibereading.app.log.OpenBookProbe
import com.vibereading.app.ui.reader.components.CatalogBottomSheet
import com.vibereading.app.ui.reader.components.CatalogGroup
import com.vibereading.app.ui.reader.components.DictPopup
import com.vibereading.app.ui.reader.components.ExplainPopup
import com.vibereading.app.ui.reader.components.IllustrationPreviewOverlay
import com.vibereading.app.ui.reader.components.PageInfoOverlays
import com.vibereading.app.ui.reader.components.LlmSettingsSheet
import com.vibereading.app.ui.reader.components.ReaderSettingsSheet
import com.vibereading.app.ui.reader.components.SelectionHandles
import com.vibereading.app.ui.reader.components.SelectionToolbar
import com.vibereading.app.ui.reader.components.TextSelectionState
import com.vibereading.app.ui.reader.pagination.*
import com.vibereading.app.ui.theme.ReaderBgPresets
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    onBack: () -> Unit
) {
    val state by vm.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val flushScope = rememberCoroutineScope()
    val readingSettings = state.readingSettings
    val bgPresets = listOf(
        ReaderBgPresets.WarmCream,
        ReaderBgPresets.DarkCream,
        ReaderBgPresets.GreenTint,
        ReaderBgPresets.GrayCream,
        ReaderBgPresets.DarkNight
    )
    val rawBgColor = if (state.nightMode) ReaderBgPresets.DarkNight
    else bgPresets.getOrElse(readingSettings.bgColorIndex) { ReaderBgPresets.WarmCream }
    // rememberUpdatedState 确保手势协程（pointerInput）内始终读到最新值，
    // 避免切换阅读背景后仿真卷页位图仍使用旧主题颜色。
    val bgColor by rememberUpdatedState(rawBgColor)
    val isDark = state.nightMode || readingSettings.bgColorIndex == 4
    val flipMode = readingSettings.pageFlipMode
    val isPagerMode = flipMode != ReadingSettings.FLIP_SCROLL
    val accentColor = MaterialTheme.colorScheme.primary
    // 语义色板：把 isDark 亮/暗三元集中一处（正文/标题/气泡/弹窗共用）
    val rawPalette = remember(isDark) { ReaderPalette.of(isDark) }
    val palette by rememberUpdatedState(rawPalette)
    // 剪贴板与 Toast（选词复制）
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Build catalog groups
    val catalogGroups = remember(state.chapters) {
        val groups = mutableListOf<CatalogGroup>()
        var currentSection: String? = null
        val currentChapters = mutableListOf<Chapter>()

        for (chapter in state.chapters) {
            if (chapter.section != null && chapter.section != currentSection) {
                if (currentChapters.isNotEmpty()) {
                    groups.add(CatalogGroup(currentSection, currentChapters.toList()))
                }
                currentSection = chapter.section
                currentChapters.clear()
                currentChapters.add(chapter)
            } else {
                currentChapters.add(chapter)
            }
        }
        if (currentChapters.isNotEmpty()) {
            groups.add(CatalogGroup(currentSection, currentChapters.toList()))
        }
        groups
    }

    // ── 整页排版（章窗口模型：窗口=当前章±1，每章全量排版常驻，ADR-001） ──
    val measurer = rememberTextMeasurer()
    val bgMeasurer = rememberTextMeasurer(cacheSize = 8) // 后台预载独立实例（后台线程测量）
    val density = LocalDensity.current
    // 中英分体：返回 (中文字体, 英文字体)；parse 失败/未选回退系统字体
    val (cnFont, enFont) = remember(readingSettings.customFontUri, readingSettings.enCustomFontUri, readingSettings.fontId, readingSettings.enFontId) {
        ReaderFonts.readerFontFamilies(context, readingSettings).also {
            OpenBookProbe.step("字体解析完成")
        }
    }
    val pageStyle = remember(readingSettings, density, state.mode, cnFont, enFont) {
        PageStyle.of(readingSettings, density, state.mode, cnFont, enFont).also {
            OpenBookProbe.step("排版样式构造完成")
        }
    }
    // 页几何：内容区 = 屏尺寸 − 页边距（与 BookPager 渲染内边距严格一致，排版所见即所排）
    // 屏幕像素取 displayMetrics 实际值（不通过 screenWidthDp*density 转换，
    // 因 screenWidthDp 为截断整数，411dp*2.625=1078.875→round=1079≠1080 实际宽度，
    // 差 1px 导致排版区窄 1px → 仿真翻页位图与 Compose 页换行不一致 → 文字重排抖动）
    val displayMetrics = LocalContext.current.resources.displayMetrics
    // 系统栏高度：缓存最大值后锁定，沉浸式切换时 insets 变化不触发重排
    // （排版/渲染用缓存值保持稳定；浮层定位仍用实时值以跟随栏显隐）
    val rawStatusBarPx = WindowInsets.systemBars.getTop(density)
    val rawNavBarPx = WindowInsets.systemBars.getBottom(density)
    val cachedStatusBarPx = remember { mutableIntStateOf(rawStatusBarPx) }
    val cachedNavBarPx = remember { mutableIntStateOf(rawNavBarPx) }
    if (rawStatusBarPx > cachedStatusBarPx.intValue) cachedStatusBarPx.intValue = rawStatusBarPx
    if (rawNavBarPx > cachedNavBarPx.intValue) cachedNavBarPx.intValue = rawNavBarPx
    // 显示挖孔（刘海/挖孔屏）：缓存后锁定，全面屏手机文字不被摄像头挖孔遮挡
    val layoutDirection = LocalLayoutDirection.current
    val rawCutoutTopPx = WindowInsets.displayCutout.getTop(density)
    val rawCutoutLeftPx = WindowInsets.displayCutout.getLeft(density, layoutDirection)
    val rawCutoutRightPx = WindowInsets.displayCutout.getRight(density, layoutDirection)
    val cachedCutoutTopPx = remember { mutableIntStateOf(rawCutoutTopPx) }
    val cachedCutoutLeftPx = remember { mutableIntStateOf(rawCutoutLeftPx) }
    val cachedCutoutRightPx = remember { mutableIntStateOf(rawCutoutRightPx) }
    if (rawCutoutTopPx > cachedCutoutTopPx.intValue) cachedCutoutTopPx.intValue = rawCutoutTopPx
    if (rawCutoutLeftPx > cachedCutoutLeftPx.intValue) cachedCutoutLeftPx.intValue = rawCutoutLeftPx
    if (rawCutoutRightPx > cachedCutoutRightPx.intValue) cachedCutoutRightPx.intValue = rawCutoutRightPx
    // 顶部安全区 = max(状态栏, 挖孔)，全面屏挖孔手机文字不被遮挡
    val statusBarPx = maxOf(cachedStatusBarPx.intValue, cachedCutoutTopPx.intValue)
    val navBarPx = cachedNavBarPx.intValue           // 排版/渲染用（稳定）
    val cutoutLeftPx = cachedCutoutLeftPx.intValue    // 页眉/工具栏左侧避让
    val cutoutRightPx = cachedCutoutRightPx.intValue  // 页眉/工具栏右侧避让
    // 底部工具栏动态高度（用于翻译进度面板定位）
    var bottomBarHeightDp by remember { mutableFloatStateOf(0f) }
    // padding 用 roundToPx 对齐 Compose 布局系统（dp→round(density*dp)→Int）
    val padHPx = with(density) { readingSettings.paddingH.dp.roundToPx() }
    val padVPx = with(density) { readingSettings.paddingV.dp.roundToPx() }
    // 内容区 = 屏幕整像素 − 两侧边距整像素（Int 运算，与 Compose 约束一致）；
    // Compose Layout 系统对每个 padding 值做 roundToPx 后相减，此计算复现相同逻辑
    val geometry = ReaderPageGeometry.of(
        screenWidthPx = displayMetrics.widthPixels,
        screenHeightPx = displayMetrics.heightPixels,
        statusBarPx = statusBarPx,
        navBarPx = navBarPx,
        padHPx = padHPx,
        padVPx = padVPx
    )
    val screenWidthPx = geometry.screenWidthPx
    val screenHeightPx = geometry.screenHeightPx
    val contentWidthPx = geometry.contentWidthPx
    val contentHeightPx = geometry.contentHeightPx

    // 窗口 key 只含低频变化（模式/指纹/翻页类型）：边距、字号等排版样式变化走下方
    // restyle 热更新（后台重排版、主线程原子换入），避免拖动滑杆时每个 tick 在
    // composition 内同步重排全章造成严重卡顿。
    //
    // 中心章排版不在 composition 内同步执行（EPUB 长章整章排版会阻塞主线程数百毫秒，
    // 表现为打开书籍时卡顿）：由下方 LaunchedEffect 走 window.recenterAsync 后台排版，
    // 且首帧只排到恢复 offset 所在页（剩余后台续排），期间 pageCount 不含未排部分，
    // 由打开过渡遮罩呈现；邻居章由 paginateNeighbors 后台排版后幂等扩展窗口并保持
    // 当前视觉页。
    //
    // 分页指纹只含影响排版的字段（id/title/section/index/content/translatedContent），
    // 不含 status/errorMessage——翻译状态变化（IN_PROGRESS→DONE）不应重建窗口，
    // 否则 pagerState.pageCount 在程序化跳章（scrollToPage）后突变，currentPage 偏移到
    // 新窗口末尾，导致「下一章跳到最后一页」。状态变化只影响 UI chrome（状态点/面板）。
    // 指纹仅取当前正读章节的译文长度：后台预译「下一章」写库虽使 chapters 变化，但当前章
    // 译文长度不变 → 指纹字符串相等 → 窗口不重建、当前画面不跳动；邻居译文由切章时的
    // recenterSync 重新排版取得。
    val paginationFingerprint = remember(state.chapters) {
        state.chapters.find { it.id == state.activeChapterId }
            ?.let { "${it.id}:${it.translatedContent?.length ?: -1}" }
            ?: ""
    }
    val window = remember(
        measurer, state.mode, state.sourceLanguage, paginationFingerprint, isPagerMode
    ) {
        OpenBookProbe.step("BookWindow 创建（中心章走后台排版）")
        BookWindow(
            chapters = state.chapters,
            style = pageStyle,
            mode = state.mode,
            sourceLanguage = state.sourceLanguage,
            contentWidthPx = contentWidthPx,
            contentHeightPx = contentHeightPx,
            measurer = measurer,
            backgroundMeasurer = { bgMeasurer },
            displayDensity = density.density
        )
    }
    // 仿真卷页尺寸 = 全屏（对齐 Legado：位图/覆盖层/手势均使用全屏坐标系）

    val pagerState = rememberPagerState(initialPage = 0) { window.pageCount }
    val scope = rememberCoroutineScope()
    val simFlip = remember { SimFlipState() }

    // 全屏插图预览（ADR-002 D5）：非空 = 预览叠加层可见；视觉叠加层，不参与排版
    var previewIllustrationPath by remember { mutableStateOf<String?>(null) }
    // 仿真卷页动画协程句柄：新手势/切模式时取消旧动画，防止新旧 touchX/Y 互相干扰
    var simFlipJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 长按选词状态（瞬时交互：翻页/滚动/切章/开浮层时清除）
    val selectionState = remember { TextSelectionState() }
    // 词典弹窗锚点（查词时从选区位置捕获，选区清除后弹窗仍停在该处）
    var dictAnchor by remember { mutableStateOf(Offset.Zero) }

    // mode 切换时清除仿真卷页旧位图，避免反面显示旧模式文字
    LaunchedEffect(state.mode) {
        simFlipJob?.cancel()
        simFlipJob = null
        simFlip.cleanup()
    }

    // 分页模式「程序化跳章」目标（目录/上下章/窗口边界续翻）；「当前章重定位」也走这里
    var pagerJumpTarget by remember { mutableStateOf<Long?>(null) }
    var pagerJumpOffset by remember { mutableIntStateOf(0) }
    // 首次恢复必须在窗口可按 sourceOffset 定位后才放行位置追踪
    var initialSeekDone by remember { mutableStateOf(false) }
    // 窗口滑动期间抑制「翻页同步章」，避免 recenter 滚动与跨章同步互相打架
    var windowSliding by remember { mutableStateOf(false) }

    // 排版样式/内容区尺寸变化（边距、字号、行距等滑杆拖动期高频触发）：
    // 后台重排窗口章并原子换入，主线程只做索引空间重建与视觉页重映射。
    // key 重启即取消上一个 restyle（拖动期每个 tick 只保留最新样式），利用
    // LaunchedEffect 的取消语义实现「最新样式 wins」。提交完成后按 offset
    // 恢复当前视觉页（页高/页宽变化导致每页容纳内容变化，页码必然漂移）。
    LaunchedEffect(window, pageStyle, contentWidthPx, contentHeightPx) {
        if (!isPagerMode) return@LaunchedEffect // 滚动模式排版不依赖窗口分页，边距即时生效
        if (window.matchesStyle(pageStyle, contentWidthPx, contentHeightPx)) return@LaunchedEffect
        val curChapter = window.chapterOfPage(pagerState.currentPage) ?: state.activeChapterId
        val curOffset = window.offsetOfPage(pagerState.currentPage)?.first
            ?: state.position?.offset ?: 0
        windowSliding = true
        try {
            window.restyle(pageStyle, contentWidthPx, contentHeightPx)
            val idx = curChapter?.let { cid ->
                window.indexOf(cid, curOffset.toLong())
                    ?: window.indexOf(cid, 0)
            }
                ?: window.indexOf(window.centerChapterId ?: 0L, 0)
                ?: 0
            if (pagerState.currentPage != idx) {
                pagerState.scrollToPage(idx)
            }
        } finally {
            windowSliding = false
        }
    }

    // 分页模式：窗口中心跟随「当前章」滑动（索引空间重映射，视觉页不变）+ 程序化跳章/初始定位
    // key 含 pagerJumpTarget：点击当前章目录项时 activeChapterId 不变也能触发。
    LaunchedEffect(window, state.activeChapterId, pagerJumpTarget) {
        if (!isPagerMode) return@LaunchedEffect
        if (state.activeChapterId == null) return@LaunchedEffect
        val isProgrammatic = pagerJumpTarget != null
        val target = pagerJumpTarget ?: state.activeChapterId
        if (target == null) return@LaunchedEffect
        val sourceOffset = when {
            isProgrammatic -> pagerJumpOffset
            !initialSeekDone -> state.position?.offset ?: 0
            // 窗口重建时 pagerState.currentPage 是旧窗口页索引，映射到新窗口会错位；
            // 始终使用 state.position 的 offset 作为主源（由翻页进度写入，始终可靠）。
            else -> state.position?.offset ?: 0
        }
        windowSliding = true
        // 页内跨章翻页（平移/覆盖）：动画已把 currentPage 推进到新章页。非程序化场景下若视觉页
        // 已落在目标章，跳过滚动（position.offset 由 progress LE 写入，已反映正确位置）。
        // 程序化跳章（上下章按钮/目录）必须无条件 scrollToPage，因为 currentPage 可能落在
        // 目标章的末页（边界续翻残留），只有 scrollToPage(首页) 才能归位。
        val currentPageAlreadyOnTarget = !isProgrammatic && initialSeekDone &&
            window.chapterOfPage(pagerState.currentPage) == target
        // 非程序化跳章且窗口中心已正确时，不重新同步（避免缩小已扩展的窗口导致 currentPage 越界
        // 被 clamped 到末页，用户落到章末而非首页）。
        // 这发生在程序化跳章完成、pagerJumpTarget 被清除后，LaunchedEffect 因 activeChapterId
        // 变化再次触发时——此时 window 已在 LaunchedEffect(window, state.activeChapterId) 中扩展
        // 了邻居，不应再缩小回单章。
        // 中心章缺失时后台排版（打开书籍首帧/程序化跳章/切模式提速），已在排版表时
        // 近零开销；完成后在主线程重建索引空间，保持原 recenterSync 的幂等语义。
        // 传入目标 offset：首帧只排到覆盖该位置所在页（EPUB 长章提速），剩余后台续排。
        if (isProgrammatic || !initialSeekDone || window.centerChapterId != target) {
            window.recenterAsync(target, sourceOffset)
        }
        // 窗口滑动后索引空间变化：只使用新窗口内目标页，防止旧索引失效时跳到窗口第一页。
        val idx = window.indexOf(target, sourceOffset.toLong())
            ?: window.indexOf(target, 0)
            ?: window.indexOf(window.centerChapterId ?: target, 0)
            ?: 0
        if (!currentPageAlreadyOnTarget && pagerState.currentPage != idx) {
            pagerState.scrollToPage(idx)
        }
        initialSeekDone = true
        windowSliding = false
        // 不在 LE 内清除 pagerJumpTarget：清除会立即触发 LE 重启，此时 activeChapterId 可能
        // 还是旧章（navigateTo 是异步的），重启用旧 target + 旧 currentPage 算出错误 idx，
        // 覆盖刚完成的 scrollToPage(0)，导致「下一章跳到最后一页」/「上一章没反应」。
        // 改由下方 LaunchedEffect 在 activeChapterId 确认到达 target 且视觉页就位后清除。
        scope.launch {
            // 首帧已就绪：后台把中心章剩余部分续排完整，再预载 ±2 外缘章
            window.ensurePaginatorComplete(target)
            // 刷新索引空间前记下当前视觉页的（章, 章内页号），刷新后重映射落位：
            // 续排补齐与前一章插入都会让页索引整体平移，不重映射会闪现错误页
            // （如从 16/54 瞬跳 12/54 再跳回）。
            val curChapter = window.chapterOfPage(pagerState.currentPage)
            val curPageInChapter = window.pageInChapterOfPage(pagerState.currentPage)
            window.refreshWindow()
            val remapped = curChapter?.let { window.indexOf(it, curPageInChapter) }
            if (remapped != null && pagerState.currentPage != remapped) {
                pagerState.scrollToPage(remapped)
            }
            window.preloadNeighbors(target)
        }
    }

    // 程序化跳章收尾：activeChapterId 已到达 target 且 currentPage 落在目标章正确偏移页后，
    // 清除 pagerJumpTarget。在此之前 LE1/LE2 都视为跳章进行中，避免用过期状态覆盖。
    // 不能只检查 chapterOfPage==target（currentPage 可能落在目标章末页），必须校验偏移。
    LaunchedEffect(window, state.activeChapterId, pagerJumpTarget, pagerState.currentPage) {
        val jt = pagerJumpTarget ?: return@LaunchedEffect
        if (state.activeChapterId != jt) return@LaunchedEffect
        // currentPage 的章节原文偏移须与 pagerJumpOffset 一致才算跳章到位
        val pageOffset = window.offsetOfPage(pagerState.currentPage)?.first
        if (window.chapterOfPage(pagerState.currentPage) == jt && pageOffset == pagerJumpOffset) {
            pagerJumpTarget = null
        }
    }

    // 打开/切章后：后台排版中心章 ±1，完成后主线程扩展窗口并保持当前视觉页
    // （recenterSync 幂等：邻居已在 paginators 时只重建索引空间，不重复排版）
    LaunchedEffect(window, state.activeChapterId) {
        if (!isPagerMode) return@LaunchedEffect
        val target = state.activeChapterId ?: return@LaunchedEffect
        if (!window.hasNeighbors(target)) {
            window.paginateNeighbors(target) // 后台排版，不阻塞 UI
        }
        // 窗口扩展（pageCount 从中心章独占变为 ±1 邻居）后，pagerState.currentPage 的索引空间
        // 变化：原来 idx=0 是中心章首页，扩展后 idx=0 变成前一章，中心章首页移到新 idx。
        // 必须重新定位 currentPage 到目标章的正确页，否则视觉页漂移到错误章/末页。
        // 程序化跳章进行中时用 pagerJumpOffset（用户意图的首页 offset=0），不用过期 position.offset。
        val jumpTarget = pagerJumpTarget
        val curChapter = jumpTarget ?: state.activeChapterId
        val curOffset = if (jumpTarget != null) pagerJumpOffset else state.position?.offset
        windowSliding = true
        window.recenterSync(target)
        val newIdx = window.indexOf(curChapter ?: target, curOffset?.toLong() ?: 0L)
            ?: window.indexOf(target, 0)
            ?: 0
        if (pagerState.currentPage != newIdx) {
            pagerState.scrollToPage(newIdx)
        }
        windowSliding = false
    }

    // 分页模式：翻页时保存「章 + 章内页」进度；翻入新章同步 activeChapter（触发窗口滑动）
    // 不含 window 键：窗口重建时 pagerState.currentPage 是旧索引，用新窗口取偏移会污染 position；
    // 窗口重建后的位置恢复由上方 recenterSync LaunchedEffect 负责。
    LaunchedEffect(pagerState.currentPage, isPagerMode) {
        if (!isPagerMode || !initialSeekDone || windowSliding) return@LaunchedEffect
        val cp = window.chapterOfPage(pagerState.currentPage) ?: return@LaunchedEffect
        val offset = window.offsetOfPage(pagerState.currentPage)?.first ?: return@LaunchedEffect
        vm.updateProgress(cp, offset)
        if (cp != state.activeChapterId) {
            vm.navigateTo(cp, offset)
        }
    }

    // 退出路径兜底：翻页进度由上方 LaunchedEffect(pagerState.currentPage) 异步写入，
    // 翻页后瞬间退出（返回键/后台）时该 effect 可能尚未运行，直接 flush 会把
    // 上一页的位置落库。flush 前先从当前视觉页同步一次进度（幂等，与翻页 LE
    // 同一数据源与同一组守卫，窗口滑动期间不采信旧索引）。
    fun syncPagerProgressBeforeFlush() {
        if (!isPagerMode || !initialSeekDone || windowSliding) return
        val cp = window.chapterOfPage(pagerState.currentPage) ?: return
        val offset = window.offsetOfPage(pagerState.currentPage)?.first ?: return
        vm.updateProgress(cp, offset)
    }

    /** 卷页快照对（对齐 Legado setBitmap）：curBitmap=当前页, targetBitmap=目标页。 */
    fun curlBitmaps(cur: Int, target: Int): Pair<Bitmap, Bitmap>? {
        val curBmp = renderPageBitmap(
            window, cur, state.mode, pageStyle, geometry, palette, density,
            bgColor.toArgb(), accentColor.toArgb(), measurer,
            imageResolver = { path, w -> com.vibereading.app.data.image.BookImageStore.loadBitmap(path, w) }
        ) ?: return null
        val targetBmp = renderPageBitmap(
            window, target, state.mode, pageStyle, geometry, palette, density,
            bgColor.toArgb(), accentColor.toArgb(), measurer,
            imageResolver = { path, w -> com.vibereading.app.data.image.BookImageStore.loadBitmap(path, w) }
        )
        if (targetBmp == null) { curBmp.recycle(); return null }
        return curBmp to targetBmp
    }

    // ── 卷页自动动画共享驱动 ──
    // 入口只负责算好位移 (dx,dy) 与落地页；逐帧插值、adjustTouchY、落地提交和收尾清理
    // 统一在这里，避免点按翻页（startSimFlip）与拖拽抬手（simFlipAnimStart）双处同形。
    // commitPage < 0 表示回弹/不落地。
    fun launchCurlAnim(dx: Float, dy: Float, commitPage: Int) {
        val wf = screenWidthPx.toFloat()
        val hf = screenHeightPx.toFloat()
        val sx = simFlip.touchX
        val sy = simFlip.touchY
        val endX = sx + dx
        val endY = sy + dy
        simFlipJob = scope.launch {
            try {
                val animatable = androidx.compose.animation.core.Animatable(0f)
                animatable.animateTo(
                    targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = simFlipDurationMs(dx, dy, wf, hf).toInt(),
                        easing = androidx.compose.animation.core.LinearEasing
                    )
                ) {
                    simFlip.touchX = sx + (endX - sx) * value
                    simFlip.touchY = sy + (endY - sy) * value
                    // 动画帧也必须调用 adjustTouchY，保持与手势阶段相同的 Y 吸顶/吸底规则，
                    // 否则回弹动画末尾 touchY 偏离调整值，卷页几何跳变（右上角突然卷页）
                    simFlip.adjustTouchY(hf)
                }
                if (commitPage >= 0 && pagerState.currentPage != commitPage) {
                    pagerState.scrollToPage(commitPage)
                }
                simFlip.cleanup()
                simFlipJob = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消由各取消点收尾（DOWN 打断落地 / 新动画覆盖 / 切模式清理），
                // 这里不碰状态：若在 finally 里 cleanup，可能清掉新手势刚起的卷页
                // 位图与 isRunning，同时把新 simFlipJob 句柄置空。
                throw e
            } catch (e: Exception) {
                // 异常兜底：动画中途失败（如窗口重建竞态下 scrollToPage 抛错）时若不清理，
                // animating/isRunning 冻结在半页，直到下一次 DOWN 才被 cleanup 解冻。
                AppLog.put("仿真卷页动画异常，已清理冻结状态", e)
                simFlip.cleanup()
                simFlipJob = null
            }
        }
    }

    /** 启动仿真卷页自动动画（对齐 Legado Scroller 式：cancel 回弹 / complete 完成）。 */
    fun startSimFlip(cur: Int, next: Int, goingNext: Boolean) {
        if (simFlip.isRunning) return
        simFlipJob?.cancel()
        val pair = curlBitmaps(cur, next)
        if (pair == null) {
            scope.launch { if (pagerState.currentPage != next) pagerState.scrollToPage(next) }
            return
        }
        val wf = screenWidthPx.toFloat()
        val hf = screenHeightPx.toFloat()
        simFlip.curl.setViewSize(wf, hf)
        simFlip.curBitmap = pair.first
        simFlip.targetBitmap = pair.second
        simFlip.direction = if (goingNext) PageCurl.Direction.NEXT else PageCurl.Direction.PREV
        simFlip.bgColor = bgColor.toArgb()

        // 对齐 Legado nextPageByAnim / prevPageByAnim：起点 + 角落
        if (goingNext) {
            // NEXT：卷页角固定右边缘（cornerX=wf）。DOWN 时 calcCornerXY 按点击 x 算角：
            // 点左侧（含单手模式翻下一页）会得到 cornerX=0（左边缘），导致「翻下一页」
            // 却从左边缘卷起、姿态像翻上一页——强制右边缘与右侧点击动画完全一致；
            // cornerY 与新 startY 同源推导（对齐 calcCornerXY 的上下半屏规则），
            // 不沿用 DOWN 点击点的旧值——程序化入口（点按/跳章）没有可靠的手势角落
            simFlip.cornerX = wf
            val startY = if (simFlip.startY > hf / 2) hf * 0.9f else 1f
            simFlip.startX = wf * 0.9f
            simFlip.startY = startY
            simFlip.cornerY = if (startY > hf / 2) hf else 0f
            simFlip.touchX = wf * 0.9f
            simFlip.touchY = startY
        } else {
            // PREV：对齐 Legado setDirection(PREV) → 角落始终在右下（cornerX=wf）
            simFlip.startX = 0f
            simFlip.startY = hf
            simFlip.cornerX = wf
            simFlip.cornerY = hf
            simFlip.touchX = 0f
            simFlip.touchY = hf
        }
        simFlip.animating = true
        simFlip.isRunning = true
        simFlip.isMoved = true
        simFlip.isCancel = false
        simFlip.settleTarget = next

        // Scroller 式动画（对齐 Legado onAnimStart）：complete 滚过屏幕边缘完成翻页
        val dx = if (goingNext) {
            if (simFlip.cornerX > 0f) -(wf + simFlip.touchX) else wf - simFlip.touchX
        } else {
            wf + wf - simFlip.touchX   // PREV: 往右滚过屏幕，越过右边缘
        }
        val dy = if (simFlip.cornerY > 0f) (hf - simFlip.touchY) else (1f - simFlip.touchY)
        launchCurlAnim(dx, dy, commitPage = next)
    }

    /** 仿真卷页抬手动画（对齐 Legado SimulationPageDelegate.onAnimStart：cancel 回弹 / complete 完成）。 */
    fun simFlipAnimStart(cur: Int, target: Int) {
        simFlipJob?.cancel()
        // 自置可见性：不依赖手势阶段遗留的 animating 值，动画生命周期自包含
        simFlip.animating = true
        simFlip.isRunning = true
        // 记录本动画将要落地的页（打断时提交用）；回弹/越界不做翻页
        val commit = simFlipCommitPage(simFlip.isCancel, target, window.pageCount)
        simFlip.settleTarget = commit
        val wf = screenWidthPx.toFloat()
        val hf = screenHeightPx.toFloat()
        val dx: Float
        val dy: Float
        if (simFlip.isCancel) {
            // 回弹：滚回卷角（对齐 Legado SimulationPageDelegate.onAnimStart isCancel）。
            // 不能滚回手指起点 startX/startY——按下点本就比物理角点往里偏一小段，
            // 取消时书页会停在离角落一小段距离的地方消失而不是完整折回角落。
            dx = if (simFlip.direction != PageCurl.Direction.NEXT) {
                // PREV：滚出左屏边缘（对齐 Legado：isCancel 时 PREV 统一 -(viewWidth+touchX)）
                -(wf + simFlip.touchX)
            } else if (simFlip.cornerX > 0) {
                wf - simFlip.touchX    // NEXT 右边缘角：收到右缘
            } else {
                -simFlip.touchX        // NEXT 左边缘角：收到左缘
            }
            dy = if (simFlip.cornerY > 0) hf - simFlip.touchY else -simFlip.touchY
        } else {
            // 完成：滚过屏幕边缘（对齐 Legado SimulationPageDelegate.onAnimStart !isCancel）
            if (simFlip.direction == PageCurl.Direction.NEXT) {
                dx = -(wf + simFlip.touchX)     // NEXT: 往左滚过屏幕
            } else {
                dx = wf + wf - simFlip.touchX   // PREV: 往右滚过屏幕，越过右边缘
            }
            dy = if (simFlip.cornerY > 0f) hf - simFlip.touchY else 1f - simFlip.touchY
        }
        launchCurlAnim(dx, dy, commitPage = commit)
    }

    /** 分页翻页：左/右 1/3 点按一页（跨章自动续翻）；仿真走真卷页动画。 */
    fun goPage(next: Int) {
        if (simFlip.animating) return
        val cur = pagerState.currentPage
        if (next < 0 || next >= window.pageCount) {
            // 窗口边界：跨章续翻（目标章首页或上一章末页）
            val ci = state.chapters.indexOfFirst { it.id == state.activeChapterId }
            val nid = when {
                next >= window.pageCount -> state.chapters.getOrNull(ci + 1)?.id
                next < 0 -> state.chapters.getOrNull(ci - 1)?.id
                else -> null
            } ?: return
            val targetOffset = if (next >= window.pageCount) 0
            else state.chapters.firstOrNull { it.id == nid }?.content?.length ?: 0
            pagerJumpTarget = nid
            pagerJumpOffset = targetOffset
            vm.navigateTo(nid, targetOffset)
            return
        }
        if (flipMode == ReadingSettings.FLIP_SIMULATION) {
            startSimFlip(cur, next, next > cur)
            return
        }
        scope.launch {
            if (flipMode == ReadingSettings.FLIP_NO_ANIM) {
                pagerState.scrollToPage(next)
            } else {
                pagerState.animateScrollToPage(next)
            }
        }
    }

    // 滚动模式跨章滚动状态：分页模式不解析全书（打开书籍提速），
    // 首次进入滚动模式时构建并跨模式缓存；章节内容变化时重置
    var scrollChunks by remember(state.chapters, pageStyle.titleMode) {
        mutableStateOf(emptyList<ScrollItem>())
    }
    // key 用 state.chapters 而非 scrollChunks.isEmpty()：章节未加载时构建产出空列表，
    // isEmpty() 不发生 true→false 翻转，effect 不会被标记为需要重新执行；
    // 随后 state.chapters 加载触发 remember 重置 scrollChunks 为空，但 effect key 不变、不重启 → 死锁。
    // 改为 key 章节列表本身：章节加载完成时 key 变化，effect 必然重启。
    LaunchedEffect(!isPagerMode, state.chapters) {
        // 章节未加载时不构建：空构建产出空列表，会让渲染条件误判为「正在构建」而非「无内容」
        if (!isPagerMode && state.chapters.isNotEmpty()) {
            scrollChunks = buildScrollChunks(state.chapters, pageStyle.titleMode)
        }
    }
    val scrollState = rememberLazyListState()
    // 程序化跳章标记（目录/上下章按钮设置，滚动跟踪不响应）
    var pendingJumpChapter by remember { mutableStateOf<Long?>(null) }
    // 程序化滚动进行中标记：期间滚动跟踪不响应，避免回卷（初始定位/跳章后 300ms 内）
    var suppressTracking by remember { mutableStateOf(false) }

    // ── 打开书籍过渡遮罩 ──
    // 章节/位置未恢复、分页中心章后台排版未完成、或滚动内容构建中时显示；
    // 内容就绪后淡出（掩盖加载过程感）。书籍确无章节（章节流已到达且为空）时不遮罩，
    // 落回 EmptyReaderHint。注意「章节流已到达」必须用 chaptersLoaded：并行加载下
    // 书籍信息可能先于章节列表到达，用书名非空推断会瞬时误判成无章节的书。
    val bookHasNoChapters = state.chaptersLoaded && state.chapters.isEmpty()
    val opening = !bookHasNoChapters && (
        !state.restoreReady ||
            (isPagerMode && window.pageCount == 0) ||
            (!isPagerMode && state.chapters.isNotEmpty() && scrollChunks.isEmpty())
        )
    LaunchedEffect(opening) {
        if (!opening) OpenBookProbe.finish()
    }

    // 初始定位 + 切换到滚动模式时定位到当前章
    LaunchedEffect(scrollChunks, !isPagerMode, state.activeChapterId, state.position?.offset) {
        if (isPagerMode || scrollChunks.isEmpty() || !state.restoreReady) return@LaunchedEffect
        val idx = scrollChunks.indexInChunks(state.activeChapterId, state.position?.offset ?: 0) ?: return@LaunchedEffect
        suppressTracking = true
        scrollState.scrollToItem(idx)
        kotlinx.coroutines.delay(300)
        suppressTracking = false
    }

    // 滚动模式：可见内容项直接提供章节 + 原文 offset
    LaunchedEffect(scrollState, scrollChunks, isPagerMode) {
        if (isPagerMode) return@LaunchedEffect
        snapshotFlow {
            scrollState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                ?.let { scrollChunks.getOrNull(it) }
        }.collect { visibleItem ->
            if (suppressTracking || pendingJumpChapter != null || visibleItem == null) return@collect
            vm.updateProgress(visibleItem.chapterId, visibleItem.sourceStartOffset)
        }
    }

    // 程序化跳章后滚动到位（目录/上下章）
    LaunchedEffect(state.activeChapterId, scrollChunks.size) {
        val pending = pendingJumpChapter
        if (pending != null && pending == state.activeChapterId) {
            val idx = scrollChunks.indexInChunks(state.activeChapterId)
            if (idx != null) {
                suppressTracking = true
                scrollState.scrollToItem(idx)
                kotlinx.coroutines.delay(300)
                suppressTracking = false
            }
            pendingJumpChapter = null
        }
    }

    /** 程序化跳章：分页模式滚动到目标章首页（当前章重定位也走这里）；滚动模式标记 pending。 */
    fun jumpToChapter(id: Long) {
        if (isPagerMode) {
            pagerJumpTarget = id
            pagerJumpOffset = 0
        } else {
            pendingJumpChapter = id
        }
        pagerJumpOffset = 0
        vm.navigateTo(id, 0)
    }

    /** 分页模式跳章（上一/下一章）：边界无章可跳则忽略。 */
    fun jumpChapterBy(offset: Int) {
        val idx = state.chapters.indexOfFirst { it.id == state.activeChapterId }
        val target = if (offset < 0 && idx > 0) state.chapters[idx - 1].id
        else if (offset > 0 && idx in 0 until state.chapters.size - 1) state.chapters[idx + 1].id
        else return // 边界：没有可跳的章节
        jumpToChapter(target)
    }

    // 浮层可见性追踪：供 pointerInput 内点按时判断是否拦截翻页（不加入 key 避免手势重启）
    val anyOverlayVisible = state.toolbarVisible || state.catalogVisible || state.settingsVisible || state.llmSettingsVisible
    val overlayVisible by rememberUpdatedState(anyOverlayVisible)
    // 单手模式追踪：pointerInput 块内读取外部状态必须经 rememberUpdatedState 拿最新值
    // （闭包是手势协程启动时快照的旧引用，直接读 readingSettings.oneHandMode 会读到
    //  开启设置前的旧值导致开关无效；与 overlayVisible 同款模式，不加入 key）
    val oneHandMode by rememberUpdatedState(readingSettings.oneHandMode)
    // 词典/解释弹窗打开中：下一次点击只关弹窗不翻页（与选区清除同款交互）
    val isDictPopupOpen by rememberUpdatedState(state.dictQueryWord != null || state.explainWord != null)

    // ── 选区/词典弹窗生命周期：翻页、滚动、切章、切模式、开浮层时清除 ──
    LaunchedEffect(isPagerMode, scrollState, pagerState) {
        val scrolling = if (isPagerMode) {
            snapshotFlow { pagerState.isScrollInProgress }
        } else {
            snapshotFlow { scrollState.isScrollInProgress }
        }
        scrolling.collect { inProgress ->
            if (inProgress) {
                selectionState.clear()
                vm.dismissDictPopup()
                vm.dismissExplainPopup()
            }
        }
    }
    LaunchedEffect(pagerState.currentPage, state.mode, state.activeChapterId, state.chapters) {
        selectionState.clear()
        vm.dismissExplainPopup()
    }
    LaunchedEffect(state.toolbarVisible, state.catalogVisible, state.settingsVisible, state.llmSettingsVisible) {
        if (anyOverlayVisible) {
            selectionState.clear()
            vm.dismissExplainPopup()
        }
    }

    // 页面离开/进入后台时，把内存中的最新原文位置同步到 Room。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                syncPagerProgressBeforeFlush()
                flushScope.launch { vm.flushProgress() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ── 阅读器沉浸式（对齐 Legado BaseReadBookActivity.upSystemUiVisibility） ──
    // toolBarHide = 浮层关闭 → 应隐藏系统栏（若设置允许）；浮层打开 → 应显示系统栏
    val toolBarHide = !anyOverlayVisible
    val immersiveView = LocalView.current
    val immersiveActivity = immersiveView.context as? Activity

    // 离开阅读器时恢复系统栏
    DisposableEffect(Unit) {
        onDispose {
            immersiveActivity?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, immersiveView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
        }
    }

    // 系统返回键：恢复系统栏 + 存储进度 + 返回（对齐工具栏返回按钮行为）
    BackHandler {
        immersiveActivity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, immersiveView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
        syncPagerProgressBeforeFlush()
        flushScope.launch {
            vm.flushProgress()
            onBack()
        }
    }

    // 对齐 Legado：toolBarHide + hideStatusBar/hideNavigationBar 双条件控制
    // WindowCompat.getInsetsController 内部按 API 级别走 window.insetsController（R+）
    // 或 systemUiVisibility flags（legacy），对齐 Legado 双路径但用统一 compat 接口
    LaunchedEffect(toolBarHide, readingSettings.hideStatusBar, readingSettings.hideNavigationBar) {
        val window = immersiveActivity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, immersiveView)
        if (toolBarHide && readingSettings.hideNavigationBar) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        if (toolBarHide && readingSettings.hideStatusBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // 对齐 Legado：阅读器内覆盖 Theme.kl 的 SideEffect（状态栏颜色 + 图标明暗）
    // SideEffect 在子组件注册晚于父组件 Theme，故执行顺序在 Theme 之后，可覆盖
    SideEffect {
        immersiveActivity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, immersiveView)
            // 状态栏图标：浅色背景用深色图标，深色背景用浅色图标（对齐 Legado curStatusIconDark）
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
            // 状态栏/导航栏颜色：对齐阅读器背景（对齐 Legado readBarStyleFollowPage）
            window.statusBarColor = bgColor.toArgb()
            window.navigationBarColor = bgColor.toArgb()
        }
    }

    // 手势容器在窗口中的位置，用于手柄坐标转换
    var containerWindowOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .onGloballyPositioned { containerWindowOffset = it.positionInWindow() }
            .pointerInput(isPagerMode, flipMode, readingSettings.paddingH, readingSettings.paddingV, statusBarPx, navBarPx, window) {
                val inSim = isPagerMode && flipMode == ReadingSettings.FLIP_SIMULATION
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (inSim) {
                        // ── 仿真模式手势（对齐 Legado HorizontalPageDelegate + SimulationPageDelegate）──
                        // 全屏坐标系：位图=全屏，手势坐标直接用屏幕坐标（不减边距），对齐 Legado
                        val viewW = size.width.toFloat()
                        val viewH = size.height.toFloat()
                        val slopSquare = 30f * 30f // 对齐 Legado pageSlopSquare2

                        // DOWN: 记录起点，reset 状态，计算角落
                        // 有新触摸打断自动动画时：先把未完成的翻页稳妥落地（瞬时跳到动画目标页），
                        // 再做清理——否则动画会在中途直接消失、翻页也不生效（突兀）。
                        val hadRunningAnim = simFlipJob != null
                        simFlipJob?.cancel()
                        simFlipJob = null
                        val settle = if (hadRunningAnim) {
                            simFlipSettlePage(simFlip, pagerState.currentPage, window.pageCount)
                        } else -1
                        simFlip.cleanup()
                        if (settle >= 0) {
                            // requestScrollToPage = 无动画瞬时落地（internal snapToItem 的公开入口）
                            try {
                                pagerState.requestScrollToPage(settle)
                            } catch (e: Exception) {
                                // 窗口重建等罕见竞态：放弃接管分页器，落日志供「设置 → 调试」定位
                                AppLog.put("仿真打断落地 requestScrollToPage($settle) 失败", e)
                            }
                        }
                        val downX = down.position.x
                        val downY = down.position.y
                        simFlip.onDown(downX, downY)
                        // 本次 DOWN 打断了动画并提交翻页：该手势的左右点按只作「打断确认」，不再翻页
                        simFlip.downSettledFlip = settle >= 0
                        simFlip.calcCornerXY(downX, viewW, viewH)
                        simFlip.curl.setViewSize(viewW, viewH)
                        // 已有选区时：DOWN 被手柄消费则保留选区，否则清除
                        val hadSelectionAtDown = selectionState.isSelecting
                        if (hadSelectionAtDown && !down.isConsumed) {
                            selectionState.clear()
                        }
                        val hadDictAtDown = isDictPopupOpen

                        var curlActive = false
                        var gestureStartedWithOverlay = overlayVisible

                        // 手势循环被中途接管（选词消费事件/指针消失）时的退场：已卷起的页按
                        // 「抬手取消」走回弹动画收场，保持视觉连续；绝不带位图静默退出——
                        // 那会让冻结的卷页画面停留到下一次 DOWN 才被清理。后续新 DOWN 仍可
                        // 正常打断这次回弹并按 settleTarget 落地。
                        fun abortCurlBounce() {
                            if (!curlActive) return
                            val cur = pagerState.currentPage
                            val target = if (simFlip.direction == PageCurl.Direction.NEXT) cur + 1 else cur - 1
                            simFlip.isCancel = true
                            simFlipAnimStart(cur, target)
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                // 被跟踪指针从事件流消失（系统取消）：按中途退场收尾
                                abortCurlBounce()
                                break
                            }
                            if (!change.pressed) {
                                // ── UP ──
                                if (hadDictAtDown) {
                                    // 词典/解释弹窗打开中：只关弹窗，不翻页
                                    vm.dismissDictPopup()
                                    vm.dismissExplainPopup()
                                    break
                                }
                                if (hadSelectionAtDown) {
                                    // 选区已在 DOWN 清除；若本次是新的长按选词（内层已消费），
                                    // 新选区保持不动。但已卷起的页不能直接 break 冻结：以回弹
                                    // 收场（isCancel=true 不提交翻页），保持视觉连续，也避免
                                    // animating/isRunning 残留到下一次 DOWN 才被 cleanup 清掉。
                                    if (curlActive) {
                                        val cur = pagerState.currentPage
                                        val target = if (simFlip.direction == PageCurl.Direction.NEXT) cur + 1 else cur - 1
                                        simFlip.isCancel = true
                                        simFlipAnimStart(cur, target)
                                    }
                                    break
                                }
                                if (curlActive) {
                                    // 抬手：启动 Scroller 式动画（cancel 回弹 / complete 完成）
                                    val cur = pagerState.currentPage
                                    val target = if (simFlip.direction == PageCurl.Direction.NEXT) cur + 1 else cur - 1
                                    simFlipAnimStart(cur, target)
                                } else if (!simFlip.isMoved && !change.isConsumed) {
                                    // 单击：三段点按
                                    val x = down.position.x
                                    val third = size.width / 3f
                                    if (overlayVisible) {
                                        vm.dismissAllOverlays()
                                    } else {
                                        // 本手势 DOWN 已打断并提交了一次翻页：左右点按视为「打断确认」，
                                        // 不再重复翻页（否则 PREV 右滑被打断后又被点按翻回原页，右下角
                                        // 反复卷页乱闪、反直觉）；中间 1/3 仍可开关工具栏。
                                        val flipConsumed = simFlip.downSettledFlip
                                        // 单手模式：左 1/3 点击也翻下一页（左手拇指够不到右侧）
                                        val leftGoNext = oneHandMode
                                        when {
                                            x < third -> {
                                                if (!flipConsumed) goPage(pagerState.currentPage + if (leftGoNext) 1 else -1)
                                            }
                                            x < third * 2 -> vm.toggleToolbar()
                                            else -> {
                                                if (!flipConsumed) goPage(pagerState.currentPage + 1)
                                            }
                                        }
                                    }
                                }
                                break
                            }
                            // ── MOVE ──
                            // 内层（选词长按）已消费的事件不再驱动卷页，防止长按后拖动误触发；
                            // 已卷起时先回弹收场再退出，避免残留冻结画面
                            if (change.isConsumed) {
                                abortCurlBounce()
                                break
                            }
                            val focusX = change.position.x
                            val focusY = change.position.y
                            if (!simFlip.isMoved) {
                                val deltaX = focusX - simFlip.startX
                                val deltaY = focusY - simFlip.startY
                                val distance = deltaX * deltaX + deltaY * deltaY
                                if (distance > slopSquare) {
                                    if (gestureStartedWithOverlay) {
                                        vm.dismissAllOverlays()
                                        gestureStartedWithOverlay = false
                                    }
                                    simFlip.isMoved = true
                                    if (focusX - simFlip.startX > 0) {
                                        // 右滑 → PREV
                                        val target = pagerState.currentPage - 1
                                        if (target < 0) break
                                        simFlip.setDirection(PageCurl.Direction.PREV, viewW, viewH)
                                        val pair = curlBitmaps(pagerState.currentPage, target)
                                        if (pair == null) break
                                        simFlip.curBitmap = pair.first
                                        simFlip.targetBitmap = pair.second
                                        simFlip.bgColor = bgColor.toArgb()
                                        simFlip.animating = true
                                        curlActive = true
                                    } else {
                                        // 左滑 → NEXT
                                        val target = pagerState.currentPage + 1
                                        if (target >= window.pageCount) break
                                        simFlip.setDirection(PageCurl.Direction.NEXT, viewW, viewH)
                                        val pair = curlBitmaps(pagerState.currentPage, target)
                                        if (pair == null) break
                                        simFlip.curBitmap = pair.first
                                        simFlip.targetBitmap = pair.second
                                        simFlip.bgColor = bgColor.toArgb()
                                        simFlip.animating = true
                                        curlActive = true
                                    }
                                }
                            }
                            if (curlActive) {
                                // isCancel 判定（对齐 Legado HorizontalPageDelegate.onScroll）
                                simFlip.isCancel = if (simFlip.direction == PageCurl.Direction.NEXT) {
                                    focusX > simFlip.lastX
                                } else {
                                    focusX < simFlip.lastX
                                }
                                simFlip.lastX = focusX
                                simFlip.lastY = focusY
                                // 更新触摸点（跟手）
                                simFlip.touchX = focusX
                                simFlip.touchY = focusY
                                // 垂直位置调整（对齐 Legado SimulationPageDelegate.onTouch MOVE）
                                simFlip.adjustTouchY(viewH)
                                simFlip.isRunning = true
                            }
                            if (change.isConsumed) {
                                abortCurlBounce()
                                break
                            }
                        }
                    } else {
                        // ── 其他模式：三段点按；有浮层时，滑动先关闭浮层再交给分页器 ──
                        val downX = down.position.x
                        val downY = down.position.y
                        val slopSquare = 30f * 30f
                        var moved = false
                        // 已有选区时：DOWN 被手柄消费则保留选区，否则清除
                        val hadSelectionAtDown = selectionState.isSelecting
                        if (hadSelectionAtDown && !down.isConsumed) {
                            selectionState.clear()
                        }
                        val hadDictAtDown = isDictPopupOpen
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (hadDictAtDown) {
                                    // 词典/解释弹窗打开中：只关弹窗，不翻页
                                    vm.dismissDictPopup()
                                    vm.dismissExplainPopup()
                                    break
                                }
                                if (hadSelectionAtDown) {
                                    // 选区已在 DOWN 清除；若本次是新的长按选词（内层已消费），
                                    // 新选区保持不动。这里只确保不触发翻页
                                    break
                                }
                                if (!moved && !change.isConsumed) {
                                    val x = down.position.x
                                    val third = size.width / 3f
                                    if (overlayVisible) {
                                        // 点击浮层时只关闭浮层，不翻页
                                        vm.dismissAllOverlays()
                                    } else if (isPagerMode) {
                                        // 单手模式：左 1/3 点击也翻下一页（单手拇指够不到左侧）
                                        val leftGoNext = oneHandMode
                                        when {
                                            x < third -> goPage(pagerState.currentPage + if (leftGoNext) 1 else -1)
                                            x < third * 2 -> vm.toggleToolbar()
                                            else -> goPage(pagerState.currentPage + 1)
                                        }
                                    } else {
                                        // 滚动模式：仅中间 1/3 开关菜单
                                        if (x >= third && x < third * 2) {
                                            vm.toggleToolbar()
                                        }
                                    }
                                }
                                break
                            }
                            if (!moved && readerShouldDismissOverlayOnGestureStart(overlayVisible)) {
                                val dx = change.position.x - downX
                                val dy = change.position.y - downY
                                if (dx * dx + dy * dy > slopSquare) {
                                    vm.dismissAllOverlays()
                                    moved = true
                                }
                            }
                            if (change.isConsumed) break
                        }
                    }
                }
            }
    ) {
        // ── Main content ──
        when {
            isPagerMode -> {
                when {
                    window.pageCount > 0 ->
                        ReaderPager(
                            pagerState = pagerState,
                            window = window,
                            flipMode = flipMode,
                            palette = palette,
                            mode = state.mode,
                            pageStyle = pageStyle,
                            paddingH = readingSettings.paddingH,
                            paddingV = readingSettings.paddingV,
                            statusBarPx = statusBarPx,
                            navBarPx = navBarPx,
                            simFlip = simFlip,
                            // 卷页动画进行中或菜单栏显示时禁用长按选词
                            selectionState = if (simFlip.isRunning || anyOverlayVisible) null else selectionState,
                            // 菜单栏/浮层显示时禁用原文气泡点击（点击落回外层手势关闭菜单）
                            bubbleEnabled = !anyOverlayVisible,
                            onIllustrationClick = { previewIllustrationPath = it },
                            // 中文两端对齐：正文内容区宽度（与排版几何同源）
                            contentWidthPx = geometry.contentWidthPx.toInt()
                        )
                    state.chaptersLoaded -> EmptyReaderHint(isDark)
                    // else：打开中（章节加载/中心章后台排版），由全局过渡遮罩呈现
                }
            }
            else -> {
                when {
                    state.chapters.isEmpty() -> EmptyReaderHint(isDark)
                    scrollChunks.isEmpty() ->
                        // 滚动内容解析中（全书构建），过渡遮罩呈现，完成后自动填充
                        ReaderOpeningShade(
                            bookTitle = state.bookTitle.ifEmpty { "正在打开" },
                            bgColor = bgColor,
                            titleColor = palette.titleText,
                            accentColor = accentColor
                        )
                    else ->
                        ScrollReader(
                            chapters = state.chapters,
                            chunks = scrollChunks,
                            scrollState = scrollState,
                            state = state,
                            pageStyle = pageStyle,
                            palette = palette,
                            paddingH = readingSettings.paddingH,
                            paddingV = readingSettings.paddingV,
                            statusBarPx = statusBarPx,
                            navBarPx = navBarPx,
                            onJumpChapter = { id ->
                                pendingJumpChapter = id
                                vm.navigateTo(id, 0)
                            },
                            selectionState = if (anyOverlayVisible) null else selectionState,
                            // 菜单栏/浮层显示时禁用原文气泡点击（点击落回外层手势关闭菜单）
                            bubbleEnabled = !anyOverlayVisible,
                            onIllustrationClick = { previewIllustrationPath = it },
                            // 中文两端对齐：正文内容区宽度（与排版几何同源）
                            contentWidthPx = geometry.contentWidthPx.toInt()
                        )
                }
            }
        }

        // ── 打开书籍过渡遮罩：内容就绪后淡出，掩盖章节加载与中心章后台排版的过程感 ──
        AnimatedVisibility(
            visible = opening,
            enter = EnterTransition.None,
            exit = fadeOut(animationSpec = tween(durationMillis = 280)),
            modifier = Modifier.fillMaxSize()
        ) {
            ReaderOpeningShade(
                bookTitle = state.bookTitle.ifEmpty { "正在打开" },
                bgColor = bgColor,
                titleColor = palette.titleText,
                accentColor = accentColor
            )
        }

        // ── 页眉/页脚（视觉覆盖层，仅分页模式；顶栏/底栏隐藏，底部面板不遮挡）──
        if (isPagerMode && window.pageCount > 0 && !state.toolbarVisible) {
            PageInfoOverlays(
                window = window,
                chapters = state.chapters,
                activeChapterId = state.activeChapterId,
                pagerState = pagerState,
                palette = palette,
                padH = readingSettings.paddingH,
                padV = readingSettings.paddingV,
                headerContentGap = readingSettings.headerContentGap,
                footerContentGap = readingSettings.footerContentGap,
                statusBarPx = statusBarPx,
                navBarPx = navBarPx,
                cutoutLeftPx = cutoutLeftPx,
                cutoutRightPx = cutoutRightPx
            )
        }

        // ── 顶栏（无目录按钮 — 目录在底部栏） ──
        AnimatedVisibility(
            visible = state.toolbarVisible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReaderTopToolbar(
                bookTitle = state.bookTitle,
                mode = state.mode,
                activeChapterStatus = state.activeChapter?.status,
                barColor = bgColor,
                onBack = {
                    // 离开前恢复系统栏，避免书架布局跳动
                    immersiveActivity?.window?.let { window ->
                        val controller = WindowCompat.getInsetsController(window, immersiveView)
                        controller.show(WindowInsetsCompat.Type.systemBars())
                        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                    }
                    syncPagerProgressBeforeFlush()
                    flushScope.launch {
                        vm.flushProgress()
                        onBack()
                    }
                },
                onToggleMode = { vm.switchMode(it) }
            )
        }

        // ── 底栏：两行（章节滑动 + 操作） ──
        AnimatedVisibility(
            visible = state.toolbarVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onGloballyPositioned { coords ->
                    bottomBarHeightDp = with(density) { coords.size.height.toDp() }.value
                }
        ) {
            ReaderBottomBar(
                chapters = state.chapters,
                activeChapterId = state.activeChapterId,
                accentColor = accentColor,
                barColor = bgColor,
                isRetryEnabled = state.mode == "en" && !state.isStreaming
                    && state.activeChapter?.status in setOf(
                        Chapter.STATUS_DONE, Chapter.STATUS_FAILED, Chapter.STATUS_IN_PROGRESS
                    ),
                onPrev = { jumpChapterBy(-1) },
                onNext = { jumpChapterBy(1) },
                onChapterJump = { id -> jumpToChapter(id) },
                onToggleCatalog = { vm.toggleCatalog() },
                onOpenLlmSettings = { vm.initLlmEditFields(); vm.toggleLlmSettings() },
                onRetry = { state.activeChapterId?.let { vm.retryTranslation(it) } },
                onOpenSettings = { vm.toggleSettings() }
            )
        }

        // ── 翻译状态面板（流式进度 + 非流式章节状态） ──
        val activeChapter = state.activeChapter
        val showStatusPanel = state.isStreaming || (
            state.mode == "en" && activeChapter != null
            && activeChapter.status != Chapter.STATUS_DONE
            && !state.catalogVisible && !state.settingsVisible && !state.llmSettingsVisible
        )
        TranslationStatusPanel(
            state = state,
            pageStyle = pageStyle,
            isDark = isDark,
            bottomBarHeightDp = bottomBarHeightDp,
            navBarPx = navBarPx,
            visible = showStatusPanel
        )

        // ── 长按选词工具栏（无选区时隐藏；弹窗为独立 focusable 窗口） ──
        // showToolbar 控制显隐：拖拽手柄时工具栏隐藏，选区保留；拖拽结束或长按选中后显示
        if (selectionState.isSelecting && selectionState.showToolbar) {
            SelectionToolbar(
                selectionState = selectionState,
                palette = palette,
                onLookup = { word ->
                    dictAnchor = selectionState.popupPosition
                    selectionState.clear()
                    vm.lookupDictWord(word)
                },
                onExplain = { word ->
                    dictAnchor = selectionState.popupPosition
                    val paraText = selectionState.paragraphText
                    selectionState.clear()
                    vm.explainWord(word, paraText)
                },
                onCopy = { word ->
                    clipboard.setText(AnnotatedString(word))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    selectionState.clear()
                },
                onDismissRequest = {
                    // 点击工具栏外部：仅关闭工具栏，保留选区供手柄继续交互
                    selectionState.dismissToolbar()
                }
            )
        }

        // ── 选择手柄（竖线+圆点，拖拽扩展选区；覆盖层渲染，不触发翻页） ──
        SelectionHandles(
            selectionState = selectionState,
            palette = palette,
            density = density,
            containerWindowOffset = containerWindowOffset
        )

        // ── 词典查询结果弹窗 ──
        state.dictQueryWord?.let { queryWord ->
            DictPopup(
                queryWord = queryWord,
                entry = state.dictEntry,
                loading = state.dictLoading,
                anchor = dictAnchor,
                palette = palette,
                pageStyle = pageStyle,
                onDismiss = { vm.dismissDictPopup() }
            )
        }

        // ── LLM 词语解释弹窗 ──
        state.explainWord?.let { word ->
            ExplainPopup(
                queryWord = word,
                result = state.explainResult,
                loading = state.explainLoading,
                error = state.explainError,
                anchor = dictAnchor,
                palette = palette,
                pageStyle = pageStyle,
                onDismiss = { vm.dismissExplainPopup() }
            )
        }

        // ── 全屏插图预览（最上层；单击关闭，双指缩放） ──
        previewIllustrationPath?.let { path ->
            IllustrationPreviewOverlay(path = path) { previewIllustrationPath = null }
        }
    }

    // ── Catalog bottom sheet ──
    if (state.catalogVisible) {
        CatalogBottomSheet(
            groups = catalogGroups,
            activeChapterId = state.activeChapterId,
            onChapterClick = { id -> jumpToChapter(id) },
            onDismiss = { vm.dismissCatalog() }
        )
    }

    // ── Reading settings sheet ──
    if (state.settingsVisible) {
        ReaderSettingsSheet(
            settings = readingSettings,
            accentColor = accentColor,
            onUpdate = { new -> vm.updateReadingSettings { new } },
            onDismiss = { vm.dismissSettings() }
        )
    }

    // ── LLM 翻译设置面板 ──
    if (state.llmSettingsVisible) {
        LlmSettingsSheet(
            llmSettings = state.llmSettings,
            profiles = state.profiles,
            activeProfileId = state.activeProfileId,
            editingProfileId = state.editingProfileId,
            editApiKey = vm.editApiKey.collectAsState().value,
            editApiBase = vm.editApiBase.collectAsState().value,
            editModel = vm.editModel.collectAsState().value,
            accentColor = accentColor,
            testResult = state.llmTestResult,
            testSuccess = state.llmTestSuccess,
            onUpdateApiKey = vm::updateEditApiKey,
            onUpdateApiBase = vm::updateEditApiBase,
            onUpdateModel = vm::updateEditModel,
            onUpdateChapterMaxChars = vm::updateLlmChapterMaxChars,
            onUpdateMaxOutputTokens = vm::updateLlmMaxOutputTokens,
            onToggleThinking = vm::updateLlmThinking,
            onToggleExplainThinking = vm::updateLlmExplainThinking,
            onToggleAutoTranslateNext = vm::updateLlmAutoTranslateNext,
            onUpdateTemperature = vm::updateLlmTemperature,
            onUpdateTopP = vm::updateLlmTopP,
            onSwitchProfile = vm::switchProfile,
            onEditProfile = vm::editProfileInSheet,
            onCancelEdit = vm::cancelProfileEditInSheet,
            onSave = vm::saveLlmSettings,
            onTest = vm::testLlmConnection,
            onDismiss = { vm.dismissLlmSettings() }
        )
    }
}
