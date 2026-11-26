package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SkyBlueBlackWhiteColorScheme = darkColorScheme(
    primary = SkyBlue,
    onPrimary = PureBlack,
    primaryContainer = SkyBlueContainer,
    onPrimaryContainer = SkyBlue,
    secondary = SkyBlue,
    onSecondary = PureBlack,
    secondaryContainer = SkyBlueContainer,
    onSecondaryContainer = PureWhite,
    tertiary = PureWhite,
    onTertiary = PureBlack,
    tertiaryContainer = SubtleSurface,
    onTertiaryContainer = PureWhite,
    error = SkyBlue,
    onError = PureBlack,
    errorContainer = SkyBlueContainer,
    onErrorContainer = PureWhite,
    background = PureBlack,
    onBackground = PureWhite,
    surface = PureBlack,
    onSurface = PureWhite,
    surfaceContainerLow = SubtleSurface,
    surfaceContainer = SubtleSurface,
    surfaceContainerHigh = SkyBlueContainer,
    surfaceVariant = SubtleSurface,
    onSurfaceVariant = PureWhite.copy(alpha = 0.72f),
    outline = PureWhite.copy(alpha = 0.22f),
    outlineVariant = PureWhite.copy(alpha = 0.12f),
)

val VaultShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun VaultGuardTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SkyBlueBlackWhiteColorScheme,
        typography = AppTypography,
        shapes = VaultShapes,
        content = content,
    )
}
