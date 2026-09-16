package com.vibereading.app.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.vibereading.app.domain.model.ReadingSettings
import com.vibereading.app.ui.reader.components.TextSelectionState
import com.vibereading.app.ui.reader.pagination.BookWindow
import com.vibereading.app.ui.reader.pagination.PageCurl

/** 阅读器点按结束后应执行的单一动作。 */
enum class ReaderTapAction {
    PREVIOUS_PAGE,
    NEXT_PAGE,
    TOGGLE_TOOLBAR,
    DISMISS_OVERLAY,
    DISMISS_POPUP,
    NONE
}

/** 阅读器三分区点按策略。 */
fun resolveReaderTapAction(
    x: Float,
    width: Float,
    flipMode: String,
    oneHandMode: Boolean,
    overlayVisible: Boolean,
    popupVisible: Boolean,
    hadSelectionAtDown: Boolean,
    settledSimulationFlip: Boolean
): ReaderTapAction {
    if (popupVisible) return ReaderTapAction.DISMISS_POPUP
    if (hadSelectionAtDown) return ReaderTapAction.NONE
    if (overlayVisible) return ReaderTapAction.DISMISS_OVERLAY
    if (width <= 0f) return ReaderTapAction.NONE

    val third = width / 3f
    val inMiddle = x >= third && x < third * 2f
    if (flipMode == ReadingSettings.FLIP_SCROLL) {
        return if (inMiddle) ReaderTapAction.TOGGLE_TOOLBAR else ReaderTapAction.NONE
    }
    return when {
        inMiddle -> ReaderTapAction.TOGGLE_TOOLBAR
        settledSimulationFlip -> ReaderTapAction.NONE
        x < third && oneHandMode -> ReaderTapAction.NEXT_PAGE
        x < third -> ReaderTapAction.PREVIOUS_PAGE
        else -> ReaderTapAction.NEXT_PAGE
    }
}

fun readerPagerScrollEnabled(flipMode: String): Boolean =
    flipMode != ReadingSettings.FLIP_NO_ANIM && flipMode != ReadingSettings.FLIP_SIMULATION

fun readerShouldDismissOverlayOnGestureStart(overlayVisible: Boolean): Boolean = overlayVisible

/** pointerInput 内所需的最新交互状态，由 ReaderScreen 用 rememberUpdatedState 适配。 */
data class ReaderGestureState(
    val overlayVisible: () -> Boolean,
    val popupVisible: () -> Boolean,
    val oneHandMode: () -> Boolean
)

interface ReaderGestureActions {
    fun goPage(page: Int)
    fun toggleToolbar()
    fun dismissOverlays()
    fun dismissPopups()
}

/** 阅读内容统一手势层；仿真 MOVE 仅向 [ReaderCurlController] 提交坐标。 */
fun Modifier.readerContentGestures(
    isPagerMode: Boolean,
    flipMode: String,
    pagerState: PagerState,
    window: BookWindow,
    selectionState: TextSelectionState,
    curlController: ReaderCurlController,
    state: ReaderGestureState,
    actions: ReaderGestureActions,
    layoutKey: Any
): Modifier = pointerInput(isPagerMode, flipMode, layoutKey, window) {
    val inSimulation = isPagerMode && flipMode == ReadingSettings.FLIP_SIMULATION
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (inSimulation) {
            val viewWidth = size.width.toFloat()
            val viewHeight = size.height.toFloat()
            val slopSquare = 30f * 30f
            curlController.interruptAndPrepare(
                down.position.x, down.position.y, viewWidth, viewHeight
            )
            val simFlip = curlController.state
            val hadSelectionAtDown = selectionState.isSelecting
            if (hadSelectionAtDown && !down.isConsumed) selectionState.clear()
            val hadPopupAtDown = state.popupVisible()
            var curlActive = false
            var gestureStartedWithOverlay = state.overlayVisible()

            fun abortCurlBounce() {
                if (curlActive) curlController.finishDrag(cancel = true)
            }

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    abortCurlBounce()
                    break
                }
                if (!change.pressed) {
                    when {
                        hadPopupAtDown -> actions.dismissPopups()
                        hadSelectionAtDown -> abortCurlBounce()
                        curlActive -> curlController.finishDrag()
                        !simFlip.isMoved && !change.isConsumed -> performTapAction(
                            resolveReaderTapAction(
                                down.position.x, size.width.toFloat(), flipMode,
                                state.oneHandMode(), state.overlayVisible(), false,
                                hadSelectionAtDown = false,
                                settledSimulationFlip = simFlip.downSettledFlip
                            ),
                            pagerState,
                            actions
                        )
                    }
                    break
                }
                if (change.isConsumed) {
                    abortCurlBounce()
                    break
                }
                val focusX = change.position.x
                val focusY = change.position.y
                if (!simFlip.isMoved) {
                    val deltaX = focusX - simFlip.startX
                    val deltaY = focusY - simFlip.startY
                    if (deltaX * deltaX + deltaY * deltaY > slopSquare) {
                        if (gestureStartedWithOverlay) {
                            actions.dismissOverlays()
                            gestureStartedWithOverlay = false
                        }
                        simFlip.isMoved = true
                        val direction = if (deltaX > 0) PageCurl.Direction.PREV else PageCurl.Direction.NEXT
                        val target = if (direction == PageCurl.Direction.PREV) {
                            pagerState.currentPage - 1
                        } else {
                            pagerState.currentPage + 1
                        }
                        curlActive = curlController.beginDrag(direction, target, viewWidth, viewHeight)
                        if (!curlActive && target !in 0 until window.pageCount) break
                    }
                }
                if (curlActive) curlController.move(focusX, focusY, viewHeight)
                if (change.isConsumed) {
                    abortCurlBounce()
                    break
                }
            }
        } else {
            val downX = down.position.x
            val downY = down.position.y
            val slopSquare = 30f * 30f
            var moved = false
            val hadSelectionAtDown = selectionState.isSelecting
            if (hadSelectionAtDown && !down.isConsumed) selectionState.clear()
            val hadPopupAtDown = state.popupVisible()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    when {
                        hadPopupAtDown -> actions.dismissPopups()
                        hadSelectionAtDown -> Unit
                        !moved && !change.isConsumed -> performTapAction(
                            resolveReaderTapAction(
                                down.position.x, size.width.toFloat(), flipMode,
                                state.oneHandMode(), state.overlayVisible(), false,
                                hadSelectionAtDown = false,
                                settledSimulationFlip = false
                            ),
                            pagerState,
                            actions
                        )
                    }
                    break
                }
                if (!moved && readerShouldDismissOverlayOnGestureStart(state.overlayVisible())) {
                    val dx = change.position.x - downX
                    val dy = change.position.y - downY
                    if (dx * dx + dy * dy > slopSquare) {
                        actions.dismissOverlays()
                        moved = true
                    }
                }
                if (change.isConsumed) break
            }
        }
    }
}

private fun performTapAction(
    action: ReaderTapAction,
    pagerState: PagerState,
    actions: ReaderGestureActions
) {
    when (action) {
        ReaderTapAction.PREVIOUS_PAGE -> actions.goPage(pagerState.currentPage - 1)
        ReaderTapAction.NEXT_PAGE -> actions.goPage(pagerState.currentPage + 1)
        ReaderTapAction.TOGGLE_TOOLBAR -> actions.toggleToolbar()
        ReaderTapAction.DISMISS_OVERLAY -> actions.dismissOverlays()
        ReaderTapAction.DISMISS_POPUP -> actions.dismissPopups()
        ReaderTapAction.NONE -> Unit
    }
}
