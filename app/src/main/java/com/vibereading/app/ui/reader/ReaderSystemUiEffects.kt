package com.vibereading.app.ui.reader

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
    onBack: () -> Unit
): () -> Unit {
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val activity = view.context as? Activity

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
        scope.launch {
            flushProgress()
            onBack()
        }
        Unit
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                syncProgress()
                scope.launch { flushProgress() }
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
