package com.programmersbox.scrcpyviewer.media

import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.file.Files
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenshotSaveTest {
    private val dir = Files.createTempDirectory("screenshot-test")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A 4x3 BGRA bitmap: left half red, right half blue. */
    private fun bitmap(): Bitmap {
        val info = ImageInfo(4, 3, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val bytes = ByteArray(4 * 3 * 4)
        for (y in 0 until 3) for (x in 0 until 4) {
            val i = (y * 4 + x) * 4
            if (x < 2) bytes[i + 2] = 0xff.toByte() else bytes[i] = 0xff.toByte() // B G R A
            bytes[i + 3] = 0xff.toByte()
        }
        return Bitmap().apply {
            allocPixels(info)
            installPixels(info, bytes, 16)
            setImmutable()
        }
    }

    @Test
    fun `copies the frame inside use and saves a png named after the model`() {
        val src = bitmap()
        val copy = assertNotNull(Screenshot.copy(VideoFrame.of(src)))
        src.close() // the decoder may release the frame right after the copy
        val sub = dir.resolve("captures")
        val file = Screenshot.save(copy, sub, "Pixel 6 Pro")
        copy.asSkiaBitmap().close()
        assertTrue(Regex("""Pixel_6_Pro-\d{8}-\d{6}\.png""").matches(file.fileName.toString()), file.fileName.toString())
        assertEquals(sub, file.parent)
        val image = assertNotNull(ImageIO.read(file.toFile()), "a readable PNG")
        assertEquals(4, image.width)
        assertEquals(3, image.height)
        assertEquals(0xffff0000.toInt(), image.getRGB(0, 0))
        assertEquals(0xff0000ff.toInt(), image.getRGB(3, 2))
    }

    @Test
    fun `a released frame gives no copy`() {
        val frame = VideoFrame(bitmap(), Any())
        frame.release()
        assertNull(Screenshot.copy(frame))
    }

    @Test
    fun `capture names never overwrite within the same second`() {
        val now = LocalDateTime.of(2026, 10, 23, 9, 5, 7)
        val first = Screenshot.captureFile(dir, "a/b:c", "mp4", now)
        assertEquals("a_b_c-20261023-090507.mp4", first.fileName.toString())
        Files.createFile(first)
        assertEquals("a_b_c-20261023-090507-2.mp4", Screenshot.captureFile(dir, "a/b:c", "mp4", now).fileName.toString())
    }
}
