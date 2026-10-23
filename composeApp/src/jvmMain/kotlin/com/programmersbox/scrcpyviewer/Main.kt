package com.programmersbox.scrcpyviewer

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.DeviceTracker
import com.programmersbox.scrcpyviewer.media.AudioPlayer
import com.programmersbox.scrcpyviewer.media.VideoDecoder
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.ServerLauncher
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorController
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorWindow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
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

    val serverLauncher = ServerLauncher(adbClient, bundledTools)

    /** Open mirror sessions; they run on [scope], so they live until their window or the app is closed. */
    val mirrorController = MirrorController(
        scope = scope,
        devices = deviceTracker.devices,
        optionsFor = { serial -> deviceOptions.workingSet(serial).value.values },
        newSession = { serial, values ->
            ScrcpySession(
                serial, values, serverLauncher,
                decoderFactory = { meta, session -> VideoDecoder(meta.codecId, session::publishFrame, session::publishFps) },
                audioFactory = { codecId, _ -> AudioPlayer.forOptions(codecId, values) },
                scope = scope,
            )
        },
        setClipboard = { text -> Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) },
    )

    init {
        scope.launch { mirrorRequests.collect { mirrorController.open(it) } }
    }
}

fun main() {
    val graph = AppGraph()
    application {
        val settings by graph.settings.settings.collectAsState()
        val mirrors by graph.mirrorController.open.collectAsState()
        Window(
            onCloseRequest = {
                // Stop every server (and remove its tunnel) before leaving.
                runBlocking { withTimeoutOrNull(5_000) { graph.mirrorController.closeAll() } }
                exitApplication()
            },
            title = "Scrcpy Viewer",
        ) {
            App(graph)
        }
        for ((serial, session) in mirrors) {
            key(serial) {
                MirrorWindow(serial, session, graph.mirrorController, settings.themeMode, settings.captureDir)
            }
        }
    }
}
