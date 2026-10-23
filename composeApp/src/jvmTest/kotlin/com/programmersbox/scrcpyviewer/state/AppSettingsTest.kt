package com.programmersbox.scrcpyviewer.state

import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsTest {
    private val dir = Files.createTempDirectory("settings")
    private val file = dir.resolve("settings.json")

    @Test
    fun `settings persist and default when file missing or corrupt`() {
        val missing = AppSettings(file).settings.value
        assertEquals(ThemeMode.System, missing.themeMode)
        assertNull(missing.adbPathOverride)
        assertTrue(missing.captureDir.endsWith("ScrcpyViewer"))
        assertEquals(defaultCaptureDir(), missing.captureDir)

        AppSettings(file).update { it.copy(themeMode = ThemeMode.Dark, adbPathOverride = "/opt/adb", captureDir = "/tmp/cap") }
        assertEquals(Settings(ThemeMode.Dark, "/opt/adb", "/tmp/cap"), AppSettings(file).settings.value)

        Files.writeString(file, "{garbage")
        assertEquals(Settings(), AppSettings(file).settings.value)
        assertTrue(Files.exists(dir.resolve("settings.json.bak")))
    }

    @Test
    fun `captureDir resolved when absent from file`() {
        Files.writeString(file, """{"themeMode":"Light"}""")
        val s = AppSettings(file).settings.value
        assertEquals(ThemeMode.Light, s.themeMode)
        assertEquals(defaultCaptureDir(), s.captureDir)
    }
}
