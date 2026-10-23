package com.programmersbox.scrcpyviewer.media

import com.programmersbox.scrcpyviewer.scrcpy.MediaPacket
import com.programmersbox.scrcpyviewer.scrcpy.StreamProtocol
import com.programmersbox.scrcpyviewer.scrcpy.TestLogs
import com.programmersbox.scrcpyviewer.scrcpy.VideoCodecMeta
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionRecordingTest {
    init {
        TestLogs.install()
    }

    private val dir: Path = Files.createTempDirectory("session-recording-test")
    private val meta = VideoCodecMeta(StreamProtocol.CODEC_H264, 320, 240)
    private val ended = CopyOnWriteArrayList<RecordResult>()

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun recording(name: String, format: String = "mp4") =
        SessionRecording(dir.resolve(name), format, 0) { _, r -> ended += r }

    private fun SessionRecording.feedVideo() {
        val s = loadH264Sample()
        videoSink.onPacket(MediaPacket(0, isConfig = true, isKeyFrame = false, data = s.config))
        s.frames.forEachIndexed { n, f -> videoSink.onPacket(MediaPacket(n * 100_000L, false, n == 0, f)) }
    }

    private fun SessionRecording.finish(): RecordResult {
        stop()
        assertTrue(join(10_000))
        assertEquals(1, ended.size, "onEnded once: $ended")
        return ended.single()
    }

    @Test
    fun `waits until both streams are known, keeping early packets`() {
        val rec = recording("gated.mp4")
        rec.videoSink.onMeta(meta)
        rec.feedVideo() // before the audio side is known: held back
        assertFalse(rec.started, "audio still unknown")
        rec.audioAbsent()
        assertTrue(rec.started)
        val result = rec.finish()
        assertEquals(RecordResult.Saved(dir.resolve("gated.mp4")), result)
        assertTrue(Files.size(dir.resolve("gated.mp4")) > 0, "the held packets were recorded")
    }

    @Test
    fun `audio known first then video meta starts it`() {
        val rec = recording("order.mkv", "mkv")
        rec.audioAbsent()
        assertFalse(rec.started, "video still unknown")
        rec.videoSink.onMeta(meta)
        assertTrue(rec.started)
        rec.feedVideo()
        assertIs<RecordResult.Saved>(rec.finish())
    }

    @Test
    fun `stopped before both streams are known fails without a recorder`() {
        val rec = recording("never.mp4")
        rec.videoSink.onMeta(meta)
        rec.feedVideo()
        val result = rec.finish()
        assertIs<RecordResult.Failed>(result)
        assertTrue(rec.finished)
        assertFalse(rec.started)
        assertFalse(Files.exists(dir.resolve("never.mp4")))
        rec.stop() // idempotent: onEnded is not called again
        assertEquals(1, ended.size)
    }

    @Test
    fun `a combination the recorder rejects ends at once`() {
        val rec = recording("audio-only.m4a", "m4a")
        rec.audioSink.onCodec(StreamProtocol.CODEC_OPUS)
        rec.videoSink.onMeta(meta) // video in an audio-only container
        assertTrue(rec.finished)
        val result = assertIs<RecordResult.Failed>(ended.single())
        assertEquals("Audio container does not support video stream", result.message)
        rec.feedVideo() // ignored
        rec.stop()
        assertEquals(1, ended.size)
    }
}
