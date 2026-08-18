package com.vibereading.app.ui.reader.pagination

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 仿真卷页绘制：从 Legado SimulationPageDelegate 1:1 移植。
 *
 * 用 android.graphics.* 按 Legado 几何绘制：
 * - 拖拽点 → 页脚（corner）的贝塞尔卷曲（quadTo 双曲线闭合页脚区域 mPath0）；
 * - 卷起页正面（mPath1 内的 sheet 位图）+ 背脊阴影；
 * - 卷起页阴影（折痕两侧渐变）；
 * - 卷起页背面（base 位图沿折痕镜像 + 折痕阴影）。
 *
 * 每次翻页动画由外部把「触摸点」从起点线性插值到终点，逐帧调用 [draw]。
 */
class PageCurl(
    var viewWidth: Float = 0f,
    var viewHeight: Float = 0f
) {
    enum class Direction { NEXT, PREV }

    // 不让 x,y 为 0，否则在点计算时会有问题
    private var mTouchX = 0.1f
    private var mTouchY = 0.1f

    // 拖拽点对应的页脚
    private var mCornerX = 1f
    private var mCornerY = 1f

    private val mPath0: Path = Path()
    private val mPath1: Path = Path()

    // 贝塞尔曲线起始点/控制点/顶点/结束点（两条）
    private val mBezierStart1 = PointF()
    private val mBezierControl1 = PointF()
    private val mBezierVertex1 = PointF()
    private var mBezierEnd1 = PointF()
    private val mBezierStart2 = PointF()
    private val mBezierControl2 = PointF()
    private val mBezierVertex2 = PointF()
    private var mBezierEnd2 = PointF()

    private var mMiddleX = 0f
    private var mMiddleY = 0f
    private var mDegrees = 0f
    private var mTouchToCornerDis = 0f

    private val mColorMatrixFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    private val mMatrix: Matrix = Matrix()
    private val mMatrixArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)

    // 是否属于右上/左下
    private var mIsRtOrLb = false
    private var mMaxLength = hypot(viewWidth.toDouble(), viewHeight.toDouble()).toFloat()

    // 背面颜色组
    private val mBackShadowColors: IntArray = intArrayOf(-0xeeeeef, 0x111111)

    // 前面颜色组
    private val mFrontShadowColors: IntArray = intArrayOf(-0x7feeeeef, 0x111111)

    private val mBackShadowDrawableLR: GradientDrawable
    private val mBackShadowDrawableRL: GradientDrawable
    private val mFolderShadowDrawableLR: GradientDrawable
    private val mFolderShadowDrawableRL: GradientDrawable

    private val mFrontShadowDrawableHBT: GradientDrawable
    private val mFrontShadowDrawableHTB: GradientDrawable
    private val mFrontShadowDrawableVLR: GradientDrawable
    private val mFrontShadowDrawableVRL: GradientDrawable

    private val mPaint: Paint = Paint().apply { style = Paint.Style.FILL }

    init {
        // 设置颜色数组
        val color = intArrayOf(0x333333, -0x4fcccccd)
        mFolderShadowDrawableRL = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, color).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        mFolderShadowDrawableLR = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, color).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        mBackShadowDrawableRL = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, mBackShadowColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        mBackShadowDrawableLR = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, mBackShadowColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        mFrontShadowDrawableVLR = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, mFrontShadowColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        mFrontShadowDrawableVRL = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, mFrontShadowColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        mFrontShadowDrawableHTB = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, mFrontShadowColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        mFrontShadowDrawableHBT = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, mFrontShadowColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
    }

    fun setViewSize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        mMaxLength = hypot(width.toDouble(), height.toDouble()).toFloat()
    }

    /** 开始一次卷页：设置触摸点与页脚。 */
    fun start(touchX: Float, touchY: Float, cornerX: Float, cornerY: Float) {
        mTouchX = if (touchX == 0f) 0.1f else touchX
        mTouchY = if (touchY == 0f) 0.1f else touchY
        mCornerX = cornerX
        mCornerY = cornerY
        mIsRtOrLb = (mCornerX == 0f && mCornerY == viewHeight) ||
            (mCornerY == 0f && mCornerX == viewWidth)
    }

    /**
     * 绘制一帧。
     * @param base  底页位图（NEXT=当前页，PREV=上一页）
     * @param sheet 卷起页位图（NEXT=下一页，PREV=当前页）
     */
    fun draw(canvas: Canvas, base: Bitmap?, sheet: Bitmap?, direction: Direction, bgColor: Int) {
        base ?: return
        sheet ?: return
        calcPoints()
        drawCurrentPageArea(canvas, base)
        drawNextPageAreaAndShadow(canvas, sheet)
        drawCurrentPageShadow(canvas)
        drawCurrentBackArea(canvas, base, bgColor)
    }

    /**
     * 绘制翻起页背面
     */
    private fun drawCurrentBackArea(canvas: Canvas, bitmap: Bitmap?, bgColor: Int) {
        bitmap ?: return
        val i = ((mBezierStart1.x + mBezierControl1.x) / 2).toInt()
        val f1 = abs(i - mBezierControl1.x)
        val i1 = ((mBezierStart2.y + mBezierControl2.y) / 2).toInt()
        val f2 = abs(i1 - mBezierControl2.y)
        val f3 = min(f1, f2)
        mPath1.reset()
        mPath1.moveTo(mBezierVertex2.x, mBezierVertex2.y)
        mPath1.lineTo(mBezierVertex1.x, mBezierVertex1.y)
        mPath1.lineTo(mBezierEnd1.x, mBezierEnd1.y)
        mPath1.lineTo(mTouchX, mTouchY)
        mPath1.lineTo(mBezierEnd2.x, mBezierEnd2.y)
        mPath1.close()
        val mFolderShadowDrawable: GradientDrawable
        val left: Int
        val right: Int
        if (mIsRtOrLb) {
            left = (mBezierStart1.x - 1).toInt()
            right = (mBezierStart1.x + f3 + 1).toInt()
            mFolderShadowDrawable = mFolderShadowDrawableLR
        } else {
            left = (mBezierStart1.x - f3 - 1).toInt()
            right = (mBezierStart1.x + 1).toInt()
            mFolderShadowDrawable = mFolderShadowDrawableRL
        }
        canvas.save()
        canvas.clipPath(mPath0, Region.Op.INTERSECT)
        canvas.clipPath(mPath1, Region.Op.INTERSECT)

        mPaint.colorFilter = mColorMatrixFilter
        val dis = hypot(
            mCornerX - mBezierControl1.x.toDouble(),
            mBezierControl2.y - mCornerY.toDouble()
        ).toFloat()
        val f8 = (mCornerX - mBezierControl1.x) / dis
        val f9 = (mBezierControl2.y - mCornerY) / dis
        mMatrixArray[0] = 1 - 2 * f9 * f9
        mMatrixArray[1] = 2 * f8 * f9
        mMatrixArray[3] = mMatrixArray[1]
        mMatrixArray[4] = 1 - 2 * f8 * f8
        mMatrix.reset()
        mMatrix.setValues(mMatrixArray)
        mMatrix.preTranslate(-mBezierControl1.x, -mBezierControl1.y)
        mMatrix.postTranslate(mBezierControl1.x, mBezierControl1.y)
        canvas.drawColor(bgColor)
        canvas.drawBitmap(bitmap, mMatrix, mPaint)
        mPaint.colorFilter = null
        canvas.rotate(mDegrees, mBezierStart1.x, mBezierStart1.y)
        mFolderShadowDrawable.setBounds(
            left, mBezierStart1.y.toInt(),
            right, (mBezierStart1.y + mMaxLength).toInt()
        )
        mFolderShadowDrawable.draw(canvas)
        canvas.restore()
    }

    /**
     * 绘制翻起页的阴影
     */
    private fun drawCurrentPageShadow(canvas: Canvas) {
        val degree: Double = if (mIsRtOrLb) {
            Math.PI / 4 - atan2(mBezierControl1.y - mTouchY, mTouchX - mBezierControl1.x)
        } else {
            Math.PI / 4 - atan2(mTouchY - mBezierControl1.y, mTouchX - mBezierControl1.x)
        }
        // 翻起页阴影顶点与touch点的距离
        val d1 = 25f * 1.414 * cos(degree)
        val d2 = 25f * 1.414 * sin(degree)
        val x = (mTouchX + d1).toFloat()
        val y: Float = if (mIsRtOrLb) {
            (mTouchY + d2).toFloat()
        } else {
            (mTouchY - d2).toFloat()
        }
        mPath1.reset()
        mPath1.moveTo(x, y)
        mPath1.lineTo(mTouchX, mTouchY)
        mPath1.lineTo(mBezierControl1.x, mBezierControl1.y)
        mPath1.lineTo(mBezierStart1.x, mBezierStart1.y)
        mPath1.close()
        canvas.save()
        canvas.clipOutPath(mPath0)
        canvas.clipPath(mPath1, Region.Op.INTERSECT)

        var leftX: Int
        var rightX: Int
        var mCurrentPageShadow: GradientDrawable
        if (mIsRtOrLb) {
            leftX = mBezierControl1.x.toInt()
            rightX = (mBezierControl1.x + 25).toInt()
            mCurrentPageShadow = mFrontShadowDrawableVLR
        } else {
            leftX = (mBezierControl1.x - 25).toInt()
            rightX = (mBezierControl1.x + 1).toInt()
            mCurrentPageShadow = mFrontShadowDrawableVRL
        }
        var rotateDegrees = Math.toDegrees(
            atan2(mTouchX - mBezierControl1.x, mBezierControl1.y - mTouchY).toDouble()
        ).toFloat()
        canvas.rotate(rotateDegrees, mBezierControl1.x, mBezierControl1.y)
        mCurrentPageShadow.setBounds(
            leftX, (mBezierControl1.y - mMaxLength).toInt(),
            rightX, mBezierControl1.y.toInt()
        )
        mCurrentPageShadow.draw(canvas)
        canvas.restore()

        mPath1.reset()
        mPath1.moveTo(x, y)
        mPath1.lineTo(mTouchX, mTouchY)
        mPath1.lineTo(mBezierControl2.x, mBezierControl2.y)
        mPath1.lineTo(mBezierStart2.x, mBezierStart2.y)
        mPath1.close()
        canvas.save()
        canvas.clipOutPath(mPath0)
        canvas.clipPath(mPath1, Region.Op.INTERSECT)

        if (mIsRtOrLb) {
            leftX = mBezierControl2.y.toInt()
            rightX = (mBezierControl2.y + 25).toInt()
            mCurrentPageShadow = mFrontShadowDrawableHTB
        } else {
            leftX = (mBezierControl2.y - 25).toInt()
            rightX = (mBezierControl2.y + 1).toInt()
            mCurrentPageShadow = mFrontShadowDrawableHBT
        }
        rotateDegrees = Math.toDegrees(
            atan2(mBezierControl2.y - mTouchY, mBezierControl2.x - mTouchX).toDouble()
        ).toFloat()
        canvas.rotate(rotateDegrees, mBezierControl2.x, mBezierControl2.y)
        val temp =
            if (mBezierControl2.y < 0) (mBezierControl2.y - viewHeight).toDouble()
            else mBezierControl2.y.toDouble()
        val hmg = hypot(mBezierControl2.x.toDouble(), temp)
        if (hmg > mMaxLength)
            mCurrentPageShadow.setBounds(
                (mBezierControl2.x - 25 - hmg).toInt(), leftX,
                (mBezierControl2.x + mMaxLength - hmg).toInt(), rightX
            )
        else
            mCurrentPageShadow.setBounds(
                (mBezierControl2.x - mMaxLength).toInt(), leftX,
                mBezierControl2.x.toInt(), rightX
            )
        mCurrentPageShadow.draw(canvas)
        canvas.restore()
    }

    /**
     * 绘制卷起页正面 + 背面阴影
     */
    private fun drawNextPageAreaAndShadow(canvas: Canvas, bitmap: Bitmap?) {
        bitmap ?: return
        mPath1.reset()
        mPath1.moveTo(mBezierStart1.x, mBezierStart1.y)
        mPath1.lineTo(mBezierVertex1.x, mBezierVertex1.y)
        mPath1.lineTo(mBezierVertex2.x, mBezierVertex2.y)
        mPath1.lineTo(mBezierStart2.x, mBezierStart2.y)
        mPath1.lineTo(mCornerX, mCornerY)
        mPath1.close()
        mDegrees = Math.toDegrees(
            atan2(
                (mBezierControl1.x - mCornerX).toDouble(),
                mBezierControl2.y - mCornerY.toDouble()
            )
        ).toFloat()
        val leftX: Int
        val rightX: Int
        val mBackShadowDrawable: GradientDrawable
        if (mIsRtOrLb) { //左下及右上
            leftX = mBezierStart1.x.toInt()
            rightX = (mBezierStart1.x + mTouchToCornerDis / 4).toInt()
            mBackShadowDrawable = mBackShadowDrawableLR
        } else {
            leftX = (mBezierStart1.x - mTouchToCornerDis / 4).toInt()
            rightX = mBezierStart1.x.toInt()
            mBackShadowDrawable = mBackShadowDrawableRL
        }
        canvas.save()
        canvas.clipPath(mPath0, Region.Op.INTERSECT)
        canvas.clipPath(mPath1, Region.Op.INTERSECT)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.rotate(mDegrees, mBezierStart1.x, mBezierStart1.y)
        mBackShadowDrawable.setBounds(
            leftX, mBezierStart1.y.toInt(),
            rightX, (mMaxLength + mBezierStart1.y).toInt()
        ) //左上及右下角的xy坐标值,构成一个矩形
        mBackShadowDrawable.draw(canvas)
        canvas.restore()
    }

    /**
     * 绘制当前页区域（被卷起的区域不绘制，露出底页）。
     * 注意：硬件画布只允许 INTERSECT / DIFFERENCE，剪除用 clipOutPath（对齐 Legado 的
     * Build.VERSION >= O 分支），不能用 Region.Op.XOR。
     */
    private fun drawCurrentPageArea(canvas: Canvas, bitmap: Bitmap?) {
        bitmap ?: return
        mPath0.reset()
        mPath0.moveTo(mBezierStart1.x, mBezierStart1.y)
        mPath0.quadTo(mBezierControl1.x, mBezierControl1.y, mBezierEnd1.x, mBezierEnd1.y)
        mPath0.lineTo(mTouchX, mTouchY)
        mPath0.lineTo(mBezierEnd2.x, mBezierEnd2.y)
        mPath0.quadTo(mBezierControl2.x, mBezierControl2.y, mBezierStart2.x, mBezierStart2.y)
        mPath0.lineTo(mCornerX, mCornerY)
        mPath0.close()

        canvas.save()
        canvas.clipOutPath(mPath0)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.restore()
    }

    /**
     * 计算拖拽点对应的各贝塞尔点（对齐 Legado calcPoints）
     */
    private fun calcPoints() {
        mMiddleX = (mTouchX + mCornerX) / 2
        mMiddleY = (mTouchY + mCornerY) / 2
        // 防止 mTouchX == mCornerX 时 mMiddleX == mCornerX 导致分母为 0
        val dxCorner = mCornerX - mMiddleX
        mBezierControl1.x = if (dxCorner == 0f) mMiddleX
            else mMiddleX - (mCornerY - mMiddleY) * (mCornerY - mMiddleY) / dxCorner
        mBezierControl1.y = mCornerY
        mBezierControl2.x = mCornerX

        val f4 = mCornerY - mMiddleY
        if (f4 == 0f) {
            mBezierControl2.y = mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) / 0.1f
        } else {
            mBezierControl2.y =
                mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) / (mCornerY - mMiddleY)
        }
        mBezierStart1.x = mBezierControl1.x - (mCornerX - mBezierControl1.x) / 2
        mBezierStart1.y = mCornerY

        // 固定左边上下两个点
        if (mTouchX > 0 && mTouchX < viewWidth) {
            if (mBezierStart1.x < 0 || mBezierStart1.x > viewWidth) {
                if (mBezierStart1.x < 0)
                    mBezierStart1.x = viewWidth - mBezierStart1.x

                val f1 = abs(mCornerX - mTouchX)
                val f2 = viewWidth * f1 / mBezierStart1.x
                mTouchX = abs(mCornerX - f2)

                val f3 = abs(mCornerX - mTouchX) * abs(mCornerY - mTouchY) / f1
                mTouchY = abs(mCornerY - f3)

                mMiddleX = (mTouchX + mCornerX) / 2
                mMiddleY = (mTouchY + mCornerY) / 2

                mBezierControl1.x =
                    if (mCornerX == mMiddleX) mMiddleX
                    else mMiddleX - (mCornerY - mMiddleY) * (mCornerY - mMiddleY) / (mCornerX - mMiddleX)
                mBezierControl1.y = mCornerY

                mBezierControl2.x = mCornerX

                val f5 = mCornerY - mMiddleY
                if (f5 == 0f) {
                    mBezierControl2.y =
                        mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) / 0.1f
                } else {
                    mBezierControl2.y =
                        mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) / (mCornerY - mMiddleY)
                }

                mBezierStart1.x = mBezierControl1.x - (mCornerX - mBezierControl1.x) / 2
            }
        }
        mBezierStart2.x = mCornerX
        mBezierStart2.y = mBezierControl2.y - (mCornerY - mBezierControl2.y) / 2

        mTouchToCornerDis = hypot(
            (mTouchX - mCornerX).toDouble(),
            (mTouchY - mCornerY).toDouble()
        ).toFloat()

        mBezierEnd1 = getCross(
            PointF(mTouchX, mTouchY), mBezierControl1, mBezierStart1,
            mBezierStart2
        )
        mBezierEnd2 = getCross(
            PointF(mTouchX, mTouchY), mBezierControl2, mBezierStart1,
            mBezierStart2
        )

        mBezierVertex1.x = (mBezierStart1.x + 2 * mBezierControl1.x + mBezierEnd1.x) / 4
        mBezierVertex1.y = (2 * mBezierControl1.y + mBezierStart1.y + mBezierEnd1.y) / 4
        mBezierVertex2.x = (mBezierStart2.x + 2 * mBezierControl2.x + mBezierEnd2.x) / 4
        mBezierVertex2.y = (2 * mBezierControl2.y + mBezierStart2.y + mBezierEnd2.y) / 4
    }

