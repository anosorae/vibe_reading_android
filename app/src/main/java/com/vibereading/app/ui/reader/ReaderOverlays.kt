package com.vibereading.app.ui.reader

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.toSize
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.domain.parser.SourceLanguageDetector
import com.vibereading.app.ui.reader.components.CatalogBottomSheet
import com.vibereading.app.ui.reader.components.CatalogGroup
import com.vibereading.app.ui.reader.components.DictPopup
import com.vibereading.app.ui.reader.components.ExplainPopup
import com.vibereading.app.ui.reader.components.IllustrationPreviewOverlay
import com.vibereading.app.ui.reader.components.LlmSettingsSheet
import com.vibereading.app.ui.reader.components.LlmSettingsSheetActions
import com.vibereading.app.ui.reader.components.LlmSettingsSheetUiState
import com.vibereading.app.ui.reader.components.PageInfoOverlays
import com.vibereading.app.ui.reader.components.ReaderSettingsSheet
import com.vibereading.app.ui.reader.components.SelectionHandles
import com.vibereading.app.ui.reader.components.SelectionToolbar
import com.vibereading.app.ui.reader.components.TextSelectionState
import com.vibereading.app.ui.reader.pagination.BookWindow
import androidx.compose.foundation.pager.PagerState
import kotlinx.coroutines.flow.StateFlow

@Stable
class ReaderOverlayRuntime {
    var previewIllustrationPath by mutableStateOf<String?>(null)
    var dictAnchor by mutableStateOf(Offset.Zero)
    var containerWindowOffset by mutableStateOf(Offset.Zero)
    var bottomBarHeightDp by mutableFloatStateOf(0f)
}

@Composable
fun rememberReaderOverlayRuntime(): ReaderOverlayRuntime = remember { ReaderOverlayRuntime() }

data class ReaderOverlayModel(
    val state: ReaderUiState,
    val layout: ReaderLayoutSpec,
    val window: BookWindow,
    val pagerState: PagerState,
    val catalogGroups: List<CatalogGroup>,
    val opening: Boolean,
    val isPagerMode: Boolean,
    val isDark: Boolean,
    val background: Color,
    val accent: Color,
    val editApiKey: String,
    val editApiBase: String,
    val editModel: String
)

interface ReaderOverlayActions : LlmSettingsSheetActions {
    fun leaveReader()
    fun switchMode(mode: String)
    fun jumpChapterBy(delta: Int)
    fun jumpToChapter(id: Long)
    fun toggleCatalog()
    fun openLlmSettings()
    fun retryTranslation(id: Long)
    fun toggleSettings()
    fun dismissCatalog()
    fun updateReadingSettings(settings: ReadingSettings)
    fun dismissSettings()
    fun lookupWord(word: String)
    fun explainWord(word: String, paragraph: String)
    fun dismissDictPopup()
    fun dismissExplainPopup()
}

