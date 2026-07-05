package com.quantumcast.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.quantumcast.data.settings.ThemeMode

// Brand palette - fallback when dynamic color unavailable (Android < 12)
private val BrandOrange = Color(0xFFFF6D00)
private val BrandOrangeLight = Color(0xFFFF9E40)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF924000),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF320F00),
    secondary = Color(0xFF765849),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCC),
    onSecondaryContainer = Color(0xFF2C160A),
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF201A18),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF201A18),
    surfaceVariant = Color(0xFFF4DDD5),
    onSurfaceVariant = Color(0xFF53433E),
    outline = Color(0xFF85736F)
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandOrange,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF8C3A00),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = BrandOrangeLight,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF5A3000),
    onSecondaryContainer = Color(0xFFFFDDB5),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFF9E9E9E),
    outline = Color(0xFF444444)
)

@Composable
fun RadioTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
