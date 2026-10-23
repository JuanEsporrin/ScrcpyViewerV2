package com.programmersbox.scrcpyviewer.media

import com.programmersbox.scrcpyviewer.scrcpy.MediaPacket
import com.programmersbox.scrcpyviewer.scrcpy.StreamProtocol
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.PointerPointer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AudioPlayerTest {

    // ---- Ring buffer: the cases of scrcpy v5.0 app/tests/test_audiobuf.c (4-byte samples) ----

    private fun ints(vararg v: Int): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putInt(it) } }.array()

    private fun AudioRingBuffer.readInts(count: Int): IntArray {
        val out = ByteArray(count * 4)
        val r = read(out, 0, count)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(r) { bb.getInt(it * 4) }
    }

    @Test
    fun `ring buffer simple`() {
        val buf = AudioRingBuffer(frameBytes = 4, capacity = 20)
        assertEquals(5, buf.write(ints(1, 2, 3, 4, 5), 0, 5))
        assertContentEquals(intArrayOf(1, 2, 3, 4), buf.readInts(4))
        assertEquals(3, buf.write(ints(6, 7, 8), 0, 3))
        assertEquals(1, buf.write(ints(9), 0, 1))
        assertContentEquals(intArrayOf(5, 6, 7, 8, 9), buf.readInts(8))
    }

    @Test
    fun `ring buffer boundaries`() {
        val buf = AudioRingBuffer(frameBytes = 4, capacity = 20)
        val samples = ints(1, 2, 3, 4, 5, 6)
        repeat(3) { assertEquals(6, buf.write(samples, 0, 6)) }
        assertContentEquals(intArrayOf(1, 2, 3, 4, 5, 6, 1, 2, 3), buf.readInts(9))
        assertEquals(5, buf.write(ints(7, 8, 9, 10, 11), 0, 5))
        assertEquals(1, buf.write(ints(12), 0, 1))
        assertEquals(3, buf.read(null, 0, 3))
        assertEquals(12, buf.available)
        assertContentEquals(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), buf.readInts(12))
    }

    @Test
    fun `ring buffer partial read and write`() {
        val buf = AudioRingBuffer(frameBytes = 4, capacity = 10)
        val samples = ints(1, 2, 3, 4, 5, 6)
        assertEquals(6, buf.write(samples, 0, 6))
        assertEquals(4, buf.write(samples, 0, 6))
        assertEquals(0, buf.write(samples, 0, 6))
        assertContentEquals(intArrayOf(1, 2, 3), buf.readInts(3))
        assertEquals(3, buf.write(samples, 0, 6))
        assertContentEquals(intArrayOf(4, 5, 6, 1, 2, 3, 4, 1, 2, 3), buf.readInts(15))
        assertEquals(4, buf.writeSilence(4))
        assertContentEquals(intArrayOf(0, 0, 0, 0), buf.readInts(4))
    }

    // ---- Jitter buffer ----

    private fun frames(from: Int, count: Int): ByteArray = ints(*IntArray(count) { from + it })

    private fun JitterBuffer.pullInts(count: Int): IntArray {
        val out = ByteArray(count * 4) { 0x55 }
        pull(out, count)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(count) { bb.getInt(it * 4) }
    }

    @Test
    fun `jitter buffer drops oldest on overrun`() {
        // 1000 Hz so 1 ms = 1 frame: target 10 frames; before playback the cap is target + 10 ms = 20 frames.
        val jb = JitterBuffer(frameBytes = 4, sampleRate = 1000, targetFrames = 10)
        jb.push(frames(1, 30), 30)
        assertEquals(20, jb.available)
        assertEquals(10, jb.stats().droppedFrames)
        assertContentEquals(IntArray(20) { 11 + it }, jb.pullInts(20), "the oldest 10 frames were dropped")

        // While playing, the ring capacity (here 40) is the hard limit and the cap is target * 1.1 + 60 ms = 71.
        val small = JitterBuffer(frameBytes = 4, sampleRate = 1000, targetFrames = 10, capacityFrames = 40)
        small.push(frames(1, 10), 10)
        small.pullInts(5) // starts playback
        small.push(frames(100, 50), 50)
        assertEquals(40, small.available)
        assertContentEquals(IntArray(40) { 110 + it }, small.pullInts(40), "only the newest frames remain")
    }

    @Test
    fun `jitter buffer plays silence on underrun`() {
        val jb = JitterBuffer(frameBytes = 4, sampleRate = 1000, targetFrames = 10)
        // Before the target is reached nothing is played.
        assertContentEquals(IntArray(8), jb.pullInts(8))
        jb.push(frames(1, 5), 5)
        assertContentEquals(IntArray(8), jb.pullInts(8))
        assertEquals(0, jb.stats().underruns, "initial buffering is not an underrun")
        assertEquals(5, jb.available)

        jb.push(frames(6, 10), 10) // 15 buffered >= target: playback starts
        assertContentEquals(IntArray(8) { 1 + it }, jb.pullInts(8))
        assertTrue(jb.isPlaying)
        // 7 left: the pull runs short and the rest is silence.
        assertContentEquals(IntArray(8) { if (it < 7) 9 + it else 0 }, jb.pullInts(8))
        assertEquals(1, jb.stats().underruns)
        assertEquals(1, jb.stats().underrunFrames)
        assertTrue(!jb.isPlaying, "re-buffering after an underrun")

        // Re-buffers: 5 frames are not enough to resume.
        jb.push(frames(100, 5), 5)
        assertContentEquals(IntArray(4), jb.pullInts(4))
        jb.push(frames(105, 5), 5)
        assertContentEquals(IntArray(4) { 100 + it }, jb.pullInts(4))
        assertEquals(1, jb.stats().underruns)
    }

    @Test
    fun `jitter buffer slows down when buffering stays above target`() {
        val jb = JitterBuffer(frameBytes = 4, sampleRate = 1000, targetFrames = 100)
        jb.push(frames(0, 100), 100)
        jb.pullInts(1) // start
        var compensation: Int? = null
        // Keep ~150 frames buffered when pushing (above the target of 100, below the drop threshold of 170) for
        // more than a second of input.
        repeat(30) {
            compensation = jb.push(frames(0, 50), 50) ?: compensation
            jb.pullInts(50)
        }
        assertEquals(0, jb.stats().droppedFrames)
        assertEquals(0, jb.stats().underruns)
        val c = assertNotNull(compensation, "a compensation was computed after 1 s of input")
        assertTrue(c < 0, "drops samples to shrink the buffering, got $c")
        assertTrue(c >= -JitterBuffer.compensationDistance(1000) / 50, "limited to 2%")
    }

    // ---- Decoding ----

    /** Reads the Ogg Opus sample with avformat: the OpusHead extradata as config packet, then the media packets. */
    private fun opusPackets(): List<MediaPacket> {
        val file = Files.createTempFile("sample", ".opus")
        try {
            Files.write(file, checkNotNull(javaClass.getResourceAsStream("/sample.opus")).readBytes())
            val fmt = AVFormatContext(null)
            check(avformat.avformat_open_input(fmt, file.toString(), null, null) >= 0)
            try {
                check(avformat.avformat_find_stream_info(fmt, null as PointerPointer<*>?) >= 0)
                val par = fmt.streams(0).codecpar()
                assertEquals(avcodec.AV_CODEC_ID_OPUS, par.codec_id())
                val extradata = ByteArray(par.extradata_size()).also { par.extradata().get(it) }
                val out = mutableListOf(MediaPacket(0, isConfig = true, isKeyFrame = false, data = extradata))
                val pkt = AVPacket()
                while (avformat.av_read_frame(fmt, pkt) >= 0) {
                    val data = ByteArray(pkt.size()).also { pkt.data().get(it) }
                    val ptsUs = avutil.av_rescale_q(pkt.pts(), fmt.streams(0).time_base(), avutil.av_make_q(1, 1_000_000))
                    out += MediaPacket(ptsUs.coerceAtLeast(0), isConfig = false, isKeyFrame = true, data = data)
                    avcodec.av_packet_unref(pkt)
                }
                return out
            } finally {
                avformat.avformat_close_input(fmt)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** An output that never consumes (the playback thread parks in write until close). */
    private class StalledOutput : AudioOutput {
        val stopped = CountDownLatch(1)
        override val bufferBytes = 1920
        override fun write(buf: ByteArray, len: Int) = stopped.await()
        override fun close() = stopped.countDown()
    }

    @Test
    fun `decodes opus sample`() {
        val packets = opusPackets()
        val output = StalledOutput()
        val player = AudioPlayer(StreamProtocol.CODEC_OPUS, bufferMs = 50, outputBufferMs = 10, openOutput = { _, _ -> output })
        try {
            player.onCodec(StreamProtocol.CODEC_OPUS)
            packets.forEach(player::onPacket)
            val frames = player.decodedFrames
            assertTrue(abs(frames - 48_000) <= 48_000 * 5 / 100, "~48000 PCM frames (1 s at 48 kHz), got $frames")
        } finally {
            player.close()
        }
        assertEquals(0L, output.stopped.count, "close stops the output")
    }

    @Test
    fun `decodes raw pcm`() {
        // 0.5 s of raw S16LE stereo, sent in 10 ms packets, as the device does with --audio-codec=raw.
        val output = StalledOutput()
        val player = AudioPlayer(StreamProtocol.CODEC_RAW, bufferMs = 50, outputBufferMs = 10, openOutput = { _, _ -> output })
        try {
            player.onCodec(StreamProtocol.CODEC_RAW)
            repeat(50) { n -> player.onPacket(MediaPacket(n * 10_000L, false, true, ByteArray(480 * 4) { 1 })) }
            assertEquals(24_000L, player.decodedFrames)
        } finally {
            player.close()
        }
    }
}
