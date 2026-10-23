package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionGroup
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.ui.devices.DevicesContent
import com.programmersbox.scrcpyviewer.ui.devices.DevicesUiState
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/** Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`. */
class DevicesScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")

    private val values = OptionValues(
        mapOf("max-size" to "1920", "no-audio" to null, "show-touches" to null, "stay-awake" to null, "video-codec" to "h265", "record" to ""),
    )

    private val state = DevicesUiState(
        devices = listOf(
            Device("39121FDJH004AB", DeviceState.Device, "device", "Pixel_8_Pro", null, 1, false, DeviceInfo("Pixel 8 Pro", "15", 35)),
            Device("adb-R5CT90ABCDE-xK2fPq._adb-tls-connect._tcp", DeviceState.Device, "device", "SM_X710", null, 2, true),
            Device("ZY22H8KLMN", DeviceState.Unauthorized, "unauthorized", null, null, null, false),
        ),
        selectedSerial = "39121FDJH004AB",
        visibleOptions = OptionCatalog.all,
        values = values,
        errors = values.validate(),
        presetLabel = "QA default (modified)",
        command = "scrcpy -s 39121FDJH004AB --max-size=1920 --video-codec=h265 --no-audio --show-touches --stay-awake --record",
        canMirror = false,
        adbConnected = true,
        hasBasePreset = true,
    )

    private fun render(name: String, mode: ThemeMode, state: DevicesUiState = this.state) {
        val out = outDir.resolve(name)
        renderToPng(1280, 800, out) {
            ScrcpyViewerTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    DevicesContent(state, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {})
                }
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    @Test
    fun `devices light`() = render("devices-light.png", ThemeMode.Light)

    @Test
    fun `devices dark`() = render("devices-dark.png", ThemeMode.Dark)

    @Test
    fun `devices light filtered`() = render(
        "devices-light-audio.png", ThemeMode.Light,
        state.copy(groupFilter = OptionGroup.Audio, visibleOptions = OptionCatalog.all.filter { it.group == OptionGroup.Audio }),
    )

    @Test
    fun `devices light preset menu`() {
        val out = outDir.resolve("devices-light-preset-menu.png")
        renderToPng(1280, 800, out) {
            ScrcpyViewerTheme(ThemeMode.Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    DevicesContent(
                        state.copy(
                            presets = listOf(
                                Preset("n", "New preset", options = OptionValues(emptyMap())),
                                Preset("r", "recording", options = values),
                            ),
                            basePresetId = "r",
                            presetLabel = "recording",
                        ),
                        {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {},
                        presetMenuOpen = true,
                    )
                }
            }
        }
        assertTrue(Files.size(out) > 10_000)
    }
}
