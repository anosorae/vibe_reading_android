package com.vibereading.app.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.vibereading.app.R
import com.vibereading.app.ui.theme.LocalIsDarkTheme
import kotlin.math.min

/**
 * 书架背景插画：固定在屏幕底部、通栏铺满，书卡从上面滚过去。
 *
 * 它是**背景**而不是列表末尾的一张图：资产宽高比 1.87 很扁，画面上半是墙面与窗光、
 * 下半是桌面上一本摊开的书和一枝植物，所以贴底铺满时构图完整（410dp 屏上 219dp 高，
 * 6 本书两行时插画完全不被遮挡）。**不要改成 `ContentScale.Crop` 铺满整屏**：按屏高缩放
 * 后横向只剩中间约 24%，书和植物都在右侧，会被裁光。
 *
 * 浅色模式按**当前主题的页面底色**实时派生 duotone 单色调和（[backdropDuotoneMatrix]）：
 * 位图灰度化后映射到「背景色相暗端 → 白」的渐变，插画永远与页面同色系。一份位图跟随
 * 全部主题——原资产烧死的蓝调水彩只和默认黛蓝底搭，换暖底就发脏，色相必须在渲染期派生。
 *
 * 深色模式不画：这是一整幅浅色水彩，套在深底上只会变成一块发灰的雾。引文文字已随
 * 页脚一起移除（用户要求），所以深色档就是干净的页面底色。
 */
@Composable
internal fun ShelfBackdrop(modifier: Modifier = Modifier) {
    if (LocalIsDarkTheme.current) return
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val height = backdropHeight(maxWidth)
        Image(
            painter = painterResource(R.drawable.shelf_backdrop),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(height),
            // Crop + 底部对齐：正常比例下正好 1:1 不裁；被 heightIn 夹住时（横屏/矮屏）
            // 裁掉的是上方的墙面，保住画面下半的书和植物
            contentScale = ContentScale.Crop,
            alignment = Alignment.BottomCenter,
            colorFilter = ColorFilter.colorMatrix(
                backdropDuotoneMatrix(MaterialTheme.colorScheme.background)
            )
        )
    }
}

// duotone 调和参数（2026-09 定稿，与设计模拟图对齐）：暗端只借背景的色相，
// 饱和度封顶、亮度压到 0.72 —— 高亮底色（如 #F6F9FC）自己饱和度极低但 HSL 值不小，
// 不封顶的话暗端会过艳；不压亮度的话暗端贴着白，插画就消失了。
private const val BackdropTintMaxSaturation = 0.35f
private const val BackdropTintLightness = 0.72f

/**
 * 把页面底色派生成 duotone 渐变的 ColorMatrix：位图按 601 亮度灰度化，
 * 亮度 0 映射到「背景色相的暗端」、255 映射到白，alpha 原样保留（上下缘渐隐靠它）。
 */
private fun backdropDuotoneMatrix(background: Color): ColorMatrix {
    val hsl = background.toHslComponents()
    val tint = Color.hsl(
        hue = hsl[0] * 360f,
        saturation = min(hsl[1], BackdropTintMaxSaturation),
        lightness = BackdropTintLightness
    )
    // 601 亮度权重，与定稿时的设计模拟（PIL convert("L")）同一口径
    val luminance = floatArrayOf(0.299f, 0.587f, 0.114f)
    val tintRgb = floatArrayOf(tint.red * 255f, tint.green * 255f, tint.blue * 255f)
    return ColorMatrix(
        FloatArray(20).also { m ->
            for (row in 0..2) {
                // 亮度 L → tint + (白 - tint) * L/255，展开成 ColorMatrix 的行
                val scale = (255f - tintRgb[row]) / 255f
                for (col in 0..2) m[row * 5 + col] = luminance[col] * scale
                m[row * 5 + 4] = tintRgb[row]
            }
            m[18] = 1f
        }
    )
}

/** 标准 RGB→HSL，三个分量都归一到 0..1。 */
private fun Color.toHslComponents(): FloatArray {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2f
    if (max == min) return floatArrayOf(0f, 0f, lightness)
    val delta = max - min
    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val hue = when (max) {
        red -> (green - blue) / delta + if (green < blue) 6f else 0f
        green -> (blue - red) / delta + 2f
        else -> (red - green) / delta + 4f
    } / 6f
    return floatArrayOf(hue, saturation, lightness)
}
