package com.vibereading.app.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 实际导航往返覆盖共享容器的注册、释放和再次匹配，避免第二次开书出现残留层。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookContainerTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `shared cover survives repeated navigation round trips`() {
        lateinit var nav: NavHostController
        val pageSizes = mutableSetOf<IntSize>()
        compose.setContent {
            nav = rememberNavController()
            SharedTransitionLayout {
                val shared = this
                NavHost(nav, startDestination = "shelf",
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None }
                ) {
                    composable("shelf") {
                        Box(Modifier.size(80.dp, 110.dp).then(
                            with(shared) { bookContainerBounds(1, this@composable) }
                        ).testTag("cover"))
                    }
                    composable("reader") {
                        Box(Modifier.fillMaxSize().then(
                            with(shared) { bookContainerBounds(1, this@composable, isCover = false) }
                        ).onSizeChanged { pageSizes.add(it) }.testTag("page"))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        repeat(3) {
            compose.onNodeWithTag("cover").assertIsDisplayed()
            compose.runOnIdle { nav.navigate("reader") }
            compose.mainClock.advanceTimeBy(160)
            // 中途封面和正文必须同时存在，不能退化为先移除书架再显示阅读页。
            compose.onNodeWithTag("cover").assertExists()
            compose.onNodeWithTag("page").assertExists()
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithTag("page").assertIsDisplayed()
            compose.runOnIdle { nav.popBackStack() }
            compose.mainClock.advanceTimeBy(160)
            compose.onNodeWithTag("cover").assertExists()
            compose.onNodeWithTag("page").assertExists()
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithTag("cover").assertIsDisplayed()
        }
        assertTrue(pageSizes.isNotEmpty())
        assertEquals("开合只缩放绘制层，正文排版尺寸必须保持稳定", 1, pageSizes.size)
    }
}
