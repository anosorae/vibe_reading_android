package com.vibereading.app.ui.reader

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.reader.components.CatalogBottomSheet
import com.vibereading.app.ui.reader.components.CatalogGroup
import com.vibereading.app.ui.reader.components.PageInfoOverlays
import com.vibereading.app.ui.reader.components.LlmSettingsSheet
import com.vibereading.app.ui.reader.components.ReaderSettingsSheet
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
    var pagerJumpOffset by remember { mutableIntStateOf(0) }
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
            isProgrammatic -> pagerJumpOffset
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

    // 滚动模式跨章滚动状态
    val scrollChunks = remember(state.chapters, state.mode, pageStyle.titleMode) {
        buildScrollChunks(state.chapters, pageStyle.titleMode)
    }
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
