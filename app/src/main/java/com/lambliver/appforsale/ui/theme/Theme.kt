package com.lambliver.appforsale.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Linear 風深色：近黑畫布 + 薰衣草主色 + surface 階梯 + 髮線層次。
 * 預設關閉 dynamic color，展場畫面色彩與對比穩定。
 */
private val LinearDarkColorScheme = darkColorScheme(
    primary = LinearPrimary,
    onPrimary = LinearOnPrimary,
    primaryContainer = LinearPrimaryContainerDark,
    onPrimaryContainer = LinearOnPrimaryContainer,
    secondary = LinearBrandSecure,
    onSecondary = LinearCanvas,
    secondaryContainer = LinearSurface3,
    onSecondaryContainer = LinearInk,
    tertiary = LinearPrimaryHover,
    onTertiary = LinearCanvas,
    tertiaryContainer = LinearTertiaryContainer,
    onTertiaryContainer = LinearOnTertiaryContainer,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = LinearCanvas,
    onBackground = LinearInk,
    surface = LinearSurface1,
    onSurface = LinearInk,
    surfaceVariant = LinearSurface2,
    onSurfaceVariant = LinearInkSubtle,
    outline = LinearHairline,
    outlineVariant = LinearHairlineStrong,
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = LinearPrimaryFocus,
    surfaceTint = Color.Transparent,
)

@Composable
fun AppforsaleTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        darkTheme && dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicDarkColorScheme(context)
        else -> LinearDarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
