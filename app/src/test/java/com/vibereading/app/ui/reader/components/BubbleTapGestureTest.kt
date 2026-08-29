package com.vibereading.app.ui.reader.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 气泡点按手势消费契约单测（Robolectric + Compose UI 测试注入指针事件）。
 *
 * 用户症状：阅读时点到气泡本体有时触发翻页。外层翻页手势的契约是「抬手未被消费 →
 * 三段点按翻页」（ReaderScreen 手势容器），气泡手势必须保证气泡触控区内的点按
 * 消费抬起事件，否则 up 落到外层即翻页。
 *
 * 反馈回路：外层计数器复刻「未消费 up → 翻页」契约；mainClock 推进虚拟时钟越过
 * 系统长按超时（Robolectric 下 500ms），模拟「标注小气泡时按久了」的真实慢点按。
 * 快/慢点按都必须：弹窗切换一次、外层不翻页。
 *
 * 注：组合内协程挂起点（含手势的 withTimeoutOrNull）挂在 compose 测试规则的虚拟
 * 时钟 TestScheduler 上，用 mainClock.advanceTimeBy 推进；Thread.sleep / Robolectric
 * looper 调度器推进均不影响该时钟（已实测）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34, 35])
class BubbleTapGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private var turns = 0
    private var toggles = 0

    @Before
    fun reset() {
        turns = 0
        toggles = 0
    }

    private fun setContent() {
        compose.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("outer")
                    .pointerInput(Unit) {
                        // 外层翻页手势的最小契约：抬手未被消费 → 翻页
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!change.isConsumed) turns++
                                    break
                                }
                            }
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = 120.dp, height = 56.dp)
                        .testTag("bubble")
                        .bubbleTapGesture(
                            selectionState = null,
                            enabled = true
                        ) { toggles++ }
                )
            }
        }
        compose.waitForIdle()
    }

    /** 慢点按：按下后虚拟时钟推进越过系统长按超时再抬手。 */
    private fun slowTap() {
        compose.onNodeWithTag("bubble").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithTag("bubble").performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun quickTap() {
        compose.onNodeWithTag("bubble").performTouchInput { down(center); up() }
        compose.waitForIdle()
    }

    @Test
    fun 快点按_开弹窗_不翻页() {
        setContent()
        quickTap()
        assertEquals(1, toggles)
        assertEquals(0, turns)
    }

    @Test
    fun 慢点按_开弹窗_不翻页() {
        setContent()
        slowTap()
        assertEquals(1, toggles)
        assertEquals(0, turns)
    }
}
