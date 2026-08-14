package com.vibereading.app.ui.reader.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.vibereading.app.ui.reader.chapterStatusColor

data class CatalogGroup(
    val section: String?,
    val chapters: List<Chapter>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogBottomSheet(
    groups: List<CatalogGroup>,
    activeChapterId: Long?,
    onChapterClick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // Track expanded sections — lift state up so it controls item visibility
    val expandedSections = remember { mutableStateOf(setOf<String>()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
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
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${groups.sumOf { it.chapters.size }} 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

            // Chapter list
            LazyColumn(
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
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.rotate(rotation)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            section,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text(
            "($chapterCount)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChapterItem(
    chapter: Chapter,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val statusColor = chapterStatusColor(chapter.status)

    val bgColor = if (isActive) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
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
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
