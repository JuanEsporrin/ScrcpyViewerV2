package com.programmersbox.scrcpyviewer

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.DeviceTracker
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.DeviceOptionsStore
import com.programmersbox.scrcpyviewer.tools.BundledTools
import com.programmersbox.scrcpyviewer.ui.App
import com.programmersbox.scrcpyviewer.ui.devices.DevicesViewModel
import com.programmersbox.scrcpyviewer.ui.devices.deviceTitle
import com.programmersbox.scrcpyviewer.ui.presets.PresetsViewModel
import com.programmersbox.scrcpyviewer.ui.settings.SettingsViewModel
import com.programmersbox.scrcpyviewer.ui.settings.parseAdbVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Paths

/** Dependency holder for the whole app; later tasks add fields. */
class AppGraph {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataDir = AppDataDir.root()

    val settings = AppSettings(dataDir.resolve("settings.json"))
    val presetStore = PresetStore(dataDir.resolve("presets.json"), scope)
    val deviceOptions = DeviceOptionsStore(dataDir.resolve("devices.json"), presetStore, scope)
    val bundledTools = BundledTools(dataDir = dataDir)
    val adbClient = AdbClient(adbPath = {
        settings.settings.value.adbPathOverride?.let { Paths.get(it) } ?: bundledTools.adbExecutable()
    })
    val deviceTracker = DeviceTracker(adbClient, scope)

    /** Serials the user asked to mirror (double-click or Mirror button). Consumed by the mirror window host. */
    val mirrorRequests = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val devicesViewModel = DevicesViewModel(
        deviceTracker.devices, deviceTracker.connected, deviceOptions, presetStore, scope, mirrorRequests,
    )

    val presetsViewModel = PresetsViewModel(
        presetStore, deviceOptions, devicesViewModel.selectedSerial,
        deviceLabel = { serial -> devicesViewModel.state.value.devices.firstOrNull { it.serial == serial }?.let(::deviceTitle) ?: serial },
        scope = scope,
    )

    val settingsViewModel = SettingsViewModel(
        settings, presetStore,
        bundledAdb = bundledTools::adbExecutable,
        adbVersion = { path ->
            val result = AdbClient(adbPath = { path }).exec("version")
            if (result.exitCode == 0) parseAdbVersion(result.stdout) else null
        },
        restartTracker = deviceTracker::restart,
        scope = scope,
    )

    /** The device currently selected on the Devices screen. */
    val selectedSerial: StateFlow<String?> get() = devicesViewModel.selectedSerial
}

fun main() {
    val graph = AppGraph()
    application {
        Window(onCloseRequest = ::exitApplication, title = "Scrcpy Viewer") {
            App(graph)
        }
    }
}
