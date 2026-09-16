package com.vibereading.app.ui.bookshelf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vibereading.app.domain.model.BookShelfItem

/**
 * 网格卡片（设计基线的主视觉）：封面贴齐卡片顶部同圆角，正文区固定高度、
 * 进度行贴底对齐 —— 固定高度是刻意的，同一行的卡片才对得齐，书名单行省略号。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookGridCard(
    item: BookShelfItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
    coverHeight: Dp,
    coverModifier: Modifier = Modifier
) {
    val book = item.book
    val read = readChapters(item.progress, book.totalChapters)
    Surface(
        modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        ),
        shape = RoundedCornerShape(ShelfMetrics.CardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverHeight)
                    .then(coverModifier)
                    .clip(RoundedCornerShape(ShelfMetrics.CardCorner))
            ) {
                BookCover(title = book.title, coverPath = book.coverPath, modifier = Modifier.fillMaxSize())
                if (book.totalChapters > 0) {
                    ShelfCoverBadge(
                        text = "$read/${book.totalChapters}",
                        modifier = Modifier.align(Alignment.TopEnd).padding(ShelfMetrics.BadgeInset)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ShelfMetrics.CardBodyHeight)
                    .padding(horizontal = ShelfMetrics.CardPadding)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        book.title,
                        style = ShelfTypography.cardTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 14.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (read > 0) "已读 $read / ${book.totalChapters} 章" else "尚未开始阅读",
                        style = ShelfTypography.cardMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.weight(1f))
                    ShelfProgressRow(progress = item.progress)
                    Spacer(Modifier.height(9.dp))
                }
                // ⋮ 走覆盖层而不是塞进书名那一行：IconButton 的最小触摸区比书名行高，
                // 放进 Row 会把它顶高 5dp，整块正文就被推下去了
                ShelfMoreButton(onMoreClick, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

/** 列表行：同一套视觉语言（同圆角容器、同字号刻度、同进度行），信息密度按行放宽。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookRow(
    item: BookShelfItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
    coverModifier: Modifier = Modifier
) {
    val book = item.book
    val read = readChapters(item.progress, book.totalChapters)
    Surface(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        ),
        shape = RoundedCornerShape(ShelfMetrics.CardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            val coverWidth = 56.dp
            Box(
                modifier = Modifier
                    .width(coverWidth)
                    .height(coverWidth / ShelfMetrics.CoverAspect)
                    .then(coverModifier)
                    .clip(RoundedCornerShape(ShelfMetrics.CardCorner))
            ) {
                BookCover(title = book.title, coverPath = book.coverPath, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        book.title,
                        style = ShelfTypography.rowTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    ShelfMoreButton(onMoreClick)
                }
                Text(
                    item.lastReadChapterTitle ?: "未开始阅读",
                    style = ShelfTypography.cardMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append("共 ${book.totalChapters} 章")
                        if (read > 0) append(" · 已读 $read 章")
                        if (item.translatedCount > 0) append(" · 已译 ${item.translatedCount} 章")
                    },
                    style = ShelfTypography.cardMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                ShelfProgressRow(progress = item.progress)
            }
        }
    }
}

/** 封面右上角的「已读/总章数」胶囊：白底 + 强调色文字，与封面留 4.5dp 内缩。 */
@Composable
private fun ShelfCoverBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.92f)
    ) {
        Text(
            text,
            style = ShelfTypography.cardNumeric,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                horizontal = ShelfMetrics.BadgePaddingH,
                vertical = ShelfMetrics.BadgePaddingV
            )
        )
    }
}

/**
 * 进度行：细轨道 + 右侧百分比。用 Canvas 而不是 `LinearProgressIndicator`，
 * 因为 M3 那条有固定的 4dp 高度与「轨道与指示器之间的间隙」，
 * 设计基线要的是 5.5dp 圆头轨道 + 可见的最窄填充，自己画更直接。
 */
@Composable
private fun ShelfProgressRow(progress: Float, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val fill = MaterialTheme.colorScheme.primary
    val minFillPx = with(LocalDensity.current) { ShelfMetrics.ProgressMinFill.toPx() }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(ShelfMetrics.ProgressTrackHeight)
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(color = track, cornerRadius = radius)
            val filled = progressFillWidth(size.width, progress, minFillPx)
            if (filled > 0f) {
                drawRoundRect(
                    color = fill,
                    size = Size(filled, size.height),
                    cornerRadius = radius
                )
            }
        }
        Spacer(Modifier.width(ShelfMetrics.ProgressPercentGap))
        Text(
            percentLabel(progress),
            style = ShelfTypography.cardNumeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** ⋮：与长按同一个动作面板，尺寸取设计稿的 20dp（卡片整体仍可点可长按）。 */
@Composable
private fun ShelfMoreButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(22.dp)) {
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = "更多操作",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
