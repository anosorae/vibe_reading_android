package com.vibereading.app.ui.reader

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 生命周期 flush、返回处理与阅读器系统栏效果。 */
@Composable
fun ReaderSystemUiEffects(
    toolbarHidden: Boolean,
    hideStatusBar: Boolean,
    hideNavigationBar: Boolean,
    isDark: Boolean,
    background: Color,
    scope: CoroutineScope,
    syncProgress: () -> Unit,
    flushProgress: suspend () -> Unit,
    onBack: () -> Unit,
    resumeReadingTime: () -> Unit = {},
    pauseReadingTime: () -> Unit = {},
    flushReadingTime: suspend () -> Unit = {}
): () -> Unit {
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val activity = view.context as? Activity

    // DisposableEffect 只在 lifecycleOwner 变化时重注册，观察者闭包若直接捕获参数
    // 会一直持有首帧组合的 syncProgress（当时 window 为空 → chapterOfPage 恒 null，
    // ON_STOP 的「先按当前页同步再落盘」实际一直是 no-op）。经 rememberUpdatedState
    // 转发，观察者拿到的恒为最新组合的闭包（含当前 window/isPagerMode）。
    val currentSyncProgress by rememberUpdatedState(syncProgress)
    val currentFlushProgress by rememberUpdatedState(flushProgress)
    val currentResumeReadingTime by rememberUpdatedState(resumeReadingTime)
    val currentPauseReadingTime by rememberUpdatedState(pauseReadingTime)
    val currentFlushReadingTime by rememberUpdatedState(flushReadingTime)

    val restoreSystemBars = {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                show(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
        }
    }
    val leaveReader: () -> Unit = {
        restoreSystemBars()
        syncProgress()
        currentPauseReadingTime()
        scope.launch {
            currentFlushReadingTime()
            flushProgress()
            onBack()
        }
        Unit
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> currentResumeReadingTime()
                Lifecycle.Event.ON_STOP -> {
                    currentPauseReadingTime()
                    currentSyncProgress()
                    scope.launch {
                        currentFlushReadingTime()
                        currentFlushProgress()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) { onDispose { restoreSystemBars() } }
    BackHandler(onBack = leaveReader)

    LaunchedEffect(toolbarHidden, hideStatusBar, hideNavigationBar) {
        val window = activity?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            if (toolbarHidden && hideNavigationBar) hide(WindowInsetsCompat.Type.navigationBars())
            else show(WindowInsetsCompat.Type.navigationBars())
            if (toolbarHidden && hideStatusBar) hide(WindowInsetsCompat.Type.statusBars())
            else show(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
            window.statusBarColor = background.toArgb()
            window.navigationBarColor = background.toArgb()
        }
    }
    return leaveReader
}
