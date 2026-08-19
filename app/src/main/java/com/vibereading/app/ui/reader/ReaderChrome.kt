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
fun EmptyReaderHint(isDark: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "没有可阅读的内容",
            color = if (isDark) VibeColors.Stone else VibeColors.WarmGray
        )
    }
}

/** 顶栏：返回 + 书名 + 中英切换 + 当前章翻译状态圆点（目录入口在底部栏，顶栏不放）。 */
@Composable
fun ReaderTopToolbar(
    bookTitle: String,
    mode: String,
    activeChapterStatus: Int?,
    barColor: Color,
    onBack: () -> Unit,
    onToggleMode: (String) -> Unit
) {
    Surface(
        color = barColor.copy(alpha = 0.95f),
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
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(2.dp)
            ) {
                ModeButton("中文", mode == "zh", onClick = { onToggleMode("zh") })
                ModeButton("英文", mode == "en", onClick = { onToggleMode("en") })
            }
            if (activeChapterStatus != null) {
                val dotColor = chapterStatusColor(activeChapterStatus)
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
                Chapter.STATUS_IN_PROGRESS -> ("翻译中…" to VibeColors.BlueMuted)
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
