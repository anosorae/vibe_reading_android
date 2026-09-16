package com.vibereading.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.ThemeMode
import com.vibereading.app.domain.model.ThemeSettings

// ── 稳定系统栏 insets（沉浸式切换时不归零，防止非阅读页布局跳动） ──
// 阅读器隐藏系统栏后 WindowInsets.systemBars 归零，但非阅读页的 Scaffold 需要稳定的参考尺寸；
// 此 CompositionLocal 由 VibeReadingTheme 内的 StableSystemBarInsetsProvider 提供，
// 缓存系统栏最大尺寸（只增不减），确保从阅读器返回时布局不跳动。
val LocalStableSystemBarInsets = compositionLocalOf { WindowInsets(0) }

/**
 * 当前主题解析后的深浅（`themeMode` 已折算成系统/浅色/深色），**不是** `isSystemInDarkTheme()`。
 * 需要按「自己这块表面是深是浅」挑色时用它（例：目录抽屉是 Material 表面，
 * 取主题深浅；阅读器正文背景的深浅要用 `ReaderBgPresets.isDark`）。
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun StableSystemBarInsetsProvider(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val statusBarPx = WindowInsets.systemBars.getTop(density)
    val navBarPx = WindowInsets.systemBars.getBottom(density)
    val cachedTop = remember { mutableIntStateOf(statusBarPx) }
    val cachedBottom = remember { mutableIntStateOf(navBarPx) }
    if (statusBarPx > cachedTop.intValue) cachedTop.intValue = statusBarPx
    if (navBarPx > cachedBottom.intValue) cachedBottom.intValue = navBarPx
    val stableInsets = WindowInsets(
        top = with(density) { maxOf(statusBarPx, cachedTop.intValue).toDp() },
        bottom = with(density) { maxOf(navBarPx, cachedBottom.intValue).toDp() }
    )
    CompositionLocalProvider(LocalStableSystemBarInsets provides stableInsets) {
        content()
    }
}

/**
 * 全局主题（对齐 Legado themeMode / ThemeConfig）：
 * themeMode = 跟随系统 / 浅色 / 深色；accent = 原木 / 青简。
 * 阅读器页面的背景色/文字色由 ReadingSettings + ReaderBgPresets 独立控制，不依赖本主题。
 *
 * 4 套 colorScheme 必须把 M3 的色彩角色**全部**显式填上：未填的角色会静默落回
 * `lightColorScheme()`/`darkColorScheme()` 的 Material 基线值（淡紫调），而底部弹窗、
 * 对话框、下拉菜单、`surfaceTint` 恰好都读这些角色 —— 只填常用角色会得到「暖米底上
 * 浮出一层淡紫弹窗」。`python tools/check_theme_contrast.py` 会检查是否有遗漏。
 */
@Composable
fun VibeReadingTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val settings by settingsRepo.theme.settings.collectAsState(initial = ThemeSettings())

    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    val colorScheme = when (settings.accent) {
        AppAccent.VIBE -> if (dark) vibeDarkColorScheme() else vibeColorScheme()
        AppAccent.WEREAD -> if (dark) wereadDarkColorScheme() else wereadColorScheme()
    }

    // 状态栏/导航栏的明暗图标跟随主题（阅读器 SideEffect 执行顺序在 Theme 之后，可覆盖）。
    // 栏底色只在 API 35 以下生效：API 35 起 targetSdk 35 的应用被强制边到边，
    // statusBarColor/navigationBarColor 已被系统忽略，此时由各页面自己铺满背景。
    val view = LocalView.current
    val activity = view.context as? Activity
    SideEffect {
        activity?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            if (android.os.Build.VERSION.SDK_INT < 35) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
            }
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    StableSystemBarInsetsProvider {
        CompositionLocalProvider(LocalIsDarkTheme provides dark) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = AppTypography,
                shapes = AppShapes,
                content = content
            )
        }
    }
}

/**
 * 全局排版：沿用 M3 默认字阶，只把**正文类角色**的行高放松一些。
 * M3 默认行高按拉丁文设计（bodyMedium 14/20 ≈ 1.43），中文字面填满 em 框，
 * 同样的行高在中英混排段落里偏挤。不调整字号，避免影响各处既有版式。
 */
val AppTypography: Typography = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(lineHeight = 26.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 22.sp),
        bodySmall = bodySmall.copy(lineHeight = 18.sp)
    )
}

