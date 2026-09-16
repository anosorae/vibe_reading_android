package com.vibereading.app.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.material3.MaterialTheme
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

    @Test
    fun `statistics placeholder renders`() {
        compose.setContent {
            MaterialTheme { StatisticsPlaceholderScreen() }
        }
        compose.onNodeWithText("阅读统计").assertIsDisplayed()
        compose.onNodeWithText("统计功能即将上线\n你的阅读轨迹会在这里汇聚。").assertIsDisplayed()
    }

    @Test
    fun `bottom navigation exposes three tabs`() {
        compose.setContent {
            MaterialTheme {
                AppBottomBar(selectedTab = AppTab.BOOKSHELF, onSelect = {})
            }
        }
        compose.onNodeWithText("书架").assertIsDisplayed()
        compose.onNodeWithText("统计").assertIsDisplayed()
        compose.onNodeWithText("我的").assertIsDisplayed()
    }
}
