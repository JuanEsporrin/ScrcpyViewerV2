package com.programmersbox.scrcpyviewer.media

import androidx.compose.ui.graphics.asSkiaBitmap
import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.options.OptionValues
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
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.PointerPointer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Real-device recording and screenshot check; excluded from the default `jvmTest` run (see `MirrorDeviceTest`).
 * Records ~10 s with audio through the `record` option, then reads the file back with avformat. The outputs stay in
 * `build/device-test-output` for inspection. It cannot check what the file looks or sounds like in a player.
 */
class RecorderDeviceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adbPath: Path = System.getenv("PATH").split(java.io.File.pathSeparator)
        .map { Paths.get(it, "adb") }.first { Files.isExecutable(it) }
    private val launcher = ServerLauncher(AdbClient(adbPath = { adbPath }), BundledTools(dataDir = Files.createTempDirectory("recorder-device-test")))
    /** `-PdeviceSerials=<serial>`; the test is skipped without it. */
    private val serial: String? = System.getProperty("scrcpyviewer.deviceSerials")?.split(',')?.first()?.takeIf { it.isNotBlank() }
    private val out: Path = Files.createDirectories(Paths.get("build", "device-test-output").toAbsolutePath())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `records 10 s with audio and takes a screenshot`() = runBlocking {
        assumeTrue("no -PdeviceSerials given", serial != null)
        val serial = serial!!
        val file = out.resolve("$serial-record.mp4")
        Files.deleteIfExists(file)
        val session = ScrcpySession(
            serial, OptionValues(mapOf("max-size" to "640", "record" to file.toString())), launcher,
            decoderFactory = { meta, s -> VideoDecoder(meta.codecId, s::publishFrame, s::publishFps) },
            audioFactory = { _, _ -> null }, // record the audio without playing it
            scope = scope,
        )
        val sessionSize = try {
            withTimeout(20_000) {
                session.status.first { it == SessionStatus.Running || it == SessionStatus.Failed }
                check(session.status.value == SessionStatus.Running) { "session failed: ${session.error.value}" }
            }
            val size = withTimeout(10_000) { session.frameSize.first { it != null }!! }
            withTimeout(10_000) { session.frames.first { it != null } }
            val bitmap = assertNotNull(Screenshot.copyLatest { session.frames.value })
            val png = try {
                Screenshot.save(bitmap, out, "device-test")
            } finally {
                bitmap.asSkiaBitmap().close()
            }
            val image = assertNotNull(ImageIO.read(png.toFile()))
            println("[$serial] screenshot $png: ${image.width}x${image.height}")
            assertEquals(size.width, image.width)
            assertEquals(size.height, image.height)
            delay(10_000)
            assertEquals(file, session.recording.value?.file)
            size
        } finally {
            withTimeout(20_000) { session.close() }
        }
        assertEquals(RecordResult.Saved(file), withTimeout(5_000) { session.recordingResults.first() })

        val fmt = AVFormatContext(null)
        check(avformat.avformat_open_input(fmt, file.toString(), null, null) >= 0)
        try {
            check(avformat.avformat_find_stream_info(fmt, null as PointerPointer<*>?) >= 0)
            val types = (0 until fmt.nb_streams()).map { fmt.streams(it).codecpar().codec_type() }
            val duration = fmt.duration() / 1e6
            val v = fmt.streams(types.indexOf(avutil.AVMEDIA_TYPE_VIDEO)).codecpar()
            println("[$serial] $file: streams=$types duration=%.2f s video=${v.width()}x${v.height()} session=$sessionSize size=${Files.size(file)} B".format(duration))
            assertEquals(listOf(avutil.AVMEDIA_TYPE_VIDEO, avutil.AVMEDIA_TYPE_AUDIO), types)
            assertEquals(sessionSize.width, v.width())
            assertEquals(sessionSize.height, v.height())
            // Recording starts with the session, before the 10 s wait (the first frame and the screenshot).
            assertTrue(abs(duration - 10.5) < 1.5, "duration %.2f s".format(duration))
        } finally {
            avformat.avformat_close_input(fmt)
        }
    }
}
