package com.vibereading.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibereading.app.data.repository.BookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatisticsUiState(
    val isLoading: Boolean = true,
    val stats: ReadingStats = readingStatsOf(emptyList())
)

/** 统计页：与书架共用 `BookRepository.getShelfItems()` 数据源，进度/译文数天然同源。 */
class StatisticsViewModel(
    bookRepo: BookRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatisticsUiState())
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            bookRepo.getShelfItems().collect { items ->
                _uiState.update { StatisticsUiState(isLoading = false, stats = readingStatsOf(items)) }
            }
        }
    }

    class Factory(
        private val bookRepo: BookRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StatisticsViewModel(bookRepo) as T
        }
    }
}
