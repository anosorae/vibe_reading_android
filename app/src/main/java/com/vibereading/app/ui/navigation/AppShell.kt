package com.vibereading.app.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.vibereading.app.ui.bookshelf.BookshelfScreen
import com.vibereading.app.ui.bookshelf.BookshelfViewModel
import com.vibereading.app.ui.settings.SettingsScreen
import com.vibereading.app.ui.settings.SettingsViewModel

internal enum class AppTab(val label: String, val icon: ImageVector) {
    BOOKSHELF("书架", Icons.Filled.MenuBook),
    STATISTICS("统计", Icons.Filled.BarChart),
    PROFILE("我的", Icons.Filled.Person)
}

@Composable
internal fun AppShell(
    bookshelfVm: BookshelfViewModel,
    settingsVm: SettingsViewModel,
    onOpenBook: (Long) -> Unit,
    onOpenLogs: () -> Unit,
    onExitProfile: () -> Unit,
    coverTransition: @Composable (Long) -> Modifier
) {
    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.BOOKSHELF.name) }
    val selectedTab = AppTab.valueOf(selectedTabName)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            AppBottomBar(
                selectedTab = selectedTab,
                onSelect = { selectedTabName = it.name }
            )
        }
    ) { padding ->
        when (selectedTab) {
            AppTab.BOOKSHELF -> BookshelfScreen(
                vm = bookshelfVm,
                onOpenBook = onOpenBook,
                onOpenSettings = { selectedTabName = AppTab.PROFILE.name },
                coverTransition = coverTransition,
                modifier = Modifier.appShellContentPadding(padding)
            )
            AppTab.STATISTICS -> StatisticsPlaceholderScreen(
                modifier = Modifier.appShellContentPadding(padding)
            )
            AppTab.PROFILE -> SettingsScreen(
                vm = settingsVm,
                onBack = {
                    selectedTabName = AppTab.BOOKSHELF.name
                    onExitProfile()
                },
                onOpenLogs = onOpenLogs,
                modifier = Modifier.appShellContentPadding(padding)
            )
        }
    }
}

@Composable
internal fun AppBottomBar(
    selectedTab: AppTab,
    onSelect: (AppTab) -> Unit
) {
    NavigationBar(
        tonalElevation = 0.dp,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
    ) {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

private fun Modifier.appShellContentPadding(padding: PaddingValues): Modifier =
    padding(padding)
