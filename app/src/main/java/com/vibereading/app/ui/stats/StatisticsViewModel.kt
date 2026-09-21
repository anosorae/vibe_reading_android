package com.vibereading.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ReadingTimeRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatisticsUiState(
    val isLoading: Boolean = true,
    val stats: ReadingStats = readingStatsOf(emptyList())
)

/** 统计页：与书架共用 `BookRepository.getShelfItems()` 数据源，时长来自 `reading_time_daily` 聚合。 */
class StatisticsViewModel(
    bookRepo: BookRepository,
    readingTimeRepo: ReadingTimeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatisticsUiState())
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                bookRepo.getShelfItems(),
                readingTimeRepo.observeDailyTotals(),
                readingTimeRepo.observeBookTotals()
            ) { items, daily, bookTotals ->
                readingStatsOf(
                    items,
                    ReadingTimeSnapshot(
                        dailyTotals = daily.associate { it.epochDay to it.seconds },
                        bookTotals = bookTotals.associate { it.bookId to it.seconds },
                        todayEpochDay = LocalDate.now().toEpochDay()
                    )
                )
            }.collect { stats ->
                _uiState.update { StatisticsUiState(isLoading = false, stats = stats) }
            }
        }
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
