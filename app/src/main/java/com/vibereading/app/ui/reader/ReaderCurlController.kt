package com.vibereading.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import com.vibereading.app.data.image.BookImageStore
import com.vibereading.app.log.AppLog
import com.vibereading.app.ui.reader.pagination.BookWindow
import com.vibereading.app.ui.reader.pagination.PageCurl
import com.vibereading.app.ui.reader.pagination.PageStyle
import com.vibereading.app.ui.reader.pagination.SimFlipState
import com.vibereading.app.ui.reader.pagination.renderPageBitmap
import com.vibereading.app.ui.reader.pagination.simFlipCommitPage
import com.vibereading.app.ui.reader.pagination.simFlipDurationMs
import com.vibereading.app.ui.reader.pagination.simFlipSettlePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 仿真卷页位图、动画与 Job 生命周期；PageCurl 几何算法仍由 pagination 包负责。 */
@Stable
class ReaderCurlController(
    val state: SimFlipState,
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    private lateinit var config: Config

    data class Config(
        val pagerState: PagerState,
        val window: BookWindow,
        val mode: String,
        val layout: ReaderLayoutSpec,
        val density: Density,
        val backgroundArgb: Int,
        val measurer: TextMeasurer
    )

    fun update(config: Config) {
        this.config = config
    }

    fun cancelAndCleanup() {
        job?.cancel()
        job = null
        state.cleanup()
    }

    /** 新 DOWN 打断动画时先提交既定目标页，再重置卷页状态。 */
    fun interruptAndPrepare(downX: Float, downY: Float, viewWidth: Float, viewHeight: Float): Boolean {
        val hadRunningAnimation = job != null
        job?.cancel()
        job = null
        val settle = if (hadRunningAnimation) {
            simFlipSettlePage(state, config.pagerState.currentPage, config.window.pageCount)
        } else -1
        state.cleanup()
        if (settle >= 0) {
            try {
                config.pagerState.requestScrollToPage(settle)
            } catch (e: Exception) {
                AppLog.put("仿真打断落地 requestScrollToPage($settle) 失败", e)
            }
        }
        state.onDown(downX, downY)
        state.downSettledFlip = settle >= 0
        state.calcCornerXY(downX, viewWidth, viewHeight)
        state.curl.setViewSize(viewWidth, viewHeight)
        return settle >= 0
    }

    /** MOVE 首次越过 slop 后建立目标页快照。 */
    fun beginDrag(direction: PageCurl.Direction, target: Int, viewWidth: Float, viewHeight: Float): Boolean {
        if (target !in 0 until config.window.pageCount) return false
        state.setDirection(direction, viewWidth, viewHeight)
        val pair = curlBitmaps(config.pagerState.currentPage, target) ?: return false
        state.curBitmap = pair.first
        state.targetBitmap = pair.second
        state.bgColor = config.backgroundArgb
        state.animating = true
        return true
    }

    /** MOVE 更新完全委托到控制器，手势层只负责提供坐标。 */
    fun move(focusX: Float, focusY: Float, viewHeight: Float) {
        state.isCancel = if (state.direction == PageCurl.Direction.NEXT) {
            focusX > state.lastX
        } else {
            focusX < state.lastX
        }
        state.lastX = focusX
        state.lastY = focusY
        state.touchX = focusX
        state.touchY = focusY
        state.adjustTouchY(viewHeight)
        state.isRunning = true
    }

    fun finishDrag(cancel: Boolean = false) {
        val cur = config.pagerState.currentPage
        val target = if (state.direction == PageCurl.Direction.NEXT) cur + 1 else cur - 1
        if (cancel) state.isCancel = true
        simFlipAnimStart(cur, target)
    }

    /** 卷页快照对（对齐 Legado setBitmap）：curBitmap=当前页，targetBitmap=目标页。 */
    private fun curlBitmaps(cur: Int, target: Int): Pair<Bitmap, Bitmap>? {
        val layout = config.layout
        val curBitmap = renderPageBitmap(
            config.window, cur, config.mode, layout.pageStyle, layout.geometry, layout.palette,
            config.density, config.backgroundArgb, config.measurer,
            imageResolver = { path, width -> BookImageStore.loadBitmap(path, width) }
        ) ?: return null
        val targetBitmap = renderPageBitmap(
            config.window, target, config.mode, layout.pageStyle, layout.geometry, layout.palette,
            config.density, config.backgroundArgb, config.measurer,
            imageResolver = { path, width -> BookImageStore.loadBitmap(path, width) }
        )
        if (targetBitmap == null) {
            curBitmap.recycle()
            return null
        }
        return curBitmap to targetBitmap
    }

    /** 卷页自动动画共享驱动。commitPage < 0 表示回弹。 */
    private fun launchCurlAnim(dx: Float, dy: Float, commitPage: Int) {
        val width = config.layout.geometry.screenWidthPx.toFloat()
        val height = config.layout.geometry.screenHeightPx.toFloat()
        val startX = state.touchX
        val startY = state.touchY
        val endX = startX + dx
        val endY = startY + dy
        job = scope.launch {
            try {
                Animatable(0f).animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = simFlipDurationMs(dx, dy, width, height).toInt(),
                        easing = LinearEasing
                    )
                ) {
                    state.touchX = startX + (endX - startX) * value
                    state.touchY = startY + (endY - startY) * value
                    state.adjustTouchY(height)
                }
                if (commitPage >= 0 && config.pagerState.currentPage != commitPage) {
                    config.pagerState.scrollToPage(commitPage)
                }
                state.cleanup()
                job = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("仿真卷页动画异常，已清理冻结状态", e)
                state.cleanup()
                job = null
            }
        }
    }

    /** 点按触发的仿真卷页。 */
    fun startSimFlip(cur: Int, next: Int, goingNext: Boolean) {
        if (state.isRunning) return
        job?.cancel()
        val pair = curlBitmaps(cur, next)
        if (pair == null) {
            scope.launch { if (config.pagerState.currentPage != next) config.pagerState.scrollToPage(next) }
            return
        }
        val width = config.layout.geometry.screenWidthPx.toFloat()
        val height = config.layout.geometry.screenHeightPx.toFloat()
        state.curl.setViewSize(width, height)
        state.curBitmap = pair.first
        state.targetBitmap = pair.second
        state.direction = if (goingNext) PageCurl.Direction.NEXT else PageCurl.Direction.PREV
        state.bgColor = config.backgroundArgb
        if (goingNext) {
            state.cornerX = width
            val startY = if (state.startY > height / 2) height * 0.9f else 1f
            state.startX = width * 0.9f
            state.startY = startY
            state.cornerY = if (startY > height / 2) height else 0f
            state.touchX = width * 0.9f
            state.touchY = startY
        } else {
            state.startX = 0f
            state.startY = height
            state.cornerX = width
            state.cornerY = height
            state.touchX = 0f
            state.touchY = height
        }
        state.animating = true
        state.isRunning = true
        state.isMoved = true
        state.isCancel = false
        state.settleTarget = next
        val dx = if (goingNext) {
            if (state.cornerX > 0f) -(width + state.touchX) else width - state.touchX
        } else {
            width + width - state.touchX
        }
        val dy = if (state.cornerY > 0f) height - state.touchY else 1f - state.touchY
        launchCurlAnim(dx, dy, next)
    }

    /** 拖拽抬手后的回弹或完成动画。 */
    private fun simFlipAnimStart(cur: Int, target: Int) {
        job?.cancel()
        state.animating = true
        state.isRunning = true
        val commit = simFlipCommitPage(state.isCancel, target, config.window.pageCount)
        state.settleTarget = commit
        val width = config.layout.geometry.screenWidthPx.toFloat()
        val height = config.layout.geometry.screenHeightPx.toFloat()
        val dx: Float
        val dy: Float
        if (state.isCancel) {
            dx = if (state.direction != PageCurl.Direction.NEXT) {
                -(width + state.touchX)
            } else if (state.cornerX > 0) {
                width - state.touchX
            } else {
                -state.touchX
            }
            dy = if (state.cornerY > 0) height - state.touchY else -state.touchY
        } else {
            dx = if (state.direction == PageCurl.Direction.NEXT) {
                -(width + state.touchX)
            } else {
                width + width - state.touchX
            }
            dy = if (state.cornerY > 0f) height - state.touchY else 1f - state.touchY
        }
        launchCurlAnim(dx, dy, commit)
    }
}
