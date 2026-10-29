package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.withFakeAppKoin
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.ui.settings.SettingsContent
import com.programmersbox.scrcpyviewer.ui.settings.SettingsUiState
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/** Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`. */
class SettingsScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")

    private val state = SettingsUiState(
        settings = Settings(ThemeMode.System, "/opt/android/platform-tools/adb", "/Users/me/Movies/ScrcpyViewer"),
        presets = listOf(Preset("qa", "QA default", options = OptionValues(emptyMap())), Preset("low", "Low bandwidth", options = OptionValues(emptyMap()))),
        defaultPresetId = "qa",
        bundledAdbVersion = "1.0.41 (35.0.2)",
        customAdbVersion = "1.0.41 (34.0.4)",
    )

    private fun render(name: String, mode: ThemeMode, state: SettingsUiState = this.state, customTitleBarSupported: Boolean = true) {
        val out = outDir.resolve(name)
        renderToPng(1280, 800, out) {
            ScrcpyViewerTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    SettingsContent(state.copy(settings = state.settings.copy(themeMode = mode)), {}, {}, {}, {}, {}, {}, {}, customTitleBarSupported = customTitleBarSupported)
                }
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    @Test
    fun `settings light`() = render("settings-light.png", ThemeMode.Light)

    @Test
    fun `settings dark`() = render("settings-dark.png", ThemeMode.Dark)

    @Test
    fun `settings bundled with path error`() = render(
        "settings-light-error.png", ThemeMode.Light,
        state.copy(settings = state.settings.copy(adbPathOverride = null), adbPathError = "File not found: /nope/adb", defaultPresetId = null),
    )

    @Test
    fun `settings without custom title bar support`() =
        render("settings-light-no-custom-bar.png", ThemeMode.Light, customTitleBarSupported = false)

    @Test
    fun `app light with rail`() = withFakeAppKoin { koin ->
        // Fake adb: fixed devices, nothing on this machine is touched.
        val out = outDir.resolve("app-light.png")
        koin.get<AppSettings>().update { it.copy(themeMode = ThemeMode.Light) }
        renderToPng(1280, 800, out) { App() }
        assertTrue(Files.size(out) > 10_000, "app-light should not be blank")
    }
}
