package com.vibereading.app.ui.reader.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vibereading.app.log.AppLog
import com.vibereading.app.ui.reader.ReaderPalette
import com.vibereading.app.ui.reader.pagination.ReaderMetrics
import kotlin.math.roundToInt

/**
 * 屏幕级选择手柄覆盖层。
 *
 * 在 [ReaderScreen] 层级渲染，使用窗口坐标定位。
 * [containerWindowOffset] 为父容器在窗口坐标系中的位置，
 * 用于将 handle 的窗口坐标转换为父容器内的偏移量。
 * 长按选中词后显示两个手柄（START / END），拖拽手柄扩展选区。
 */
@Composable
fun SelectionHandles(
    selectionState: TextSelectionState,
    palette: ReaderPalette,
    density: androidx.compose.ui.unit.Density,
    containerWindowOffset: Offset = Offset.Zero
) {
    if (!selectionState.isSelecting) return
    val layout = selectionState.layoutResult ?: return
    val paraOffset = selectionState.paragraphWindowOffset
    val start = selectionState.selectionStart
    val end = selectionState.selectionEnd
    if (start < 0 || end > (selectionState.paragraphText.length)) return

    // 用 getCursorRect 获取对齐后的光标位置（两端对齐时 getBoundingBox 不含 justification 偏移），
    // 并补回拉伸字符后的光标度量回退（cursorDrawnCorrection），使竖线贴住绘制的字形边缘
    val startCursor = layout.cursorRectSafely(start) ?: return
    val endCursor = layout.cursorRectSafely(end) ?: return

    // 将窗口坐标转换为父容器内偏移量
    // START: getCursorRect(start).left = 首字符左边缘；END: getCursorRect(end).left = 末字符右边缘
    // Y 锚定 cursorRect.bottom（字符行底）：iOS 风格手柄从选区高亮底部向下延伸
    val startPos = Offset(
        paraOffset.x + startCursor.left + cursorDrawnCorrection(selectionState.charStretchPx, start) - containerWindowOffset.x,
        paraOffset.y + startCursor.bottom - containerWindowOffset.y
    )
    val endPos = Offset(
        paraOffset.x + endCursor.left + cursorDrawnCorrection(selectionState.charStretchPx, end) - containerWindowOffset.x,
        paraOffset.y + endCursor.bottom - containerWindowOffset.y
    )

    SelectionHandle(
        handleType = HandleType.START,
        position = startPos,
        palette = palette,
        density = density,
        selectionState = selectionState,
        containerWindowOffset = containerWindowOffset
    )
    SelectionHandle(
        handleType = HandleType.END,
        position = endPos,
        palette = palette,
        density = density,
        selectionState = selectionState,
        containerWindowOffset = containerWindowOffset
    )
}

/**
 * 单个选择手柄：竖线 + 圆点（iOS 风格）。
 *
 * 拖拽手势将屏幕坐标转换为段落内字符偏移，更新选区。
 * 拖拽结束时调用 [TextSelectionState.endDrag] 弹出工具栏。
 * 轻触手柄（无拖拽）时也调用 [TextSelectionState.endDrag] 显示工具栏。
 *
 * 触摸仲裁（D6）：DOWN 事件立即消费，外层翻页手势通过 [PointerInputChange.isConsumed]
 * 判定手柄已拦截，不清除选区。替代了旧版 [TextSelectionState.isPositionNearHandle]
 * 几何命中检测（浮点光标位置与整数 Box 偏移存在 0.5px 间隙）。
 */
