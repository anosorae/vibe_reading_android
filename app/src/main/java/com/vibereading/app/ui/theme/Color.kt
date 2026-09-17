package com.vibereading.app.ui.theme

import androidx.compose.ui.graphics.Color

// 全局主题原始色板。
//
// 这里每个取值的**约束条件是 WCAG 对比度**，不是事后补测：正文与「带文字的容器」要求
// ≥4.5:1，描边、状态圆点等非文本图形要求 ≥3:1，分隔线刻意低对比。
// 改动任何一个值后跑 `python tools/check_theme_contrast.py`，它会按下面的约束逐对复核，
// 并检查 Theme.kt 里 4 套 colorScheme 是否把 M3 的角色都填满了（漏填会静默落回
// Material 基线的淡紫调）。
//
// 两个易踩的点：
// 1. 这些 token 同时被阅读器（ReaderPalette / ChapterStatusUi / ReaderChrome）使用，
//    而阅读器的 5 档背景独立于全局主题，所以 `Amber`/`BlueMuted` 这类状态色必须是
//    「亮底暗底都能过 3:1」的中间调 —— 调亮调暗都会让其中一边失效。
// 2. 浅色档的 `White` 是表面阶梯最高层，不是「白色文字」；文字色请用对应的 on* 语义色。

// ── 原木 (Vibe) 浅色 ──

object VibeColors {
    // 表面阶梯：浅色档越高越亮，White 供底部弹窗/对话框这类最高层容器使用
    val White = Color(0xFFFFFFFF)
    val Cream = Color(0xFFFAF7F2)       // background / surface
    val Parchment = Color(0xFFF3EDE4)   // surfaceVariant / surfaceContainer
    val Linen = Color(0xFFEDE7DC)       // surfaceContainerHigh / surfaceDim
    val Sand = Color(0xFFD9D0C3)        // outlineVariant（分隔线，低对比为设计意图）
    val Outline = Color(0xFF8F8577)     // outline：浅底 3.39:1
    val Stone = Color(0xFFB8AFA3)       // 仅深色阅读底上的弱化文字（浅底 2.03:1，勿用于浅底）
    val Charcoal = Color(0xFF2C2825)
    val Ink = Color(0xFF1A1714)
    val WarmGray = Color(0xFF6B635B)    // onSurfaceVariant：浅底 5.52:1

    // 主色（赭）：浅底当文字 5.03:1，白字压其上 5.38:1
    val Sienna = Color(0xFFA65332)
    val SiennaLight = Color(0xFFD4845A)  // 装饰用亮赭（气泡/选词底色），不承担文字对比
    val SiennaContainer = Color(0xFFF7E3D8)
    val OnSiennaContainer = Color(0xFF5C2A13)

    // 次色（苔绿）：浅底当文字 4.97:1，白字 5.32:1
    val Sage = Color(0xFF56725A)
    val SageLight = Color(0xFFE8F0E4)
    val OnSageLight = Color(0xFF2F4634)

    // 提醒色（琥珀）：亮/暗阅读底都 ≥3.2:1，所以配深色字而非白字。
    // 注意：它是**图形用**中间调，不能当 `tertiary` 的文字色 —— 白字或深字压其上
    // 都只有 3.4:1 左右（正在用 `Tertiary`）。
    val Amber = Color(0xFFA17D2F)
    val AmberLight = Color(0xFFF5EAD2)
    val OnAmberLight = Color(0xFF4E3A0D)

    // 第三色（深琥珀）：浅底当文字 4.58:1，白字压其上 4.90:1。青简浅色档共用。
    val Tertiary = Color(0xFF8E6B2C)
    val OnTertiary = Color(0xFFFFFFFF)

    // 危险色：浅底当文字 4.75:1，白字 5.07:1，暗阅读底 3.48:1
    val RedMuted = Color(0xFFB74D3C)
    val RedContainer = Color(0xFFF9DDD8)
    val OnRedContainer = Color(0xFF5C1F16)

    // 进行中（蓝灰）：亮/暗阅读底都 ≥3.5:1
    val BlueMuted = Color(0xFF5B7FA8)
}

// ── 青简 (Weread) 浅色 ──
// 冷调米底 + 松青主色。次色与提醒色是青简自有的：原先直接借用原木的苔绿/琥珀，
// 在薄荷青主色旁边几乎读不出差异。