/** 圆角刻度：本轮钮 4 / 小控件 8 / 卡片与输入框 12 / 底部弹窗与图片块 16。 */
val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

// ── 原木 (Vibe) ──

private fun vibeColorScheme() = lightColorScheme(
    primary = VibeColors.Sienna,
    onPrimary = VibeColors.White,
    primaryContainer = VibeColors.SiennaContainer,
    onPrimaryContainer = VibeColors.OnSiennaContainer,
    inversePrimary = VibeDarkColors.Primary,
    secondary = VibeColors.Sage,
    onSecondary = VibeColors.White,
    secondaryContainer = VibeColors.SageLight,
    onSecondaryContainer = VibeColors.OnSageLight,
    tertiary = VibeColors.Tertiary,
    onTertiary = VibeColors.OnTertiary,
    tertiaryContainer = VibeColors.AmberLight,
    onTertiaryContainer = VibeColors.OnAmberLight,
    error = VibeColors.RedMuted,
    onError = VibeColors.White,
    errorContainer = VibeColors.RedContainer,
    onErrorContainer = VibeColors.OnRedContainer,
    background = VibeColors.Cream,
    onBackground = VibeColors.Charcoal,
    surface = VibeColors.Cream,
    onSurface = VibeColors.Charcoal,
    surfaceVariant = VibeColors.Parchment,
    onSurfaceVariant = VibeColors.WarmGray,
    surfaceTint = VibeColors.Sienna,
    surfaceDim = VibeColors.Linen,
    surfaceBright = VibeColors.White,
    surfaceContainerLowest = VibeColors.White,
    surfaceContainerLow = Color(0xFFF7F3EB),
    surfaceContainer = VibeColors.Parchment,
    surfaceContainerHigh = VibeColors.Linen,
    surfaceContainerHighest = Color(0xFFEAE3D8),
    outline = VibeColors.Outline,
    outlineVariant = VibeColors.Sand,
    scrim = Color.Black,
    inverseSurface = VibeColors.Ink,
    inverseOnSurface = VibeColors.Cream
)

private fun vibeDarkColorScheme() = darkColorScheme(
    primary = VibeDarkColors.Primary,
    onPrimary = VibeDarkColors.OnPrimary,
    primaryContainer = VibeDarkColors.PrimaryContainer,
    onPrimaryContainer = VibeDarkColors.OnPrimaryContainer,
    inversePrimary = VibeColors.Sienna,
    secondary = VibeDarkColors.Secondary,
    onSecondary = VibeDarkColors.OnSecondary,
    secondaryContainer = VibeDarkColors.SecondaryContainer,
    onSecondaryContainer = VibeDarkColors.OnSecondaryContainer,
    tertiary = VibeDarkColors.Tertiary,
    onTertiary = VibeDarkColors.OnTertiary,
    tertiaryContainer = VibeDarkColors.TertiaryContainer,
    onTertiaryContainer = VibeDarkColors.OnTertiaryContainer,
    error = VibeDarkColors.Error,
    onError = VibeDarkColors.OnError,
    errorContainer = VibeDarkColors.ErrorContainer,
    onErrorContainer = VibeDarkColors.OnErrorContainer,
    background = VibeDarkColors.Background,
    onBackground = VibeDarkColors.OnBackground,
    surface = VibeDarkColors.Surface,
    onSurface = VibeDarkColors.OnSurface,
    surfaceVariant = VibeDarkColors.SurfaceVariant,
    onSurfaceVariant = VibeDarkColors.OnSurfaceVariant,
    surfaceTint = VibeDarkColors.Primary,
    surfaceDim = VibeDarkColors.Background,
    surfaceBright = VibeDarkColors.ContainerHighest,
    surfaceContainerLowest = VibeDarkColors.ContainerLowest,
    surfaceContainerLow = VibeDarkColors.ContainerLow,
    surfaceContainer = VibeDarkColors.Container,
    surfaceContainerHigh = VibeDarkColors.ContainerHigh,
    surfaceContainerHighest = VibeDarkColors.ContainerHighest,
    outline = VibeDarkColors.Outline,
    outlineVariant = VibeDarkColors.OutlineVariant,
    scrim = Color.Black,
    inverseSurface = VibeDarkColors.InverseSurface,
    inverseOnSurface = VibeDarkColors.InverseOnSurface
)