/** 内容内覆盖层与 modal 的唯一 z-order。 */
@Composable
fun BoxScope.ReaderContentOverlays(
    model: ReaderOverlayModel,
    runtime: ReaderOverlayRuntime,
    selectionState: TextSelectionState,
    density: Density,
    actions: ReaderOverlayActions
) {
    val state = model.state
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    AnimatedVisibility(
        visible = model.opening,
        enter = EnterTransition.None,
        exit = fadeOut(tween(280)),
        modifier = Modifier.fillMaxSize()
    ) {
        ReaderOpeningShade(
            state.bookTitle.ifEmpty { "正在打开" }, model.background,
            model.layout.palette.titleText, model.accent
        )
    }
    if (model.isPagerMode && model.window.pageCount > 0 && !state.toolbarVisible) {
        PageInfoOverlays(model.window, state.chapters, state.activeChapterId, model.pagerState, model.layout)
    }
    AnimatedVisibility(
        state.toolbarVisible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        ReaderTopToolbar(
            state.bookTitle, state.mode, state.activeChapter?.status, model.background,
            actions::leaveReader, actions::switchMode
        )
    }

    val activeNeedsTranslation = state.mode == "en" || state.sourceLanguage == SourceLanguageDetector.EN
    AnimatedVisibility(
        state.toolbarVisible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).onGloballyPositioned {
            runtime.bottomBarHeightDp = with(density) { it.size.height.toDp() }.value
        }
    ) {
        ReaderBottomBar(
            chapters = state.chapters,
            activeChapterId = state.activeChapterId,
            accentColor = model.accent,
            barColor = model.background,
            isRetryEnabled = activeNeedsTranslation && !state.isStreaming && state.activeChapter?.status in setOf(
                Chapter.STATUS_DONE, Chapter.STATUS_FAILED, Chapter.STATUS_IN_PROGRESS
            ),
            onPrev = { actions.jumpChapterBy(-1) },
            onNext = { actions.jumpChapterBy(1) },
            onChapterJump = actions::jumpToChapter,
            onToggleCatalog = actions::toggleCatalog,
            onOpenLlmSettings = actions::openLlmSettings,
            onRetry = { state.activeChapterId?.let(actions::retryTranslation) },
            onOpenSettings = actions::toggleSettings
        )
    }
    val activeChapter = state.activeChapter
    val showStatusPanel = state.isStreaming || (
        activeNeedsTranslation && activeChapter != null && activeChapter.status != Chapter.STATUS_DONE &&
            !state.catalogVisible && !state.settingsVisible && !state.llmSettingsVisible
        )
    TranslationStatusPanel(
        state, model.layout.pageStyle, model.isDark, runtime.bottomBarHeightDp,
        model.layout.geometry.navBarPx, showStatusPanel
    )
    if (selectionState.isSelecting && selectionState.showToolbar) {
        SelectionToolbar(
            selectionState,
            model.layout.palette,
            onLookup = { word ->
                runtime.dictAnchor = selectionState.popupPosition
                selectionState.clear()
                actions.lookupWord(word)
            },
            onExplain = { word ->
                runtime.dictAnchor = selectionState.popupPosition
                val paragraph = selectionState.paragraphText
                selectionState.clear()
                actions.explainWord(word, paragraph)
            },
            onCopy = { word ->
                clipboard.setText(AnnotatedString(word))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                selectionState.clear()
            },
            onDismissRequest = selectionState::dismissToolbar
        )
    }
    SelectionHandles(
        selectionState, model.layout.palette, density, runtime.containerWindowOffset
    )
    state.dictQueryWord?.let { word ->
        DictPopup(
            word, state.dictEntry, state.dictLoading, runtime.dictAnchor,
            model.layout.palette, model.layout.pageStyle, actions::dismissDictPopup
        )
    }
    state.explainWord?.let { word ->
        ExplainPopup(
            word, state.explainResult, state.explainLoading, state.explainError,
            runtime.dictAnchor, model.layout.palette, model.layout.pageStyle, actions::dismissExplainPopup
        )
    }
    runtime.previewIllustrationPath?.let { path ->
        IllustrationPreviewOverlay(path) { runtime.previewIllustrationPath = null }
    }
}

/** Modal 保持在内容 Box 之后，位于所有内容内覆盖层之上。 */
@Composable
fun ReaderModalOverlays(model: ReaderOverlayModel, actions: ReaderOverlayActions) {
    val state = model.state
    if (state.catalogVisible) {
        CatalogBottomSheet(model.catalogGroups, state.activeChapterId, actions::jumpToChapter, actions::dismissCatalog)
    }
    if (state.settingsVisible) {
        ReaderSettingsSheet(state.readingSettings, model.accent, actions::updateReadingSettings, actions::dismissSettings)
    }
    if (state.llmSettingsVisible) {
        val sheetState = remember(
            state.llmSettings, state.profiles, state.activeProfileId, state.editingProfileId,
            model.editApiKey, model.editApiBase, model.editModel, state.llmTestResult, state.llmTestSuccess
        ) {
            LlmSettingsSheetUiState(
                state.llmSettings, state.profiles, state.activeProfileId, state.editingProfileId,
                model.editApiKey, model.editApiBase, model.editModel, state.llmTestResult, state.llmTestSuccess
            )
        }
        LlmSettingsSheet(sheetState, actions, model.accent)
    }
}