// 黛蓝 (Indigo) 浅色 —— 默认档。
//
// 取值对齐全局设计规范（Modern Minimal + iOS-inspired + Soft Card UI）：
// 背景 #F6F9FC、卡片纯白、主文字 #101828、次级文字 #667A99、分隔线 #E7EDF4、主色 #0A84FF。
// 两处刻意偏离规范原值（本文件的对比度约束是正文 ≥4.5:1）：
// 1. `Accent`：规范蓝 #0A84FF 当文字只有 3.45:1，取同色相加深的 #0A6FDE（压 #F6F9FC 4.58:1）
//    —— 肉眼仍是「系统蓝」，但当 `primary` 被当正文/选中态文字用时达标（先例：#1091D2 → #08759F）；
// 2. `WarmGray`：规范次级文字 #667A99 压白卡只有 4.37:1，取 #5C6F8F（白卡 5.09:1），
//    色相同为蓝灰，仅加深一档。
object IndigoColors {
    val White = Color(0xFFFFFFFF)
    val Cream = Color(0xFFF6F9FC)       // background / surface：规范页面底色（浅灰蓝）
    val SurfaceVariant = Color(0xFFEFF3F9)
    val ContainerLow = Color(0xFFFFFFFF)      // 卡片 / 悬浮底栏：规范纯白卡片
    val ContainerHigh = Color(0xFFEEF3FA)     // 圆钮 / 进度槽 / 状态徽标底
    val ContainerHighest = Color(0xFFE7EDF4)
    val Sand = Color(0xFFE7EDF4)        // outlineVariant（分隔线，规范值，低对比为设计意图）
    val Outline = Color(0xFF667A99)     // outline：规范次级文字值复用，浅底 4.14:1（图形门槛 3:1）
    val Charcoal = Color(0xFF101828)         // 主文字：规范值
    val WarmGray = Color(0xFF5C6F8F)
    val Accent = Color(0xFF0A6FDE)
    val AccentLight = Color(0xFF5EB2FF)
    val AccentContainer = Color(0xFFE8F2FF)  // 底栏选中胶囊底（淡蓝）
    val OnAccentContainer = Color(0xFF0B3B66)
    val Secondary = Color(0xFF4C6E92)
    val SecondaryContainer = Color(0xFFE3EDF6)
    val OnSecondaryContainer = Color(0xFF1B3A55)
    val Tertiary = Color(0xFF3E7C4F)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFE4F0E7)
    val OnTertiaryContainer = Color(0xFF1C4227)
    val RedMuted = Color(0xFFB54842)
    val RedContainer = Color(0xFFF9DEDB)
    val OnRedContainer = Color(0xFF5E201C)
}

/**
 * 全局功能色（低饱和绿/橙）：统计页数字与图标、设置页分区图标等 chrome 场景使用。
 * 约束：压白卡当文字 ≥4.5:1（绿 5.00:1 / 橙 4.91:1），淡色档只作图标圆底，不承担文字对比。
 */
object FunctionalColors {
    val Green = Color(0xFF3E7C4F)
    val GreenLight = Color(0xFFE4F0E7)
    val Orange = Color(0xFFA85E1A)
    val OrangeLight = Color(0xFFF7ECDF)
}

object MossColors {
    val White = Color(0xFFFFFFFF)
    val Cream = Color(0xFFF3F8F4)
    val SurfaceVariant = Color(0xFFE2EFE6)
    val ContainerLow = Color(0xFFF8FCF9)
    val ContainerHigh = Color(0xFFE8F3EB)
    val ContainerHighest = Color(0xFFD6E8DA)
    val Sand = Color(0xFFC1D4C5)
    val Outline = Color(0xFF617866)
    val Charcoal = Color(0xFF162D20)
    val WarmGray = Color(0xFF526B5A)
    val Accent = Color(0xFF247963)
    val AccentLight = Color(0xFF7CC8AC)
    val AccentContainer = Color(0xFFD9F0E6)
    val OnAccentContainer = Color(0xFF0E4435)
    val Secondary = Color(0xFF557965)
    val SecondaryContainer = Color(0xFFDDEBE1)
    val OnSecondaryContainer = Color(0xFF203E2B)
    val Tertiary = Color(0xFF7A6540)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFF3E8D2)
    val OnTertiaryContainer = Color(0xFF493716)
    val RedMuted = Color(0xFFB54842)
    val RedContainer = Color(0xFFF9DEDB)
    val OnRedContainer = Color(0xFF5E201C)
}

