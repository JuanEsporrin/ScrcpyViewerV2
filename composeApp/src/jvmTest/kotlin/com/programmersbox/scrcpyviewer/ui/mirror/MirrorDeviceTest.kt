package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.graphics.asSkiaBitmap
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.adb.DeviceTracker
import com.programmersbox.scrcpyviewer.media.VideoDecoder
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.ServerLauncher
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.tools.BundledTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-device checks; excluded from the default `jvmTest` run. Run with
 * `./gradlew :composeApp:jvmTest -PdeviceTests [-PdeviceSerials=SERIAL1,SERIAL2]`.
 * Uses the `adb` on PATH (the one whose server is already running) and only non-destructive options.
 */
class MirrorDeviceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adbPath: Path = System.getenv("PATH").split(java.io.File.pathSeparator)
        .map { Paths.get(it, "adb") }.first { Files.isExecutable(it) }
    private val adb = AdbClient(adbPath = { adbPath })
    private val tools = BundledTools(dataDir = Files.createTempDirectory("mirror-device-test"))
    private val launcher = ServerLauncher(adb, tools)
    private val decoders = ConcurrentHashMap<ScrcpySession, VideoDecoder>()
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")

    private val serials: List<String> = System.getProperty("scrcpyviewer.deviceSerials")?.split(',')
        ?: adbLines("devices").drop(1).filter { it.endsWith("\tdevice") }.map { it.substringBefore('\t') }
    private val primary = serials.firstOrNull { it == "MBX6P240401889" } ?: serials.first()

    private val values = OptionValues(mapOf("max-size" to "1024", "no-audio" to null))

    private fun adbLines(vararg args: String): List<String> {
        val p = ProcessBuilder(listOf(adbPath.toString()) + args).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readLines().also { p.waitFor() }
    }

    private fun session(serial: String): ScrcpySession = ScrcpySession(
        serial, values, launcher,
        decoderFactory = { meta, s -> VideoDecoder(meta.codecId, s::publishFrame, s::publishFps).also { decoders[s] = it } },
        audioFactory = { _, _ -> null },
        scope = scope,
    )

    private suspend fun ScrcpySession.awaitFrames(count: Long, timeoutMs: Long = 20_000): VideoDecoder = withTimeout(timeoutMs) {
        status.first { it == SessionStatus.Running }
        while (decoders[this@awaitFrames] == null) delay(10)
        val d = decoders.getValue(this@awaitFrames)
        while (d.decodedFrames < count) {
            check(status.value == SessionStatus.Running) { "session ended: ${error.value}" }
            delay(20)
        }
        d
    }

    @AfterTest
    fun tearDown() {
        runBlocking { decoders.keys.forEach { it.close() } }
        scope.cancel()
    }

    @Test
    fun `a decodes frames and writes one to png`() = runBlocking<Unit> {
        val s = session(primary)
        val start = System.nanoTime()
        s.awaitFrames(1)
        println("[device] $primary: first frame after ${(System.nanoTime() - start) / 1_000_000} ms")
        // A still screen only gets repeated frames (a few per second); open and close the shade to get motion.
        s.send(ControlMessage.ExpandNotificationPanel)
        delay(700)
        s.send(ControlMessage.CollapsePanels)
        val d = s.awaitFrames(30)
        println("[device] $primary: 30 frames in ${(System.nanoTime() - start) / 1_000_000} ms, size ${s.frameSize.value}, name ${s.deviceName.value}")
        val bitmap = d.frame.value!!.asSkiaBitmap()
        val pixels = bitmap.readPixels()!!
        val distinct = (0 until pixels.size / 4).map { i -> pixels[i * 4].toInt() and 0xff }.toSet().size
        println("[device] frame content: $distinct distinct blue levels")
        assertTrue(distinct > 16, "the decoded frame is a real image, not blank")
        val png = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!
        Files.createDirectories(outDir)
        Files.write(outDir.resolve("mirror-frame.png"), png.bytes)
        s.close()
    }

    @Test
    fun `b home key resumes the launcher`() = runBlocking<Unit> {
        val home = adbLines("-s", primary, "shell", "cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").last().trim()
        val s = session(primary)
        s.awaitFrames(1)
        s.send(ControlMessage.InjectKeycode(InputMapper.KEY_ACTION_DOWN, InputMapper.KEYCODE_HOME, 0, 0))
        s.send(ControlMessage.InjectKeycode(InputMapper.KEY_ACTION_UP, InputMapper.KEYCODE_HOME, 0, 0))
        delay(1500)
        val resumed = adbLines("-s", primary, "shell", "dumpsys activity activities | grep -m1 mResumedActivity").joinToString(" ")
        println("[device] home activity: $home")
        println("[device] after HOME: $resumed")
        assertTrue(resumed.contains(home.substringBefore('/')), "launcher ${home} should be resumed: $resumed")
        s.close()
    }

    @Test
    fun `c two devices at once, closing one keeps the other`() = runBlocking<Unit> {
        // Two devices when attached; else two sessions on one device (each server has its own scid).
        val other = serials.firstOrNull { it != primary } ?: primary.also {
            println("[device] only $primary attached: running two sessions on it")
        }
        val a = session(primary)
        val b = session(other)
        val da = a.awaitFrames(10)
        val db = b.awaitFrames(10, timeoutMs = 30_000)
        println("[device] both running: $primary ${a.frameSize.value}, $other ${b.frameSize.value}")
        a.close()
        assertEquals(SessionStatus.Disconnected, a.status.value)
        val before = db.decodedFrames
        // Animate the other device so it keeps producing frames, then collapse again.
        b.send(ControlMessage.ExpandNotificationPanel)
        delay(1000)
        b.send(ControlMessage.CollapsePanels)
        delay(2000)
        val after = db.decodedFrames
        println("[device] $other after closing $primary: frames $before -> $after, status ${b.status.value}; closed one stayed at ${da.decodedFrames}")
        assertEquals(SessionStatus.Running, b.status.value)
        assertTrue(after > before, "the other session keeps decoding")
        b.close()
    }

    @Test
    fun `d decoded fps over 5 s`() = runBlocking<Unit> {
        val s = session(primary)
        val d = s.awaitFrames(5)
        // Idle screen: the encoder only repeats frames.
        var n0 = d.decodedFrames
        var t0 = System.nanoTime()
        delay(5000)
        val idle = (d.decodedFrames - n0) * 1e9 / (System.nanoTime() - t0)
        // Moving screen: open and close the notification shade for 5 s.
        n0 = d.decodedFrames
        t0 = System.nanoTime()
        repeat(5) {
            s.send(ControlMessage.ExpandNotificationPanel)
            delay(500)
            s.send(ControlMessage.CollapsePanels)
            delay(500)
        }
        val moving = (d.decodedFrames - n0) * 1e9 / (System.nanoTime() - t0)
        println("[device] $primary fps over 5 s: idle %.1f, animating %.1f (session.fps=%.1f)".format(idle, moving, s.fps.value))
        // One fresh bitmap per frame: native memory must stay flat (replaced frames are released after a delay).
        fun rssMb() = ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString())
            .start().inputStream.bufferedReader().readText().trim().toLong() / 1024
        val samples = ArrayList<String>()
        repeat(3) {
            repeat(5) {
                s.send(ControlMessage.ExpandNotificationPanel)
                delay(500)
                s.send(ControlMessage.CollapsePanels)
                delay(500)
            }
            samples += "${d.decodedFrames} frames: ${rssMb()} MB"
        }
        println("[device] test JVM RSS while animating: ${samples.joinToString("; ")}")
        // Still screen for a while: fps must go back down, not stick at the last value.
        delay(2500)
        println("[device] fps 2.5 s after the animation: %.1f".format(s.fps.value))
        assertTrue(moving > 0)
        s.close()
    }

    @Test
    fun `e killed server shows disconnected and reconnect works`() = runBlocking<Unit> {
        val tracker = DeviceTracker(adb, scope)
        val controller = MirrorController(
            scope, tracker.devices,
            optionsFor = { values },
            newSession = { serial, v -> session(serial) },
        )
        withTimeout(10_000) { tracker.devices.first { list -> list.any { it.serial == primary && it.state == DeviceState.Device } } }
        controller.open(primary)
        val first = controller.open.value.getValue(primary)
        first.awaitFrames(5)
        adbLines("-s", primary, "shell", "pkill", "-f", "com.genymobile.scrcpy.Server")
        withTimeout(10_000) { first.status.first { it == SessionStatus.Disconnected } }
        println("[device] after pkill: status ${first.status.value}, error ${first.error.value}")
        withTimeout(10_000) { controller.reconnectable.first { primary in it } }
        controller.reconnect(primary)
        val second = controller.open.value.getValue(primary)
        val d = second.awaitFrames(10)
        println("[device] reconnected: ${second.status.value}, ${d.decodedFrames} frames")
        controller.closeAll()
    }
}
