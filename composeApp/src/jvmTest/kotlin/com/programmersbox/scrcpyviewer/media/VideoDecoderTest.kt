package com.programmersbox.scrcpyviewer.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.MediaPacket
import com.programmersbox.scrcpyviewer.scrcpy.StreamProtocol
import com.programmersbox.scrcpyviewer.scrcpy.VideoCodecMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VideoDecoderTest {

    /** Annex B NAL units (start codes stripped from the split points, kept in the output). */
    private fun nalUnits(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                starts += if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
                i += 3
            } else i++
        }
        return starts.mapIndexed { n, s -> data.copyOfRange(s, starts.getOrElse(n + 1) { data.size }) }
    }

    private fun nalType(nal: ByteArray): Int {
        val header = if (nal[2] == 1.toByte()) nal[3] else nal[4]
        return header.toInt() and 0x1f
    }

    private class Sample(val config: ByteArray, val frames: List<ByteArray>)

    private fun sample(): Sample {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/sample.h264")).readBytes()
        val nals = nalUnits(bytes)
        val config = nals.filter { nalType(it) == 7 || nalType(it) == 8 }
        // Each frame packet ends with its VCL NAL (types 1..5); SEI etc. go with the next frame.
        val frames = ArrayList<ByteArray>()
        var pending = ByteArray(0)
        for (nal in nals - config.toSet()) {
            pending += nal
            if (nalType(nal) in 1..5) {
                frames += pending
                pending = ByteArray(0)
            }
        }
        return Sample(config.reduce { a, b -> a + b }, frames)
    }

    private fun VideoDecoder.feed(s: Sample) {
        onMeta(VideoCodecMeta(StreamProtocol.CODEC_H264, 320, 240))
        onPacket(MediaPacket(0, isConfig = true, isKeyFrame = false, data = s.config))
        s.frames.forEachIndexed { n, f -> onPacket(MediaPacket(n * 100_000L, isConfig = false, isKeyFrame = n == 0, data = f)) }
    }

    @Test
    fun `decodes sample h264`() {
        val s = sample()
        assertEquals(5, s.frames.size)
        val published = ArrayList<ImageBitmap>()
        val decoder = VideoDecoder(StreamProtocol.CODEC_H264, onFrame = { published += it })
        try {
            decoder.feed(s)
            assertEquals(IntSize(320, 240), decoder.frameSize.value)
            val frame = assertNotNull(decoder.frame.value, "at least one frame decoded")
            assertEquals(320, frame.width)
            assertEquals(240, frame.height)
            assertTrue(decoder.decodedFrames >= 1)
            // testsrc is colourful: the converted pixels must not be blank.
            val pixels = assertNotNull(frame.asSkiaBitmap().readPixels())
            assertTrue(pixels.count { it != 0.toByte() } > pixels.size / 4, "decoded frame has content")
            // Every frame is its own immutable bitmap: nothing published is written again.
            assertEquals(published.size, published.map { System.identityHashCode(it.asSkiaBitmap()) }.distinct().size)
            assertTrue(frame.asSkiaBitmap().isImmutable)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun `config packet alone is not decoded and is merged into the next packet`() {
        val s = sample()
        val decoder = VideoDecoder(StreamProtocol.CODEC_H264)
        try {
            decoder.onPacket(MediaPacket(0, isConfig = true, isKeyFrame = false, data = s.config))
            assertEquals(0, decoder.decodedFrames)
            // The first frame decodes only because the config was prepended to it.
            s.frames.forEachIndexed { n, f -> decoder.onPacket(MediaPacket(n * 100_000L, false, n == 0, f)) }
            assertTrue(decoder.decodedFrames >= 1)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun `replaced frames are released after the delay, the current one is kept`() {
        val published = ArrayList<ImageBitmap>()
        val decoder = VideoDecoder(StreamProtocol.CODEC_H264, onFrame = { published += it })
        try {
            decoder.feed(sample())
            Thread.sleep(VideoDecoder.RELEASE_DELAY_MS + 50)
            decoder.feed(sample()) // the next publish retires the old ones
            val first = published.first().asSkiaBitmap()
            assertTrue(first.isClosed, "a frame replaced long ago is released")
            assertTrue(!decoder.frame.value!!.asSkiaBitmap().isClosed, "the current frame is kept")
        } finally {
            decoder.close()
        }
        assertTrue(!decoder.frame.value!!.asSkiaBitmap().isClosed, "the last frame survives close()")
    }

    @Test
    fun `fps drops to zero without frames and on close`() {
        val decoder = VideoDecoder(StreamProtocol.CODEC_H264)
        try {
            decoder.feed(sample())
            Thread.sleep(1_200)
            assertTrue(decoder.fps.value > 0.0, "frames in the first second: ${decoder.fps.value}")
            Thread.sleep(1_100)
            assertEquals(0.0, decoder.fps.value, "no frames in the last second")
            decoder.feed(sample())
        } finally {
            decoder.close()
        }
        assertEquals(0.0, decoder.fps.value)
    }

    @Test
    fun `session resize reallocates and updates frame size`() {
        val decoder = VideoDecoder(StreamProtocol.CODEC_H264)
        try {
            decoder.onMeta(VideoCodecMeta(StreamProtocol.CODEC_H264, 320, 240))
            assertEquals(IntSize(320, 240), decoder.frameSize.value)
            decoder.onSession(240, 320)
            assertEquals(IntSize(240, 320), decoder.frameSize.value)
        } finally {
            decoder.close()
        }
    }
}
