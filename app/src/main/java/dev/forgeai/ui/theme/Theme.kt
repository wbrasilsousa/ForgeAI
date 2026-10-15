package dev.forgeai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = PrimaryViolet,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3A2E8C),
    onPrimaryContainer = PrimaryVioletLight,
    secondary = SecondaryCyan,
    onSecondary = Color(0xFF062A36),
    tertiary = TertiaryMint,
    background = DarkBackground,
    onBackground = OnDark,
    surface = DarkSurface,
    onSurface = OnDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnDarkMuted,
    surfaceContainerLowest = Color(0xFF080B11),
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = Color(0xFF2A3450),
    error = DarkError,
    onError = Color(0xFF3B0A0A),
    outline = Color(0xFF39415A),
)

private val LightColors = lightColorScheme(
    primary = PrimaryIndigo,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3E0FF),
    onPrimaryContainer = Color(0xFF241A80),
    secondary = SecondaryBlue,
    tertiary = TertiaryGreen,
    background = LightBackground,
    onBackground = OnLight,
    surface = LightSurface,
    onSurface = OnLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = OnLightMuted,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = Color(0xFFD6DBE8),
    error = LightError,
    outline = Color(0xFFB9BFD0),
)

@Composable
fun ForgeAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
