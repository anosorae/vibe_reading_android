package com.vibereading.app.web

import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.LlmProfileRepository
import com.vibereading.app.domain.model.Book
import com.vibereading.app.domain.model.BookShelfItem
import com.vibereading.app.domain.model.Chapter
import com.vibereading.app.domain.model.ReadingPosition
import com.vibereading.app.domain.parser.SourceLanguageDetector
import com.vibereading.app.ui.reader.TranslationCoordinator
import com.vibereading.app.ui.reader.TranslationLaunchResult
import com.vibereading.app.ui.reader.TranslationTaskKey
import com.vibereading.app.ui.reader.content.ReadingContent
import com.vibereading.app.log.AppLog
import kotlinx.coroutines.flow.first

/**
 * Web 伴读服务的业务处理层（ADR-005）：纯 suspend 函数，返回可 Gson 序列化的 DTO，
 * 由 [CompanionServer] 的阻塞线程经 runBlocking 桥接调用。
 *
 * 定位边界：只读书籍与共享进度 + 翻译「开始/重试」；不提供导入、删书和 LLM 配置管理。
 */
class CompanionApi(
    private val bookRepo: BookRepository,
    private val chapterRepo: ChapterRepository,
    private val llmProfileRepo: LlmProfileRepository,
    private val coordinatorProvider: () -> TranslationCoordinator
) {

    // ── 书架 ──

    suspend fun books(): List<CompanionBook> =
        bookRepo.getShelfItems().first()
            .sortedByDescending { it.book.lastReadAt }
            .map { CompanionBook.from(it) }

    suspend fun book(bookId: Long): Book? = bookRepo.getBookByIdOnce(bookId)

    // ── 章节列表（目录 + 状态轮询共用） ──

    suspend fun chapterList(bookId: Long): CompanionChapterList? {
        val book = bookRepo.getBookByIdOnce(bookId) ?: return null
        val chapters = chapterRepo.getChaptersByBookList(bookId)
        val lastReadChapter = chapters.find { it.id == book.lastReadChapterId }
        return CompanionChapterList(
            book = CompanionBook.from(
                BookShelfItem(
                    book = book,
                    translatedCount = chapters.count { it.status == Chapter.STATUS_DONE },
                    lastReadChapterTitle = lastReadChapter?.title,
                    progress = BookShelfItem.progressOf(book.totalChapters, lastReadChapter?.chapterIndex)
                )
            ),
            chapters = chapters.sortedBy { it.chapterIndex }.map { CompanionChapter.from(it) }
        )
    }

    // ── 章节正文 ──

    suspend fun chapterContent(bookId: Long, chapterId: Long): CompanionChapterContent? {
        val chapter = chapterRepo.getChapterById(bookId, chapterId) ?: return null
        return CompanionChapterContent.from(ReadingContent.fromChapter(chapter))
    }

    suspend fun chapterStatus(bookId: Long, chapterId: Long): CompanionChapter? =
        chapterRepo.getChapterById(bookId, chapterId)?.let { CompanionChapter.from(it) }

    // ── 进度（伴读进度共享：后写覆盖，ADR-005） ──

    /**
     * 回写阅读进度：offset 以「视口顶部段落 startOffset」上报，此处按章节内容长度
     * 规范化后走与 App 相同的进度入口。
     */
    suspend fun saveProgress(bookId: Long, chapterId: Long, offset: Int): Boolean {
        val chapter = chapterRepo.getChapterById(bookId, chapterId) ?: return false
        val normalized = ReadingPosition.clampOffset(offset, chapter.content.length)
        return bookRepo.updateLastReadProgress(bookId, chapterId, normalized)
    }

    // ── 显示模式 ──

    suspend fun setLanguageMode(bookId: Long, mode: String): Boolean {
        if (mode != SourceLanguageDetector.ZH && mode != SourceLanguageDetector.EN) return false
        return bookRepo.updateLanguageMode(bookId, mode)
    }

    // ── 翻译联动（仅开始/重试，ADR-005） ──

    /**
     * 对章节发起翻译（ADR-005：Web 只提供开始/重试）。同章活动任务幂等，
     * 不同章节可并行；数据库遗留 IN_PROGRESS 但内存无任务时按重试恢复。
     */
    suspend fun startTranslation(bookId: Long, chapterId: Long): TranslationStartResult {
        val book = bookRepo.getBookByIdOnce(bookId) ?: return TranslationStartResult(error = "书籍不存在")
        val chapter = chapterRepo.getChapterById(bookId, chapterId)
            ?: return TranslationStartResult(error = "章节不存在")
        if (chapter.status == Chapter.STATUS_DONE) {
            return TranslationStartResult(error = "该章已翻译完成，重译请在手机 App 内操作")
        }
        val settings = llmProfileRepo.activeLlmSettings.first()
        if (settings.apiKey.isBlank()) {
            return TranslationStartResult(error = "请先在手机 App 内配置 API Key")
        }

        val coordinator = coordinatorProvider()
        val key = TranslationTaskKey(bookId, chapterId)
        val launch = when {
            coordinator.isRunning(key) ->
                TranslationLaunchResult.AlreadyRunning(
                    key,
                    coordinator.stateOf(key)?.runId ?: 0L
                )
            chapter.status == Chapter.STATUS_PENDING ->
                coordinator.start(bookId, chapterId, settings, book.sourceLanguage)
            else ->
                coordinator.retry(bookId, chapterId, settings, book.sourceLanguage)
        }
        return when (launch) {
            is TranslationLaunchResult.Started -> {
                AppLog.put("Web 伴读触发翻译：书 $bookId 章 $chapterId")
                TranslationStartResult(started = true)
            }
            is TranslationLaunchResult.AlreadyRunning ->
                TranslationStartResult(alreadyRunning = true)
            is TranslationLaunchResult.Rejected ->
                TranslationStartResult(error = launch.reason)
            TranslationLaunchResult.NotFound ->
                TranslationStartResult(error = "章节不存在")
        }
    }
}

/** [CompanionApi.startTranslation] 的结果描述。 */
data class TranslationStartResult(
    val started: Boolean = false,
    val alreadyRunning: Boolean = false,
    val error: String? = null
)
