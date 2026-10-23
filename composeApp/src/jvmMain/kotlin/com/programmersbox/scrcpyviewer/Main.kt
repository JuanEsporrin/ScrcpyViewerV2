package com.programmersbox.scrcpyviewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import javax.swing.SwingUtilities
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.DeviceTracker
import com.programmersbox.scrcpyviewer.media.AudioPlayer
import com.programmersbox.scrcpyviewer.media.DelayedVideoSink
import com.programmersbox.scrcpyviewer.media.VideoDecoder
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.ServerLauncher
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorController
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorWindow
import kotlinx.coroutines.launch
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
                decoderFactory = { meta, session ->
                    // video-buffer delays what is shown, not what is recorded (the recorder is another sink).
                    DelayedVideoSink.forOptions(VideoDecoder(meta.codecId, session::publishFrame, session::publishFps), values)
                },
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
        val shutdown = remember { AppShutdown(graph.mirrorController, exit = ::exitApplication) }
        val shutdownState by shutdown.state.collectAsState()
        DisposableEffect(Unit) {
            shutdown.installQuitHandler { SwingUtilities.invokeLater(shutdown::request) }
            onDispose { }
        }
        LaunchedEffect(shutdownState) { shutdown.run() }
        val shuttingDown = shutdownState as? AppShutdown.State.ShuttingDown
        Window(
            onCloseRequest = shutdown::request,
            title = "Scrcpy Viewer",
            visible = shuttingDown == null,
        ) {
            App(graph)
        }
        for ((serial, session) in mirrors) {
            key(serial) {
                MirrorWindow(serial, session, graph.mirrorController, settings.themeMode, settings.captureDir)
            }
        }
        if (shuttingDown?.finishingRecording == true) FinishingRecordingWindow(settings.themeMode)
    }
}

/** Shown while quitting waits for recorders to write their trailers. It cannot be closed. */
@Composable
private fun FinishingRecordingWindow(themeMode: ThemeMode) {
    Window(
        onCloseRequest = {},
        title = "Scrcpy Viewer",
        state = rememberWindowState(size = DpSize(340.dp, 120.dp), position = WindowPosition(Alignment.Center)),
        resizable = false,
    ) {
        ScrcpyViewerTheme(themeMode) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Finishing recording…", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