// ── 青简 (Weread) ──

private fun wereadColorScheme() = lightColorScheme(
    primary = WereadColors.Accent,
    onPrimary = WereadColors.White,
    primaryContainer = WereadColors.AccentContainer,
    onPrimaryContainer = WereadColors.OnAccentContainer,
    inversePrimary = WereadDarkColors.Primary,
    secondary = WereadColors.Secondary,
    onSecondary = WereadColors.White,
    secondaryContainer = WereadColors.SecondaryContainer,
    onSecondaryContainer = WereadColors.OnSecondaryContainer,
    tertiary = VibeColors.Tertiary,
    onTertiary = VibeColors.OnTertiary,
    tertiaryContainer = VibeColors.AmberLight,
    onTertiaryContainer = VibeColors.OnAmberLight,
    error = VibeColors.RedMuted,
    onError = WereadColors.White,
    errorContainer = VibeColors.RedContainer,
    onErrorContainer = VibeColors.OnRedContainer,
    background = WereadColors.Cream,
    onBackground = WereadColors.Charcoal,
    surface = WereadColors.Cream,
    onSurface = WereadColors.Charcoal,
    surfaceVariant = WereadColors.SurfaceVariant,
    onSurfaceVariant = WereadColors.WarmGray,
    surfaceTint = WereadColors.Accent,
    surfaceDim = WereadColors.ContainerHigh,
    surfaceBright = WereadColors.White,
    surfaceContainerLowest = WereadColors.White,
    surfaceContainerLow = WereadColors.ContainerLow,
    surfaceContainer = WereadColors.SurfaceVariant,
    surfaceContainerHigh = WereadColors.ContainerHigh,
    surfaceContainerHighest = WereadColors.ContainerHighest,
    outline = WereadColors.Outline,
    outlineVariant = WereadColors.Sand,
    scrim = Color.Black,
    inverseSurface = VibeColors.Ink,
    inverseOnSurface = WereadColors.Cream
)

private fun wereadDarkColorScheme() = darkColorScheme(
    primary = WereadDarkColors.Primary,
    onPrimary = WereadDarkColors.OnPrimary,
    primaryContainer = WereadDarkColors.PrimaryContainer,
    onPrimaryContainer = WereadDarkColors.OnPrimaryContainer,
    inversePrimary = WereadColors.Accent,
    secondary = WereadDarkColors.Secondary,
    onSecondary = WereadDarkColors.OnSecondary,
    secondaryContainer = WereadDarkColors.SecondaryContainer,
    onSecondaryContainer = WereadDarkColors.OnSecondaryContainer,
    tertiary = WereadDarkColors.Tertiary,
    onTertiary = WereadDarkColors.OnTertiary,
    tertiaryContainer = WereadDarkColors.TertiaryContainer,
    onTertiaryContainer = WereadDarkColors.OnTertiaryContainer,
    error = WereadDarkColors.Error,
    onError = WereadDarkColors.OnError,
    errorContainer = WereadDarkColors.ErrorContainer,
    onErrorContainer = WereadDarkColors.OnErrorContainer,
    background = WereadDarkColors.Background,
    onBackground = WereadDarkColors.OnBackground,
    surface = WereadDarkColors.Surface,
    onSurface = WereadDarkColors.OnSurface,
    surfaceVariant = WereadDarkColors.SurfaceVariant,
    onSurfaceVariant = WereadDarkColors.OnSurfaceVariant,
    surfaceTint = WereadDarkColors.Primary,
    surfaceDim = WereadDarkColors.Background,
    surfaceBright = WereadDarkColors.ContainerHighest,
    surfaceContainerLowest = WereadDarkColors.ContainerLowest,
    surfaceContainerLow = WereadDarkColors.ContainerLow,
    surfaceContainer = WereadDarkColors.Container,
    surfaceContainerHigh = WereadDarkColors.ContainerHigh,
    surfaceContainerHighest = WereadDarkColors.ContainerHighest,
    outline = WereadDarkColors.Outline,
    outlineVariant = WereadDarkColors.OutlineVariant,
    scrim = Color.Black,
    inverseSurface = WereadDarkColors.InverseSurface,
    inverseOnSurface = WereadDarkColors.InverseOnSurface
)
