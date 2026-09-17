package com.vibereading.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.ui.bookshelf.ShelfMetrics
import com.vibereading.app.ui.bookshelf.percentLabel
import com.vibereading.app.ui.components.IconCircle
import com.vibereading.app.ui.components.SoftCard
import com.vibereading.app.ui.theme.FunctionalColors
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets

/**
 * 统计 Tab 页：书库阅读与翻译的全景卡片。
 * 视觉对齐全局设计规范（24dp 页边距 / 24dp 圆角白卡 / 16dp 卡距 / 线性图标 + 浅色圆底）。
 */
@Composable
internal fun StatisticsScreen(
    vm: StatisticsViewModel,
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.uiState.collectAsState()
    val stableInsets = LocalStableSystemBarInsets.current

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = stableInsets
    ) { padding ->
        val stats = state.stats
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {}
            stats.bookCount == 0 -> StatsEmptyContent(Modifier.fillMaxSize().padding(padding))
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                StatsHeader()
                TranslationHeroCard(stats)
                OverviewGridCard(stats)
                LibraryCompositionCard(stats)
                RecentReadingCard(stats, onOpenBook)
                Text(
                    "统计口径：阅读进度与已译章节均为本地书库实时数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                // AppShell 的悬浮底栏覆盖在内容上方；预留完整滚动安全区
                Spacer(Modifier.height(ShelfMetrics.NavBarHeight + ShelfMetrics.NavBarBottomGap))
            }
        }
    }
}

@Composable
private fun StatsHeader() {
    Column {
        Text(
            "统计",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "书库的阅读与翻译全景",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 头牌卡：双语覆盖率 + 已译章节进度。 */
@Composable
private fun TranslationHeroCard(stats: ReadingStats) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircle(
                icon = Icons.Outlined.Translate,
                tint = MaterialTheme.colorScheme.primary,
                circleSize = 44.dp,
                iconSize = 22.dp
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    percentLabel(stats.translationRatio),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "双语阅读覆盖率",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        StatsProgress(stats.translationRatio, trackHeight = 8.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            "已译 ${formatStatCount(stats.translatedChapters)} / ${formatStatCount(stats.totalChapters)} 章 · 覆盖书架全部书籍",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 概览 2×2：藏书 / 在读 / 读到末章 / 章节总量。 */
@Composable
private fun OverviewGridCard(stats: ReadingStats) {
    SoftCard {
        Row(Modifier.fillMaxWidth()) {
            StatTile(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                tint = MaterialTheme.colorScheme.primary,
                value = formatStatCount(stats.bookCount),
                label = "藏书"
            )
            StatTile(
                icon = Icons.Outlined.AutoStories,
                tint = FunctionalColors.Green,
                value = formatStatCount(stats.readingCount),
                label = "在读"
            )
        }
        StatsCardDivider()
        Row(Modifier.fillMaxWidth()) {
            StatTile(
                icon = Icons.Outlined.TaskAlt,
                tint = FunctionalColors.Orange,
                value = formatStatCount(stats.reachedEndCount),
                label = "读到末章"
            )
            StatTile(
                icon = Icons.Outlined.Bookmark,
                tint = MaterialTheme.colorScheme.secondary,
                value = formatStatCount(stats.totalChapters),
                label = "章节总量"
            )
        }
    }
}

@Composable
private fun RowScope.StatTile(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    value: String,
    label: String
) {
    Row(
        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconCircle(icon = icon, tint = tint, circleSize = 38.dp, iconSize = 20.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 书库构成：中文/英文原著、TXT/EPUB 四格。 */
@Composable
private fun LibraryCompositionCard(stats: ReadingStats) {
    SoftCard {
        Text(
            "书库构成",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            CompositionTile(value = stats.chineseBookCount, label = "中文原著")
            CompositionTile(value = stats.englishBookCount, label = "英文原著")
        }
        StatsCardDivider()
        Row(Modifier.fillMaxWidth()) {
            CompositionTile(value = stats.txtCount, label = "TXT 文本")
            CompositionTile(value = stats.epubCount, label = "EPUB 电子书")
        }
    }
}

@Composable
private fun RowScope.CompositionTile(value: Int, label: String) {
    Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
        Text(
            "$value 本",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 最近阅读：点击行直接打开书籍。 */
@Composable
private fun RecentReadingCard(stats: ReadingStats, onOpenBook: (Long) -> Unit) {
    if (stats.recent.isEmpty()) return
    SoftCard {
        Text(
            "最近阅读",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        stats.recent.forEachIndexed { index, item ->
            if (index > 0) StatsCardDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenBook(item.bookId) }
                    .padding(vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        val caption = buildString {
                            append("已读 ${item.readChapters}/${item.totalChapters} 章")
                            if (item.translatedCount > 0) append(" · 已译 ${item.translatedCount} 章")
                        }
                        Text(
                            caption,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        percentLabel(item.progress),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(8.dp))
                StatsProgress(item.progress, trackHeight = 6.dp)
            }
        }
    }
}

@Composable
private fun StatsEmptyContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.BarChart,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "还没有可统计的内容",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "先去书架导入一本书，阅读轨迹会在这里汇聚。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ── 小部件 ──

/** 胶囊进度条：浅槽 + 主色填充；有进度时保持最小可见填充。 */
@Composable
internal fun StatsProgress(progress: Float, trackHeight: androidx.compose.ui.unit.Dp) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(0.03f))
                    .height(trackHeight)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun StatsCardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
