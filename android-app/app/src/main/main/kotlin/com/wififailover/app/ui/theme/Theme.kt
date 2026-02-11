package com.wififailover.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7FC8FF),
    secondary = Color(0xFF4DA6FF),
    tertiary = Color(0xFF00D4FF),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    error = Color(0xFFFF6B6B)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0366D6),
    secondary = Color(0xFF1F6FEB),
    tertiary = Color(0xFF00A4EF),
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFF8FB),
    error = Color(0xFFDA3633)
)

@Composable
fun WiFiFailoverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
