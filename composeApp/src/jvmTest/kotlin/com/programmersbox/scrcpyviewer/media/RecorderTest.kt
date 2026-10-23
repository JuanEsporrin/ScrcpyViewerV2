package com.programmersbox.scrcpyviewer.media

import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.LocalPairLauncher
import com.programmersbox.scrcpyviewer.scrcpy.MediaPacket
import com.programmersbox.scrcpyviewer.scrcpy.RecordingVideoSink
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.scrcpy.StreamProtocol
import com.programmersbox.scrcpyviewer.scrcpy.TestLogs
import com.programmersbox.scrcpyviewer.scrcpy.VideoCodecMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.DataOutputStream
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class RecorderTest {
    init {
        TestLogs.install()
    }

    private val dir: Path = Files.createTempDirectory("recorder-test")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() {
        scope.cancel()
        dir.deleteRecursively()
    }

    // ---- Samples (Task 12's sample.h264, the Ogg Opus sample of Task 13) ----

    private class H264Sample(val config: ByteArray, val frames: List<ByteArray>)

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

    private fun nalType(nal: ByteArray): Int = (if (nal[2] == 1.toByte()) nal[3] else nal[4]).toInt() and 0x1f

    private fun h264(): H264Sample {
        val nals = nalUnits(checkNotNull(javaClass.getResourceAsStream("/sample.h264")).readBytes())
        val config = nals.filter { nalType(it) == 7 || nalType(it) == 8 }
        val frames = ArrayList<ByteArray>()
        var pending = ByteArray(0)
        for (nal in nals - config.toSet()) {
            pending += nal
            if (nalType(nal) in 1..5) {
                frames += pending
                pending = ByteArray(0)
            }
        }
        return H264Sample(config.reduce { a, b -> a + b }, frames)
    }

    private val meta = VideoCodecMeta(StreamProtocol.CODEC_H264, 320, 240)

    private fun Recorder.feedVideo(s: H264Sample, startUs: Long = 0) {
        onVideo(MediaPacket(0, isConfig = true, isKeyFrame = false, data = s.config))
        s.frames.forEachIndexed { n, f ->
            onVideo(MediaPacket(startUs + n * 100_000L, isConfig = false, isKeyFrame = n == 0, data = f))
        }
    }

    /** The OpusHead extradata as config packet, then the media packets of sample.opus. */
    private fun opusPackets(): List<MediaPacket> {
        val file = dir.resolve("sample.opus")
        Files.write(file, checkNotNull(javaClass.getResourceAsStream("/sample.opus")).readBytes())
        val fmt = AVFormatContext(null)
        check(avformat.avformat_open_input(fmt, file.toString(), null, null) >= 0)
        try {
            check(avformat.avformat_find_stream_info(fmt, null as PointerPointer<*>?) >= 0)
            val par = fmt.streams(0).codecpar()
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
    }

    // ---- Reading the result back with avformat ----

    private class StreamInfo(val type: Int, val codecId: Int, val width: Int, val height: Int, val rotation: Double?)
    private class FileInfo(val format: String, val durationSeconds: Double, val streams: List<StreamInfo>)

    private fun probe(file: Path): FileInfo {
        val fmt = AVFormatContext(null)
        check(avformat.avformat_open_input(fmt, file.toString(), null, null) >= 0) { "cannot open $file" }
        try {
            check(avformat.avformat_find_stream_info(fmt, null as PointerPointer<*>?) >= 0)
            val streams = (0 until fmt.nb_streams()).map { i ->
                val par = fmt.streams(i).codecpar()
                var rotation: Double? = null
                for (k in 0 until par.nb_coded_side_data()) {
                    val sd = par.coded_side_data().getPointer(k.toLong())
                    if (sd.type() == avcodec.AV_PKT_DATA_DISPLAYMATRIX) {
                        rotation = avutil.av_display_rotation_get(IntPointer(sd.data()))
                    }
                }
                StreamInfo(par.codec_type(), par.codec_id(), par.width(), par.height(), rotation)
            }
            return FileInfo(fmt.iformat().name().string, fmt.duration() / 1e6, streams)
        } finally {
            avformat.avformat_close_input(fmt)
        }
    }

    private fun record(file: Path, format: String, video: VideoCodecMeta?, audio: Int?, orientation: Int = 0, block: Recorder.() -> Unit): RecordResult {
        var ended: RecordResult? = null
        val recorder = Recorder(file, format, video, audio, orientation, onEnded = { ended = it })
        recorder.block()
        recorder.close()
        return assertNotNull(ended, "onEnded is called before close() returns")
    }

    // ---- Tests ----

    @Test
    fun `records sample h264 to mp4`() {
        val s = h264()
        assertEquals(5, s.frames.size)
        val file = dir.resolve("out.mp4")
        val result = record(file, "mp4", meta, null) { feedVideo(s) }
        assertEquals(RecordResult.Saved(file), result)
        val info = probe(file)
        assertTrue("mp4" in info.format, info.format)
        assertEquals(1, info.streams.size)
        val v = info.streams.single()
        assertEquals(avutil.AVMEDIA_TYPE_VIDEO, v.type)
        assertEquals(avcodec.AV_CODEC_ID_H264, v.codecId)
        assertEquals(320, v.width)
        assertEquals(240, v.height)
        assertNull(v.rotation, "no display matrix at orientation 0")
        // 5 frames 100 ms apart; the last one gets v5.0's arbitrary 100 ms.
        assertTrue(abs(info.durationSeconds - 0.5) < 0.05, "duration ${info.durationSeconds}")
    }

    @Test
    fun `waits for config before writing`() {
        val s = h264()
        val opus = opusPackets()
        val file = dir.resolve("av.mkv")
        val result = record(file, "mkv", meta, StreamProtocol.CODEC_OPUS) {
            feedVideo(s)
            // The audio config packet has not arrived: nothing is written yet.
            Thread.sleep(300)
            assertFalse(headerWritten, "header written before the audio config packet")
            onAudio(opus.first())
            val deadline = System.nanoTime() + 5_000_000_000
            while (!headerWritten && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(headerWritten, "header written once both config packets arrived")
            opus.drop(1).forEach(::onAudio)
        }
        assertEquals(RecordResult.Saved(file), result)
        val info = probe(file)
        assertEquals(listOf(avutil.AVMEDIA_TYPE_VIDEO, avutil.AVMEDIA_TYPE_AUDIO), info.streams.map { it.type })
        assertEquals(avcodec.AV_CODEC_ID_OPUS, info.streams[1].codecId)
        assertEquals(320, info.streams[0].width)
    }

    @Test
    fun `fails when the first packet is not a config packet`() {
        val s = h264()
        val file = dir.resolve("bad.mp4")
        val result = record(file, "mp4", meta, null) {
            onVideo(MediaPacket(0, isConfig = false, isKeyFrame = true, data = s.config + s.frames[0]))
        }
        assertIs<RecordResult.Failed>(result)
        assertFalse(Files.exists(file), "a file without a header is removed")
    }

    @Test
    fun `mkv chosen from extension`() {
        assertEquals("mkv", Recorder.formatFor(Path.of("clip.mkv"), null))
        assertEquals("mp4", Recorder.formatFor(Path.of("clip.mp4"), null))
        assertEquals("opus", Recorder.formatFor(Path.of("a/b.c/clip.opus"), null))
        assertEquals("mkv", Recorder.formatFor(Path.of("clip.mp4"), "mkv"), "record-format wins")
        assertNull(Recorder.formatFor(Path.of("clip"), null))
        assertNull(Recorder.formatFor(Path.of("clip.avi"), null))

        val file = dir.resolve("clip.mkv")
        val format = assertNotNull(Recorder.formatFor(file, null))
        val result = record(file, format, meta, null) { feedVideo(h264()) }
        assertEquals(RecordResult.Saved(file), result)
        val info = probe(file)
        assertTrue("matroska" in info.format, info.format)
        assertEquals(1, info.streams.size)
    }

    @Test
    fun `record orientation sets the display matrix`() {
        val file = dir.resolve("rotated.mp4")
        record(file, "mp4", meta, null, orientation = 90) { feedVideo(h264()) }
        val rotation = assertNotNull(probe(file).streams.single().rotation)
        // v5.0 passes the clockwise angle to av_display_rotation_set (clockwise); the getter reports it
        // counterclockwise, as ffprobe does ("rotation of -90.00 degrees").
        assertEquals(-90.0, rotation, 0.01)
    }

    @Test
    fun `rejects combinations v5_0 rejects`() {
        val f = dir.resolve("x")
        assertFailsWith<IllegalArgumentException> { Recorder(f, "m4a", meta, StreamProtocol.CODEC_OPUS, 0) }
        assertFailsWith<IllegalArgumentException> { Recorder(f, "opus", null, StreamProtocol.CODEC_AAC, 0) }
        assertFailsWith<IllegalArgumentException> { Recorder(f, "wav", null, StreamProtocol.CODEC_OPUS, 0) }
        assertFailsWith<IllegalArgumentException> { Recorder(f, "mp4", null, StreamProtocol.CODEC_RAW, 0) }
        assertFailsWith<IllegalArgumentException> { Recorder(f, "mp4", meta.copy(codecId = StreamProtocol.CODEC_VP8), null, 0) }
        assertFailsWith<IllegalArgumentException> { Recorder(f, "mp4", null, null, 0) }
        assertFailsWith<IllegalArgumentException> { Recorder(f, "avi", meta, null, 0) }
        assertFailsWith<IllegalArgumentException> { Recorder(f, "mp4", meta, null, 45) }
        assertFalse(Files.exists(f))
    }

    @Test
    fun `drops video until the first key frame when started mid-stream`() {
        val s = h264()
        val file = dir.resolve("mid.mp4")
        val result = record(file, "mp4", meta, null) {
            onVideo(MediaPacket(0, isConfig = true, isKeyFrame = false, data = s.config))
            // Two non-key frames from the middle of a GOP come before the key frame.
            onVideo(MediaPacket(1_000_000, isConfig = false, isKeyFrame = false, data = s.frames[3]))
            onVideo(MediaPacket(1_100_000, isConfig = false, isKeyFrame = false, data = s.frames[4]))
            s.frames.forEachIndexed { n, f ->
                onVideo(MediaPacket(2_000_000 + n * 100_000L, isConfig = false, isKeyFrame = n == 0, data = f))
            }
        }
        assertEquals(RecordResult.Saved(file), result)
        assertTrue(abs(probe(file).durationSeconds - 0.5) < 0.05)
    }

    // ---- Through the session ----

    private fun session(launcher: LocalPairLauncher, values: Map<String, String?>) = ScrcpySession(
        "SER", OptionValues(values), launcher,
        decoderFactory = { _, _ -> RecordingVideoSink() },
        audioFactory = { _, _ -> null },
        scope = scope,
    )

    /** Device side of the video socket, after the codec id and session header ([videoOut]). */
    private class FakeVideo(val out: DataOutputStream) {
        fun packet(header: Long, data: ByteArray) {
            out.writeLong(header); out.writeInt(data.size); out.write(data); out.flush()
        }

        fun sendFrames(s: H264Sample, startUs: Long) {
            packet(StreamProtocol.FLAG_CONFIG, s.config)
            s.frames.forEachIndexed { n, f ->
                packet((startUs + n * 100_000L) or (if (n == 0) StreamProtocol.FLAG_KEY_FRAME else 0L), f)
            }
        }
    }

    private suspend fun ScrcpySession.awaitRunning() = withTimeout(5_000) {
        status.first { it == SessionStatus.Running || it == SessionStatus.Failed }
        assertEquals(SessionStatus.Running, status.value, "error: ${error.value}")
    }

    private fun videoOut(launcher: LocalPairLauncher) = runBlocking {
        val out = DataOutputStream(launcher.device.await().video!!.getOutputStream())
        // Session header: bit 63 set, then width and height.
        out.writeInt(StreamProtocol.CODEC_H264)
        out.writeInt(0x80000000.toInt()); out.writeInt(320); out.writeInt(240); out.flush()
        FakeVideo(out)
    }

    @Test
    fun `record option records from session start and close finishes the file`() = runBlocking {
        val file = dir.resolve("sub/start.mp4")
        val launcher = LocalPairLauncher(video = true, audio = false, control = false)
        val s = session(launcher, mapOf("record" to file.toString()))
        s.awaitRunning()
        assertEquals(file, s.recording.value?.file, "recording from the start")
        videoOut(launcher).sendFrames(h264(), startUs = 5_000_000)
        delay(300)
        withTimeout(15_000) { s.close() } // teardown waits for the trailer
        assertNull(s.recording.value)
        assertEquals(RecordResult.Saved(file), withTimeout(5_000) { s.recordingResults.first() })
        val info = probe(file)
        assertEquals(320, info.streams.single().width)
        assertTrue(abs(info.durationSeconds - 0.5) < 0.05, "duration ${info.durationSeconds}")
    }

    @Test
    fun `recording started mid-stream asks for a key frame and stopRecording saves`() = runBlocking {
        val launcher = LocalPairLauncher(video = true, audio = false, control = true)
        val s = session(launcher, emptyMap())
        s.awaitRunning()
        val video = videoOut(launcher)
        val sample = h264()
        video.sendFrames(sample, startUs = 0)
        withTimeout(5_000) { while (s.frameSize.value == null) delay(10) }
        delay(200) // the config packet has been read
        val file = dir.resolve("mid.mkv")
        s.startRecording(file)
        val reset = ControlMessage.ResetVideo.serialize()
        val got = ByteArray(reset.size)
        withContext(Dispatchers.IO) { DataInputStream(launcher.device.await().control!!.getInputStream()).readFully(got) }
        assertContentEquals(reset, got)
        // After the reset: a new config packet and a key frame.
        video.sendFrames(sample, startUs = 1_000_000)
        delay(300)
        s.stopRecording()
        assertNull(s.recording.value)
        assertEquals(RecordResult.Saved(file), withTimeout(5_000) { s.recordingResults.first() })
        val info = probe(file)
        assertTrue("matroska" in info.format)
        assertTrue(abs(info.durationSeconds - 0.5) < 0.05, "duration ${info.durationSeconds}")
        assertFailsWith<IllegalArgumentException> { s.startRecording(dir.resolve("noext")) }
        s.close()
    }

    @Test
    fun `time-limit stops the recording`() = runBlocking {
        val file = dir.resolve("limited.mp4")
        val launcher = LocalPairLauncher(video = true, audio = false, control = false)
        val s = session(launcher, mapOf("record" to file.toString(), "time-limit" to "1"))
        s.awaitRunning()
        videoOut(launcher).sendFrames(h264(), startUs = 0)
        assertEquals(RecordResult.Saved(file), withTimeout(5_000) { s.recordingResults.first() })
        assertNull(s.recording.value)
        assertEquals(SessionStatus.Running, s.status.value, "only the recording stops")
        s.close()
    }

    @Test
    fun `an unwritable record target is reported`() = runBlocking {
        val blocker = Files.createFile(dir.resolve("file"))
        val launcher = LocalPairLauncher(video = true, audio = false, control = false)
        val s = session(launcher, mapOf("record" to blocker.resolve("x.mp4").toString()))
        val result = withTimeout(5_000) { s.recordingResults.first() }
        assertIs<RecordResult.Failed>(result)
        s.awaitRunning() // mirroring goes on
        assertNull(s.recording.value)
        s.close()
    }
}
