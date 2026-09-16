package com.vibereading.app.ui.bookshelf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vibereading.app.domain.model.BookShelfItem

/** 网格封面宽高比（宽/高）：普通书封约 0.68，封面高度由列宽推出，不再按「一屏几行」反推。 */
private const val COVER_ASPECT = 0.68f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfTopBar(
    stableInsets: WindowInsets,
    searchExpanded: Boolean,
    searchText: String,
    layout: String,
    onSearchTextChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onToggleLayout: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(stableInsets)
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "译读",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "用双语，阅读更大的世界",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HeaderCircleButton(Icons.Filled.Search, "搜索", onToggleSearch)
            Spacer(Modifier.width(8.dp))
            HeaderCircleButton(
                if (layout == "grid") Icons.Filled.ViewList else Icons.Filled.GridView,
                if (layout == "grid") "切换列表" else "切换网格",
                onToggleLayout
            )
            Spacer(Modifier.width(8.dp))
            HeaderCircleButton(Icons.Filled.Settings, "设置", onOpenSettings)
        }
        if (searchExpanded) {
            Spacer(Modifier.height(14.dp))
            TextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                placeholder = { Text("搜索书名") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun HeaderCircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BoxScope.BookshelfContent(
    state: BookshelfUiState,
    searchText: String,
    coverTransition: @Composable (Long) -> Modifier,
    onOpenBook: (Long) -> Unit,
    onLongClickBook: (BookShelfItem) -> Unit,
    onSort: (String) -> Unit,
    onToggleOrder: (String) -> Unit
) {
    when {
        state.isLoading -> CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center),
            color = MaterialTheme.colorScheme.primary
        )

        state.items.isEmpty() -> EmptyShelf()
        else -> Column(modifier = Modifier.fillMaxSize()) {
            ReadingBanner()
            SortBar(
                sort = state.sort,
                sortOrder = state.sortOrder,
                onSort = onSort,
                onToggleOrder = onToggleOrder
            )
            when {
                state.filteredItems.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "没有匹配「${searchText}」的书籍",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.layout == "grid" -> BooksGrid(
                    items = state.filteredItems,
                    coverTransition = coverTransition,
                    onOpenBook = onOpenBook,
                    onLongClickBook = onLongClickBook
                )

                else -> BooksList(
                    items = state.filteredItems,
                    coverTransition = coverTransition,
                    onOpenBook = onOpenBook,
                    onLongClickBook = onLongClickBook
                )
            }
        }
    }
}

@Composable
private fun EmptyShelf() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "书架空空如也",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "点击右下角 + 上传 TXT / EPUB 书籍",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ReadingBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "阅读，让平凡的日子\n也有了光。",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Good Books, A Brighter You.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun BooksGrid(
    items: List<BookShelfItem>,
    coverTransition: @Composable (Long) -> Modifier,
    onOpenBook: (Long) -> Unit,
    onLongClickBook: (BookShelfItem) -> Unit
) {
    val horizontalPadding = 16.dp
    val verticalPadding = 6.dp
    val horizontalSpacing = 14.dp
    val verticalSpacing = 20.dp
    // 卡片宽度下限：再窄书名就只能显示一两个字
    val minCardWidth = 96.dp
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 列数按可用宽度推（手机 3 列，平板/折叠屏展开后自动加列），
        // 封面高度再由列宽按书封比例推出 —— 原先写死 3 列 + 用屏高反推封面高度，
        // 平板上一屏 3 行会把封面拉成大幅方块
        val columns = maxOf(
            3,
            ((maxWidth - horizontalPadding * 2 + horizontalSpacing) / (minCardWidth + horizontalSpacing)).toInt()
        )
        val cardWidth = (maxWidth - horizontalPadding * 2 - horizontalSpacing * (columns - 1)) / columns
        val coverHeight = cardWidth / COVER_ASPECT
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing)
        ) {
            items(items, key = { it.book.id }) { item ->
                BookGridCard(
                    item = item,
                    onClick = { onOpenBook(item.book.id) },
                    onLongClick = { onLongClickBook(item) },
                    coverHeight = coverHeight,
                    coverModifier = coverTransition(item.book.id)
                )
            }
        }
    }
}

@Composable
private fun BooksList(
    items: List<BookShelfItem>,
    coverTransition: @Composable (Long) -> Modifier,
    onOpenBook: (Long) -> Unit,
    onLongClickBook: (BookShelfItem) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(items, key = { it.book.id }) { item ->
            BookRow(
                item = item,
                onClick = { onOpenBook(item.book.id) },
                onLongClick = { onLongClickBook(item) },
                coverModifier = coverTransition(item.book.id)
            )
        }
    }
}

@Composable
internal fun BoxScope.ShelfMessageBanner(message: String?) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
    ) {
        if (message != null) {
            // 失败/成功用容器色 + on* 文字色，不写死白字：写死白字在换主题后
            // 会跟着底色一起漂，对比度不再可控
            val failed = message.contains("失败")
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (failed) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 4.dp
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = if (failed) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SortBar(
    sort: String,
    sortOrder: String,
    onSort: (String) -> Unit,
    onToggleOrder: (String) -> Unit
) {
    val options = listOf(
        ShelfSort.RECENT to "最近阅读",
        ShelfSort.TITLE to "书名",
        ShelfSort.CREATED to "上传时间"
    )
    var menuExpanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == sort }?.second ?: "最近阅读"
    val isDesc = sortOrder == SortOrder.DESC
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onToggleOrder(if (isDesc) SortOrder.ASC else SortOrder.DESC) }) {
                Icon(Icons.Filled.SwapVert, contentDescription = "切换排序方向", tint = MaterialTheme.colorScheme.primary)
            }
            Box {
                TextButton(onClick = { menuExpanded = true }) {
                    Text("排序  $currentLabel", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    options.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { menuExpanded = false; onSort(key) }
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.GridView, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("全部书籍", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Filled.ChevronRight, contentDescription = "全部书籍", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookRow(
    item: BookShelfItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    coverModifier: Modifier = Modifier
) {
    val book = item.book
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BookCover(
            title = book.title,
            coverPath = book.coverPath,
            modifier = Modifier.width(56.dp).height(76.dp).then(coverModifier)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            if (item.lastReadChapterTitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        item.lastReadChapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    "未开始阅读",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "共 ${book.totalChapters} 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.translatedCount > 0) {
                    Text(
                        " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "已译 ${item.translatedCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookGridCard(
    item: BookShelfItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    coverHeight: Dp = 160.dp,
    coverModifier: Modifier = Modifier
) {
    val book = item.book
    val readChapters = if (book.totalChapters > 0 && item.progress > 0f) {
        (item.progress * book.totalChapters).toInt().coerceAtLeast(1)
    } else 0
    Surface(
        modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(bottom = 14.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverHeight)
                    .then(coverModifier)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            ) {
                BookCover(title = book.title, coverPath = book.coverPath, modifier = Modifier.fillMaxSize())
                Surface(
                    shape = RoundedCornerShape(bottomStart = 16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        "${readChapters}/${book.totalChapters}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (readChapters > 0) "已读 $readChapters / ${book.totalChapters} 章" else "尚未开始阅读",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(
                progress = { item.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
    }
}
