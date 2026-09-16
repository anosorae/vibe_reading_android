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
import com.vibereading.app.domain.parser.SourceLanguageDetector
import com.vibereading.app.domain.parser.TxtParser
import com.vibereading.app.log.AppLog
import com.vibereading.app.ui.reader.TranslationCoordinator
import java.io.File
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
    /** 书架操作提示（导入结果 / 封面设置结果）；文案含「失败」时横幅显示红色。 */
    val shelfMessage: String? = null,
    val accent: AppAccent = AppAccent.VIBE,
    val layout: String = "list",     // "list" | "grid"
    val sort: String = ShelfSort.RECENT,
    val sortOrder: String = SortOrder.DESC
)

class BookshelfViewModel(
    private val bookRepo: BookRepository,
    private val chapterRepo: ChapterRepository,
    private val settingsRepo: SettingsRepository,
    private val translationCoordinator: TranslationCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookshelfUiState())
    val uiState: StateFlow<BookshelfUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")

    // 排序方式 + 排序方向合成流
    private val sortPref = combine(settingsRepo.bookshelf.sort, settingsRepo.bookshelf.sortOrder) { sort, order -> sort to order }

    init {
        viewModelScope.launch {
            combine(
                bookRepo.getShelfItems(),
                settingsRepo.theme.settings,
                settingsRepo.bookshelf.layout,
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
        viewModelScope.launch { settingsRepo.bookshelf.saveLayout(layout) }
    }

    fun switchSort(sort: String) {
        viewModelScope.launch { settingsRepo.bookshelf.saveSort(sort) }
    }

    fun switchSortOrder(order: String) {
        viewModelScope.launch { settingsRepo.bookshelf.saveSortOrder(order) }
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
                _uiState.update { it.copy(isLoading = false, shelfMessage = message) }
            } catch (e: Exception) {
                AppLog.put("书籍上传失败", e)
                _uiState.update {
                    it.copy(isLoading = false, shelfMessage = "上传失败: ${e.message}")
                }
            }
        }
    }

    /** TXT 导入：解码 → 分章 → 判定原文语言 → 入库。 */
    private suspend fun importTxt(bytes: ByteArray, fileName: String): String {
        val text = TxtParser.decodeBytes(bytes)
        val chapterDicts = TxtParser.parseText(text)
        if (chapterDicts.isEmpty()) throw Exception("未识别到任何章节")

        val title = fileName.removeSuffix(".txt").removeSuffix(".TXT")
            .ifBlank { "未知书名" }
        // 跳过空首章取首个有足够文本的章节判定（ADR-003）
        val sourceLanguage = SourceLanguageDetector.detectFirstNonBlank(chapterDicts.map { it.content })

        val bookId = bookRepo.insert(
            Book(
                title = title,
                totalChapters = chapterDicts.size,
                sourceLanguage = sourceLanguage,
                languageMode = sourceLanguage // 首开即原文模式（ADR-003）
            )
        )
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
        // 跳过「卷首」等空/纯封面章节取首个有足够文本的章节判定（ADR-003）
        val sourceLanguage = SourceLanguageDetector.detectFirstNonBlank(
            EpubParser.chapterTextsForDetection(parsed)
        )

        val newBook = Book(
            title = title,
            totalChapters = parsed.chapters.size,
            format = "epub",
            sourceLanguage = sourceLanguage,
            languageMode = sourceLanguage // 首开即原文模式（ADR-003）
        )
        val bookId = bookRepo.insert(newBook)
        val nameByHref = BookImageStore.saveImages(bookId, parsed.images) { href ->
            val ext = href.substringAfterLast('.', "")
            if (ext.length in 1..5) ".$ext" else ".jpg"
        }
        parsed.coverBytes?.let { BookImageStore.saveEmbeddedCover(bookId, it) }?.let { coverPath ->
            bookRepo.getBookByIdOnce(bookId)?.let { book -> bookRepo.update(book.copy(coverPath = coverPath)) }
        }
        val dicts = EpubParser.toChapterDicts(bookId, parsed, { href -> nameByHref[href] }, sourceLanguage)
        // 空章节（如纯封面页产生的空卷首）剔除后修正总章数
        if (dicts.size != newBook.totalChapters) {
            bookRepo.update(newBook.copy(totalChapters = dicts.size))
        }
        chapterRepo.insertAll(TxtParser.toChapters(bookId, dicts))
        return "「$title」导入成功，共 ${dicts.size} 章"
    }

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            translationCoordinator.cancelBook(bookId)
            // 先清插图/封面磁盘文件再删库记录（ADR-002 D3）
            try {
                BookImageStore.deleteBookFiles(bookId)
            } catch (e: Exception) {
                AppLog.put("删书清理图片失败 bookId=$bookId", e)
            }
            bookRepo.delete(bookId)
        }
    }

    /**
     * 设置/更换封面：内容 URI 先流式拷到缓存临时文件（避免整张原图进堆），
     * 由 [BookImageStore.saveUserCover] 降采样 + EXIF 旋正后落盘。
     * 旧用户封面在写库成功后删除；内嵌封面备份保留，供「恢复原封面」。
     */
    fun setCover(context: Context, bookId: Long, uri: Uri) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                val temp = File(context.cacheDir, "cover_upload_${System.currentTimeMillis()}.tmp")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { input.copyTo(it) }
                    } ?: throw Exception("无法读取所选图片")
                    val newPath = BookImageStore.saveUserCover(bookId, temp)
                        ?: throw Exception("无法识别该图片")
                    val book = bookRepo.getBookByIdOnce(bookId) ?: throw Exception("书籍不存在")
                    val oldPath = book.coverPath
                    bookRepo.update(book.copy(coverPath = newPath))
                    if (oldPath != null && oldPath != newPath && !BookImageStore.isEmbeddedCover(oldPath)) {
                        BookImageStore.deleteCover(oldPath)
                    }
                    "封面已更新"
                } catch (e: Exception) {
                    AppLog.put("封面设置失败 bookId=$bookId", e)
                    "封面保存失败：${e.message ?: "无法识别该图片"}"
                } finally {
                    temp.delete()
                }
            }
            _uiState.update { it.copy(shelfMessage = message) }
        }
    }

    /**
     * 移除当前封面：有内嵌备份则回退到出版社原封面，否则回退渐变占位。
     * 只删用户上传的封面文件，内嵌备份永不删除。
     */
    fun removeCover(bookId: Long) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val book = bookRepo.getBookByIdOnce(bookId) ?: throw Exception("书籍不存在")
                    val current = book.coverPath
                    val embedded = BookImageStore.embeddedCoverPath(bookId)
                    if (BookImageStore.canRestoreEmbeddedCover(bookId, current)) {
                        bookRepo.update(book.copy(coverPath = embedded))
                        current?.let { BookImageStore.deleteCover(it) }
                        "已恢复原封面"
                    } else {
                        bookRepo.update(book.copy(coverPath = null))
                        current?.takeIf { !BookImageStore.isEmbeddedCover(it) }
                            ?.let { BookImageStore.deleteCover(it) }
                        "封面已移除"
                    }
                } catch (e: Exception) {
                    AppLog.put("移除封面失败 bookId=$bookId", e)
                    "封面移除失败"
                }
            }
            _uiState.update { it.copy(shelfMessage = message) }
        }
    }

    /**
     * 修正书籍原文语言（ADR-003）：清空全部章节译文并重置显示模式为新原文语言。
     * 旧译文按原方向生成，方向反了是垃圾数据，必须清除，用户随后按新方向重新翻译。
     */
    fun correctSourceLanguage(bookId: Long, sourceLanguage: String) {
        viewModelScope.launch {
            try {
                translationCoordinator.cancelBook(bookId)
                chapterRepo.resetAllChapters(bookId)
                bookRepo.updateSourceLanguage(bookId, sourceLanguage)
                bookRepo.updateLanguageMode(bookId, sourceLanguage) // 显示模式重置为新的原文语言
            } catch (e: Exception) {
                AppLog.put("修正书籍原文语言失败 bookId=$bookId", e)
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(shelfMessage = null) }
    }

    class Factory(
        private val bookRepo: BookRepository,
        private val chapterRepo: ChapterRepository,
        private val settingsRepo: SettingsRepository,
        private val translationCoordinator: TranslationCoordinator
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BookshelfViewModel(bookRepo, chapterRepo, settingsRepo, translationCoordinator) as T
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
