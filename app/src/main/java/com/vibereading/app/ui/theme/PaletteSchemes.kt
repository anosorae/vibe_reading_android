package com.vibereading.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private fun lightPaletteScheme(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    tertiary: Color,
    tertiaryContainer: Color,
    onTertiaryContainer: Color,
    background: Color,
    onBackground: Color,
    surfaceVariant: Color,
    onSurfaceVariant: Color,
    surfaceContainerLow: Color,
    surfaceContainerHigh: Color,
    outline: Color,
    outlineVariant: Color,
    error: Color,
    errorContainer: Color,
    onErrorContainer: Color
): ColorScheme = lightColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = primary,
    secondary = secondary,
    onSecondary = Color.White,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = Color.White,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    error = error,
    onError = Color.White,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    background = background,
    onBackground = onBackground,
    surface = background,
    onSurface = onBackground,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceTint = primary,
    surfaceDim = surfaceContainerHigh,
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = surfaceContainerLow,
    surfaceContainer = surfaceVariant,
    surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHigh,
    outline = outline,
    outlineVariant = outlineVariant,
    scrim = Color.Black,
    inverseSurface = onBackground,
    inverseOnSurface = background
)

private fun darkPaletteScheme(
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    onSecondary: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    tertiary: Color,
    onTertiary: Color,
    tertiaryContainer: Color,
    onTertiaryContainer: Color,
    background: Color,
    onBackground: Color,
    surface: Color,
    surfaceVariant: Color,
    onSurfaceVariant: Color,
    containerLow: Color,
    containerHigh: Color,
    outline: Color,
    outlineVariant: Color,
    error: Color,
    onError: Color,
    errorContainer: Color,
    onErrorContainer: Color
): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = primary,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    error = error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onBackground,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceTint = primary,
    surfaceDim = background,
    surfaceBright = containerHigh,
    surfaceContainerLowest = background,
    surfaceContainerLow = containerLow,
    surfaceContainer = surfaceVariant,
    surfaceContainerHigh = containerHigh,
    surfaceContainerHighest = containerHigh,
    outline = outline,
    outlineVariant = outlineVariant,
    scrim = Color.Black,
    inverseSurface = onBackground,
    inverseOnSurface = background
)

fun indigoColorScheme() = lightPaletteScheme(
    primary = IndigoColors.Accent,
    onPrimary = IndigoColors.White,
    primaryContainer = IndigoColors.AccentContainer,
    onPrimaryContainer = IndigoColors.OnAccentContainer,
    secondary = IndigoColors.Secondary,
    secondaryContainer = IndigoColors.SecondaryContainer,
    onSecondaryContainer = IndigoColors.OnSecondaryContainer,
    tertiary = IndigoColors.Tertiary,
    tertiaryContainer = IndigoColors.TertiaryContainer,
    onTertiaryContainer = IndigoColors.OnTertiaryContainer,
    background = IndigoColors.Cream,
    onBackground = IndigoColors.Charcoal,
    surfaceVariant = IndigoColors.SurfaceVariant,
    onSurfaceVariant = IndigoColors.WarmGray,
    surfaceContainerLow = IndigoColors.ContainerLow,
    surfaceContainerHigh = IndigoColors.ContainerHigh,
    outline = IndigoColors.Outline,
    outlineVariant = IndigoColors.Sand,
    error = IndigoColors.RedMuted,
    errorContainer = IndigoColors.RedContainer,
    onErrorContainer = IndigoColors.OnRedContainer
)

fun indigoDarkColorScheme() = darkPaletteScheme(
    primary = Color(0xFF80C9E8), onPrimary = Color(0xFF003548),
    primaryContainer = Color(0xFF07506A), onPrimaryContainer = Color(0xFFBDEAFF),
    secondary = Color(0xFFA8CDDF), onSecondary = Color(0xFF123746),
    secondaryContainer = Color(0xFF315564), onSecondaryContainer = Color(0xFFC5E9F8),
    tertiary = Color(0xFFA9C8D8), onTertiary = Color(0xFF163541),
    tertiaryContainer = Color(0xFF3A5968), onTertiaryContainer = Color(0xFFD0ECF7),
    background = Color(0xFF101A20), onBackground = Color(0xFFE0F1F8),
    surface = Color(0xFF151F25), surfaceVariant = Color(0xFF3E4A50),
    onSurfaceVariant = Color(0xFFC0CCD1), containerLow = Color(0xFF1B272E),
    containerHigh = Color(0xFF29373E), outline = Color(0xFF8D9BA1),
    outlineVariant = Color(0xFF4E5A60), error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005), errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

