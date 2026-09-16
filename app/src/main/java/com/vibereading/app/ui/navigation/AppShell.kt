package com.vibereading.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vibereading.app.ui.bookshelf.ShelfMetrics
import com.vibereading.app.ui.bookshelf.ShelfTypography
import com.vibereading.app.ui.bookshelf.BookshelfScreen
import com.vibereading.app.ui.bookshelf.BookshelfViewModel
import com.vibereading.app.ui.settings.SettingsScreen
import com.vibereading.app.ui.settings.SettingsViewModel
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets

internal enum class AppTab(val label: String, val icon: ImageVector) {
    BOOKSHELF("书架", Icons.AutoMirrored.Filled.MenuBook),
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
        containerColor = MaterialTheme.colorScheme.background,
        // 顶部系统栏留白交给各 Tab 页面自己处理（书架/设置的 Scaffold 都传了 stableInsets）。
        // 这里若用默认值，AppShell 会把状态栏高度加进 innerPadding，而 Tab 页面又加一遍
        // —— 实测书架品牌名因此被顶到 134dp（设计基线是状态栏 + 22dp），差了一个状态栏。
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AppBottomBar(
                stableInsets = LocalStableSystemBarInsets.current,
                selectedTab = selectedTab,
                onSelect = { selectedTabName = it.name }
            )
        }
    ) { padding ->
        when (selectedTab) {
            AppTab.BOOKSHELF -> BookshelfScreen(
                vm = bookshelfVm,
                onOpenBook = onOpenBook,
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

/**
 * 悬浮式底栏（设计基线）：**不是** M3 的 `NavigationBar`。
 *
 * 两处和 M3 默认样式的分歧都是刻意的：
 * 1. 选中态是一个**包住图标和文字**的浅蓝胶囊，而 M3 的 `indicator` 只包图标；
 * 2. 底栏是左右留白 + 圆角 + 投影的浮动条，不是通栏。
 * 自己拼能同时拿到这两点，且不必和 `NavigationBarItem` 的固定高度博弈。
 *
 * 底部系统栏留白用 `LocalStableSystemBarInsets` 而不是 `WindowInsets.navigationBars`：
 * 从阅读器（沉浸式，系统栏归零）返回时后者会先塌成 0 再弹回，底栏会跳一下。
 */
@Composable
internal fun AppBottomBar(
    stableInsets: WindowInsets,
    selectedTab: AppTab,
    onSelect: (AppTab) -> Unit
) {
    val shape = RoundedCornerShape(ShelfMetrics.NavBarCorner)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(stableInsets.only(WindowInsetsSides.Bottom))
            .padding(horizontal = ShelfMetrics.PagePadding)
            .padding(bottom = ShelfMetrics.NavBarBottomGap)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ShelfMetrics.NavBarHeight)
                .shadow(elevation = 10.dp, shape = shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(ShelfMetrics.NavBarItemInset)
                        .clip(RoundedCornerShape(ShelfMetrics.NavItemCorner))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                        .clickable { onSelect(tab) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(20.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            tab.label,
                            style = ShelfTypography.navLabel,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.appShellContentPadding(padding: PaddingValues): Modifier =
    padding(padding)
