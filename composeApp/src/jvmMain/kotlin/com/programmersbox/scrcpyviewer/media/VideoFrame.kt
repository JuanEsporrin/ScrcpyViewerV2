package com.programmersbox.scrcpyviewer.media

import org.jetbrains.skia.Bitmap

/**
 * One decoded frame: an immutable BGRA Skia [Bitmap] whose native memory is released as soon as a newer frame
 * replaces it. Every access to the pixels must go through [use], which holds the frame's lock (shared with the
 * decoder, which takes it to release the bitmap), so a released bitmap is never touched. Copy inside [use] to keep
 * the pixels longer (screenshot, recording). [width] and [height] can be read at any time.
 */
class VideoFrame internal constructor(
    private val bitmap: Bitmap,
    private val lock: Any,
) {
    val width: Int = bitmap.width
    val height: Int = bitmap.height

    private var released = false // guarded by lock

    /** Runs [block] with the bitmap, or returns null if the frame was already released. */
    fun <T> use(block: (Bitmap) -> T): T? = synchronized(lock) {
        if (released) null else block(bitmap)
    }

    val isReleased: Boolean get() = synchronized(lock) { released }

    /** Releases the native pixels; later [use] calls return null. */
    internal fun release() = synchronized(lock) {
        if (!released) {
            released = true
            bitmap.close()
        }
    }

    companion object {
        /** A standalone frame (previews, screenshots) that is never released by a decoder. */
        fun of(bitmap: Bitmap): VideoFrame = VideoFrame(bitmap, Any())
    }
}
