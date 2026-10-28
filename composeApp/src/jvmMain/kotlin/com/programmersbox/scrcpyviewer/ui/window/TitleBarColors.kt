package com.programmersbox.scrcpyviewer.ui.window

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Title bar colours, taken from the app's Material 3 scheme so the bar matches the nav rail / mirror strip. */
data class BarColors(val background: Color, val inactiveBackground: Color, val content: Color, val border: Color)

fun barColors(scheme: ColorScheme): BarColors = BarColors(
    background = scheme.surfaceContainer,
    inactiveBackground = scheme.surfaceContainerLow,
    content = scheme.onSurface,
    border = scheme.outlineVariant,
)