@Composable
private fun SelectionHandle(
    handleType: HandleType,
    position: Offset,  // 选区边界在父容器坐标系中的位置（已减去 containerWindowOffset）
    palette: ReaderPalette,
    density: androidx.compose.ui.unit.Density,
    selectionState: TextSelectionState,
    containerWindowOffset: Offset = Offset.Zero  // 父容器在窗口中的位置，用于 drag 坐标转换
) {
    val handleSize = ReaderMetrics.HANDLE_SIZE_DP.dp
    val handleSizePx = with(density) { handleSize.toPx() }
    val visualSizePx = with(density) { ReaderMetrics.HANDLE_VISUAL_SIZE_DP.dp.toPx() } // 视觉区（竖线+圆点）高度
    val lineWidth = ReaderMetrics.HANDLE_LINE_WIDTH_DP.dp
    val dotRadius = ReaderMetrics.HANDLE_DOT_RADIUS_DP.dp
    val dotPadding = ReaderMetrics.HANDLE_DOT_PADDING_DP.dp

    // 手柄盒子定位：竖线对齐选区边界，圆点向外展开
    // START 竖线在盒子右边缘（对齐首字符左边界），END 竖线在盒子左边缘（对齐末字符右边界）
    val offsetX = when (handleType) {
        HandleType.START -> position.x - handleSizePx  // 盒子右边缘 = position.x = bbox.left
        HandleType.END -> position.x                    // 盒子左边缘 = position.x = bbox.right
    }
    // 手柄顶部对齐字符行底部（iOS 风格：竖线从选区高亮底部向下延伸）
    val offsetY = position.y

    // rememberUpdatedState 确保手势协程（pointerInput key 不变时不会重启）始终读到最新值，
    // 避免手柄反转后闭包捕获的 offsetX/offsetY/containerWindowOffset 过期导致选区跳跃。
    val currentOffsetX by rememberUpdatedState(offsetX)
    val currentOffsetY by rememberUpdatedState(offsetY)
    val currentContainerOffset by rememberUpdatedState(containerWindowOffset)

    // 拖拽中手指在段落局部坐标系的位置
    var dragLocalPos by remember { mutableStateOf(Offset.Zero) }
    // 抓取瞬间「手指 → 命中点」的固定偏移（对齐 Legado ReadBookActivity.onTouch 的
    // rawX ± cursorWidth / rawY - cursorHeight）：命中点对到手柄竖线指向的位置，
    // 手指只负责拖动、悬在目标行下方的行间间隙里，不再遮住正在判定的字符行。
    var grabHitOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(handleSize)
            .pointerInput(selectionState) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // D6：立即消费 DOWN，外层翻页手势通过 down.isConsumed 判定手柄已拦截
                    down.consume()

                    // 命中点 = 手指 + 抓起瞬间固定的偏移：
                    // - 水平：对到手柄竖线 X（竖线是选区边界的视觉锚点）。竖线恰在字符边界时
                    //   getOffsetForPosition/逐字符命中会取到未选中一侧，故再微偏 0.5px 到已选区
                    //   一侧，避免刚抓起手柄选区就跳一个字符；
                    // - 垂直：整体抬高手柄视觉高度（触控盒更高不影响命中语义），
                    //   命中行 = 竖线根部指向的行（对齐 Legado rawY - height）。
                    val barLocalX = when (handleType) {
                        HandleType.START -> handleSizePx // 竖线在盒子右边缘
                        HandleType.END -> 0f             // 竖线在盒子左边缘
                    }
                    val sideEpsilon = if (handleType == HandleType.START) 0.5f else -0.5f
                    grabHitOffset = Offset(
                        barLocalX - down.position.x + sideEpsilon,
                        -visualSizePx
                    )
                    // 手柄盒左上角在父容器坐标 = (offsetX.roundToInt(), offsetY.roundToInt())
                    // 手指在容器局部 = 盒左上角 + down.position（盒内局部）
                    // 手指在窗口 = 容器局部 + containerWindowOffset
                    // 手指在段落局部 = 窗口 - paragraphWindowOffset
                    dragLocalPos = Offset(
                        currentOffsetX.roundToInt() + down.position.x + currentContainerOffset.x - selectionState.paragraphWindowOffset.x,
                        currentOffsetY.roundToInt() + down.position.y + currentContainerOffset.y - selectionState.paragraphWindowOffset.y
                    )

                    var totalDrag = Offset.Zero
                    var dragStarted = false
                    val touchSlop = viewConfiguration.touchSlop

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // UP：拖拽结束或轻触手柄，弹出工具栏
                            selectionState.endDrag()
                            break
                        }
                        val dragAmount = change.position - change.previousPosition
                        totalDrag += dragAmount
                        if (!dragStarted && totalDrag.getDistanceSquared() > touchSlop * touchSlop) {
                            dragStarted = true
                            selectionState.startDrag(handleType)
                        }
                        if (dragStarted) {
                            change.consume()
                            dragLocalPos += dragAmount
                            val layout = selectionState.layoutResult ?: continue
                            // 命中点对到手柄竖线尖端（远离手指），而不是手指正下方
                            val hitPos = dragLocalPos + grabHitOffset
                            val rawOffset = runCatching {
                                layout.getOffsetForPosition(hitPos)
                            }.onFailure { AppLog.put("getOffsetForPosition 失败", it) }
                                .getOrNull() ?: continue
                            // 两端对齐补偿：按字符单元格 + 视觉间隙中点归属
                            val adjusted = perCharHitTest(layout, hitPos, rawOffset, selectionState.charStretchPx)
                            selectionState.dragTo(adjusted)
                        }
                    }
                }
            }
    ) {
        // 单个 Canvas 绘制竖线 + 圆点，根据 handleType 对称布局
        Canvas(modifier = Modifier.fillMaxSize()) {
            val lineW = lineWidth.toPx()
            val r = dotRadius.toPx()
            val dotPadPx = dotPadding.toPx()

            // 竖线 X：START 在右边缘（对齐选区左边界），END 在左边缘（对齐选区右边界）
            val lineX = when (handleType) {
                HandleType.START -> size.width - lineW
                HandleType.END -> 0f
            }
            // 圆点中心 X：紧贴竖线内侧（水滴与竖线连成一体，避免视觉分离）
            val dotCenterX = when (handleType) {
                HandleType.START -> size.width - lineW - r * 0.5f
                HandleType.END -> lineW + r * 0.5f
            }
            // 圆点中心 Y：视觉区底部（视觉区顶部锚定行底，盒体多出的下沿是透明触控延伸，不参与绘制）
            val dotCenterY = visualSizePx - r - dotPadPx

            // 竖线：从顶部到圆点上方
            drawRect(
                color = palette.handleColor,
                topLeft = Offset(lineX, 2.dp.toPx()),
                size = Size(lineW, (dotCenterY - 2.dp.toPx()).coerceAtLeast(0f))
            )
            // 阴影（朝盒内偏移，避免贴边被裁剪）
            val shadowOffsetX = when (handleType) {
                HandleType.START -> -1f
                HandleType.END -> 1f
            }
            drawCircle(
                color = palette.handleColor.copy(alpha = 0.3f),
                radius = r + 2f,
                center = Offset(dotCenterX + shadowOffsetX, dotCenterY + 1f)
            )
            // 主圆点
            drawCircle(
                color = palette.handleColor,
                radius = r,
                center = Offset(dotCenterX, dotCenterY)
            )
        }
    }
}
