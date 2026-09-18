package com.vibereading.app.ui.navigation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vibereading.app.ui.bookshelf.ShelfMetrics
import com.vibereading.app.ui.bookshelf.BookshelfScreen
import com.vibereading.app.ui.bookshelf.BookshelfViewModel
import com.vibereading.app.ui.settings.SettingsScreen
import com.vibereading.app.ui.settings.SettingsViewModel
import com.vibereading.app.ui.stats.StatisticsScreen
import com.vibereading.app.ui.stats.StatisticsViewModel
import com.vibereading.app.ui.theme.LocalIsDarkTheme
import com.vibereading.app.ui.theme.LocalStableSystemBarInsets

internal enum class AppTab(val label: String, val icon: ImageVector) {
    BOOKSHELF("书架", Icons.AutoMirrored.Filled.MenuBook),
    STATISTICS("统计", Icons.Filled.BarChart),
    PROFILE("我的", Icons.Filled.Person)
}

/**
 * 三栏导航外壳（书架 / 统计 / 我的）。
 *
 * 布局是**悬浮 overlay** 而非 Scaffold bottomBar：Tab 内容铺满整屏（玻璃底栏下方
 * 也有内容可透出），底部滚动余量作为参数下发到各页滚动容器；液态玻璃底栏与
 * 加号按钮悬浮在底部系统安全区之上。页面内容每帧录进 [captureLayer]，
 * 玻璃容器据此绘制实时背景模糊（材质与降级口径见 [LiquidGlassSurface]）。
 */
@Composable
internal fun AppShell(
    bookshelfVm: BookshelfViewModel,
    statsVm: StatisticsViewModel,
    settingsVm: SettingsViewModel,
    onOpenBook: (Long) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenLlmSettings: () -> Unit,
    onOpenTranslationParams: () -> Unit,
    onOpenAbout: () -> Unit,
    onExitProfile: () -> Unit,
    coverTransition: @Composable (Long) -> Modifier
) {
    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.BOOKSHELF.name) }
    val selectedTab = AppTab.valueOf(selectedTabName)
    val isDark = LocalIsDarkTheme.current
    val stableInsets = LocalStableSystemBarInsets.current

    // 底部滚动余量：从各页 Scaffold 的导航栏 inset 之上，再垫出「距手势区 12 + 条高 64 + 呼吸 16」，
    // 列表最后一项能完整滚出玻璃底栏
    val bottomChromePadding = ShelfMetrics.NavBarBottomGap +
        ShelfMetrics.NavBarHeight + ShelfMetrics.NavBarScrollBreath
    // 加号按钮底距：系统导航栏之上、玻璃底栏上方 10dp
    val fabBottomPadding = with(LocalDensity.current) {
        stableInsets.getBottom(this).toDp() + ShelfMetrics.NavBarBottomGap +
            ShelfMetrics.NavBarHeight + 10.dp
    }

    // 页面内容捕获层 + 帧失效信号：内容每帧重绘后自增，玻璃表面据此跟进重绘模糊底衬
    val captureLayer = rememberGraphicsLayer()
    val captureTick = remember { mutableIntStateOf(0) }

    // 上传书籍入口收在 AppShell：与玻璃加号按钮同层（原书架 Scaffold 的 FAB 槽位）。
    // TXT 与 EPUB 一起可选（ADR-002）；部分文件管理器对 epub 上报的 MIME 不规范，
    // 同时给出具体类型与通配扩展名兜底
    val context = LocalContext.current
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { bookshelfVm.uploadBook(context, it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    captureLayer.record { this@drawWithContent.drawContent() }
                    drawContent()
                    captureTick.intValue++
                }
        ) {
            when (selectedTab) {
                AppTab.BOOKSHELF -> BookshelfScreen(
                    vm = bookshelfVm,
                    onOpenBook = onOpenBook,
                    coverTransition = coverTransition,
                    bottomChromePadding = bottomChromePadding
                )
                AppTab.STATISTICS -> StatisticsScreen(
                    vm = statsVm,
                    onOpenBook = onOpenBook,
                    bottomChromePadding = bottomChromePadding
                )
                AppTab.PROFILE -> SettingsScreen(
                    vm = settingsVm,
                    onBack = {
                        selectedTabName = AppTab.BOOKSHELF.name
                        onExitProfile()
                    },
                    onOpenLogs = onOpenLogs,
                    onOpenLlmSettings = onOpenLlmSettings,
                    onOpenTranslationParams = onOpenTranslationParams,
                    onOpenAbout = onOpenAbout,
                    showTopBar = false,
                    bottomChromePadding = bottomChromePadding
                )
            }
        }

        if (selectedTab == AppTab.BOOKSHELF) {
            GlassAddBookFab(
                onClick = { fileLauncher.launch(arrayOf("text/plain", "application/epub+zip", "*/*")) },
                isDark = isDark,
                captureLayer = captureLayer,
                captureTick = captureTick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = ShelfMetrics.PagePadding, bottom = fabBottomPadding)
            )
        }

        GlassBottomBar(
            stableInsets = stableInsets,
            selectedTab = selectedTab,
            onSelect = { selectedTabName = it.name },
            captureLayer = captureLayer,
            captureTick = captureTick,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
