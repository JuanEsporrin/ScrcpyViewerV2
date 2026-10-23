package com.programmersbox.scrcpyviewer.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Screenshots of the mirrored screen, and the capture file names shared with recordings. */
object Screenshot {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * A copy of [frame]'s pixels, taken inside [VideoFrame.use] (the decoder may release the frame right after).
     * Null when the frame was already released.
     */
    fun copy(frame: VideoFrame): ImageBitmap? = frame.use { src ->
        val pixels = src.readPixels() ?: return@use null
        val copy = Bitmap()
        // installPixels allocates the copy's own pixel memory.
        if (!copy.installPixels(src.imageInfo, pixels, src.rowBytes)) {
            copy.close()
            return@use null
        }
        copy.setImmutable()
        copy.asComposeImageBitmap()
    }

    /**
     * [copy] of the frame [latest] returns; a frame released before the copy (a newer one was just published) is
     * retried with the newer one. Null when there is no frame.
     */
    fun copyLatest(latest: () -> VideoFrame?): ImageBitmap? {
        repeat(3) {
            val frame = latest() ?: return null
            copy(frame)?.let { return it }
        }
        return null
    }

    /**
     * Writes [bitmap] as `<model>-<yyyyMMdd-HHmmss>.png` into [dir] (created if missing) and returns the file. Does
     * file I/O: call it off the UI thread.
     */
    fun save(bitmap: ImageBitmap, dir: Path, model: String): Path {
        val file = captureFile(dir, model, "png")
        val data = Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { it.encodeToData(EncodedImageFormat.PNG) }
            ?: throw IOException("Could not encode the screenshot")
        Files.write(file, data.bytes)
        return file
    }

    /**
     * `<dir>/<model>-<yyyyMMdd-HHmmss>.<ext>`, creating [dir] if missing. The model is made file-name safe; a name
     * already taken in the same second gets `-2`, `-3`, … appended.
     */
    fun captureFile(dir: Path, model: String, ext: String, now: LocalDateTime = LocalDateTime.now()): Path {
        Files.createDirectories(dir)
        val safe = model.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "device" }
        val base = "$safe-${stamp.format(now)}"
        var file = dir.resolve("$base.$ext")
        var n = 2
        while (Files.exists(file)) file = dir.resolve("$base-${n++}.$ext")
        return file
    }
}
