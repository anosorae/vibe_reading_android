package com.vibereading.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.ui.reader.pagination.PageStyle
import com.vibereading.app.ui.theme.VibeColors
import com.vibereading.app.ui.theme.VibeDarkColors
import kotlin.math.roundToInt

/** 底部栏章节标签：全局章号 = 章节列表位置 + 1，与滑块位置和「共N章」同一口径；
 *  序章/楔子显示原名。internal 供 PageInfoOverlays 页眉复用（同一口径，不另起炉灶）。
 *  不从标题提取章号：分卷书每卷重新编号，标题里的「第一章」和全局位置对不上，
 *  拖滑块切到第二卷开头会显示成「第1章 / 共N章」。 */
/** 标题自带章号检测：中文「第N章/回/节/卷」（容忍「第 28 章」式空格）、英文「Chapter N」
 *  与「1. / 1、」式数字编号（英文原版书常见，如「1. Good Morning Brother」）。 */
private val titleNumberRegex = Regex(
    """^\s*(第\s*\d+\s*[章回节卷]|Chapter\s+\d+|\d+\s*[.、)])""",
    RegexOption.IGNORE_CASE
)

internal fun chapterLabel(chapters: List<Chapter>, index: Int): String {
    if (index !in chapters.indices) return "—"
    val title = chapters[index].title
    return when {
        title == "序章" || title == "楔子" || title.startsWith("序") || title.startsWith("楔") -> "序章"
        else -> "第${index + 1}章"
    }
}

/** 页眉章节文本：标题自带章号（分卷书的卷内编号、英文 Chapter N）时只显示标题，
 *  避免与全局章号前缀拼成「第203章 · 第29章 诞生」式重复；标题无章号时（如「seed」）
 *  前置全局章号「第N章 · 标题」保留位置信息。 */
internal fun chapterHeaderText(chapters: List<Chapter>, index: Int): String {
    if (index !in chapters.indices) return ""
    val title = chapters[index].title
    val label = chapterLabel(chapters, index)
    return when {
        title == label || title.startsWith(label) -> title
        titleNumberRegex.containsMatchIn(title) -> title
        else -> "$label · $title"
    }
}

@Composable
fun EmptyReaderHint(isDark: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "没有可阅读的内容",
            color = if (isDark) VibeColors.Stone else VibeColors.WarmGray
        )
    }
}

/** 打开书籍过渡遮罩：书名 + 轻量进度指示；背景与阅读器背景一致，淡出时无缝衔接正文。 */
@Composable
fun ReaderOpeningShade(
    bookTitle: String,
    bgColor: Color,
    titleColor: Color,
    accentColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                bookTitle,
                color = titleColor,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(
                color = accentColor,
                strokeWidth = 2.dp,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/** 顶栏：返回 + 书名 + 中英切换 + 当前章翻译状态圆点（目录入口在底部栏，顶栏不放）。 */
@Composable
fun ReaderTopToolbar(
    bookTitle: String,
    mode: String,
    activeChapterStatus: Int?,
    barColor: Color,
    accentColor: Color,
    isDark: Boolean,
    onBack: () -> Unit,
    onToggleMode: (String) -> Unit
) {
    // 阅读器 chrome 固定配色：不随全局主题（切换主题色时阅读页内部保持稳定）
    val chrome = readerChromeColors(isDark)
    Surface(
        color = barColor.copy(alpha = 0.95f),
        contentColor = chrome.text,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
            Text(
                bookTitle,
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
                        chrome.pillBg,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(2.dp)
            ) {
                ModeButton("中文", mode == "zh", readerControlAccent(accentColor, isDark), chrome, onClick = { onToggleMode("zh") })
                ModeButton("英文", mode == "en", readerControlAccent(accentColor, isDark), chrome, onClick = { onToggleMode("en") })
            }
            if (activeChapterStatus != null) {
                val dotColor = chapterStatusColor(activeChapterStatus, isDark)
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

// ── Bottom control bar: 上一章 | slider | 下一章 / 目录 | 翻译 | 重翻 | 设置 ──
@Composable
fun ReaderBottomBar(
    chapters: List<Chapter>,
    activeChapterId: Long?,
    barColor: Color,
    isDark: Boolean,
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
    val chrome = readerChromeColors(isDark)
    val labelColor = chrome.mutedText
    Surface(
        color = barColor.copy(alpha = 0.97f),
        contentColor = chrome.text,
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
                                    thumbColor = labelColor,
                                    activeTrackColor = labelColor,
                                    inactiveTrackColor = chrome.pillBg
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

            HorizontalDivider(color = chrome.divider)

            // Row 2: catalog | 翻译 | retry | settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 整条底栏统一 chrome 次级灰（不随主题 accent）；重翻不可用态再压一档透明度
                BottomAction("目录", Icons.Filled.List, labelColor, onToggleCatalog)
                BottomAction("翻译", Icons.Filled.Translate, labelColor, onOpenLlmSettings)
                BottomAction(
                    "重翻",
                    Icons.Filled.Refresh,
                    labelColor,
                    onRetry,
                    enabled = isRetryEnabled
                )
                BottomAction("设置", Icons.Filled.Settings, labelColor, onOpenSettings)
            }
        }
    }
}

@Composable
private fun BottomAction(
    label: String,
    icon: ImageVector,
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
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint.copy(alpha = alpha), modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(1.dp))
        Text(label, fontSize = 10.sp, color = tint.copy(alpha = alpha))
    }
}

@Composable
private fun ModeButton(
    text: String,
    isActive: Boolean,
    accentColor: Color,
    chrome: ReaderChromeColors,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isActive) accentColor else Color.Transparent,
        tonalElevation = if (isActive) 2.dp else 0.dp
    ) {
        Text(
            text,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (isActive) chrome.onAccent else chrome.mutedText,
            fontSize = 13.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** 翻译状态面板：流式进度（思考 + 正式回复）+ 非流式章节状态提示。需在 Box 内调用（自对齐底部）。 */
@Composable
fun BoxScope.TranslationStatusPanel(
    state: ReaderUiState,
    pageStyle: PageStyle,
    isDark: Boolean,
    bottomBarHeightDp: Float,
    navBarPx: Int,
    visible: Boolean
) {
    val activeChapter = state.activeChapter
    if (!visible) return
    val density = LocalDensity.current
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
            LaunchedEffect(scrollState) {
                // 等布局产生新高度再跟随，避免逐 token 重启任务并读到上一帧高度。
                snapshotFlow { scrollState.maxValue }.collect { maxValue ->
                    scrollState.scrollTo(maxValue)
                }
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
                    color = if (isDark) VibeDarkColors.OutlineVariant else VibeColors.Sand
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
            // 运行期失败（如「请先配置 API Key」）不写库，章节仍是 PENDING：
            // 这类原因必须显示，否则用户只看到「待翻译」而不知道卡在哪。
            val runtimeReason = state.errorMessage
            val reason = runtimeReason ?: activeChapter.errorMessage
            val (hintText, hintColor) = chapterStatusHint(status, isDark)
            Column(modifier = Modifier.padding(12.dp)) {
                Text(hintText, fontSize = 12.sp, color = hintColor)
                if (reason != null && (runtimeReason != null || chapterStatusHasReason(status))) {
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
