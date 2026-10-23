package com.programmersbox.scrcpyviewer

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.media.RecordResult
import com.programmersbox.scrcpyviewer.media.VideoDecoder
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.ServerLauncher
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.tools.BundledTools
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorController
import com.programmersbox.scrcpyviewer.ui.mirror.RecordingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-device check of `start-app` and of quitting through [AppShutdown] while recording; excluded from the default
 * run (`-PdeviceTests -PdeviceSerials=<serial>`). Opens Settings with start-app, records ~5 s, quits the way Cmd+Q
 * does, then returns the device to HOME. The MP4 stays in `build/device-test-output` (check it with ffprobe).
 */
class ShutdownDeviceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adbPath: Path = System.getenv("PATH").split(java.io.File.pathSeparator)
        .map { Paths.get(it, "adb") }.first { Files.isExecutable(it) }
    private val adb = AdbClient(adbPath = { adbPath })
    private val launcher = ServerLauncher(adb, BundledTools(dataDir = Files.createTempDirectory("shutdown-device-test")))
    private val serial: String? = System.getProperty("scrcpyviewer.deviceSerials")?.split(',')?.first()?.takeIf { it.isNotBlank() }
    private val out: Path = Files.createDirectories(Paths.get("build", "device-test-output").toAbsolutePath())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `start-app opens Settings and quitting while recording finishes the file`() = runBlocking {
        assumeTrue("no -PdeviceSerials given", serial != null)
        val serial = serial!!
        val file = out.resolve("$serial-shutdown.mp4")
        Files.deleteIfExists(file)
        val values = OptionValues(mapOf("max-size" to "640", "start-app" to "com.android.settings", "record" to file.toString()))
        val controller = MirrorController(
            scope,
            MutableStateFlow(listOf(Device(serial, DeviceState.Device, "device", null, null, null, false))),
            optionsFor = { values },
            newSession = { s, v ->
                ScrcpySession(
                    s, v, launcher,
                    decoderFactory = { meta, session -> VideoDecoder(meta.codecId, session::publishFrame, session::publishFps) },
                    audioFactory = { _, _ -> null },
                    scope = scope,
                )
            },
        )
        val result = scope.async { withTimeout(60_000) { controller.recordingResults.first() } }
        delay(50)
        controller.open(serial)
        val session = controller.open.value.getValue(serial)
        try {
            withTimeout(20_000) {
                session.status.first { it == SessionStatus.Running || it == SessionStatus.Failed }
                check(session.status.value == SessionStatus.Running) { "session failed: ${session.error.value}" }
            }
            delay(5_000)
            val focus = adb.shell(serial, "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
            println("[$serial] focus: ${focus.trim()}")
            assertTrue("com.android.settings" in focus, "Settings should be in front: $focus")
            var exited = false
            val shutdown = AppShutdown(controller, exit = { exited = true })
            shutdown.request()
            assertEquals(AppShutdown.State.ShuttingDown(finishingRecording = true), shutdown.state.value)
            withTimeout(20_000) { shutdown.run() }
            assertTrue(exited)
            assertEquals(RecordingEvent(serial, RecordResult.Saved(file)), result.await())
            println("[$serial] recorded ${Files.size(file)} B to $file")
        } finally {
            adb.exec("-s", serial, "shell", "input", "keyevent", "KEYCODE_HOME")
        }
    }
}
