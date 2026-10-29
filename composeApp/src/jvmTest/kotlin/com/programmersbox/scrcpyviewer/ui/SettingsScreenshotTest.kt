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
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.ui.update.UpdatesContent
import com.programmersbox.scrcpyviewer.update.Release
import com.programmersbox.scrcpyviewer.update.UpdateState
import java.time.Instant
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

    private val release = Release(
        tagName = "v1.5.0",
        htmlUrl = "https://github.com/jakepurple13/ScrcpyViewerV2/releases/tag/v1.5.0",
        publishedAt = "2026-10-27T09:00:00Z",
    )
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    private fun renderUpdates(name: String, ui: UpdateUiState) {
        val out = outDir.resolve(name)
        renderToPng(1280, 800, out) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    SettingsContent(
                        state, {}, {}, {}, {}, {}, {}, {},
                        updates = { UpdatesContent(ui, {}, {}, {}, now) },
                    )
                }
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    private fun updatesUi(state: UpdateState, available: Release? = null, canSelfUpdate: Boolean = true) = UpdateUiState(
        state = state, available = available, currentVersion = "1.4.0",
        lastChecked = now.minusSeconds(120), canSelfUpdate = canSelfUpdate,
    )

    @Test
    fun `updates available`() =
        renderUpdates("settings-updates-available.png", updatesUi(UpdateState.Available(release), release))

    @Test
    fun `updates downloading`() =
        renderUpdates("settings-updates-downloading.png", updatesUi(UpdateState.Downloading(release, 20_000_000, 50_000_000), release))

    @Test
    fun `updates up to date`() = renderUpdates("settings-updates-uptodate.png", updatesUi(UpdateState.UpToDate))

    @Test
    fun `updates idle`() = renderUpdates("settings-updates-idle.png", updatesUi(UpdateState.Idle, canSelfUpdate = false))

    @Test
    fun `updates error`() = renderUpdates(
        "settings-updates-error.png",
        updatesUi(UpdateState.Failed("Couldn't check for updates: GitHub returned HTTP 403", null)),
    )

    @Test
    fun `updates mac hint`() = renderUpdates(
        "settings-updates-mac-hint.png",
        updatesUi(UpdateState.Idle).copy(macNotInApplications = true),
    )

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