object LotusColors {
    val White = Color(0xFFFFFFFF)
    val Cream = Color(0xFFFBF6FA)
    val SurfaceVariant = Color(0xFFF1E7F0)
    val ContainerLow = Color(0xFFFFFAFD)
    val ContainerHigh = Color(0xFFF7EDF5)
    val ContainerHighest = Color(0xFFEBDCE8)
    val Sand = Color(0xFFD7C4D3)
    val Outline = Color(0xFF806A7B)
    val Charcoal = Color(0xFF30222D)
    val WarmGray = Color(0xFF705C6D)
    val Accent = Color(0xFF98518A)
    val AccentLight = Color(0xFFD68CC5)
    val AccentContainer = Color(0xFFF4DCEE)
    val OnAccentContainer = Color(0xFF4C1743)
    val Secondary = Color(0xFF7D6179)
    val SecondaryContainer = Color(0xFFEDE0EB)
    val OnSecondaryContainer = Color(0xFF422D3F)
    val Tertiary = Color(0xFF95613F)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFF8E4D7)
    val OnTertiaryContainer = Color(0xFF4C2815)
    val RedMuted = Color(0xFFB54842)
    val RedContainer = Color(0xFFF9DEDB)
    val OnRedContainer = Color(0xFF5E201C)
}

object InkColors {
    val White = Color(0xFFFFFFFF)
    val Cream = Color(0xFFF4F5F5)
    val SurfaceVariant = Color(0xFFE5E8E9)
    val ContainerLow = Color(0xFFF9FAFA)
    val ContainerHigh = Color(0xFFEDF0F1)
    val ContainerHighest = Color(0xFFDDE2E4)
    val Sand = Color(0xFFC2CBCE)
    val Outline = Color(0xFF627177)
    val Charcoal = Color(0xFF17242A)
    val WarmGray = Color(0xFF526169)
    val Accent = Color(0xFF3F6878)
    val AccentLight = Color(0xFF89B4C2)
    val AccentContainer = Color(0xFFD9EBF0)
    val OnAccentContainer = Color(0xFF173B46)
    val Secondary = Color(0xFF5A737B)
    val SecondaryContainer = Color(0xFFDDE9EC)
    val OnSecondaryContainer = Color(0xFF213D43)
    val Tertiary = Color(0xFF7B654C)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFF2E7D9)
    val OnTertiaryContainer = Color(0xFF44311D)
    val RedMuted = Color(0xFFB54842)
    val RedContainer = Color(0xFFF9DEDB)
    val OnRedContainer = Color(0xFF5E201C)
}

object WereadColors {
    val White = Color(0xFFFFFFFF)
    val Cream = Color(0xFFF3F2EC)
    val SurfaceVariant = Color(0xFFEAE8DF)
    val ContainerLow = Color(0xFFF0EFE9)
    val ContainerHigh = Color(0xFFE4E2D8)
    val ContainerHighest = Color(0xFFDEDCD2)
    val Sand = Color(0xFFD5D3CA)        // outlineVariant
    val Outline = Color(0xFF8A8478)     // outline：浅底 3.31:1
    val Charcoal = Color(0xFF2C2825)    // 与冷底共用同一炭黑，保证正文字重一致
    val WarmGray = Color(0xFF63615A)    // onSurfaceVariant：5.05:1

    // 主色（松青）：浅底当文字 4.65:1，白字 5.21:1
    val Accent = Color(0xFF2D7964)
    val AccentLight = Color(0xFF5BBFA0)  // 装饰用亮青，不承担文字对比
    val AccentContainer = Color(0xFFDBEDE6)
    val OnAccentContainer = Color(0xFF10453A)

    // 次色（雾松）：浅底 5.03:1
    val Secondary = Color(0xFF4E6E60)
    val SecondaryContainer = Color(0xFFE0EAE4)
    val OnSecondaryContainer = Color(0xFF2A4638)
}

// ── 深色系（对齐 Legado values-night 的亮色调整）──
// 深色档表面阶梯与浅色相反：越高越亮。

