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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import com.vibereading.app.domain.model.BookShelfItem
import com.vibereading.app.ui.theme.VibeColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfTopBar(
    stableInsets: WindowInsets,
    searchExpanded: Boolean,
    searchText: String,
    layout: String,
    accentColor: Color,
    onSearchTextChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onToggleLayout: () -> Unit,
    onOpenSettings: () -> Unit
) {
    TopAppBar(
        windowInsets = stableInsets,
        title = {
            if (searchExpanded) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = onSearchTextChange,
                    placeholder = { Text("搜索书名") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("译读", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }
        },
        actions = {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = if (searchExpanded) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggleLayout) {
                Icon(
                    if (layout == "grid") Icons.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = if (layout == "grid") "切换列表" else "切换网格",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    )
}

@Composable
internal fun BoxScope.BookshelfContent(
    state: BookshelfUiState,
    searchText: String,
    accentColor: Color,
    coverTransition: @Composable (Long) -> Modifier,
    onOpenBook: (Long) -> Unit,
    onLongClickBook: (BookShelfItem) -> Unit,
    onSort: (String) -> Unit,
    onToggleOrder: (String) -> Unit
) {
    when {
        state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = accentColor)
        state.items.isEmpty() -> EmptyShelf()
        else -> Column(modifier = Modifier.fillMaxSize()) {
            SortBar(
                sort = state.sort,
                sortOrder = state.sortOrder,
                accentColor = accentColor,
                onSort = onSort,
                onToggleOrder = onToggleOrder
            )
            when {
                state.filteredItems.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有匹配「${searchText}」的书籍", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.layout == "grid" -> BooksGrid(
                    items = state.filteredItems,
                    accentColor = accentColor,
                    coverTransition = coverTransition,
                    onOpenBook = onOpenBook,
                    onLongClickBook = onLongClickBook
                )
                else -> BooksList(
                    items = state.filteredItems,
                    accentColor = accentColor,
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
                tint = MaterialTheme.colorScheme.outlineVariant
            )
            Spacer(Modifier.height(16.dp))
            Text("书架空空如也", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("点击右下角 + 上传 TXT / EPUB 书籍", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun BooksGrid(
    items: List<BookShelfItem>,
    accentColor: Color,
    coverTransition: @Composable (Long) -> Modifier,
    onOpenBook: (Long) -> Unit,
    onLongClickBook: (BookShelfItem) -> Unit
) {
    val gridHorizontalPadding = 16.dp
    val gridVerticalPadding = 6.dp
    val horizontalSpacing = 14.dp
    val textAreaHeight = 34.dp
    val rows = 3
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availH = maxHeight - gridVerticalPadding * 2
        val verticalSpacing = 20.dp
        val cardHeight = (availH - verticalSpacing * (rows - 1)) / rows
        val coverHeight = cardHeight - textAreaHeight
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = gridHorizontalPadding, vertical = gridVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing)
        ) {
            items(items, key = { it.book.id }) { item ->
                BookGridCard(
                    item = item,
                    accentColor = accentColor,
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
    accentColor: Color,
    coverTransition: @Composable (Long) -> Modifier,
    onOpenBook: (Long) -> Unit,
    onLongClickBook: (BookShelfItem) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(items, key = { it.book.id }) { item ->
            BookRow(
                item = item,
                accentColor = accentColor,
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
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (message.contains("失败")) VibeColors.RedMuted else VibeColors.Sage,
                tonalElevation = 4.dp
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = Color.White,
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
    accentColor: Color,
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("排序", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Box {
            TextButton(onClick = { menuExpanded = true }) {
                Text(currentLabel, color = accentColor, fontSize = 13.sp)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                options.forEach { (key, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menuExpanded = false
                            onSort(key)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { onToggleOrder(if (isDesc) SortOrder.ASC else SortOrder.DESC) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                if (isDesc) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (isDesc) "降序" else "升序",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookRow(
    item: BookShelfItem,
    accentColor: Color,
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
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text("未开始阅读", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("共 ${book.totalChapters} 章", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.translatedCount > 0) {
                    Text(" · ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("已译 ${item.translatedCount}", fontSize = 12.sp, color = VibeColors.Sage)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookGridCard(
    item: BookShelfItem,
    accentColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    coverHeight: Dp = 160.dp,
    coverModifier: Modifier = Modifier
) {
    val book = item.book
    Column(
        modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(coverHeight).then(coverModifier).clip(RoundedCornerShape(8.dp))
        ) {
            BookCover(title = book.title, coverPath = book.coverPath, modifier = Modifier.fillMaxSize())
            if (item.translatedCount > 0) {
                Surface(
                    shape = RoundedCornerShape(topEnd = 8.dp, bottomStart = 8.dp),
                    color = VibeColors.Sage.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(
                        "${item.translatedCount}/${book.totalChapters}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(start = 2.dp, top = 4.dp, end = 2.dp)) {
            Text(
                book.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            val readChapters = if (book.totalChapters > 0 && item.progress > 0f) {
                (item.progress * book.totalChapters).toInt()
            } else 0
            if (readChapters > 0) {
                Text(
                    "已读${readChapters}/${book.totalChapters}章",
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
