package com.vibereading.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.LlmSettings
import com.vibereading.app.domain.model.ThemeSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSectionsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `theme section exposes five quick colors without 青简`() {
        compose.setContent {
            MaterialTheme {
                ThemeSettingsSection(
                    theme = ThemeSettings(accent = AppAccent.INDIGO),
                    onThemeModeChange = {},
                    onAccentChange = {}
                )
            }
        }

        compose.onNodeWithText("外观").assertIsDisplayed()
        compose.onNodeWithText("跟随系统").assertIsDisplayed()
        compose.onNodeWithText("浅色").assertIsDisplayed()
        compose.onNodeWithText("深色").assertIsDisplayed()
        compose.onNodeWithContentDescription("主题色：黛蓝").assertIsDisplayed()
        compose.onAllNodesWithText("青简").assertCountEquals(0)
    }

    @Test
    fun `overview exposes design sections and no about row`() {
        compose.setContent {
            MaterialTheme {
                LlmOverviewSection(
                    state = SettingsUiState(
                        profiles = emptyList(),
                        llmSettings = LlmSettings(model = "Qwen3.6")
                    ),
                    onOpenLlmSettings = {},
                    onOpenTranslationParams = {},
                    onToggleExplainThinking = {}
                )
            }
        }

        compose.onNodeWithText("翻译与 AI").assertIsDisplayed()
        compose.onNodeWithText("LLM 配置").assertIsDisplayed()
        compose.onNodeWithText("Qwen3.6").assertIsDisplayed()
        compose.onAllNodesWithText("关于").assertCountEquals(0)
        compose.onNodeWithText("解释时思考").assertIsDisplayed()
        compose.onNodeWithText("采样温度").assertIsDisplayed()
        compose.onNodeWithText("Top P").assertIsDisplayed()
    }

    @Test
    fun `reading and other sections expose screenshot labels`() {
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    WebCompanionSection(
                        running = false,
                        url = null,
                        onToggle = {},
                        onCopyUrl = {}
                    )
                    DebugSection(onOpenLogs = {})
                }
            }
        }

        compose.onNodeWithText("阅读体验").assertIsDisplayed()
        compose.onNodeWithText("局域网网页阅读").assertIsDisplayed()
        compose.onNodeWithText("其他").assertIsDisplayed()
        compose.onNodeWithText("日志").assertIsDisplayed()
    }
}
