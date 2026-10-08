package org.qingyingqx.smsmonitor.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val InkLightColorScheme = lightColorScheme(
    // 主操作为浅色填充，因此 onPrimary 用深色文字而非白色
    primary = InkPrimary,
    onPrimary = InkOnPrimary,
    primaryContainer = InkAccentSoft,
    onPrimaryContainer = InkOnSurface,

    secondary = InkNeutralFill,
    onSecondary = InkOnSurface,
    secondaryContainer = InkAccentSoft,
    onSecondaryContainer = InkOnSurface,

    tertiary = InkAccent,
    onTertiary = InkOnPrimary,

    background = InkBackground,
    onBackground = InkOnSurface,

    surface = InkSurface,
    onSurface = InkOnSurface,
    surfaceVariant = InkSurfaceSunken,
    onSurfaceVariant = InkSecondary,

    outline = InkOutline,
    outlineVariant = InkDivider,

    error = InkError,
    onError = Color.White,
    errorContainer = InkErrorSoft,
    onErrorContainer = InkError,
)

@Composable
fun SMSmonitorTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = InkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = InkLightColorScheme,
        typography = Typography,
        content = content
    )
}
