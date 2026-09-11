package com.vibereading.app.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vibereading.app.data.dict.DictDatabase
import com.vibereading.app.data.remote.LlmApiService
import com.vibereading.app.data.repository.BookRepository
import com.vibereading.app.data.repository.ChapterRepository
import com.vibereading.app.data.repository.LlmProfileRepository
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.log.CrashMark
import com.vibereading.app.ui.bookshelf.BookshelfScreen
import com.vibereading.app.ui.bookshelf.BookshelfViewModel
import com.vibereading.app.ui.log.LogViewerScreen
import com.vibereading.app.ui.reader.ReaderScreen
import com.vibereading.app.ui.reader.ReaderViewModel
import com.vibereading.app.ui.settings.SettingsScreen
import com.vibereading.app.ui.settings.SettingsViewModel
import com.vibereading.app.web.WebCompanionService
import com.vibereading.app.VibeReadingApp
import kotlinx.coroutines.flow.first

object Routes {
    const val BOOKSHELF = "bookshelf"
    const val READER = "reader/{bookId}"
    const val SETTINGS = "settings"
    const val LOGS = "logs"
    fun reader(bookId: Long) = "reader/$bookId"
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val application = navController.context.applicationContext as VibeReadingApp
    val db = application.database

    // 上次启动若崩溃，本次启动提示用户查看崩溃日志
    var showCrashPrompt by remember { mutableStateOf(CrashMark.consumeCrashed(application)) }
    if (showCrashPrompt) {
        AlertDialog(
            onDismissRequest = { showCrashPrompt = false },
            title = { Text("检测到崩溃") },
            text = { Text("译读上次异常退出，是否打开日志查看崩溃信息？") },
            confirmButton = {
                TextButton(onClick = {
                    showCrashPrompt = false
                    navController.navigate(Routes.LOGS)
                }) { Text("查看日志") }
            },
            dismissButton = {
                TextButton(onClick = { showCrashPrompt = false }) { Text("忽略") }
            }
        )
    }

    val bookRepo = remember { BookRepository(db.bookDao()) }
    val chapterRepo = remember { ChapterRepository(db.chapterDao()) }
    val settingsRepo = remember { SettingsRepository(application) }
    val llmProfileRepo = remember { LlmProfileRepository(db.llmProfileDao(), settingsRepo) }
    val translationService = remember { LlmApiService() }
    // 内嵌 ECDICT 词典（惰性打开：首次查词才拷贝 asset + SQLite 打开）
    val dictDatabase = remember { DictDatabase.open(application) }

    // 首次启动迁移：DataStore LLM 键 → Room llm_profiles 表
    LaunchedEffect(Unit) {
        llmProfileRepo.ensureDefaultProfile()
    }

    // Web 伴读服务（ADR-005）：不随 App 启动自动拉起——每次启动 App 开关都归为关闭，
    // 由用户在设置页手动开启。它是前台服务、会在通知栏留常驻通知，不该在用户没要求时
    // 自行出现。进程内 Activity 重建（如旋转）时服务仍在运行，此时不动标志，避免
    // 「开关显示关闭但服务实际在跑」的不一致。
    LaunchedEffect(Unit) {
        if (!WebCompanionService.isRunning && settingsRepo.webCompanionEnabled.first()) {
            settingsRepo.saveWebCompanionEnabled(false)
        }
    }

    SharedTransitionLayout {
        val bookTransitionScope = this
        NavHost(navController = navController, startDestination = Routes.BOOKSHELF) {

            composable(
                route = Routes.BOOKSHELF,
                exitTransition = {
                    if (targetState.destination.route == Routes.READER) {
                        ExitTransition.None
                    } else null
                },
                popEnterTransition = {
                    if (initialState.destination.route == Routes.READER) {
                        EnterTransition.None
                    } else null
                }
            ) {
                val vm: BookshelfViewModel = viewModel(
                    factory = BookshelfViewModel.Factory(bookRepo, chapterRepo, settingsRepo)
                )
                BookshelfScreen(
                    vm = vm,
                    onOpenBook = { bookId ->

                        navController.navigate(Routes.reader(bookId)) { launchSingleTop = true }
                    },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    coverTransition = { bookId ->
                        with(bookTransitionScope) { bookContainerBounds(bookId, this@composable) }
                    }
                )
            }

            composable(
                route = Routes.READER,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
                // 书架保持静止可见，封面和正文在其上方共享边界；不叠加整屏淡化。
                enterTransition = { EnterTransition.None },
                popEnterTransition = { null },
                popExitTransition = {
                    if (targetState.destination.route == Routes.BOOKSHELF) {
                        ExitTransition.None
                    } else null
                }
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: return@composable
                val vm: ReaderViewModel = viewModel(
                    factory = ReaderViewModel.Factory(
                        bookId, bookRepo, chapterRepo, settingsRepo, llmProfileRepo, translationService, dictDatabase,
                        llmApiService = translationService,
                        appContext = application
                    )
                )
                Box(Modifier.fillMaxSize().then(
                    with(bookTransitionScope) { bookContainerBounds(bookId, this@composable, isCover = false) }
                )) {
                    ReaderScreen(vm = vm, onBack = { navController.popBackStack() })
                }
            }

            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(settingsRepo, llmProfileRepo, application)
                )
                SettingsScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    onOpenLogs = { navController.navigate(Routes.LOGS) }
                )
            }

            composable(Routes.LOGS) {
                LogViewerScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
