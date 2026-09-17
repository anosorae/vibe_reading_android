package com.vibereading.app.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.vibereading.app.ui.theme.VibeColors
import com.vibereading.app.ui.theme.VibeDarkColors
import kotlin.math.pow

/**
 * 阅读器语义色板：把「isDark 亮/暗」三元集中一处，正文/标题/气泡/弹窗各处共用，
 * 避免同一种颜色在多处各自硬编码导致亮暗不一致。
 */
data class ReaderPalette(
    val bodyText: Color,        // 正文文字（暗=米白0.9，亮=炭黑）——分页/滚动/双语英文/卷页位图正文
    val titleText: Color,       // 章节标题（暗=米白0.9，亮=墨黑）
    val bubble: Color,           // 中文气泡半透明色块
    val popupBg: Color,         // 原文弹窗背景
    val cnText: Color,          // 弹窗中文原文 / 弱化提示文字
    val popupBorder: Color,     // 弹窗左侧描边
    val selectionHighlight: Color, // 长按选词高亮背景（与气泡同色系，仅背景不影响排版）
    val handleColor: Color,     // 选择手柄颜色（竖线+圆点，跟随主题强调色）
    // chrome 强调色（底栏按钮/滑块/目录高亮/打开过渡指示器）：取自阅读器自己的色板，
    // **不随全局主题 accent 变化**——切换黛蓝/苔绿等主题时阅读器内部保持稳定
    val accent: Color
) {
    companion object {
        fun of(isDark: Boolean): ReaderPalette = if (isDark) {
            ReaderPalette(
                bodyText = VibeColors.Cream.copy(alpha = 0.9f),
                titleText = VibeColors.Cream.copy(alpha = 0.9f),
                bubble = VibeColors.SiennaLight.copy(alpha = 0.25f),
                popupBg = VibeDarkColors.Surface,
                cnText = VibeColors.Stone,
                popupBorder = VibeColors.Sand.copy(alpha = 0.3f),
                selectionHighlight = VibeColors.SiennaLight.copy(alpha = 0.3f),
                handleColor = VibeDarkColors.Primary,
                accent = VibeDarkColors.Primary
            )
        } else {
            ReaderPalette(
                bodyText = VibeColors.Charcoal,
                titleText = VibeColors.Ink,
                bubble = VibeColors.Sienna.copy(alpha = 0.3f),
                popupBg = VibeColors.Parchment,
                cnText = VibeColors.WarmGray,
                popupBorder = VibeColors.Sand,
                selectionHighlight = VibeColors.Sienna.copy(alpha = 0.2f),
                handleColor = VibeColors.Sienna,
                accent = VibeColors.Sienna
            )
        }
    }
}

/**
 * 阅读器 chrome（顶/底栏、目录抽屉、状态面板）的固定配色：
 * 只跟「阅读背景深浅」（`ReaderBgPresets.isDark` / nightMode 折算后的 isDark）走，
 * **不随全局主题**——阅读器是独立的视觉世界（纸面 + 赭色强调），
 * 外面的主题色切换不该波及阅读页内的任何 chrome 表面。
 */
data class ReaderChromeColors(
    val sheetBg: Color,     // 目录抽屉容器
    val text: Color,        // 主文字（标题/章节名）
    val mutedText: Color,   // 次级文字（章节计数、上一章/下一章）
    val divider: Color,     // 分隔线
    val pillBg: Color,      // 顶栏中英切换胶囊底 / 滑块 inactive 轨道
    val onAccent: Color     // 压在 accent 上的文字
)

fun readerChromeColors(isDark: Boolean): ReaderChromeColors = if (isDark) {
    ReaderChromeColors(
        sheetBg = VibeDarkColors.Surface,
        text = VibeDarkColors.OnSurface,
        mutedText = VibeDarkColors.OnSurfaceVariant,
        divider = VibeDarkColors.OutlineVariant,
        pillBg = VibeDarkColors.SurfaceVariant,
        onAccent = VibeDarkColors.OnPrimary
    )
} else {
    ReaderChromeColors(
        sheetBg = VibeColors.White,
        text = VibeColors.Charcoal,
        mutedText = VibeColors.WarmGray,
        divider = VibeColors.Sand,
        pillBg = VibeColors.Parchment,
        onAccent = VibeColors.White
    )
}

/**
 * 阅读器交互控件的主题强调色变体：同一个主题色在阅读器的**暖米纸面**上比在 App 的
 * 冷白卡片上显深（冷暖对比放大了深色感），向白提一档恢复观感。
 * 底栏图标/标签/滑块与顶栏中英切换的选中胶囊都用这个变体（白字压提亮后的蓝
 * ≈ iOS 白字压系统蓝的观感）。只对「深」强调色生效（亮度 < 0.35）；粉彩亮色
 * （深色主题档的 primary）与深色阅读底保持原值——它们本来就不显深，再提亮反而发灰。
 */
fun readerControlAccent(accent: Color, isDark: Boolean): Color {
    if (isDark) return accent
    val lum = 0.2126f * srgbToLinear(accent.red) +
        0.7152f * srgbToLinear(accent.green) +
        0.0722f * srgbToLinear(accent.blue)
    return if (lum < 0.35f) lerp(accent, Color.White, 0.16f) else accent
}

private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
