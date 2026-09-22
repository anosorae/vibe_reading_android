package com.vibereading.app.ui.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderSystemUiEffectsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `退出阅读器先停表并等待时长和进度落盘再返回`() {
        val timeGate = CompletableDeferred<Unit>()
        val progressGate = CompletableDeferred<Unit>()
        var foreground = true
        var navigatedBack = false
        var timeWritten = false
        var progressWritten = false
        lateinit var leaveReader: () -> Unit
        compose.setContent {
            leaveReader = ReaderSystemUiEffects(
                toolbarHidden = false,
                hideStatusBar = false,
                hideNavigationBar = false,
                isDark = false,
                background = Color.White,
                scope = rememberCoroutineScope(),
                syncProgress = {},
                flushProgress = {
                    progressGate.await()
                    progressWritten = true
                },
                onBack = { navigatedBack = true },
                resumeReadingTime = { foreground = true },
                pauseReadingTime = { foreground = false },
                flushReadingTime = {
                    timeGate.await()
                    timeWritten = true
                }
            )
        }
        try {
            compose.runOnIdle {
                leaveReader()
                assertFalse(foreground)
                assertFalse(navigatedBack)
            }
            compose.runOnIdle { timeGate.complete(Unit) }
            compose.runOnIdle {
                assertTrue(timeWritten)
                assertFalse(navigatedBack)
                progressGate.complete(Unit)
            }
            compose.runOnIdle {
                assertTrue(progressWritten)
                assertTrue(navigatedBack)
            }
        } finally {
            timeGate.complete(Unit)
            progressGate.complete(Unit)
        }
    }

    @Test
    fun `进度写入挂起不推迟停表也不覆盖新的前台状态`() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle = registry
        }
        owner.registry.currentState = Lifecycle.State.CREATED
        val progressGate = CompletableDeferred<Unit>()
        var foreground = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ReaderSystemUiEffects(
                    toolbarHidden = false,
                    hideStatusBar = false,
                    hideNavigationBar = false,
                    isDark = false,
                    background = Color.White,
                    scope = rememberCoroutineScope(),
                    syncProgress = {},
                    flushProgress = { progressGate.await() },
                    onBack = {},
                    resumeReadingTime = { foreground = true },
                    pauseReadingTime = { foreground = false }
                )
            }
        }
        try {
            compose.runOnIdle {
                owner.registry.currentState = Lifecycle.State.STARTED
                assertTrue(foreground)
                owner.registry.currentState = Lifecycle.State.CREATED
                assertFalse(foreground)
                owner.registry.currentState = Lifecycle.State.STARTED
                assertTrue(foreground)
                progressGate.complete(Unit)
            }
            compose.runOnIdle { assertTrue(foreground) }
        } finally {
            progressGate.complete(Unit)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
        }
    }
}
