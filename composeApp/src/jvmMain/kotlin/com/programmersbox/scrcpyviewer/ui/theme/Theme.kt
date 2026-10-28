package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

enum class ThemeMode { System, Light, Dark }

/** Whether this mode resolves to the dark scheme right now ([ThemeMode.System] follows the OS). */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun ScrcpyViewerTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (themeMode.isDark()) DarkColors else LightColors,
        content = content,
    )
}
