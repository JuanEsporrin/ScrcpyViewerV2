package com.programmersbox.scrcpyviewer.media

import com.programmersbox.scrcpyviewer.scrcpy.AudioSink
import com.programmersbox.scrcpyviewer.scrcpy.MediaPacket
import com.programmersbox.scrcpyviewer.scrcpy.VideoCodecMeta
import com.programmersbox.scrcpyviewer.scrcpy.VideoSink
import kotlinx.coroutines.Job
import java.nio.file.Path

/**
 * Feeds one session recording: [videoSink] and [audioSink] are attached to the session's streams. The [Recorder]
 * is created once both streams are known (meta and codec id, or marked absent with [videoAbsent]/[audioAbsent]);
 * packets arriving before that wait here.
 *
 * [onEnded] runs exactly once: from the recorder thread when it closes the file, or from the caller of a sink
 * method / [stop] when the recording fails before the recorder exists. It is never called under a lock the caller
 * may hold, other than this object's own.
 */
class SessionRecording(
    val file: Path,
    private val format: String,
    private val orientation: Int,
    private val onEnded: (SessionRecording, RecordResult) -> Unit,
) {
    private val lock = Any()
    private var meta: VideoCodecMeta? = null
    private var videoKnown = false
    private var codec: Int? = null
    private var audioKnown = false
    private val pending = ArrayList<Pair<Boolean, MediaPacket>>()
    private var recorder: Recorder? = null
    private var stopped = false
    @Volatile private var ended = false

    /** The `time-limit` job, cancelled by the session when the recording ends. */
    @Volatile var timeLimitJob: Job? = null

    /** The recorder exists (both streams are known). */
    val started: Boolean get() = synchronized(lock) { recorder != null }

    /** Stopped, failed or ended: no packet will be recorded any more. */
    val finished: Boolean get() = ended || synchronized(lock) { stopped }

    val videoSink: VideoSink = object : VideoSink {
        override fun onMeta(meta: VideoCodecMeta) = synchronized(lock) {
            this@SessionRecording.meta = meta
            videoKnown = true
            tryStart()
        }

        // v5.0 recorder.c has no push_session: a resized stream keeps its initial size in the file.
        override fun onSession(width: Int, height: Int) = Unit
        override fun onPacket(p: MediaPacket) = packet(video = true, p)
        override fun close() = stop()
    }

    val audioSink: AudioSink = object : AudioSink {
        override fun onCodec(codecId: Int) = synchronized(lock) {
            codec = codecId
            audioKnown = true
            tryStart()
        }

        override fun onPacket(p: MediaPacket) = packet(video = false, p)
        override fun close() = stop()
    }

    /** The session has no video stream. */
    fun videoAbsent() = synchronized(lock) {
        if (!videoKnown) {
            videoKnown = true
            tryStart()
        }
    }

    /** The session has no audio stream (none requested, or disabled by the device). */
    fun audioAbsent() = synchronized(lock) {
        if (!audioKnown) {
            audioKnown = true
            tryStart()
        }
    }

    private fun packet(video: Boolean, p: MediaPacket): Unit = synchronized(lock) {
        if (stopped) return
        val r = recorder
        when {
            r == null -> pending += video to p
            video -> r.onVideo(p)
            else -> r.onAudio(p)
        }
    }

    /** Caller holds [lock]. */
    private fun tryStart() {
        if (recorder != null || stopped || !videoKnown || !audioKnown) return
        val r = try {
            Recorder(file, format, meta, codec, orientation, onEnded = ::end)
        } catch (e: IllegalArgumentException) {
            stopped = true
            pending.clear()
            end(RecordResult.Failed(file, e.message ?: e.toString()))
            return
        }
        recorder = r
        for ((video, p) in pending) if (video) r.onVideo(p) else r.onAudio(p)
        pending.clear()
    }

    private fun end(result: RecordResult) {
        ended = true
        onEnded(this, result)
    }

    /** Ends the recording (EOS also stops the recorder in v5.0); queued packets are still written. */
    fun stop() {
        val notStarted = synchronized(lock) {
            if (stopped) return
            stopped = true
            pending.clear()
            recorder?.stop()
            recorder == null
        }
        if (notStarted) end(RecordResult.Failed(file, "Recording stopped before headers were processed"))
    }

    /** Waits up to [millis] for the recorder to close the file. */
    fun join(millis: Long): Boolean = synchronized(lock) { recorder }?.join(millis) ?: true
}