// 原木深色：暖黑底 + 米色文字 + 提亮过的赭色
object VibeDarkColors {
    val White = Color(0xFFFFFFFF)
    val ContainerLowest = Color(0xFF141210)
    val Background = Color(0xFF1B1815)
    val ContainerLow = Color(0xFF201D1A)
    val Surface = Color(0xFF221F1B)
    val Container = Color(0xFF26221E)
    val SurfaceVariant = Color(0xFF2C2823)
    val ContainerHigh = Color(0xFF322E28)
    val ContainerHighest = Color(0xFF3D3832)
    val OnBackground = Color(0xFFEDE7DC)
    val OnSurface = Color(0xFFEDE7DC)
    val OnSurfaceVariant = Color(0xFFB3AA9E)
    val Outline = Color(0xFF746C63)       // 暗底 3.42:1
    val OutlineVariant = Color(0xFF4A443D) // 分隔线
    val Primary = Color(0xFFE0926A)
    val OnPrimary = Color(0xFF3A1D0B)
    val PrimaryContainer = Color(0xFF6B3A1E)
    val OnPrimaryContainer = Color(0xFFF4D8C6)
    val Secondary = Color(0xFFA8C6AC)
    val OnSecondary = Color(0xFF16301C)
    val SecondaryContainer = Color(0xFF3A5540)
    val OnSecondaryContainer = Color(0xFFCBE3CE)
    val Tertiary = Color(0xFFE3C078)
    val OnTertiary = Color(0xFF3F2E08)
    val TertiaryContainer = Color(0xFF5C4614)
    val OnTertiaryContainer = Color(0xFFF5E3BE)
    val Error = Color(0xFFF2A392)
    val OnError = Color(0xFF5C1F16)
    val ErrorContainer = Color(0xFF5A2A22)
    val OnErrorContainer = Color(0xFFFFDAD4)
    val InverseSurface = Color(0xFFEDE7DC)
    val InverseOnSurface = Color(0xFF1B1815)
}

// 青简深色：冷黑底 + 薄荷绿主色
object WereadDarkColors {
    val White = Color(0xFFFFFFFF)
    val ContainerLowest = Color(0xFF0F1311)
    val Background = Color(0xFF141917)
    val ContainerLow = Color(0xFF1E2623)
    val Surface = Color(0xFF1A211E)
    val Container = Color(0xFF232B28)
    val SurfaceVariant = Color(0xFF232B28)
    val ContainerHigh = Color(0xFF2E3733)
    val ContainerHighest = Color(0xFF39433E)
    val OnBackground = Color(0xFFE6ECE9)
    val OnSurface = Color(0xFFE6ECE9)
    val OnSurfaceVariant = Color(0xFFA8B4AF)
    val Outline = Color(0xFF74807A)        // 暗底 3.7:1
    val OutlineVariant = Color(0xFF3F4A45) // 分隔线
    val Primary = Color(0xFF5CC5A2)
    val OnPrimary = Color(0xFF06312A)
    val PrimaryContainer = Color(0xFF1E5C49)
    val OnPrimaryContainer = Color(0xFFC9EEE0)
    val Secondary = Color(0xFF8FC0AE)
    val OnSecondary = Color(0xFF0F2E25)
    val SecondaryContainer = Color(0xFF2A4C40)
    val OnSecondaryContainer = Color(0xFFC3E4D7)
    val Tertiary = Color(0xFFE0BE79)
    val OnTertiary = Color(0xFF3D2E08)
    val TertiaryContainer = Color(0xFF544113)
    val OnTertiaryContainer = Color(0xFFF2E0BB)
    val Error = Color(0xFFF2A392)
    val OnError = Color(0xFF5C1F16)
    val ErrorContainer = Color(0xFF5A2A22)
    val OnErrorContainer = Color(0xFFFFDAD4)
    val InverseSurface = Color(0xFFE6ECE9)
    val InverseOnSurface = Color(0xFF141917)
}

/**
 * 章节状态色的**深色档**：阅读器的 5 档背景里有一档是深色（DarkNight），全局主题的深色档
 * 也一样，同一个中间调不可能既在浅底当正文（≥4.5:1）又在深底当图形（≥3:1）——
 * 两个亮度区间不相交，所以状态色必须拆亮/暗两套。浅色档直接用 [VibeColors] 的语义色。
 */
object ChapterStatusDarkColors {
    val Done = Color(0xFF9DBFA3)
    val InProgress = Color(0xFF93B4D6)
    val Failed = Color(0xFFE09A8C)
    val TooLong = Color(0xFFD3B265)
    val Pending = Color(0xFF8A8177)
    val TextMuted = Color(0xFFA79E94)
}

// ── Reader background presets ──
object ReaderBgPresets {
    val WarmCream = Color(0xFFFAF7F2)
    val DarkCream = Color(0xFFF5F0E8)
    val GreenTint = Color(0xFFF0F4EE)
    val GrayCream = Color(0xFFEEECE8)
    val DarkNight = Color(0xFF2C2825)

    /** 阅读背景档位，**顺序即 `ReadingSettings.bgColorIndex` 的取值语义**：只能追加在末尾，不得插入或重排。 */
    val all: List<Color> = listOf(WarmCream, DarkCream, GreenTint, GrayCream, DarkNight)

    /** 深色档位下标（顶栏/底栏/浮层据此取深色或浅色语义色）。 */
    private val darkIndices: Set<Int> = setOf(all.indexOf(DarkNight))

    fun isDark(index: Int): Boolean = index in darkIndices
}
