package com.vibereading.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── 原木 (Vibe) 浅色系 ──
object VibeColors {
    val Cream = Color(0xFFFAF7F2)
    val Parchment = Color(0xFFF3EDE4)
    val Linen = Color(0xFFEDE7DC)
    val Sand = Color(0xFFD9D0C3)
    val Stone = Color(0xFFB8AFA3)
    val Charcoal = Color(0xFF2C2825)
    val Ink = Color(0xFF1A1714)
    val WarmGray = Color(0xFF6B635B)
    val Sienna = Color(0xFFB85C38)
    val SiennaLight = Color(0xFFD4845A)
    val Sage = Color(0xFF6B8F71)
    val SageLight = Color(0xFFE8F0E4)
    val Amber = Color(0xFFC69B3E)
    val RedMuted = Color(0xFFC45B4A)
    val BlueMuted = Color(0xFF5B7FA8)
}

// ── 青简 (Weread) 浅色系 ──
object WereadColors {
    val Cream = Color(0xFFF3F2EC)
    val Accent = Color(0xFF3A9B80)       // replaces Sienna
    val AccentLight = Color(0xFF5BBFA0)  // replaces SiennaLight
    val Linen = Color(0xFFD9D0C3)
    val Sand = Color(0xFFC5BDB0)
}

// ── 深色系（对齐 Legado values-night 的亮色调整）──

// 原木深色：暖黑底 + 米色文字 + 提亮过的赭色
object VibeDarkColors {
    val Background = Color(0xFF1B1815)
    val Surface = Color(0xFF221F1B)
    val SurfaceVariant = Color(0xFF2C2823)
    val OnBackground = Color(0xFFEDE7DC)
    val OnSurface = Color(0xFFEDE7DC)
    val OnSurfaceVariant = Color(0xFFB3AA9E)
    val Outline = Color(0xFF57504A)
    val OutlineVariant = Color(0xFF3E3933)
    val Primary = Color(0xFFE0926A)
    val OnPrimary = Color(0xFF3A1D0B)
    val PrimaryContainer = Color(0xFF6B3A1E)
    val OnPrimaryContainer = Color(0xFFF4D8C6)
    val Secondary = Color(0xFFA8C6AC)
    val OnSecondary = Color(0xFF16301C)
    val SecondaryContainer = Color(0xFF3A5540)
    val OnSecondaryContainer = Color(0xFFCBE3CE)
    val InverseSurface = Color(0xFFEDE7DC)
    val InverseOnSurface = Color(0xFF1B1815)
}

// 青简深色：冷黑底 + 薄荷绿主色
object WereadDarkColors {
    val Background = Color(0xFF141917)
    val Surface = Color(0xFF1A211E)
    val SurfaceVariant = Color(0xFF232B28)
    val OnBackground = Color(0xFFE6ECE9)
    val OnSurface = Color(0xFFE6ECE9)
    val OnSurfaceVariant = Color(0xFFA8B4AF)
    val Outline = Color(0xFF4E5B56)
    val OutlineVariant = Color(0xFF37423E)
    val Primary = Color(0xFF4FB896)
    val OnPrimary = Color(0xFF063125)
    val PrimaryContainer = Color(0xFF1E5C49)
    val OnPrimaryContainer = Color(0xFFC9EEE0)
    val Secondary = Color(0xFFA8C6AC)
    val OnSecondary = Color(0xFF16301C)
    val SecondaryContainer = Color(0xFF3A5540)
    val OnSecondaryContainer = Color(0xFFCBE3CE)
    val InverseSurface = Color(0xFFE6ECE9)
    val InverseOnSurface = Color(0xFF141917)
}

// ── Reader background presets ──
object ReaderBgPresets {
    val WarmCream = Color(0xFFFAF7F2)
    val DarkCream = Color(0xFFF5F0E8)
    val GreenTint = Color(0xFFF0F4EE)
    val GrayCream = Color(0xFFEEECE8)
    val DarkNight = Color(0xFF2C2825)
}
