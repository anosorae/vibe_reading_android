package com.vibereading.app.ui.bookshelf

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vibereading.app.domain.model.BookShelfItem
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    vm: BookshelfViewModel,
    onOpenBook: (Long) -> Unit,
    coverTransition: @Composable (Long) -> Modifier = { Modifier },
    modifier: Modifier = Modifier
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current

    var menuBook by remember { mutableStateOf<BookShelfItem?>(null) }
    var confirmDeleteBook by remember { mutableStateOf<BookShelfItem?>(null) }
    var sourceLangPickerBook by remember { mutableStateOf<BookShelfItem?>(null) }
    var confirmSourceLang by remember { mutableStateOf<Pair<BookShelfItem, String>?>(null) }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { vm.uploadBook(context, it) }
    }

    // 封面图片选择器：image/* 走 SAF（零权限）；pendingCoverBookId 记住长按的是哪本书，
    // 因为选择器返回时菜单已关闭、menuBook 已置空
    var pendingCoverBookId by remember { mutableStateOf<Long?>(null) }
    val coverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val bookId = pendingCoverBookId
        pendingCoverBookId = null
        if (uri != null && bookId != null) vm.setCover(context, bookId, uri)
    }

    val message = state.shelfMessage
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
        modifier = modifier,
        contentWindowInsets = stableInsets,
        topBar = {
            BookshelfTopBar(
                stableInsets = stableInsets,
                searchExpanded = searchExpanded,
                searchText = searchText,
                layout = state.layout,
                onSearchTextChange = {
                    searchText = it
                    vm.setSearchQuery(it)
                },
                onToggleSearch = {
                    searchExpanded = !searchExpanded
                    if (!searchExpanded) {
                        searchText = ""
                        vm.setSearchQuery("")
                    }
                },
                onToggleLayout = {
                    vm.switchLayout(if (state.layout == "grid") "list" else "grid")
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                // TXT 与 EPUB 一起可选（ADR-002）；部分文件管理器对 epub 上报的 MIME 不规范，
                // 同时给出具体类型与通配扩展名兜底
                onClick = { fileLauncher.launch(arrayOf("text/plain", "application/epub+zip", "*/*")) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "上传书籍")
            }
        }
    ) { padding ->
        // 背景插画是 Scaffold 内容的**第一个子节点**：它压在 Scaffold 的 containerColor 之上、
        // 又在外层 AppShell 的悬浮底栏之下 —— 所以底栏正好落在插画上（和设计稿一致）。
        // 放在 padding 之外，是为了让它能一路贴到屏幕底边。
        Box(modifier = Modifier.fillMaxSize()) {
            ShelfBackdrop(modifier = Modifier.align(Alignment.BottomCenter))
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                BookshelfContent(
                    state = state,
                    searchText = searchText,
                    coverTransition = coverTransition,
                    onOpenBook = onOpenBook,
                    onLongClickBook = { menuBook = it },
                    onMoreClickBook = { menuBook = it },
                    onSort = vm::switchSort,
                    onToggleOrder = vm::switchSortOrder
                )
                ShelfMessageBanner(message = message)
            }
        }
    }

    menuBook?.let { item ->
        BookActionsSheet(
            item = item,
            onDismiss = { menuBook = null },
            onOpenBook = {
                menuBook = null
                onOpenBook(item.book.id)
            },
            onSelectSourceLanguage = {
                sourceLangPickerBook = item
                menuBook = null
            },
            onSelectCover = {
                pendingCoverBookId = item.book.id
                menuBook = null
                coverLauncher.launch(arrayOf("image/*"))
            },
            onRemoveCover = {
                menuBook = null
                vm.removeCover(item.book.id)
            },
            onDelete = {
                menuBook = null
                confirmDeleteBook = item
            }
        )
    }

    DeleteBookDialog(
        item = confirmDeleteBook,
        onDismiss = { confirmDeleteBook = null },
        onConfirm = { item ->
            vm.deleteBook(item.book.id)
            confirmDeleteBook = null
        }
    )
    SourceLanguagePickerDialog(
        item = sourceLangPickerBook,
        onDismiss = { sourceLangPickerBook = null },
        onConfirm = { item, picked ->
            sourceLangPickerBook = null
            if (picked != item.book.sourceLanguage) confirmSourceLang = item to picked
        }
    )
    ConfirmSourceLanguageDialog(
        selection = confirmSourceLang,
        onDismiss = { confirmSourceLang = null },
        onConfirm = { item, targetLang ->
            vm.correctSourceLanguage(item.book.id, targetLang)
            confirmSourceLang = null
        }
    )
}
