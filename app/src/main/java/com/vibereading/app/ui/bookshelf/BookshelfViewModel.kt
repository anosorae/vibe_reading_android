package com.vibereading.app.ui.bookshelf

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibereading.app.data.image.BookImageStore
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.Book
import com.vibereading.app.domain.model.BookShelfItem
import com.vibereading.app.domain.model.ThemeSettings
import com.vibereading.app.domain.parser.EpubParser
import com.vibereading.app.domain.parser.TxtParser
import com.vibereading.app.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 书架排序方式。 */
object ShelfSort {
    const val RECENT = "recent"   // 最近阅读
    const val TITLE = "title"     // 书名
    const val CREATED = "created" // 上传时间
}

/** 排序方向。 */
object SortOrder {
    const val ASC = "asc"
    const val DESC = "desc"
}

data class BookshelfUiState(
    val items: List<BookShelfItem> = emptyList(),
    val filteredItems: List<BookShelfItem> = emptyList(),
    val isLoading: Boolean = false,
    val uploadMessage: String? = null,
    val accent: AppAccent = AppAccent.VIBE,
    val layout: String = "list",     // "list" | "grid"
    val sort: String = ShelfSort.RECENT,
    val sortOrder: String = SortOrder.DESC
)

class BookshelfViewModel(
    private val bookRepo: BookRepository,
    private val chapterRepo: ChapterRepository,
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookshelfUiState())
    val uiState: StateFlow<BookshelfUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")

    // 排序方式 + 排序方向合成流
    private val sortPref = combine(settingsRepo.bookshelfSort, settingsRepo.bookshelfSortOrder) { sort, order -> sort to order }

    init {
        viewModelScope.launch {
            combine(
                bookRepo.getShelfItems(),
                settingsRepo.themeSettings,
                settingsRepo.bookshelfLayout,
                sortPref,
                searchQuery
            ) { items, theme, layout, (sort, sortOrder), query ->
                val sorted = sortItems(items, sort, sortOrder)
                val filtered = if (query.isBlank()) {
                    sorted
                } else {
                    sorted.filter { it.book.title.contains(query.trim(), ignoreCase = true) }
                }
                _uiState.value = BookshelfUiState(
                    items = sorted,
                    filteredItems = filtered,
                    accent = theme.accent,
                    layout = layout,
                    sort = sort,
                    sortOrder = sortOrder
                )
            }.collect {}
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun switchLayout(layout: String) {
        viewModelScope.launch { settingsRepo.saveBookshelfLayout(layout) }
    }

    fun switchSort(sort: String) {
        viewModelScope.launch { settingsRepo.saveBookshelfSort(sort) }
    }

    fun switchSortOrder(order: String) {
        viewModelScope.launch { settingsRepo.saveBookshelfSortOrder(order) }
    }

    /** 书架排序：最近阅读 / 书名 / 上传时间，支持升序/降序。 */
    private fun sortItems(items: List<BookShelfItem>, sort: String, sortOrder: String): List<BookShelfItem> {
        val ascending = sortOrder == SortOrder.ASC
        return when (sort) {
            ShelfSort.TITLE -> if (ascending) items.sortedBy { it.book.title } else items.sortedByDescending { it.book.title }
            ShelfSort.CREATED -> if (ascending) items.sortedBy { it.book.createdAt } else items.sortedByDescending { it.book.createdAt }
            else -> if (ascending) items.sortedBy { it.book.lastReadAt } else items.sortedByDescending { it.book.lastReadAt }
        }
    }

    fun uploadBook(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val fileName = queryFileName(context, uri)
                // 解码/解析/落盘都在 IO 线程；Room suspend DAO 线程安全
                val message = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw Exception("无法读取文件")
                    if (fileName.endsWith(".epub", ignoreCase = true)) importEpub(bytes, fileName)
                    else importTxt(bytes, fileName)
                }
                _uiState.update { it.copy(isLoading = false, uploadMessage = message) }
            } catch (e: Exception) {
                AppLog.put("书籍上传失败", e)
                _uiState.update {
                    it.copy(isLoading = false, uploadMessage = "上传失败: ${e.message}")
                }
            }
        }
    }

    /** TXT 导入：解码 → 分章 → 入库（原有路径，保持不变）。 */
    private suspend fun importTxt(bytes: ByteArray, fileName: String): String {
        val text = TxtParser.decodeBytes(bytes)
        val chapterDicts = TxtParser.parseText(text)
        if (chapterDicts.isEmpty()) throw Exception("未识别到任何章节")

        val title = fileName.removeSuffix(".txt").removeSuffix(".TXT")
            .ifBlank { "未知书名" }

        val bookId = bookRepo.insert(Book(title = title, totalChapters = chapterDicts.size))
        chapterRepo.insertAll(TxtParser.toChapters(bookId, chapterDicts))
        return "「$title」上传成功，共 ${chapterDicts.size} 章"
    }

    /**
     * EPUB 导入（ADR-002 D1）：一次性解包转换为纯文本章节入库，不保留原文件。
     * 图片在导入期落盘私有目录（BookImageStore），封面写入 books.coverPath。
     */
    private suspend fun importEpub(bytes: ByteArray, fileName: String): String {
        val parsed = EpubParser.parse(bytes) { imageBytes ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        }
        if (parsed.chapters.isEmpty()) throw Exception("EPUB 没有可读章节")

        val title = parsed.meta.title?.takeIf { it.isNotBlank() }
            ?: fileName.removeSuffix(".epub").ifBlank { "未知书名" }

        val bookId = bookRepo.insert(
            Book(title = title, totalChapters = parsed.chapters.size, format = "epub")
        )
        val nameByHref = BookImageStore.saveImages(bookId, parsed.images) { href ->
            val ext = href.substringAfterLast('.', "")
            if (ext.length in 1..5) ".$ext" else ".jpg"
        }
        parsed.coverBytes?.let { BookImageStore.saveCover(bookId, it) }?.let { coverPath ->
            bookRepo.getBookByIdOnce(bookId)?.let { book -> bookRepo.update(book.copy(coverPath = coverPath)) }
        }
        val dicts = EpubParser.toChapterDicts(bookId, parsed) { href -> nameByHref[href] }
        chapterRepo.insertAll(TxtParser.toChapters(bookId, dicts))
        return "「$title」导入成功，共 ${parsed.chapters.size} 章"
    }

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            // 先清插图/封面磁盘文件再删库记录（ADR-002 D3）
            try {
                BookImageStore.deleteBookFiles(bookId)
            } catch (e: Exception) {
                AppLog.put("删书清理图片失败 bookId=$bookId", e)
            }
            bookRepo.delete(bookId)
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(uploadMessage = null) }
    }

    class Factory(
        private val bookRepo: BookRepository,
        private val chapterRepo: ChapterRepository,
        private val settingsRepo: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BookshelfViewModel(bookRepo, chapterRepo, settingsRepo) as T
        }
    }
}

/** Query the display name from a content URI via ContentResolver. */
private fun queryFileName(context: Context, uri: Uri): String {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else ""
    } ?: uri.lastPathSegment?.substringAfterLast("/") ?: ""
}
