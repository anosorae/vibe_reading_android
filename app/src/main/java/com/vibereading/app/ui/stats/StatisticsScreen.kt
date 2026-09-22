package com.vibereading.app.ui.stats

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.vibereading.app.ui.bookshelf.percentLabel
import com.vibereading.app.ui.components.IconCircle
import com.vibereading.app.ui.components.SoftCard
import com.vibereading.app.ui.theme.FunctionalColors
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets
import java.time.LocalDate

/**
 * 统计 Tab 页：阅读时长（头牌卡 + 趋势柱状图）、书库总览与最近阅读。
 * 视觉对齐全局设计规范（24dp 页边距 / 24dp 圆角白卡 / 16dp 卡距 / 线性图标 + 浅色圆底）。
 */
@Composable
internal fun StatisticsScreen(
    vm: StatisticsViewModel,
    onOpenBook: (Long) -> Unit,
    bottomChromePadding: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val state by vm.uiState.collectAsState()
    val stableInsets = LocalStableSystemBarInsets.current

    LifecycleStartEffect(vm) {
        vm.onForeground()
        onStopOrDispose { vm.onBackground() }
    }

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
                OverviewGridCard(stats)
                ReadingTimeCard(stats)
                RecentReadingCard(stats, onOpenBook)
                Text(
                    buildString {
                        append("统计口径：阅读进度与已译章节为本地书库实时数据")
                        stats.firstRecordEpochDay?.let {
                            append("；阅读时长自 ${formatStatsStartDate(it)} 起累计")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                // AppShell 的玻璃底栏悬浮在内容上方；预留「底栏 + 呼吸」的完整滚动余量
                Spacer(Modifier.height(bottomChromePadding))
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
            "书库的阅读全景",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 阅读时长卡：今日/本周/累计/日均 四格 + 近 7/30 天趋势柱状图（卡内分段切换）。 */
@Composable
private fun ReadingTimeCard(stats: ReadingStats) {
    var windowDays by remember { mutableIntStateOf(7) }
    SoftCard {
        Text(
            "阅读时长",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            DurationTile(
                icon = Icons.Outlined.Schedule,
                tint = MaterialTheme.colorScheme.primary,
                value = formatReadingDuration(stats.todaySeconds),
                label = "今日"
            )
            DurationTile(
                icon = Icons.Outlined.DateRange,
                tint = FunctionalColors.Green,
                value = formatReadingDuration(stats.weekSeconds),
                label = "本周"
            )
        }
        StatsCardDivider()
        Row(Modifier.fillMaxWidth()) {
            DurationTile(
                icon = Icons.Outlined.Timer,
                tint = FunctionalColors.Orange,
                value = formatReadingDuration(stats.totalSeconds),
                label = "累计"
            )
            DurationTile(
                icon = Icons.Outlined.TrendingUp,
                tint = MaterialTheme.colorScheme.secondary,
                value = formatReadingDuration(stats.avgDailySeconds),
                label = "日均"
            )
        }
        StatsCardDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "阅读趋势",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            ChartWindowToggle(windowDays) { windowDays = it }
        }
        Spacer(Modifier.height(10.dp))
        if (stats.totalSeconds == 0L) {
            Box(
                modifier = Modifier.fillMaxWidth().height(96.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无时长记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val series = remember(stats.dailyTotals, stats.todayEpochDay, windowDays) {
                dailySeries(stats.dailyTotals, stats.todayEpochDay, windowDays)
            }
            DailyBarsChart(series, Modifier.fillMaxWidth().height(96.dp))
            Spacer(Modifier.height(4.dp))
            ChartDayLabels(stats.todayEpochDay, windowDays)
        }
    }
}

@Composable
private fun RowScope.DurationTile(
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
            // 时长是长文本（「3 小时 24 分」），用 titleMedium 而非总览卡的 titleLarge
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
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

/** 7/30 天窗口切换：紧凑胶囊分段（28dp 高，iOS 风格），比 M3 SegmentedButton 明显小一号。 */
@Composable
private fun ChartWindowToggle(selectedDays: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChartWindowOption("7 天", selected = selectedDays == 7) { onSelect(7) }
        ChartWindowOption("30 天", selected = selectedDays == 30) { onSelect(30) }
    }
}

@Composable
private fun ChartWindowOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val optionColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = optionColor,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

/** 柱状图横轴标签：7 天每天标周几；30 天稀疏标日期（每第 7 天，避开两端裁切）。 */
@Composable
private fun ChartDayLabels(todayEpochDay: Long, windowDays: Int) {
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    Row(Modifier.fillMaxWidth()) {
        repeat(windowDays) { index ->
            val day = todayEpochDay - windowDays + 1 + index
            val text = if (windowDays == 7) {
                weekLabels[LocalDate.ofEpochDay(day).dayOfWeek.value - 1]
            } else if (index % 7 == 3) {
                val date = LocalDate.ofEpochDay(day)
                "${date.monthValue}/${date.dayOfMonth}"
            } else {
                ""
            }
            // 单元格只有 1/windowDays 宽：解除最大宽度约束让日期保持单行、向两侧溢出居中，
            // 否则「9/21」会在 30 天视图里被压成竖排两行
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                softWrap = false,
                modifier = Modifier
                    .weight(1f)
                    .wrapContentWidth(align = Alignment.CenterHorizontally, unbounded = true)
            )
        }
    }
}

/** 迷你柱状图：每日一根柱，主色；无记录的天画 outlineVariant 小桩，横轴恒定。 */
@Composable
private fun DailyBarsChart(series: List<Long>, modifier: Modifier = Modifier) {
    val activeColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val maxSeconds = series.max().coerceAtLeast(1L)
        val gap = 2.dp.toPx()
        val stub = 2.dp.toPx()
        val slot = size.width / series.size
        val barWidth = (slot - gap).coerceAtLeast(gap)
        val corner = 1.5.dp.toPx()
        series.forEachIndexed { index, seconds ->
            val barHeight = if (seconds > 0L) {
                (seconds.toFloat() / maxSeconds * (size.height - stub)).coerceAtLeast(stub * 2)
            } else {
                stub
            }
            drawRoundRect(
                color = if (seconds > 0L) activeColor else emptyColor,
                topLeft = Offset(index * slot + gap / 2f, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(corner, corner)
            )
        }
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

/** 最近阅读：点击行直接打开书籍；有时长记录的书追加「累读」。 */
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
                            if (item.seconds > 0L) append(" · 累读 ${formatReadingDuration(item.seconds)}")
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
