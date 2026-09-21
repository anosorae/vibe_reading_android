package com.vibereading.app

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.data.local.entity.BookEntity
import com.vibereading.app.data.local.entity.ChapterEntity
import com.vibereading.app.ui.reader.pagination.PageStyle
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain

/**
 * 单测共用夹具：只收敛**各测试逐字相同**的初始化样板
 * （Room 内存库、`TextMeasurer`、`PageStyle`、DataStore、测试书种子）。
 *
 * 各测试自己的语料、密度、字号差异仍由参数表达，不在此处做「一刀切」默认值——
 * 夹具只去掉噪音，不去掉测试的意图。
 */

// ── 排版 ──

/**
 * Robolectric NATIVE 换行测量的 [TextMeasurer]（AGENTS.md：断言结构化结果，不 pin 像素）。
 * [density] 由测试按需指定（1f 常规 / 2.625f 对齐真机 dpi）。
 */
fun newTextMeasurer(density: Density = Density(1f)): TextMeasurer = TextMeasurer(
    createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
    density,
    LayoutDirection.Ltr,
    64
)

/**
 * 排版测试的统一样式：正文 16/24、中文 14/21、标题 20/28。
 * [paragraphSpacingPx] 与 [bottomJustify] 保留默认值，与原各处手写的一致。
 */
fun testPageStyle(
    paragraphSpacingPx: Float = 10f,
    bottomJustify: Boolean = true
): PageStyle = PageStyle(
    body = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    cn = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 21.sp),
    title = TextStyle(fontFamily = FontFamily.Default, fontSize = 20.sp, lineHeight = 28.sp),
    paragraphSpacingPx = paragraphSpacingPx,
    bottomJustify = bottomJustify
)

// ── 数据库 ──

/** Room 内存库（调用方负责 `close()`）。 */
fun newInMemoryDb(): AppDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .build()

/**
 * 建一本测试书 + [chapterCount] 章，返回章节 id 列表（按 chapterIndex 升序）。
 * 标题与正文由 lambda 生成，便于各测试表达自己的语料。
 */
suspend fun seedBookAndChapters(
    db: AppDatabase,
    bookId: Long = 1L,
    chapterCount: Int = 3,
    bookTitle: String = "测试书",
    chapterTitle: (Int) -> String = { "第${it + 1}章" },
    chapterContent: (Int) -> String = { "正文${it + 1}" }
): List<Long> {
    db.bookDao().insert(
        BookEntity(
            id = bookId, title = bookTitle, totalChapters = chapterCount,
            lastReadAt = 1000L, createdAt = 1000L
        )
    )
    return db.chapterDao().insertAll(
        (0 until chapterCount).map { index ->
            ChapterEntity(
                bookId = bookId,
                title = chapterTitle(index),
                chapterIndex = index,
                content = chapterContent(index)
            )
        }
    )
}

// ── 设置存储 ──

/** 临时 DataStore 文件；调用方负责在 `@After` 删除。 */
fun newTempPreferenceFile(prefix: String): File = File.createTempFile(prefix, ".preferences_pb")

// ── 协程测试收尾 ──

/**
 * 测试收尾的固定顺序：取消作用域 → 关库 → 卸载 Main。
 *
 * resetMain 可能与 Room 执行器线程上飞行中的最后一次 Main resume 并发，
 * coroutines-test 会抛 "Dispatchers.Main is used concurrently with setting it"
 * （偶发、毫秒级窗口）。这里对 resetMain 做有界重试等窗口关闭；穷尽后仍失败
 * 则让异常照常抛出、问题可见。
 *
 * 注意不要改成「cancelAndJoin 之后再 close」：join 等不到的飞行查询会让
 * db.close() 与它互锁（Robolectric 单连接下实测挂死整个 worker），
 * 保留裸 cancel 的原语义，只加固 resetMain。
 */
fun teardownRoomAndMain(db: AppDatabase, vararg scopes: CoroutineScope?) {
    scopes.filterNotNull().forEach { it.cancel() }
    db.close()
    resetMainPatiently()
}

private fun resetMainPatiently() {
    repeat(8) {
        try {
            Dispatchers.resetMain()
            return
        } catch (_: IllegalStateException) {
            Thread.sleep(25)
        }
    }
    Dispatchers.resetMain()
}

/** 真实文件后端 DataStore（[scope] 由调用方取消）。 */
fun newPreferenceStore(scope: CoroutineScope, file: File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

/**
 * 纯内存 [DataStore]，免去真实文件。
 * [gate] 非空时首次收集先等待其完成——用于模拟「DataStore 尚未预热」的冷启动路径。
 */
fun inMemoryPreferenceStore(gate: CompletableDeferred<Unit>? = null): DataStore<Preferences> {
    val values = MutableStateFlow(emptyPreferences())
    return object : DataStore<Preferences> {
        override val data: Flow<Preferences> =
            if (gate == null) values else flow { gate.await(); emitAll(values) }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(values.value).also { values.value = it }
    }
}
