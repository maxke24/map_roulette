package com.jellemax.maproulette.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The Graphite identity: warm graphite grounds with a single amber accent by
 * night, a warm porcelain ground with a deeper amber by day. Replaces Material
 * You dynamic colour so the app has its own look instead of the wallpaper's.
 * The accent deepens on light so it stays legible on a pale ground.
 */

// Night — warm graphite + amber.
val GraphiteDark = darkColorScheme(
    primary = Color(0xFFE8B04B),
    onPrimary = Color(0xFF241C08),
    primaryContainer = Color(0xFF4A3A16),
    onPrimaryContainer = Color(0xFFF6DFAE),
    secondary = Color(0xFFC9B58A),
    onSecondary = Color(0xFF2A2410),
    secondaryContainer = Color(0xFF2E2A1E),
    onSecondaryContainer = Color(0xFFEAE0C6),
    tertiary = Color(0xFFE8B04B),
    onTertiary = Color(0xFF241C08),
    tertiaryContainer = Color(0xFF3A3016),
    onTertiaryContainer = Color(0xFFF3E4BE),
    background = Color(0xFF14170F),
    onBackground = Color(0xFFEDE9DB),
    surface = Color(0xFF191C14),
    onSurface = Color(0xFFEDE9DB),
    surfaceVariant = Color(0xFF2A2E22),
    onSurfaceVariant = Color(0xFFB7AF98),
    surfaceContainerLowest = Color(0xFF101309),
    surfaceContainerLow = Color(0xFF1A1E15),
    surfaceContainer = Color(0xFF1F231A),
    surfaceContainerHigh = Color(0xFF24281D),
    surfaceContainerHighest = Color(0xFF2C3025),
    outline = Color(0xFF6E6A58),
    outlineVariant = Color(0xFF3A3D31),
    error = Color(0xFFE4533A),
    onError = Color(0xFF2A0E08),
    errorContainer = Color(0xFF5A2317),
    onErrorContainer = Color(0xFFFBD8CE),
    inverseSurface = Color(0xFFEDE9DB),
    inverseOnSurface = Color(0xFF1A1E15),
    inversePrimary = Color(0xFFB87A1B),
)

// Day — warm porcelain + deeper amber.
val GraphiteLight = lightColorScheme(
    primary = Color(0xFFB87A1B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6E2BC),
    onPrimaryContainer = Color(0xFF3A2A06),
    secondary = Color(0xFF8A7A52),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE6D3),
    onSecondaryContainer = Color(0xFF322C18),
    tertiary = Color(0xFFB87A1B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6E2BC),
    onTertiaryContainer = Color(0xFF3A2A06),
    background = Color(0xFFF0EEE8),
    onBackground = Color(0xFF241F16),
    surface = Color(0xFFFBF9F3),
    onSurface = Color(0xFF241F16),
    surfaceVariant = Color(0xFFE7E2D5),
    onSurfaceVariant = Color(0xFF6E665A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F5EF),
    surfaceContainer = Color(0xFFF5F2EA),
    surfaceContainerHigh = Color(0xFFEFEBE1),
    surfaceContainerHighest = Color(0xFFE9E4D8),
    outline = Color(0xFF928A78),
    outlineVariant = Color(0xFFD8D2C4),
    error = Color(0xFFE23B2E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFADAD5),
    onErrorContainer = Color(0xFF5A1710),
    inverseSurface = Color(0xFF241F16),
    inverseOnSurface = Color(0xFFF5F2EA),
    inversePrimary = Color(0xFFE8B04B),
)
