package com.vibereading.app.ui.reader

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.reader.components.BilingualParagraph
import com.vibereading.app.ui.reader.components.ReadingChapterTitle
import com.vibereading.app.ui.reader.components.ReadingParagraphItem
import com.vibereading.app.ui.reader.content.ReadingContent
import com.vibereading.app.ui.reader.components.CatalogBottomSheet
import com.vibereading.app.ui.reader.components.CatalogGroup
import com.vibereading.app.ui.reader.components.PageInfoOverlays
import com.vibereading.app.ui.reader.components.LlmSettingsSheet
import com.vibereading.app.ui.reader.components.ReaderSettingsSheet
import com.vibereading.app.ui.reader.components.parseBilingualParagraphs
import com.vibereading.app.ui.reader.components.splitParagraphs
import com.vibereading.app.ui.reader.pagination.*
import com.vibereading.app.ui.theme.ReaderBgPresets
import com.vibereading.app.ui.theme.VibeColors
import com.vibereading.app.ui.theme.VibeDarkColors
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
    val bgColor = if (state.nightMode) ReaderBgPresets.DarkNight
    else bgPresets.getOrElse(readingSettings.bgColorIndex) { ReaderBgPresets.WarmCream }
    val isDark = state.nightMode || readingSettings.bgColorIndex == 4
    val flipMode = readingSettings.pageFlipMode
    val isPagerMode = flipMode != ReadingSettings.FLIP_SCROLL
    val accentColor = MaterialTheme.colorScheme.primary
    // 语义色板：把 isDark 亮/暗三元集中一处（正文/标题/气泡/弹窗共用）
    val palette = remember(isDark) { ReaderPalette.of(isDark) }

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
    val pageStyle = remember(readingSettings, density, state.mode) { PageStyle.of(readingSettings, density, state.mode) }
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

    // 窗口 key 含 isPagerMode/页边距：滚动↔分页切换、边距调整时重建窗口并重新排版
    // （否则旧窗口状态残留，导致切换不生效/边距不生效）
    // 立即 recenterSync：避免 key 变化（如沉浸式切换导致 contentHeightPx 变化）时新窗口
    // windowPages 为空 → pageCount==0 → 闪现 EmptyReaderHint（"没有任何阅读内容"）
    val window = remember(
        measurer, pageStyle, state.mode, state.chapters, contentWidthPx, contentHeightPx, isPagerMode
    ) {
        BookWindow(
            chapters = state.chapters,
            style = pageStyle,
            mode = state.mode,
            contentWidthPx = contentWidthPx,
            contentHeightPx = contentHeightPx,
            measurer = measurer,
            backgroundMeasurer = { bgMeasurer },
            displayDensity = density.density
        ).also { w ->
            state.activeChapterId?.let { w.recenterSync(it) }
        }
    }
    // 仿真卷页尺寸 = 全屏（对齐 Legado：位图/覆盖层/手势均使用全屏坐标系）

    val pagerState = rememberPagerState(initialPage = 0) { window.pageCount }
    val scope = rememberCoroutineScope()
    val simFlip = remember { SimFlipState() }

    // mode 切换时清除仿真卷页旧位图，避免反面显示旧模式文字
    LaunchedEffect(state.mode) {
        simFlip.cleanup()
    }

    // 分页模式「程序化跳章」目标（目录/上下章/窗口边界续翻）；「当前章重定位」也走这里
    var pagerJumpTarget by remember { mutableStateOf<Long?>(null) }
    var pagerJumpPage by remember { mutableIntStateOf(0) }
    // 首次恢复必须在窗口可按 sourceOffset 定位后才放行位置追踪
    var initialSeekDone by remember { mutableStateOf(false) }
    // 窗口滑动期间抑制「翻页同步章」，避免 recenter 滚动与跨章同步互相打架
    var windowSliding by remember { mutableStateOf(false) }

    // 分页模式：窗口中心跟随「当前章」滑动（索引空间重映射，视觉页不变）+ 程序化跳章/初始定位
    // key 含 pagerJumpTarget：点击当前章目录项时 activeChapterId 不变也能触发。
    LaunchedEffect(window, state.activeChapterId, pagerJumpTarget) {
        if (!isPagerMode) return@LaunchedEffect
        if (state.activeChapterId == null) return@LaunchedEffect
        val isProgrammatic = pagerJumpTarget != null
        val target = pagerJumpTarget ?: state.activeChapterId
        if (target == null) return@LaunchedEffect
        val sourceOffset = when {
            isProgrammatic -> 0
            !initialSeekDone -> state.position?.offset ?: 0
            else -> window.offsetOfPage(pagerState.currentPage)?.first ?: (state.position?.offset ?: 0)
        }
        windowSliding = true
        window.recenterSync(target)
        // 窗口滑动后索引空间变化：只使用新窗口内目标页，防止旧索引失效时跳到窗口第一页。
        val idx = window.indexOf(target, sourceOffset.toLong())
            ?: window.indexOf(target, 0)
            ?: window.indexOf(window.centerChapterId ?: target, 0)
            ?: 0
        if (pagerState.currentPage != idx) pagerState.scrollToPage(idx)
        initialSeekDone = true
        windowSliding = false
        pagerJumpTarget = null
        scope.launch { window.preloadNeighbors(target) }
    }

    // 分页模式：翻页时保存「章 + 章内页」进度；翻入新章同步 activeChapter（触发窗口滑动）
    LaunchedEffect(pagerState.currentPage, window, isPagerMode) {
        if (!isPagerMode || !initialSeekDone || windowSliding) return@LaunchedEffect
        val cp = window.chapterOfPage(pagerState.currentPage) ?: return@LaunchedEffect
        val offset = window.offsetOfPage(pagerState.currentPage)?.first ?: return@LaunchedEffect
        vm.updateProgress(cp, offset)
        if (cp != state.activeChapterId) vm.navigateTo(cp, offset)
    }

    /** 卷页快照对（对齐 Legado setBitmap）：curBitmap=当前页, targetBitmap=目标页。 */
    fun curlBitmaps(cur: Int, target: Int): Pair<Bitmap, Bitmap>? {
        val curBmp = renderPageBitmap(
            window, cur, state.mode, pageStyle, geometry, palette, density,
            bgColor.toArgb(), accentColor.toArgb(), measurer
        ) ?: return null
        val targetBmp = renderPageBitmap(
            window, target, state.mode, pageStyle, geometry, palette, density,
            bgColor.toArgb(), accentColor.toArgb(), measurer
        )
        if (targetBmp == null) { curBmp.recycle(); return null }
        return curBmp to targetBmp
    }

    /** 启动仿真卷页自动动画（对齐 Legado Scroller 式：cancel 回弹 / complete 完成）。 */
    fun startSimFlip(cur: Int, next: Int, goingNext: Boolean) {
        if (simFlip.isRunning) return
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
            // cornerY 保留点击高度（上半屏右上 / 下半屏右下，与右侧点击同规则）
            simFlip.cornerX = wf
            val startY = if (simFlip.startY > hf / 2) hf * 0.9f else 1f
            simFlip.startX = wf * 0.9f
            simFlip.startY = startY
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

        // Scroller 式动画（对齐 Legado onAnimStart）
        scope.launch {
            val startTouchX = simFlip.touchX
            val startTouchY = simFlip.touchY
            // complete: 滚过屏幕边缘（对齐 Legado SimulationPageDelegate.onAnimStart !isCancel）
            val dx = if (goingNext) {
                if (simFlip.cornerX > 0f) -(wf + startTouchX) else wf - startTouchX
            } else {
                wf - startTouchX
            }
            val dy = if (simFlip.cornerY > 0f) (hf - startTouchY) else (1f - startTouchY)
            val animationSpeed = 600 // ms，对齐 Legado defaultAnimationSpeed 量级
            val duration = if (dx != 0f) (animationSpeed * kotlin.math.abs(dx) / wf).toLong()
            else (animationSpeed * kotlin.math.abs(dy) / hf).toLong()
            val endX = startTouchX + dx
            val endY = startTouchY + dy
            val animatable = androidx.compose.animation.core.Animatable(0f)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = duration.coerceIn(150, 800).toInt(),
                    easing = androidx.compose.animation.core.LinearEasing
                )
            ) {
                simFlip.touchX = startTouchX + (endX - startTouchX) * value
                simFlip.touchY = startTouchY + (endY - startTouchY) * value
            }
            // 动画完成 → 翻页
            if (pagerState.currentPage != next) {
                pagerState.scrollToPage(next)
            }
            simFlip.cleanup()
        }
    }

    /** 仿真卷页抬手动画（对齐 Legado SimulationPageDelegate.onAnimStart：cancel 回弹 / complete 完成）。 */
    fun simFlipAnimStart(cur: Int, target: Int) {
        simFlip.isRunning = true
        val wf = screenWidthPx.toFloat()
        val hf = screenHeightPx.toFloat()
        scope.launch {
            val startTouchX = simFlip.touchX
            val startTouchY = simFlip.touchY
            val dx: Float
            val dy: Float
            if (simFlip.isCancel) {
                // 回弹：滚回屏幕边缘（对齐 Legado SimulationPageDelegate.onAnimStart isCancel）
                dx = if (startTouchX > wf / 2) {
                    wf - startTouchX   // touch 在右半 → 滚回右边
                } else {
                    -startTouchX       // touch 在左半 → 滚回左边
                }
                dy = if (simFlip.cornerY > 0f) hf - startTouchY else -startTouchY
            } else {
                // 完成：滚过屏幕边缘（对齐 Legado SimulationPageDelegate.onAnimStart !isCancel）
                if (simFlip.direction == PageCurl.Direction.NEXT) {
                    dx = -(wf + startTouchX)     // NEXT: 往左滚过屏幕
                } else {
                    dx = wf - startTouchX         // PREV: 往右滚过屏幕
                }
                dy = if (simFlip.cornerY > 0f) hf - startTouchY else 1f - startTouchY
            }
            val animationSpeed = 600
            val duration = if (dx != 0f) (animationSpeed * kotlin.math.abs(dx) / wf).toLong()
            else (animationSpeed * kotlin.math.abs(dy) / hf).toLong()
            val endX = startTouchX + dx
            val endY = startTouchY + dy
            val animatable = androidx.compose.animation.core.Animatable(0f)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = duration.coerceIn(100, 600).toInt(),
                    easing = androidx.compose.animation.core.LinearEasing
                )
            ) {
                simFlip.touchX = startTouchX + (endX - startTouchX) * value
                simFlip.touchY = startTouchY + (endY - startTouchY) * value
            }
            if (!simFlip.isCancel && target in 0 until window.pageCount) {
                if (pagerState.currentPage != target) {
                    pagerState.scrollToPage(target)
                }
            }
            simFlip.cleanup()
        }
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
            pagerJumpPage = 0
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

    // 滚动模式跨章滚动状态
    val scrollChunks = remember(state.chapters, state.mode) { buildScrollChunks(state.chapters) }
    val scrollState = rememberLazyListState()
    // 程序化跳章标记（目录/上下章按钮设置，滚动跟踪不响应）
    var pendingJumpChapter by remember { mutableStateOf<Long?>(null) }
    // 程序化滚动进行中标记：期间滚动跟踪不响应，避免回卷（初始定位/跳章后 300ms 内）
    var suppressTracking by remember { mutableStateOf(false) }

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
            pagerJumpPage = 0
        } else {
            pendingJumpChapter = id
        }
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

    // 页面离开/进入后台时，把内存中的最新原文位置同步到 Room。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                flushScope.launch { vm.flushProgress() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            kotlinx.coroutines.MainScope().launch { vm.flushProgress() }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
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
                        val downX = down.position.x
                        val downY = down.position.y
                        simFlip.onDown(downX, downY)
                        simFlip.calcCornerXY(downX, viewW, viewH)
                        simFlip.curl.setViewSize(viewW, viewH)

                        var curlActive = false
                        var gestureStartedWithOverlay = overlayVisible

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // ── UP ──
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
                                        // 单手模式：左 1/3 点击也翻下一页（左手拇指够不到右侧）
                                        val leftGoNext = oneHandMode
                                        when {
                                            x < third -> goPage(pagerState.currentPage + if (leftGoNext) 1 else -1)
                                            x < third * 2 -> vm.toggleToolbar()
                                            else -> goPage(pagerState.currentPage + 1)
                                        }
                                    }
                                }
                                break
                            }
                            // ── MOVE ──
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
                            if (change.isConsumed) break
                        }
                    } else {
                        // ── 其他模式：三段点按；有浮层时，滑动先关闭浮层再交给分页器 ──
                        val downX = down.position.x
                        val downY = down.position.y
                        val slopSquare = 30f * 30f
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
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
                if (window.pageCount == 0) {
                    EmptyReaderHint(isDark)
                } else {
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
                        simFlip = simFlip
                    )
                }
            }
            else -> {
                if (state.chapters.isEmpty()) {
                    EmptyReaderHint(isDark)
                } else {
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
                        }
                    )
                }
            }
        }

        // ── 页眉/页脚（视觉覆盖层，仅分页模式；浮层打开 / 翻译中隐藏，避免与顶栏/底部栏重叠）──
        if (isPagerMode && window.pageCount > 0 && !overlayVisible && !state.isStreaming) {
            PageInfoOverlays(
                window = window,
                chapters = state.chapters,
                activeChapterId = state.activeChapterId,
                pagerState = pagerState,
                palette = palette,
                padH = readingSettings.paddingH,
                padV = readingSettings.paddingV,
                overlayContentGap = readingSettings.overlayContentGap,
                statusBarPx = statusBarPx,
                navBarPx = navBarPx,
                cutoutLeftPx = cutoutLeftPx,
                cutoutRightPx = cutoutRightPx
            )
        }

        // ── Top toolbar (no catalog button — directory lives in the bottom bar) ──
        AnimatedVisibility(
            visible = state.toolbarVisible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = bgColor.copy(alpha = 0.95f),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        flushScope.launch {
                            vm.flushProgress()
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                    Text(
                        state.bookTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    // Mode toggle + 翻译状态小圆点（与目录同款）
                    Row(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(2.dp)
                    ) {
                        ModeButton("中文", state.mode == "zh", onClick = { vm.switchMode("zh") })
                        ModeButton("英文", state.mode == "en", onClick = { vm.switchMode("en") })
                    }
                    // 当前章翻译状态小圆点（颜色与目录一致）
                    val activeStatus = state.activeChapter?.status
                    if (activeStatus != null) {
                        val dotColor = chapterStatusColor(activeStatus)
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                }
            }
        }

        // ── Bottom bar: two rows ──
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
            BottomControlBar(
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
        if (showStatusPanel) {
            val panelBottomPadding = if (state.toolbarVisible) bottomBarHeightDp.dp + 8.dp
                else 16.dp + with(density) { navBarPx.toDp() }
            val panelColor = if (isDark) VibeDarkColors.Surface.copy(alpha = 0.92f) else VibeColors.Parchment.copy(alpha = 0.92f)

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = panelBottomPadding)
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                shape = RoundedCornerShape(12.dp),
                color = panelColor,
                shadowElevation = 8.dp
            ) {
                if (state.isStreaming) {
                    // ── 流式翻译进度 ──
                    val scrollState = rememberScrollState()
                    LaunchedEffect(state.thinkingText, state.streamingText) {
                        scrollState.scrollTo(scrollState.maxValue)
                    }
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = VibeColors.Sage
                            )
                            Spacer(Modifier.width(8.dp))
                            val phaseText = when (state.translationPhase) {
                                TranslationPhase.PREPARING -> "准备翻译…"
                                TranslationPhase.WAITING_FIRST_TOKEN -> "等待模型响应…"
                                TranslationPhase.THINKING -> "模型思考中…"
                                TranslationPhase.STREAMING -> "翻译中… (${state.streamingCharCount}字)"
                                TranslationPhase.FAILED -> "翻译失败"
                                TranslationPhase.CANCELLED -> "翻译已取消"
                                TranslationPhase.IDLE -> "翻译中…"
                            }
                            Text(
                                phaseText,
                                fontSize = 12.sp,
                                color = VibeColors.Sage
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(scrollState)
                        ) {
                            if (state.thinkingText.isNotBlank()) {
                                Text(
                                    "思考过程",
                                    fontSize = 11.sp,
                                    color = if (isDark) VibeColors.Stone else VibeColors.WarmGray
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    state.thinkingText,
                                    style = pageStyle.body.copy(fontSize = 12.sp),
                                    color = if (isDark) VibeColors.Stone else VibeColors.WarmGray,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (state.thinkingText.isNotBlank() && state.streamingText.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                            }
                            if (state.streamingText.isNotBlank()) {
                                Text(
                                    "正式回复",
                                    fontSize = 11.sp,
                                    color = if (isDark) VibeColors.Cream.copy(alpha = 0.65f) else VibeColors.Charcoal.copy(alpha = 0.6f)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    state.streamingText,
                                    style = pageStyle.body.copy(fontSize = 13.sp),
                                    color = if (isDark) VibeColors.Cream.copy(alpha = 0.85f) else VibeColors.Charcoal.copy(alpha = 0.7f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                } else if (activeChapter != null) {
                    // ── 非流式章节状态提示 ──
                    val status = activeChapter.status
                    val reason = state.errorMessage ?: activeChapter.errorMessage
                    val (hintText, hintColor) = when (status) {
                        Chapter.STATUS_FAILED -> ("翻译失败" to VibeColors.RedMuted)
                        Chapter.STATUS_IN_PROGRESS -> ("翻译中断" to VibeColors.BlueMuted)
                        Chapter.STATUS_TOO_LONG -> ("章节过长" to VibeColors.Amber)
                        else -> ("等待翻译" to VibeColors.WarmGray)
                    }
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(hintText, fontSize = 12.sp, color = hintColor)
                        if (reason != null && status in setOf(Chapter.STATUS_FAILED, Chapter.STATUS_TOO_LONG)) {
                            Text(
                                reason,
                                fontSize = 11.sp,
                                color = hintColor.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
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
            onToggleContextBoost = vm::updateLlmContextBoost,
            onUpdateContextChapters = vm::updateLlmContextChapters,
            onUpdateContextMaxChars = vm::updateLlmContextMaxChars,
            onToggleThinking = vm::updateLlmThinking,
            onSave = vm::saveLlmSettings,
            onTest = vm::testLlmConnection,
            onDismiss = { vm.dismissLlmSettings() }
        )
    }
}

@Composable
private fun EmptyReaderHint(isDark: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "没有可阅读的内容",
            color = if (isDark) VibeColors.Stone else VibeColors.WarmGray
        )
    }
}

// ── 滚动模式：与分页共用 ReadingContent 的扁平内容项 ──
private sealed interface ScrollItem {
    val chapterId: Long
    val sourceStartOffset: Int

    data class Title(
        override val chapterId: Long,
        val section: String?,
        val title: String,
        val status: Int,
        val errorMessage: String?
    ) : ScrollItem {
        override val sourceStartOffset: Int = 0
    }

    data class Paragraph(
        override val chapterId: Long,
        val paragraph: com.vibereading.app.ui.reader.content.ReadingParagraph
    ) : ScrollItem {
        override val sourceStartOffset: Int = paragraph.sourceStartOffset
    }
}

private fun buildScrollChunks(chapters: List<Chapter>): List<ScrollItem> = buildList {
    chapters.forEach { chapter ->
        val content = com.vibereading.app.ui.reader.content.ReadingContent.fromChapter(chapter)
        add(ScrollItem.Title(content.chapterId, content.section, content.title, content.status, content.errorMessage))
        content.paragraphs.forEach { add(ScrollItem.Paragraph(content.chapterId, it)) }
    }
}

private fun chapterIdOfChunkKey(key: String?): Long? =
    key?.substringAfter('-', "")?.substringBefore('-')?.toLongOrNull()

private fun List<ScrollItem>.indexInChunks(chapterId: Long?, offset: Int = 0): Int? {
    if (chapterId == null) return null
    val candidates = indices.filter { get(it).chapterId == chapterId }
    if (candidates.isEmpty()) return null
    return candidates.firstOrNull { get(it).sourceStartOffset >= offset } ?: candidates.last()
}

/** 章节号正则：提取「第N章/回/节/卷」中的数字。 */
private val chapterNumRegex = Regex("""^第(\d+)[章回节卷]""")

/** 底部栏章节标签：序章/楔子显示原名，其余取标题里的章号（避免把序章算成第1章导致整体偏移）。
 *  internal 供 PageInfoOverlays 页眉复用（同一口径，不另起炉灶）。 */
internal fun chapterLabel(chapters: List<Chapter>, index: Int): String {
    if (index !in chapters.indices) return "—"
    val title = chapters[index].title
    return when {
        title == "序章" || title == "楔子" || title.startsWith("序") || title.startsWith("楔") -> "序章"
        else -> {
            val num = chapterNumRegex.find(title)?.groupValues?.get(1)
            if (num != null) "第${num}章" else "第${index + 1}章"
        }
    }
}

@Composable
private fun ScrollReader(
    chapters: List<Chapter>,
    chunks: List<ScrollItem>,
    scrollState: LazyListState,
    state: ReaderUiState,
    pageStyle: PageStyle,
    palette: ReaderPalette,
    paddingH: Int,
    paddingV: Int,
    statusBarPx: Int,
    navBarPx: Int,
    onJumpChapter: (Long) -> Unit
) {
    val density = LocalDensity.current
    val paragraphSpacingDp = with(density) { pageStyle.paragraphSpacingPx.toDp() }
    // 内容区顶部/底部扣除系统栏高度（用缓存值，沉浸式切换不触发滚动内容跳动）
    val insetTopDp = with(density) { statusBarPx.toDp() }
    val insetBottomDp = with(density) { navBarPx.toDp() }
    LazyColumn(
        state = scrollState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = paddingH.dp),
        contentPadding = PaddingValues(
            top = insetTopDp + paddingV.dp,
            bottom = insetBottomDp + paddingV.dp
        )
    ) {
        itemsIndexed(chunks, key = { _, item ->
            when (item) {
                is ScrollItem.Title -> "title-${item.chapterId}"
                is ScrollItem.Paragraph -> "para-${item.chapterId}-${item.paragraph.index}"
            }
        }) { _, item ->
            when (item) {
                is ScrollItem.Title -> ReadingChapterTitle(
                    section = item.section,
                    title = item.title,
                    palette = palette,
                    pageStyle = pageStyle
                )
                is ScrollItem.Paragraph -> ReadingParagraphItem(
                    paragraph = item.paragraph,
                    mode = state.mode,
                    pageStyle = pageStyle,
                    palette = palette
                )
            }
        }
    }
}


// ── Bottom control bar: 上一章 | slider | 下一章 / 目录 | 翻译 | 重翻 | 设置 ──
@Composable
private fun BottomControlBar(
    chapters: List<Chapter>,
    activeChapterId: Long?,
    accentColor: Color,
    barColor: Color,
    isRetryEnabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onChapterJump: (Long) -> Unit,
    onToggleCatalog: () -> Unit,
    onOpenLlmSettings: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val chapterIndex = chapters.indexOfFirst { it.id == activeChapterId }.coerceAtLeast(0)
    var dragging by remember { mutableStateOf(false) }
    var dragChapter by remember { mutableIntStateOf(chapterIndex) }
    val sliderValue = if (dragging) dragChapter else chapterIndex
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = barColor.copy(alpha = 0.97f),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Row 1: prev | chapter slider | next
            // 底部对齐 + Slider 与按钮等高（40dp）：Slider 中心线与按钮中心线精确重合；
            // 若 Slider 保持 28dp，底边对齐后其中心仍低于按钮中心（视觉错位）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                TextButton(
                    onClick = onPrev,
                    enabled = chapterIndex > 0,
                    modifier = Modifier.width(76.dp)
                ) {
                    Text("上一章", fontSize = 13.sp, color = labelColor)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                ) {
                    if (chapters.isNotEmpty()) {
                        Text(
                            "${chapterLabel(chapters, sliderValue)} / 共${chapters.size}章",
                            fontSize = 11.sp,
                            color = labelColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        // Slider 外层 Box 撑高到与 TextButton 容器等高（48dp）：底部对齐后
                        // Box 中心线 = 按钮容器中心线；Slider 保持 28dp 紧凑高度在 Box 内居中，
                        // 触摸热区不大面积覆盖（Box 只是透明布局占位，不拦截触摸）
                        Box(
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Slider(
                                value = sliderValue.toFloat(),
                                onValueChange = {
                                    dragging = true
                                    dragChapter = it.roundToInt().coerceIn(0, chapters.size - 1)
                                },
                                onValueChangeFinished = {
                                    dragging = false
                                    if (dragChapter != chapterIndex) {
                                        onChapterJump(chapters[dragChapter].id)
                                    }
                                },
                                valueRange = 0f..(chapters.size - 1).coerceAtLeast(0).toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = accentColor,
                                    activeTrackColor = accentColor
                                ),
                                modifier = Modifier.fillMaxWidth().height(28.dp)
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onNext,
                    enabled = chapterIndex < chapters.size - 1,
                    modifier = Modifier.width(76.dp)
                ) {
                    Text("下一章", fontSize = 13.sp, color = labelColor)
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

            // Row 2: catalog | 翻译 | retry | settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomAction("目录", Icons.Filled.List, accentColor, onToggleCatalog)
                BottomAction("翻译", Icons.Filled.Translate, accentColor, onOpenLlmSettings)
                BottomAction(
                    "重翻",
                    Icons.Filled.Refresh,
                    if (isRetryEnabled) accentColor else labelColor,
                    onRetry,
                    enabled = isRetryEnabled
                )
                BottomAction("设置", Icons.Filled.Settings, accentColor, onOpenSettings)
            }
        }
    }
}

@Composable
private fun BottomAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.35f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint.copy(alpha = alpha), modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = tint.copy(alpha = alpha))
    }
}

@Composable
private fun ModeButton(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
        tonalElevation = if (isActive) 2.dp else 0.dp
    ) {
        Text(
            text,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}