/**
     * 求解直线P1P2和直线P3P4的交点坐标（带除零保护）。
     * - 垂直线（dx==0）：单独处理，避免 Infinity
     * - 平行线（a1==a2）：无交点，返回四点中心，避免 NaN
     */
    private fun getCross(P1: PointF, P2: PointF, P3: PointF, P4: PointF): PointF {
        val crossP = PointF()
        val dx1 = P2.x - P1.x
        val dy1 = P2.y - P1.y
        val dx2 = P4.x - P3.x
        val dy2 = P4.y - P3.y

        // 两条都是垂直线：平行无交点，取 X 中点
        if (dx1 == 0f && dx2 == 0f) {
            crossP.x = P1.x
            crossP.y = (P1.y + P2.y + P3.y + P4.y) / 4f
            return crossP
        }
        // P1P2 垂直：x 固定，代入 P3P4 方程求 y
        if (dx1 == 0f) {
            crossP.x = P1.x
            val a2 = dy2 / dx2
            val b2 = P3.y - a2 * P3.x
            crossP.y = a2 * crossP.x + b2
            return crossP
        }
        // P3P4 垂直：x 固定，代入 P1P2 方程求 y
        if (dx2 == 0f) {
            crossP.x = P3.x
            val a1 = dy1 / dx1
            val b1 = P1.y - a1 * P1.x
            crossP.y = a1 * crossP.x + b1
            return crossP
        }

        // 一般情况：y = ax + b
        val a1 = dy1 / dx1
        val b1 = P1.y - a1 * P1.x
        val a2 = dy2 / dx2
        val b2 = P3.y - a2 * P3.x

        // 平行线：无交点，取四点中心，避免 NaN
        if (a1 == a2) {
            crossP.x = (P1.x + P2.x + P3.x + P4.x) / 4f
            crossP.y = (P1.y + P2.y + P3.y + P4.y) / 4f
            return crossP
        }

        crossP.x = (b2 - b1) / (a1 - a2)
        crossP.y = a1 * crossP.x + b1
        return crossP
    }
}