fun mossColorScheme() = lightPaletteScheme(
    primary = MossColors.Accent, onPrimary = MossColors.White,
    primaryContainer = MossColors.AccentContainer, onPrimaryContainer = MossColors.OnAccentContainer,
    secondary = MossColors.Secondary, secondaryContainer = MossColors.SecondaryContainer,
    onSecondaryContainer = MossColors.OnSecondaryContainer, tertiary = MossColors.Tertiary,
    tertiaryContainer = MossColors.TertiaryContainer, onTertiaryContainer = MossColors.OnTertiaryContainer,
    background = MossColors.Cream, onBackground = MossColors.Charcoal,
    surfaceVariant = MossColors.SurfaceVariant, onSurfaceVariant = MossColors.WarmGray,
    surfaceContainerLow = MossColors.ContainerLow, surfaceContainerHigh = MossColors.ContainerHigh,
    outline = MossColors.Outline, outlineVariant = MossColors.Sand, error = MossColors.RedMuted,
    errorContainer = MossColors.RedContainer, onErrorContainer = MossColors.OnRedContainer
)

fun mossDarkColorScheme() = indigoDarkColorScheme()

fun lotusColorScheme() = lightPaletteScheme(
    primary = LotusColors.Accent, onPrimary = LotusColors.White,
    primaryContainer = LotusColors.AccentContainer, onPrimaryContainer = LotusColors.OnAccentContainer,
    secondary = LotusColors.Secondary, secondaryContainer = LotusColors.SecondaryContainer,
    onSecondaryContainer = LotusColors.OnSecondaryContainer, tertiary = LotusColors.Tertiary,
    tertiaryContainer = LotusColors.TertiaryContainer, onTertiaryContainer = LotusColors.OnTertiaryContainer,
    background = LotusColors.Cream, onBackground = LotusColors.Charcoal,
    surfaceVariant = LotusColors.SurfaceVariant, onSurfaceVariant = LotusColors.WarmGray,
    surfaceContainerLow = LotusColors.ContainerLow, surfaceContainerHigh = LotusColors.ContainerHigh,
    outline = LotusColors.Outline, outlineVariant = LotusColors.Sand, error = LotusColors.RedMuted,
    errorContainer = LotusColors.RedContainer, onErrorContainer = LotusColors.OnRedContainer
)

fun lotusDarkColorScheme() = indigoDarkColorScheme()

fun inkColorScheme() = lightPaletteScheme(
    primary = InkColors.Accent, onPrimary = InkColors.White,
    primaryContainer = InkColors.AccentContainer, onPrimaryContainer = InkColors.OnAccentContainer,
    secondary = InkColors.Secondary, secondaryContainer = InkColors.SecondaryContainer,
    onSecondaryContainer = InkColors.OnSecondaryContainer, tertiary = InkColors.Tertiary,
    tertiaryContainer = InkColors.TertiaryContainer, onTertiaryContainer = InkColors.OnTertiaryContainer,
    background = InkColors.Cream, onBackground = InkColors.Charcoal,
    surfaceVariant = InkColors.SurfaceVariant, onSurfaceVariant = InkColors.WarmGray,
    surfaceContainerLow = InkColors.ContainerLow, surfaceContainerHigh = InkColors.ContainerHigh,
    outline = InkColors.Outline, outlineVariant = InkColors.Sand, error = InkColors.RedMuted,
    errorContainer = InkColors.RedContainer, onErrorContainer = InkColors.OnRedContainer
)

fun inkDarkColorScheme() = indigoDarkColorScheme()
