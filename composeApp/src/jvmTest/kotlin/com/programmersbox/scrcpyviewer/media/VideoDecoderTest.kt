package com.programmersbox.scrcpyviewer.media

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

    @Test
    fun `decodes sample h264`() {
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
        assertEquals(5, frames.size)

        val decoder = VideoDecoder(StreamProtocol.CODEC_H264)
        try {
            decoder.onMeta(VideoCodecMeta(StreamProtocol.CODEC_H264, 320, 240))
            decoder.onPacket(MediaPacket(0, isConfig = true, isKeyFrame = false, data = config.reduce { a, b -> a + b }))
            frames.forEachIndexed { n, f ->
                decoder.onPacket(MediaPacket(n * 100_000L, isConfig = false, isKeyFrame = n == 0, data = f))
            }
            assertEquals(IntSize(320, 240), decoder.frameSize.value)
            val frame = assertNotNull(decoder.frame.value, "at least one frame decoded")
            assertEquals(320, frame.width)
            assertEquals(240, frame.height)
            assertTrue(decoder.decodedFrames >= 1)
        } finally {
            decoder.close()
        }
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
