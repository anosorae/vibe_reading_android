package com.vibereading.app.ui.navigation

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppShellTest {
    @get:Rule val compose = createComposeRule()

    private fun setContentWithBar(stableInsets: WindowInsets) {
        compose.setContent {
            MaterialTheme {
                GlassBottomBar(
                    stableInsets = stableInsets,
                    selectedTab = AppTab.BOOKSHELF,
                    onSelect = {},
                    captureLayer = rememberGraphicsLayer(),
                    captureTick = remember { mutableIntStateOf(0) }
                )
            }
        }
    }

    @Test
    fun `bottom navigation exposes three tabs`() {
        setContentWithBar(WindowInsets(0))
        compose.onNodeWithText("书架").assertIsDisplayed()
        compose.onNodeWithText("统计").assertIsDisplayed()
        compose.onNodeWithText("我的").assertIsDisplayed()
    }

    @Test
    fun `bottom navigation remains visible above gesture navigation inset`() {
        setContentWithBar(WindowInsets(bottom = 24.dp))
        compose.onNodeWithText("书架").assertIsDisplayed()
        compose.onNodeWithText("统计").assertIsDisplayed()
        compose.onNodeWithText("我的").assertIsDisplayed()
    }
}
