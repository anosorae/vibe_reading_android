package com.vibereading.app.ui.navigation

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
import com.vibereading.app.VibeReadingApp

object Routes {
    const val BOOKSHELF = "bookshelf"
    const val READER = "reader/{bookId}"
    const val SETTINGS = "settings"
    const val LOGS = "logs"
    fun reader(bookId: Long) = "reader/$bookId"
}

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

    NavHost(navController = navController, startDestination = Routes.BOOKSHELF) {

        composable(Routes.BOOKSHELF) {
            val vm: BookshelfViewModel = viewModel(
                factory = BookshelfViewModel.Factory(bookRepo, chapterRepo, settingsRepo)
            )
            BookshelfScreen(
                vm = vm,
                onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { entry ->
            val bookId = entry.arguments?.getLong("bookId") ?: return@composable
            val vm: ReaderViewModel = viewModel(
                factory = ReaderViewModel.Factory(
                    bookId, bookRepo, chapterRepo, settingsRepo, llmProfileRepo, translationService, dictDatabase,
                    llmApiService = translationService
                )
            )
            ReaderScreen(
                vm = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(settingsRepo, llmProfileRepo)
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
