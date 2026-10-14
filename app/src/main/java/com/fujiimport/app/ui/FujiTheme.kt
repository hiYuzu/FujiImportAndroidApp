package com.fujiimport.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF285C48), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBE2), onPrimaryContainer = Color(0xFF173D2D),
    secondary = Color(0xFF58675E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E9E2), onSecondaryContainer = Color(0xFF27382D),
    background = Color(0xFFF6F7F3), onBackground = Color(0xFF202922),
    surface = Color(0xFFF6F7F3), onSurface = Color(0xFF202922),
    surfaceVariant = Color(0xFFE5EAE3), onSurfaceVariant = Color(0xFF616B63),
    surfaceContainer = Color(0xFFEBEFE8), surfaceContainerLow = Color(0xFFF0F2EC),
    surfaceContainerHigh = Color(0xFFE5EAE3), surfaceContainerHighest = Color(0xFFE0E6DE),
    outline = Color(0xFF818B82), outlineVariant = Color(0xFFD8DFD5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6D4B9), onPrimary = Color(0xFF163C2B),
    primaryContainer = Color(0xFF244C38), onPrimaryContainer = Color(0xFFDCEBE2),
    secondary = Color(0xFFB9C9BD), onSecondary = Color(0xFF25352B),
    secondaryContainer = Color(0xFF34463A), onSecondaryContainer = Color(0xFFE1E9E2),
    background = Color(0xFF111713), onBackground = Color(0xFFE2E8DF),
    surface = Color(0xFF111713), onSurface = Color(0xFFE2E8DF),
    surfaceVariant = Color(0xFF29352C), onSurfaceVariant = Color(0xFFADB8AC),
    surfaceContainer = Color(0xFF1D2720), surfaceContainerLow = Color(0xFF18211B),
    surfaceContainerHigh = Color(0xFF263129), surfaceContainerHighest = Color(0xFF303D32),
    outline = Color(0xFF859383), outlineVariant = Color(0xFF3A473B),
)

@Composable
fun FujiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
