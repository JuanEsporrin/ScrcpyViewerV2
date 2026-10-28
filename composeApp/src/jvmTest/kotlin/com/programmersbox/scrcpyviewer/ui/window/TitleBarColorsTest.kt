package com.programmersbox.scrcpyviewer.ui.window

import com.programmersbox.scrcpyviewer.ui.theme.DarkColors
import com.programmersbox.scrcpyviewer.ui.theme.LightColors
import kotlin.test.Test
import kotlin.test.assertEquals

class TitleBarColorsTest {
    @Test
    fun `bar colours come from the M3 scheme`() {
        assertEquals(
            BarColors(LightColors.surfaceContainer, LightColors.surfaceContainerLow, LightColors.onSurface, LightColors.outlineVariant),
            barColors(LightColors),
        )
        assertEquals(DarkColors.surfaceContainer, barColors(DarkColors).background)
    }
}
