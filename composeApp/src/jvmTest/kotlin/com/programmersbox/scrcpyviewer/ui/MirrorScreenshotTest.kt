package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorContent
import com.programmersbox.scrcpyviewer.ui.mirror.MirrorUiState
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.TextLine
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/** Excluded from the default `jvmTest` run; run with `./gradlew :composeApp:jvmTest -Pscreenshots`. */
class MirrorScreenshotTest {
    private val outDir = Paths.get(System.getProperty("user.dir")).resolve("build/screenshots")

    /** A lock-screen-like 1344x2992 frame. */
    private fun syntheticFrame(): VideoFrame {
        val w = 1344
        val h = 2992
        val bitmap = Bitmap().apply { allocPixels(org.jetbrains.skia.ImageInfo(w, h, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE)) }
        // Vertical teal-to-navy gradient, written as BGRA pixels.
        val pixels = ByteArray(w * h * 4)
        for (y in 0 until h) {
            val t = y.toFloat() / h
            val r = (0x12 + (0x05 - 0x12) * t).toInt()
            val g = (0x40 + (0x0D - 0x40) * t).toInt()
            val b = (0x3F + (0x14 - 0x3F) * t).toInt()
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                pixels[i] = b.toByte(); pixels[i + 1] = g.toByte(); pixels[i + 2] = r.toByte(); pixels[i + 3] = -1
            }
        }
        bitmap.installPixels(pixels)
        val canvas = Canvas(bitmap)
        val white = Paint().apply { color = 0xFFE6F2F1.toInt() }
        canvas.drawTextLine(TextLine.make("9:41", Font(null, 260f)), 100f, 520f, white)
        canvas.drawTextLine(TextLine.make("Friday, October 23", Font(null, 64f)), 110f, 640f, white)
        val icon = Paint().apply { color = 0x29FFFFFF }
        for (row in 0 until 2) for (col in 0 until 4) {
            canvas.drawCircle(220f + col * 300f, 2300f + row * 300f, 110f, icon)
        }
        return VideoFrame.of(bitmap)
    }

    private val state = MirrorUiState(
        model = "Pixel 8 Pro",
        details = "Android 15 · USB",
        status = SessionStatus.Running,
        frameSize = IntSize(1344, 2992),
        maxSize = 1920,
        codec = "H.264",
        bitRate = "8 Mbps",
        fps = 59.8,
        command = "scrcpy -s 39121FDJH004AB --max-size=1920",
    )

    private fun render(name: String, mode: ThemeMode, state: MirrorUiState = this.state) {
        val out = outDir.resolve(name)
        val frame = mutableStateOf<VideoFrame?>(syntheticFrame())
        renderToPng(1280, 800, out) {
            ScrcpyViewerTheme(mode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    MirrorContent(state, frame, {}, {}, {}, {}, {}, {})
                }
            }
        }
        assertTrue(Files.size(out) > 10_000, "$name should not be blank")
    }

    @Test
    fun `mirror light`() = render("mirror-light.png", ThemeMode.Light)

    @Test
    fun `mirror dark`() = render("mirror-dark.png", ThemeMode.Dark)

    @Test
    fun `mirror disconnected`() = render(
        "mirror-disconnected.png", ThemeMode.Light,
        state.copy(status = SessionStatus.Disconnected, error = "video: Connection reset", fps = 0.0, canReconnect = true, actionsEnabled = false),
    )

    @Test
    fun `mirror disconnected waiting dark`() = render(
        "mirror-disconnected-dark.png", ThemeMode.Dark,
        state.copy(status = SessionStatus.Disconnected, fps = 0.0, canReconnect = false, actionsEnabled = false),
    )
}
