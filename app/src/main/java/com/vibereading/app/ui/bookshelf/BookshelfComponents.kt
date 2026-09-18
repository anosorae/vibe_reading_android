package com.vibereading.app.ui.bookshelf

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
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
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vibereading.app.domain.model.BookShelfItem

/**
 * 书架顶栏：品牌名 / 副标题 + 两个圆钮（搜索、布局切换）。
 * 设置入口刻意不在这里 —— 它就是底部「我的」Tab，两个入口是重复的。
 */
@Composable
internal fun BookshelfTopBar(
    stableInsets: WindowInsets,
    searchExpanded: Boolean,
    searchText: String,
    layout: String,
    onSearchTextChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onToggleLayout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // 只吃顶部（横向留给刘海屏）。用整份 stableInsets 会把**导航栏**高度也加进来：
            // Scaffold 拿 topBar 的实测高度当 innerPadding.top，于是整个书架被下推一个导航栏
            // （实测 53dp），排序行与网格一起下沉。
            .windowInsetsPadding(stableInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = ShelfMetrics.HeaderPadding, end = ShelfMetrics.HeaderPadding, top = 22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "译读",
                    style = ShelfTypography.brand,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "用双语，阅读更大的世界",
                    style = ShelfTypography.tagline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HeaderCircleButton(Icons.Filled.Search, "搜索", onToggleSearch)
            Spacer(Modifier.width(ShelfMetrics.HeaderButtonGap))
            HeaderCircleButton(
                // 图标画的是「切过去会变成什么」，不是当前状态
                if (layout == "grid") Icons.AutoMirrored.Filled.FormatListBulleted else Icons.Filled.GridView,
                if (layout == "grid") "切换列表" else "切换网格",
                onToggleLayout
            )
        }
        AnimatedVisibility(visible = searchExpanded, enter = fadeIn(), exit = fadeOut()) {
            TextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                placeholder = { Text("搜索书名", style = ShelfTypography.sortLabel) },
                singleLine = true,
                shape = RoundedCornerShape(ShelfMetrics.HeaderButtonSize / 2),
                textStyle = ShelfTypography.sortLabel,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun HeaderCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(ShelfMetrics.HeaderButtonSize)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun BoxScope.BookshelfContent(
    state: BookshelfUiState,
    searchText: String,
    coverTransition: @Composable (Long) -> Modifier,
    bottomChromePadding: Dp,
    onOpenBook: (Long) -> Unit,
    onLongClickBook: (BookShelfItem) -> Unit,
    onMoreClickBook: (BookShelfItem) -> Unit,
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
                        style = ShelfTypography.sortLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.layout == "grid" -> BooksGrid(
                    items = state.filteredItems,
                    coverTransition = coverTransition,
                    bottomChromePadding = bottomChromePadding,
                    onOpenBook = onOpenBook,
                    onLongClickBook = onLongClickBook,
                    onMoreClickBook = onMoreClickBook
                )

                else -> BooksList(
                    items = state.filteredItems,
                    coverTransition = coverTransition,
                    bottomChromePadding = bottomChromePadding,
                    onOpenBook = onOpenBook,
                    onLongClickBook = onLongClickBook,
                    onMoreClickBook = onMoreClickBook
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
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "书架空空如也",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "点击右下角 + 上传 TXT / EPUB 书籍",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BooksGrid(
    items: List<BookShelfItem>,
    coverTransition: @Composable (Long) -> Modifier,
    bottomChromePadding: Dp,
    onOpenBook: (Long) -> Unit,
    onLongClickBook: (BookShelfItem) -> Unit,
    onMoreClickBook: (BookShelfItem) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = shelfColumns(maxWidth)
        val cardWidth = shelfCardWidth(maxWidth, columns)
        val coverHeight = cardWidth / ShelfMetrics.CoverAspect
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            // 底部余量给玻璃底栏：最后一排书卡能完整滚出栏体，且滚动中从栏后玻璃透出
            contentPadding = PaddingValues(
                start = ShelfMetrics.PagePadding,
                end = ShelfMetrics.PagePadding,
                bottom = bottomChromePadding
            ),
            horizontalArrangement = Arrangement.spacedBy(ShelfMetrics.GridSpacing),
            verticalArrangement = Arrangement.spacedBy(ShelfMetrics.GridSpacing)
        ) {
            items(items, key = { it.book.id }) { item ->
                BookGridCard(
                    item = item,
                    onClick = { onOpenBook(item.book.id) },
                    onLongClick = { onLongClickBook(item) },
                    onMoreClick = { onMoreClickBook(item) },
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
    bottomChromePadding: Dp,
    onOpenBook: (Long) -> Unit,
    onLongClickBook: (BookShelfItem) -> Unit,
    onMoreClickBook: (BookShelfItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ShelfMetrics.PagePadding,
            end = ShelfMetrics.PagePadding,
            bottom = bottomChromePadding + 4.dp
        ),
        verticalArrangement = Arrangement.spacedBy(ShelfMetrics.GridSpacing)
    ) {
        items(items, key = { it.book.id }) { item ->
            BookRow(
                item = item,
                onClick = { onOpenBook(item.book.id) },
                onLongClick = { onLongClickBook(item) },
                onMoreClick = { onMoreClickBook(item) },
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

/**
 * 排序行：设计基线里它是**裸文字**，没有卡片底 —— 它是一条筛选器，不是内容。
 * 刻意去掉了原先那个不可点的「全部书籍 ›」装饰：它长得像入口却点不动。
 */
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ShelfMetrics.HeaderPadding,
                end = ShelfMetrics.HeaderPadding,
                top = ShelfMetrics.SortRowTopPadding,
                bottom = ShelfMetrics.SortRowBottomPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { onToggleOrder(if (isDesc) SortOrder.ASC else SortOrder.DESC) },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.Filled.SwapVert,
                contentDescription = "切换排序方向",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(6.dp))
        Box {
            Row(
                modifier = Modifier.clickable { menuExpanded = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    currentLabel,
                    style = ShelfTypography.sortLabel,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "排序方式",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
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
    }
}
