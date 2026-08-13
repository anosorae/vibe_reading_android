package com.vibereading.app.ui.bookshelf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.BookShelfItem
import com.vibereading.app.ui.theme.VibeColors
import com.vibereading.app.ui.theme.WereadColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(
    vm: BookshelfViewModel,
    onOpenBook: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val accentColor = if (state.accent == AppAccent.WEREAD) WereadColors.Accent else VibeColors.Sienna

    // 长按书籍 → 操作菜单（删除 / 开始阅读）
    var menuBook by remember { mutableStateOf<BookShelfItem?>(null) }
    // 待删除确认的书籍
    var confirmDeleteBook by remember { mutableStateOf<BookShelfItem?>(null) }
    // 搜索框展开状态
    var searchExpanded by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    // File picker
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { vm.uploadBook(context, it) }
    }

    // Upload message auto-dismiss
    val message = state.uploadMessage
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(4000)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchExpanded) {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = {
                                searchText = it
                                vm.setSearchQuery(it)
                            },
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
                        Text(
                            "Vibe Reading",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    // 搜索切换
                    IconButton(onClick = {
                        searchExpanded = !searchExpanded
                        if (!searchExpanded) {
                            searchText = ""
                            vm.setSearchQuery("")
                        }
                    }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "搜索",
                            tint = if (searchExpanded) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 布局切换 列表/网格
                    IconButton(onClick = { vm.switchLayout(if (state.layout == "grid") "list" else "grid") }) {
                        Icon(
                            if (state.layout == "grid") Icons.Filled.ViewList else Icons.Filled.GridView,
                            contentDescription = if (state.layout == "grid") "切换列表" else "切换网格",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 设置
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { fileLauncher.launch(arrayOf("text/plain")) },
                containerColor = accentColor,
                contentColor = androidx.compose.ui.graphics.Color.White
            ) {
                Icon(Icons.Filled.Add, contentDescription = "上传书籍")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = accentColor
                )
            } else if (state.items.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "书架空空如也",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点击右下角 + 上传 TXT 书籍",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 排序条（仿 Legado bookshelfSort 下拉）
                    SortBar(
                        sort = state.sort,
                        accentColor = accentColor,
                        onSort = vm::switchSort
                    )

                    // 无搜索结果
                    if (state.filteredItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "没有匹配「${searchText}」的书籍",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (state.layout == "grid") {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(state.filteredItems, key = { it.book.id }) { item ->
                                BookGridCard(
                                    item = item,
                                    accentColor = accentColor,
                                    onClick = { onOpenBook(item.book.id) },
                                    onLongClick = { menuBook = item }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(state.filteredItems, key = { it.book.id }) { item ->
                                BookRow(
                                    item = item,
                                    accentColor = accentColor,
                                    onClick = { onOpenBook(item.book.id) },
                                    onLongClick = { menuBook = item }
                                )
                            }
                        }
                    }
                }
            }

            // Upload message snackbar
            AnimatedVisibility(
                visible = message != null,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
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
                            color = androidx.compose.ui.graphics.Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }

    // Long-press action menu
    menuBook?.let { item ->
        ModalBottomSheet(onDismissRequest = { menuBook = null }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    item.book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                Text(
                    "${item.book.totalChapters}章 · 已译${item.book.translatedChapters}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(8.dp))
                ListItem(
                    headlineContent = { Text("开始阅读") },
                    leadingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    modifier = Modifier.clickable {
                        val id = menuBook?.book?.id
                        menuBook = null
                        if (id != null) onOpenBook(id)
                    }
                )
                ListItem(
                    headlineContent = { Text("删除", color = VibeColors.RedMuted) },
                    leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null, tint = VibeColors.RedMuted) },
                    modifier = Modifier.clickable {
                        menuBook = null
                        confirmDeleteBook = item
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // Delete confirmation dialog
    confirmDeleteBook?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDeleteBook = null },
            title = { Text("删除书籍") },
            text = { Text("确定要删除《${item.book.title}》吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBook(item.book.id)
                    confirmDeleteBook = null
                }) {
                    Text("删除", color = VibeColors.RedMuted)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteBook = null }) {
                    Text("取消")
                }
            }
        )
    }
}

// ── 排序条：最近阅读 / 书名 / 上传时间 ──
@Composable
private fun SortBar(
    sort: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onSort: (String) -> Unit
) {
    val options = listOf(
        ShelfSort.RECENT to "最近阅读",
        ShelfSort.TITLE to "书名",
        ShelfSort.CREATED to "上传时间"
    )
    var menuExpanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == sort }?.second ?: "最近阅读"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("排序", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Box {
            TextButton(onClick = { menuExpanded = true }) {
                Text(currentLabel, color = accentColor, fontSize = 13.sp)
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
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
        Text(
            "下拉菜单可切换排序",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

// ── 列表行：封面 + 书名 + 进度 ──
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    item: BookShelfItem,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val book = item.book

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BookCover(
            title = book.title,
            modifier = Modifier.width(56.dp).height(76.dp)
        )

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${book.totalChapters}章",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(" · ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(
                    "已译${book.translatedChapters}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VibeColors.Sage
                )
            }
            // 阅读进度条
            item.lastReadChapterTitle?.let { title ->
                Spacer(Modifier.height(6.dp))
                Text(
                    title,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { item.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ── 网格卡片：竖版封面 + 书名 + 进度条 ──
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridCard(
    item: BookShelfItem,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val book = item.book

    Column(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        BookCover(
            title = book.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        if (item.progress > 0f) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { item.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = accentColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        } else {
            Spacer(Modifier.height(3.dp))
            Text(
                "未开始",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
