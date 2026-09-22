package com.vibereading.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ReadingTimeRepository
import java.time.Duration
import java.time.ZonedDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class StatisticsUiState(
    val isLoading: Boolean = true,
    val stats: ReadingStats = readingStatsOf(emptyList())
)

/** 统计页：与书架共用 `BookRepository.getShelfItems()` 数据源，时长来自 `reading_time_daily` 聚合。 */
class StatisticsViewModel(
    bookRepo: BookRepository,
    readingTimeRepo: ReadingTimeRepository,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatisticsUiState())
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()
    private val today = MutableStateFlow(now().toLocalDate())
    private var dateUpdateJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                bookRepo.getShelfItems(),
                readingTimeRepo.observeDailyTotals(),
                readingTimeRepo.observeBookTotals(),
                today
            ) { items, daily, bookTotals, date ->
                readingStatsOf(
                    items,
                    ReadingTimeSnapshot(
                        dailyTotals = daily.associate { it.epochDay to it.seconds },
                        bookTotals = bookTotals.associate { it.bookId to it.seconds },
                        todayEpochDay = date.toEpochDay()
                    )
                )
            }.collect { stats ->
                _uiState.update { StatisticsUiState(isLoading = false, stats = stats) }
            }
        }
    }

    /** 重进 Tab / 返回前台立即刷新；仅可见时等待下一本地午夜，不依赖 Room 发射。 */
    fun onForeground() {
        today.value = now().toLocalDate()
        dateUpdateJob?.cancel()
        dateUpdateJob = viewModelScope.launch {
            while (isActive) {
                val current = now()
                val midnight = current.toLocalDate().plusDays(1).atStartOfDay(current.zone)
                delay(Duration.between(current, midnight).toMillis().coerceAtLeast(1L))
                today.value = now().toLocalDate()
            }
        }
    }

    fun onBackground() {
        dateUpdateJob?.cancel()
        dateUpdateJob = null
    }

    class Factory(
        private val bookRepo: BookRepository,
        private val readingTimeRepo: ReadingTimeRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StatisticsViewModel(bookRepo, readingTimeRepo) as T
        }
    }
}
