package com.sky22333.frpandroid.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.dynamiccolor.ColorSpec

@Composable
fun FrpAndroidTheme(
    seedColor: Int,
    content: @Composable () -> Unit,
) {
    DynamicMaterialTheme(
        seedColor = Color(seedColor),
        isDark = isSystemInDarkTheme(),
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
        animate = true,
        content = content,
    )
}
