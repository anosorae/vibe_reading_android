package com.vibereading.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.reader.components.CatalogGroup
import com.vibereading.app.ui.reader.components.LlmSettingsSheetActions
import com.vibereading.app.ui.reader.components.TextSelectionState
import com.vibereading.app.ui.reader.pagination.BookWindow
import com.vibereading.app.ui.reader.pagination.PageStyle
import com.vibereading.app.ui.reader.pagination.ReaderFonts
import com.vibereading.app.ui.reader.pagination.SimFlipState
import com.vibereading.app.ui.theme.ReaderBgPresets

@Composable
fun ReaderScreen(vm: ReaderViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsState()
    val editApiKey by vm.editApiKey.collectAsState()
    val editApiBase by vm.editApiBase.collectAsState()
    val editModel by vm.editModel.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val settings = state.readingSettings
    val backgroundValue = if (state.nightMode) ReaderBgPresets.DarkNight
    else ReaderBgPresets.all.getOrElse(settings.bgColorIndex) { ReaderBgPresets.WarmCream }
    val background by rememberUpdatedState(backgroundValue)
    val isDark = state.nightMode || ReaderBgPresets.isDark(settings.bgColorIndex)
    val paletteValue = remember(isDark) { ReaderPalette.of(isDark) }
    val palette by rememberUpdatedState(paletteValue)
    val isPagerMode = settings.pageFlipMode != ReadingSettings.FLIP_SCROLL
    val anyOverlayVisible = state.toolbarVisible || state.catalogVisible ||
        state.settingsVisible || state.llmSettingsVisible

    val measurer = rememberTextMeasurer()
    val backgroundMeasurer = rememberTextMeasurer(cacheSize = 8)
    val (cnFont, enFont) = remember(
        settings.customFontUri, settings.enCustomFontUri, settings.fontId, settings.enFontId
    ) { ReaderFonts.readerFontFamilies(context, settings) }
    val pageStyle = remember(settings, density, state.mode, cnFont, enFont) {
        PageStyle.of(settings, density, state.mode, cnFont, enFont)
    }
    val layout = rememberReaderLayoutSpec(settings, pageStyle, palette)
    // 分页指纹只含**影响排版**的字段：当前章的 id 与译文长度。不含 status/errorMessage——
    // 翻译状态变化（IN_PROGRESS→DONE）若重建窗口，pagerState.pageCount 会在程序化跳章后突变，
    // 表现为「下一章跳到最后一页」；后台预译下一章写库也不会改当前章指纹，画面不跳动，
    // 邻居译文由切章时的 recenterSync 重新排版取得。
    val paginationFingerprint = remember(state.chapters) {
        state.chapters.find { it.id == state.activeChapterId }
            ?.let { "${it.id}:${it.translatedContent?.length ?: -1}" }.orEmpty()
    }
    val window = remember(measurer, state.mode, state.sourceLanguage, paginationFingerprint, isPagerMode) {
        BookWindow(
            chapters = state.chapters,
            style = pageStyle,
            mode = state.mode,
            sourceLanguage = state.sourceLanguage,
            contentWidthPx = layout.geometry.contentWidthPx,
            contentHeightPx = layout.geometry.contentHeightPx,
            measurer = measurer,
            backgroundMeasurer = { backgroundMeasurer },
            displayDensity = density.density,
            displayFontScale = density.fontScale
        )
    }
    SideEffect { window.updateChapterSource(state.chapters) }

    val pagerState = rememberPagerState(initialPage = 0) { window.pageCount }
    val scope = rememberCoroutineScope()
    val simFlip = remember { SimFlipState() }
    val curlController = remember(scope, simFlip) { ReaderCurlController(simFlip, scope) }
    val selectionState = remember { TextSelectionState() }
    val overlayRuntime = rememberReaderOverlayRuntime()
    val pagerSession = rememberReaderPagerSession(
        pagerState, scope, window, pageStyle, layout.geometry.contentWidthPx,
        layout.geometry.contentHeightPx, isPagerMode, state,
        vm::updateProgress, { id, offset -> vm.navigateTo(id, offset) }
    )
    val scrollSession = rememberReaderScrollSession(
        isPagerMode, state, pageStyle.titleMode, vm::updateProgress
    )

    SideEffect {
        curlController.update(
            ReaderCurlController.Config(
                pagerState, window, state.mode, layout, density,
                background.toArgb(), accent.toArgb(), measurer
            )
        )
    }
    LaunchedEffect(state.mode) { curlController.cancelAndCleanup() }

    val bookHasNoChapters = state.chaptersLoaded && state.chapters.isEmpty()
    val opening = !bookHasNoChapters && (
        !state.restoreReady || (isPagerMode && window.pageCount == 0) ||
            (!isPagerMode && state.chapters.isNotEmpty() && scrollSession.chunks.isEmpty())
        )
    LaunchedEffect(opening) {
        if (!opening) {
            withFrameNanos { }
            vm.onFirstContentReady()
        }
    }

    LaunchedEffect(isPagerMode, scrollSession.listState, pagerState) {
        val scrolling = if (isPagerMode) snapshotFlow { pagerState.isScrollInProgress }
        else snapshotFlow { scrollSession.listState.isScrollInProgress }
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
    LaunchedEffect(
        state.toolbarVisible, state.catalogVisible, state.settingsVisible, state.llmSettingsVisible
    ) {
        if (anyOverlayVisible) {
            selectionState.clear()
            vm.dismissExplainPopup()
        }
    }

    val leaveReader = ReaderSystemUiEffects(
        toolbarHidden = !anyOverlayVisible,
        hideStatusBar = settings.hideStatusBar,
        hideNavigationBar = settings.hideNavigationBar,
        isDark = isDark,
        background = background,
        scope = scope,
        syncProgress = {
            pagerSession.syncProgressBeforeFlush(isPagerMode, window, vm::updateProgress)
        },
        flushProgress = vm::flushProgress,
        onBack = onBack
    )

    val overlayVisibleState = rememberUpdatedState(anyOverlayVisible)
    val popupVisibleState = rememberUpdatedState(state.dictQueryWord != null || state.explainWord != null)
    val oneHandModeState = rememberUpdatedState(settings.oneHandMode)
    val gestureState = remember {
        ReaderGestureState(
            overlayVisible = { overlayVisibleState.value },
            popupVisible = { popupVisibleState.value },
            oneHandMode = { oneHandModeState.value }
        )
    }
    // adapter 只持有稳定引用（vm/session），每次重组刷新其中读取最新 state 的闭包：
    // 刷新是副作用而非组合计算结果，放 SideEffect，避免在组合期写可变字段。
    val screenActions = remember(vm) { ReaderScreenActions(vm) }
    SideEffect {
        screenActions.update(
            leaveReader = leaveReader,
            goPage = { page ->
                pagerSession.goPage(
                    page, window, settings.pageFlipMode, state.chapters, state.activeChapterId,
                    curlController, { id, offset -> vm.navigateTo(id, offset) }
                )
            },
            jumpTo = { id ->
                if (isPagerMode) pagerSession.jumpTo(id) { chapterId, offset -> vm.navigateTo(chapterId, offset) }
                else scrollSession.jumpTo(id) { chapterId, offset -> vm.navigateTo(chapterId, offset) }
            },
            jumpBy = { delta ->
                val index = state.chapters.indexOfFirst { it.id == state.activeChapterId }
                val target = when {
                    delta < 0 && index > 0 -> state.chapters[index - 1].id
                    delta > 0 && index in 0 until state.chapters.lastIndex -> state.chapters[index + 1].id
                    else -> null
                }
                if (target != null) {
                    if (isPagerMode) pagerSession.jumpTo(target) { id, offset -> vm.navigateTo(id, offset) }
                    else scrollSession.jumpTo(target) { id, offset -> vm.navigateTo(id, offset) }
                }
            }
        )
    }

    val contentInteractions = remember(anyOverlayVisible, simFlip.isRunning, overlayRuntime) {
        ReaderContentInteractions(
            selectionState = if (anyOverlayVisible || simFlip.isRunning) null else selectionState,
            bubbleEnabled = !anyOverlayVisible,
            onIllustrationClick = { overlayRuntime.previewIllustrationPath = it }
        )
    }
    val catalogGroups = remember(state.chapters) { buildCatalogGroups(state.chapters) }
    val overlayModel = ReaderOverlayModel(
        state, layout, window, pagerState, catalogGroups, opening, isPagerMode, isDark,
        background, accent, editApiKey, editApiBase, editModel
    )
    val gestureKey = ReaderGestureLayoutKey(
        settings.paddingH, settings.paddingV, layout.geometry.statusBarPx, layout.geometry.navBarPx
    )

    Box(
        Modifier.fillMaxSize().background(background)
            .onGloballyPositioned { overlayRuntime.containerWindowOffset = it.positionInWindow() }
            .readerContentGestures(
                isPagerMode, settings.pageFlipMode, pagerState, window, selectionState,
                curlController, gestureState, screenActions, gestureKey
            )
    ) {
        ReaderContentSurface(
            state, isPagerMode, window, pagerState, scrollSession, layout,
            contentInteractions, simFlip, background, accent, isDark
        )
        ReaderContentOverlays(overlayModel, overlayRuntime, selectionState, density, screenActions)
    }
    ReaderModalOverlays(overlayModel, screenActions)
}
