package com.vibereading.app.ui.reader

import androidx.compose.ui.graphics.Color
import com.vibereading.app.ui.theme.VibeColors
import com.vibereading.app.ui.theme.VibeDarkColors

/**
 * 阅读器语义色板：把「isDark 亮/暗」三元集中一处，正文/标题/气泡/弹窗各处共用，
 * 避免同一种颜色在多处各自硬编码导致亮暗不一致。
 */
data class ReaderPalette(
    val bodyText: Color,        // 正文文字（暗=米白0.9，亮=炭黑）——分页/滚动/双语英文/卷页位图正文
    val titleText: Color,       // 章节标题（暗=米白0.9，亮=墨黑）
    val sourceBubble: Color,    // 原文气泡半透明色块
    val popupBg: Color,         // 原文弹窗背景
    val cnText: Color,          // 弹窗中文原文 / 弱化提示文字
    val popupBorder: Color,     // 弹窗左侧描边
    val selectionHighlight: Color, // 长按选词高亮背景（与气泡同色系，仅背景不影响排版）
    val handleColor: Color      // 选择手柄颜色（竖线+圆点，跟随主题强调色）
) {
    companion object {
        fun of(isDark: Boolean): ReaderPalette = if (isDark) {
            ReaderPalette(
                bodyText = VibeColors.Cream.copy(alpha = 0.9f),
                titleText = VibeColors.Cream.copy(alpha = 0.9f),
                sourceBubble = VibeColors.SiennaLight.copy(alpha = 0.25f),
                popupBg = VibeDarkColors.Surface,
                cnText = VibeColors.Stone,
                popupBorder = VibeColors.Sand.copy(alpha = 0.3f),
                selectionHighlight = VibeColors.SiennaLight.copy(alpha = 0.3f),
                handleColor = VibeDarkColors.Primary
            )
        } else {
            ReaderPalette(
                bodyText = VibeColors.Charcoal,
                titleText = VibeColors.Ink,
                sourceBubble = VibeColors.Sienna.copy(alpha = 0.3f),
                popupBg = VibeColors.Parchment,
                cnText = VibeColors.WarmGray,
                popupBorder = VibeColors.Sand,
                selectionHighlight = VibeColors.Sienna.copy(alpha = 0.2f),
                handleColor = VibeColors.Sienna
            )
        }
    }
}
