package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val VoxLightColorScheme = lightColorScheme(
    primary = TealPrimaryLight,
    onPrimary = OnTealPrimaryLight,
    primaryContainer = TealContainerLight,
    onPrimaryContainer = OnTealContainerLight,
    secondary = CoralSecondaryLight,
    onSecondary = OnCoralSecondaryLight,
    secondaryContainer = CoralContainerLight,
    onSecondaryContainer = OnCoralContainerLight,
    tertiary = IndigoTertiaryLight,
    onTertiary = OnIndigoTertiaryLight,
    tertiaryContainer = IndigoContainerLight,
    onTertiaryContainer = OnIndigoTertiaryContainerLight,
    background = AlabasterBackground,
    onBackground = CharcoalOnSurface,
    surface = AlabasterSurface,
    onSurface = CharcoalOnSurface,
    surfaceVariant = AlabasterSurfaceVariant,
    onSurfaceVariant = SlateOnSurfaceVariant,
    outline = OutlineSubtleLight,
    error = ErrorRedLight,
    errorContainer = ErrorContainerLight
)

private val VoxDarkColorScheme = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = OnTealPrimaryDark,
    primaryContainer = TealContainerDark,
    onPrimaryContainer = OnTealContainerDark,
    secondary = CoralSecondaryDark,
    onSecondary = OnCoralSecondaryDark,
    secondaryContainer = CoralContainerDark,
    onSecondaryContainer = OnCoralContainerDark,
    tertiary = IndigoTertiaryDark,
    onTertiary = OnIndigoTertiaryDark,
    tertiaryContainer = IndigoContainerDark,
    onTertiaryContainer = OnIndigoTertiaryContainerDark,
    background = ObsidianBackground,
    onBackground = IvoryOnSurface,
    surface = ObsidianSurface,
    onSurface = IvoryOnSurface,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = MistOnSurfaceVariant,
    outline = OutlineSubtleDark,
    error = ErrorRedDark,
    errorContainer = ErrorContainerDark
)

val VoxShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun VoxBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) VoxDarkColorScheme else VoxLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VoxBridgeTypography,
        shapes = VoxShapes,
        content = content
    )
}
