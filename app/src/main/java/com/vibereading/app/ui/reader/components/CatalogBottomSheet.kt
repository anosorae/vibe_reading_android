package com.vibereading.app.ui.reader.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.ui.reader.ReaderChromeColors
import com.vibereading.app.ui.reader.chapterStatusColor
import com.vibereading.app.ui.reader.readerChromeColors

data class CatalogGroup(
    val section: String?,
    val chapters: List<Chapter>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogBottomSheet(
    groups: List<CatalogGroup>,
    activeChapterId: Long?,
    accentColor: Color,
    isDark: Boolean,
    onChapterClick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // 目录抽屉属于阅读器视觉世界：配色取阅读器固定 chrome 色板（跟阅读背景深浅走），
    // 不随全局主题 accent 变化
    val chrome = readerChromeColors(isDark)
    // 预展开当前章节所在卷
    val activeSection = remember(activeChapterId, groups) {
        groups.find { it.chapters.any { ch -> ch.id == activeChapterId } }?.section
    }
    val expandedSections = remember {
        mutableStateOf(setOf<String>() + (activeSection?.let { setOf(it) } ?: emptySet()))
    }
    val listState = rememberLazyListState()

    // 打开目录后自动滚动到当前章节
    LaunchedEffect(activeChapterId) {
        if (activeChapterId != null) {
            val index = computeActiveChapterIndex(groups, activeChapterId, expandedSections.value)
            if (index >= 0) {
                listState.scrollToItem(index)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = chrome.sheetBg,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "目录",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = chrome.text
                )
                Text(
                    "${groups.sumOf { it.chapters.size }} 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = chrome.mutedText
                )
            }

            Divider(thickness = 0.5.dp, color = chrome.divider)

            // Chapter list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                groups.forEach { group ->
                    if (group.section != null) {
                        val isExpanded = group.section in expandedSections.value
                        item(key = "section_${group.section}") {
                            SectionHeader(
                                section = group.section,
                                chapterCount = group.chapters.size,
                                expanded = isExpanded,
                                accentColor = accentColor,
                                chrome = chrome,
                                onToggle = {
                                    expandedSections.value = if (isExpanded) {
                                        expandedSections.value - group.section
                                    } else {
                                        expandedSections.value + group.section
                                    }
                                }
                            )
                        }
                        // Only show chapters when section is expanded
                        if (isExpanded) {
                            items(group.chapters, key = { it.id }) { chapter ->
                                ChapterItem(
                                    chapter = chapter,
                                    isActive = chapter.id == activeChapterId,
                                    accentColor = accentColor,
                                    chrome = chrome,
                                    isDark = isDark,
                                    onClick = {
                                        onChapterClick(chapter.id)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    } else {
                        // No section header — always show chapters
                        items(group.chapters, key = { it.id }) { chapter ->
                            ChapterItem(
                                chapter = chapter,
                                isActive = chapter.id == activeChapterId,
                                accentColor = accentColor,
                                chrome = chrome,
                                isDark = isDark,
                                onClick = {
                                    onChapterClick(chapter.id)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    section: String,
    chapterCount: Int,
    expanded: Boolean,
    accentColor: Color,
    chrome: ReaderChromeColors,
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "chevron"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium,
            color = accentColor,
            modifier = Modifier.rotate(rotation)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            section,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = accentColor,
            modifier = Modifier.weight(1f)
        )
        Text(
            "($chapterCount)",
            style = MaterialTheme.typography.labelSmall,
            color = chrome.mutedText
        )
    }
}

@Composable
private fun ChapterItem(
    chapter: Chapter,
    isActive: Boolean,
    accentColor: Color,
    chrome: ReaderChromeColors,
    isDark: Boolean,
    onClick: () -> Unit
) {
    // 目录抽屉属于阅读器视觉世界：状态色按**阅读背景深浅**取（不是全局主题深浅），
    // 与顶栏圆点、阅读正文同一层表面
    val statusColor = chapterStatusColor(chapter.status, isDark)

    val bgColor = if (isActive) {
        accentColor.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isActive) accentColor else chrome.text,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 计算 activeChapterId 在 LazyColumn 中的扁平索引（含 section header 占位）。 */
private fun computeActiveChapterIndex(
    groups: List<CatalogGroup>,
    activeChapterId: Long,
    expandedSections: Set<String>
): Int {
    var index = 0
    for (group in groups) {
        if (group.section != null) {
            index++ // section header 占一项
            if (group.section in expandedSections) {
                for (chapter in group.chapters) {
                    if (chapter.id == activeChapterId) return index
                    index++
                }
            }
        } else {
            for (chapter in group.chapters) {
                if (chapter.id == activeChapterId) return index
                index++
            }
        }
    }
    return -1
}
