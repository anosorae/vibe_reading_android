package com.vibereading.app.ui.stats

import androidx.lifecycle.viewModelScope
import com.vibereading.app.data.local.AppDatabase
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ReadingTimeRepository
import com.vibereading.app.newInMemoryDb
import com.vibereading.app.seedBookAndChapters
import com.vibereading.app.teardownRoomAndMain
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatisticsViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var scheduler: TestCoroutineScheduler
    private var vm: StatisticsViewModel? = null
    private val sunday = LocalDate.of(2026, 9, 20).toEpochDay()
    private val monday = LocalDate.of(2026, 9, 21).toEpochDay()
    private val start = ZonedDateTime.parse("2026-09-20T23:59:00+08:00[Asia/Shanghai]")

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        db = newInMemoryDb()
    }

    @After
    fun tearDown() {
        teardownRoomAndMain(db, vm?.viewModelScope)
    }

    private suspend fun newVm(): StatisticsViewModel {
        seedBookAndChapters(db)
        db.readingTimeDao().addSeconds(1L, sunday, 3600L)
        return StatisticsViewModel(
            BookRepository(db.bookDao()),
            ReadingTimeRepository(db.readingTimeDao()),
            now = { start.plusNanos(scheduler.currentTime * 1_000_000) }
        ).also { vm = it }
    }

    /** Room 在真实线程执行，等待初始公开状态时不自动推进午夜定时器。 */
    private suspend fun awaitLoaded(viewModel: StatisticsViewModel): ReadingStats =
        withTimeout(5000) { viewModel.uiState.first { !it.isLoading }.stats }

    @Test
    fun `后台隔夜返回且数据库未变更时立即刷新统计`() = runBlocking {
        val viewModel = newVm()
        val initial = awaitLoaded(viewModel)
        assertEquals(sunday, initial.todayEpochDay)
        assertEquals(3600L, initial.todaySeconds)
        assertEquals(3600L, initial.weekSeconds)

        viewModel.onForeground()
        viewModel.onBackground()
        // 整晚没有 Room 写入；停表后即使越过原定午夜也不在后台更新。
        scheduler.advanceTimeBy(8 * 60 * 60 * 1000L)
        scheduler.runCurrent()
        assertEquals(initial, viewModel.uiState.value.stats)

        viewModel.onForeground()
        scheduler.runCurrent()

        val refreshed = viewModel.uiState.value.stats
        assertEquals(monday, refreshed.todayEpochDay)
        assertEquals(0L, refreshed.todaySeconds)
        assertEquals(0L, refreshed.weekSeconds)
        assertEquals(3600L, refreshed.totalSeconds)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 3600L, 0L),
            dailySeries(refreshed.dailyTotals, refreshed.todayEpochDay, 7))
        assertEquals(List(28) { 0L } + listOf(3600L, 0L),
            dailySeries(refreshed.dailyTotals, refreshed.todayEpochDay, 30))
    }

    @Test
    fun `持续前台跨过周日午夜且数据库未变更时刷新统计`() = runBlocking {
        val viewModel = newVm()
        val initial = awaitLoaded(viewModel)
        assertEquals(sunday, initial.todayEpochDay)
        assertEquals(3600L, initial.todaySeconds)
        assertEquals(3600L, initial.weekSeconds)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 3600L),
            dailySeries(initial.dailyTotals, initial.todayEpochDay, 7))

        viewModel.onForeground()
        scheduler.advanceTimeBy(60_000)
        scheduler.runCurrent()

        val refreshed = viewModel.uiState.value.stats
        assertEquals(monday, refreshed.todayEpochDay)
        assertEquals(0L, refreshed.todaySeconds)
        assertEquals(0L, refreshed.weekSeconds)
        assertEquals(3600L, refreshed.totalSeconds)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 3600L, 0L),
            dailySeries(refreshed.dailyTotals, refreshed.todayEpochDay, 7))
    }
}
