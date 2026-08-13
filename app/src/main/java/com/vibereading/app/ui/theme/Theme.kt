package com.vibereading.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.vibereading.app.data.repository.SettingsRepository
import com.vibereading.app.domain.model.AppAccent
import com.vibereading.app.domain.model.ThemeMode
import com.vibereading.app.domain.model.ThemeSettings

/**
 * 全局主题（对齐 Legado themeMode / ThemeConfig）：
 * themeMode = 跟随系统 / 浅色 / 深色；accent = 原木 / 青简。
 * 阅读器页面的背景色/文字色由 ReadingSettings + ReaderBgPresets 独立控制，不依赖本主题。
 */
@Composable
fun VibeReadingTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val settings by settingsRepo.themeSettings.collectAsState(initial = ThemeSettings())

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

    // Status bar follows theme (dark → light icons, light → dark icons)
    val view = LocalView.current
    val activity = view.context as? Activity
    SideEffect {
        activity?.window?.let { window ->
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

// ── 原木 (Vibe) ──

private fun vibeColorScheme() = lightColorScheme(
    primary = VibeColors.Sienna,
    onPrimary = Color.White,
    primaryContainer = VibeColors.SiennaLight.copy(alpha = 0.15f),
    onPrimaryContainer = VibeColors.Sienna,
    secondary = VibeColors.Sage,
    onSecondary = Color.White,
    secondaryContainer = VibeColors.SageLight,
    onSecondaryContainer = VibeColors.Sage,
    tertiary = VibeColors.Amber,
    error = VibeColors.RedMuted,
    background = VibeColors.Cream,
    onBackground = VibeColors.Charcoal,
    surface = VibeColors.Cream,
    onSurface = VibeColors.Charcoal,
    surfaceVariant = VibeColors.Parchment,
    onSurfaceVariant = VibeColors.WarmGray,
    outline = VibeColors.Linen,
    outlineVariant = VibeColors.Sand,
    inverseSurface = VibeColors.Ink,
    inverseOnSurface = VibeColors.Cream,
)

private fun vibeDarkColorScheme() = darkColorScheme(
    primary = VibeDarkColors.Primary,
    onPrimary = VibeDarkColors.OnPrimary,
    primaryContainer = VibeDarkColors.PrimaryContainer,
    onPrimaryContainer = VibeDarkColors.OnPrimaryContainer,
    secondary = VibeDarkColors.Secondary,
    onSecondary = VibeDarkColors.OnSecondary,
    secondaryContainer = VibeDarkColors.SecondaryContainer,
    onSecondaryContainer = VibeDarkColors.OnSecondaryContainer,
    tertiary = VibeColors.Amber,
    error = VibeColors.RedMuted,
    background = VibeDarkColors.Background,
    onBackground = VibeDarkColors.OnBackground,
    surface = VibeDarkColors.Surface,
    onSurface = VibeDarkColors.OnSurface,
    surfaceVariant = VibeDarkColors.SurfaceVariant,
    onSurfaceVariant = VibeDarkColors.OnSurfaceVariant,
    outline = VibeDarkColors.Outline,
    outlineVariant = VibeDarkColors.OutlineVariant,
    inverseSurface = VibeDarkColors.InverseSurface,
    inverseOnSurface = VibeDarkColors.InverseOnSurface,
)

// ── 青简 (Weread) ──

private fun wereadColorScheme() = lightColorScheme(
    primary = WereadColors.Accent,
    onPrimary = Color.White,
    primaryContainer = WereadColors.AccentLight.copy(alpha = 0.15f),
    onPrimaryContainer = WereadColors.Accent,
    secondary = VibeColors.Sage,
    onSecondary = Color.White,
    secondaryContainer = VibeColors.SageLight,
    onSecondaryContainer = VibeColors.Sage,
    tertiary = VibeColors.Amber,
    error = VibeColors.RedMuted,
    background = WereadColors.Cream,
    onBackground = VibeColors.Charcoal,
    surface = WereadColors.Cream,
    onSurface = VibeColors.Charcoal,
    surfaceVariant = VibeColors.Parchment,
    onSurfaceVariant = VibeColors.WarmGray,
    outline = WereadColors.Linen,
    outlineVariant = WereadColors.Sand,
    inverseSurface = VibeColors.Ink,
    inverseOnSurface = WereadColors.Cream,
)

private fun wereadDarkColorScheme() = darkColorScheme(
    primary = WereadDarkColors.Primary,
    onPrimary = WereadDarkColors.OnPrimary,
    primaryContainer = WereadDarkColors.PrimaryContainer,
    onPrimaryContainer = WereadDarkColors.OnPrimaryContainer,
    secondary = WereadDarkColors.Secondary,
    onSecondary = WereadDarkColors.OnSecondary,
    secondaryContainer = WereadDarkColors.SecondaryContainer,
    onSecondaryContainer = WereadDarkColors.OnSecondaryContainer,
    tertiary = VibeColors.Amber,
    error = VibeColors.RedMuted,
    background = WereadDarkColors.Background,
    onBackground = WereadDarkColors.OnBackground,
    surface = WereadDarkColors.Surface,
    onSurface = WereadDarkColors.OnSurface,
    surfaceVariant = WereadDarkColors.SurfaceVariant,
    onSurfaceVariant = WereadDarkColors.OnSurfaceVariant,
    outline = WereadDarkColors.Outline,
    outlineVariant = WereadDarkColors.OutlineVariant,
    inverseSurface = WereadDarkColors.InverseSurface,
    inverseOnSurface = WereadDarkColors.InverseOnSurface,
)
