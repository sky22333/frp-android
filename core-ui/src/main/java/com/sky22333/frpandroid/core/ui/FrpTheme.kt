package com.sky22333.frpandroid.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.sky22333.frpandroid.core.frp.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F7F86),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E9EA),
    onPrimaryContainer = Color(0xFF10363B),
    secondary = Color(0xFF6C7E80),
    secondaryContainer = Color(0xFFE1EAEB),
    surface = Color(0xFFFBFCFC),
    surfaceVariant = Color(0xFFE8EEF0),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ECED2),
    onPrimary = Color(0xFF09363A),
    primaryContainer = Color(0xFF255B61),
    onPrimaryContainer = Color(0xFFC8EFF2),
    secondary = Color(0xFFB8C9CB),
    secondaryContainer = Color(0xFF405456),
    surface = Color(0xFF101415),
    surfaceVariant = Color(0xFF3F484A),
    error = Color(0xFFFFB4AB),
)

private val AmoledColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFA9D7D9),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF123C40),
    onPrimaryContainer = Color(0xFFD4F3F4),
    secondary = Color(0xFFB8C9CB),
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF172022),
    error = Color(0xFFFFB4AB),
)

@Composable
fun FrpAndroidTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark,
        ThemeMode.Amoled -> true
    }
    val colors = when {
        themeMode == ThemeMode.Amoled -> AmoledColors
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
