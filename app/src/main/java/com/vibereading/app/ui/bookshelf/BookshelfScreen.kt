package com.vibereading.app.ui.bookshelf

import android.app.Activity
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.BookShelfItem
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets
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

    // 从阅读器返回时确保系统栏恢复（安全网：阅读器 onDispose 异步延迟时的兜底）
    val restoreView = LocalView.current
    val restoreActivity = context as? Activity
    LaunchedEffect(Unit) {
        restoreActivity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, restoreView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }

    // 稳定系统栏 insets：沉浸式切换时不归零，防止从阅读器返回时布局跳动
    val stableInsets = LocalStableSystemBarInsets.current

    Scaffold(
        contentWindowInsets = stableInsets,
        topBar = {
            TopAppBar(
                windowInsets = stableInsets,
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
                            "译读",
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
                    // 排序条
                    SortBar(
                        sort = state.sort,
                        sortOrder = state.sortOrder,
                        accentColor = accentColor,
                        onSort = vm::switchSort,
                        onToggleOrder = vm::switchSortOrder
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
                                contentPadding = PaddingValues(
                                    horizontal = gridHorizontalPadding,
                                    vertical = gridVerticalPadding
                                ),
                                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
                                verticalArrangement = Arrangement.spacedBy(verticalSpacing)
                            ) {
                                items(state.filteredItems, key = { it.book.id }) { item ->
                                    BookGridCard(
                                        item = item,
                                        accentColor = accentColor,
                                        onClick = { onOpenBook(item.book.id) },
                                        onLongClick = { menuBook = item },
                                        coverHeight = coverHeight
                                    )
                                }
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
                    "${item.book.totalChapters}章 · 已译${item.translatedCount}",
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

// ── 排序条：排序方式 + 升序/降序切换 ──
@Composable
private fun SortBar(
    sort: String,
    sortOrder: String,
    accentColor: androidx.compose.ui.graphics.Color,
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
        // 升序/降序切换按钮
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

// ── 列表行：封面 + 书名 + 阅读进度（仿 Legado 三行列表） ──
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BookCover(
            title = book.title,
            modifier = Modifier.width(56.dp).height(76.dp)
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // 第 1 行：书名
            Text(
                book.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 第 2 行：阅读进度章节标题
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
                Text(
                    "未开始阅读",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // 第 3 行：章数 + 已译
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "共 ${book.totalChapters} 章",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.translatedCount > 0) {
                    Text(" · ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "已译 ${item.translatedCount}",
                        fontSize = 12.sp,
                        color = VibeColors.Sage
                    )
                }
            }
        }
    }
}

// ── 网格卡片：封面 + 右上角角标 + 封面下方书名和进度 ──
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridCard(
    item: BookShelfItem,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    coverHeight: Dp = 160.dp
) {
    val book = item.book

    Column(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        // 封面区域：封面 + 右上角翻译角标
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverHeight)
                .clip(RoundedCornerShape(8.dp))
        ) {
            BookCover(
                title = book.title,
                modifier = Modifier.fillMaxSize()
            )

            // 右上角：已译/总章 角标
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

        // 封面下方：书名 + 阅读进度（水平 padding 补偿封面阴影的视觉偏移）
        Column(
            modifier = Modifier.padding(start = 2.dp, top = 4.dp, end = 2.dp)
        ) {
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
