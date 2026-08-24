package com.vibereading.app.ui.reader.pagination

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 回归测试：仿真卷页 PREV（右滑翻上一页）拖到右侧区域不得出现渲染错位。
 *
 * Bug 背景：setDirection(PREV) 旧实现把卷角设为浮点的 viewWidth - startX（中部 X），
 * 拖拽越过该角 X 后卷页几何反转，已揭示的上一页在右侧被当前页重新盖回 ——
 * 表现即「从左往右滑动到右侧区域时的渲染错位」。对齐 Legado 后卷角固定右下角，
 * 右滑过程中当前页卷起楔形只会单调收窄，拖到右缘时整屏应完全揭示上一页。
 *
 * 用实色位图（base=红=上一页, sheet=蓝=当前页）走真实状态机
 * （onDown → calcCornerXY → setDirection → touch 跟手 → adjustTouchY → curl.draw），
 * 断言结构事实：sheet 楔形占比随 touchX 单调不增，且拖到右缘时为 0。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PageCurlPrevSweepTest {

    private val viewW = 1080f
    private val viewH = 2400f

    /** 复用真实手势路径渲染一帧 PREV，返回 sheet（当前页=蓝）像素占比。 */
    private fun prevSheetRatio(startX: Float, startY: Float, touchX: Float): Float {
        val st = SimFlipState()
        st.onDown(startX, startY)
        st.calcCornerXY(startX, viewW, viewH)
        st.setDirection(PageCurl.Direction.PREV, viewW, viewH)
        // 跟手（对齐手势 MOVE：touchX=focusX，touchY 被 adjustTouchY 拉到底部）
        st.touchX = touchX
        st.touchY = startY
        st.adjustTouchY(viewH)

        val base = Bitmap.createBitmap(viewW.toInt(), viewH.toInt(), Bitmap.Config.ARGB_8888)
        val sheet = Bitmap.createBitmap(viewW.toInt(), viewH.toInt(), Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(viewW.toInt(), viewH.toInt(), Bitmap.Config.ARGB_8888)
        base.eraseColor(Color.RED)
        sheet.eraseColor(Color.BLUE)
        val canvas = Canvas(out)
        st.curl.setViewSize(viewW, viewH)
        st.curl.start(st.touchX, st.touchY, st.cornerX, st.cornerY)
        st.curl.draw(canvas, base, sheet, Color.WHITE)

        val px = out.loadAndroidPixels()
        var sheetCount = 0
        var total = 0
        for (argb in px) {
            val r = argb ushr 16 and 0xFF
            val g = argb ushr 8 and 0xFF
            val b = argb and 0xFF
            // 蓝（含被阴影压暗但仍是 b 主导）都算当前页卷起楔形
            if (b >= 100 && b > r && b > g) sheetCount++
            total++
        }
        base.recycle(); sheet.recycle(); out.recycle()
        return sheetCount.toFloat() / total
    }

    private fun Bitmap.loadAndroidPixels(): IntArray {
        val arr = IntArray(width * height)
        getPixels(arr, 0, width, 0, 0, width, height)
        return arr
    }

    @Test
    fun prevSwipeReachesRightEdge_sheetWedgeNeverGrowsBack() {
        // 起点偏左（startX=200）→ 卷角应固定在右下角；touchX 一路扫向屏幕右缘
        val startX = 200f
        val startY = 1200f
        val sweep = listOf(200f, 400f, 600f, 800f, 950f, 1040f, 1080f)
        val ratios = sweep.map { prevSheetRatio(startX, startY, it) }
        // 右滑过程中当前页卷起楔形必须单调收窄（不能在中途重新变宽）
        for (i in 1 until ratios.size) {
            assertTrue(
                "touchX=${sweep[i]} 时 sheet 楔形占比(${ratios[i]})不应大于前一帧(${ratios[i - 1]})，右侧渲染错位复发: $ratios",
                ratios[i] <= ratios[i - 1] + 0.001f
            )
        }
    }

    @Test
    fun prevSwipeToRightEdge_fullyRevealsPreviousPage() {
        // 手指拖到屏幕右缘时，整屏必须完全揭示上一页（当前页卷走，占比为 0）
        for (startX in listOf(120f, 540f, 780f)) {
            val ratio = prevSheetRatio(startX = startX, startY = 1200f, touchX = viewW)
            assertEquals("startX=$startX 拖到右缘后仍有当前页残留(占比 $ratio)", 0f, ratio, 0.0f)
        }
    }
}
