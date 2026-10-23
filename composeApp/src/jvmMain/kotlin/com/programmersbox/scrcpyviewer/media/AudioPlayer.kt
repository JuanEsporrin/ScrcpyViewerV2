package com.programmersbox.scrcpyviewer.media

import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.AudioSink
import com.programmersbox.scrcpyviewer.scrcpy.MediaPacket
import com.programmersbox.scrcpyviewer.scrcpy.StreamProtocol
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs

/** Where the playback thread writes S16LE 48 kHz stereo PCM. [write] blocks while the device buffer is full. */
interface AudioOutput : AutoCloseable {
    /** Size of the device buffer actually granted, in bytes. */
    val bufferBytes: Int
    fun write(buf: ByteArray, len: Int)
    /** Stops playback and unblocks a pending [write]. */
    override fun close()
}

/** A [SourceDataLine] on the default output device, opened with a buffer of [requestedBytes]. */
class LineOutput(format: AudioFormat, requestedBytes: Int) : AudioOutput {
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).also { l ->
        try {
            l.open(format, requestedBytes)
            l.start()
        } catch (t: Throwable) {
            l.close()
            throw t
        }
    }
    override val bufferBytes: Int get() = line.bufferSize
    override fun write(buf: ByteArray, len: Int) {
        line.write(buf, 0, len)
    }
    override fun close() {
        line.stop()
        line.flush() // unblocks write
        line.close()
    }
}

/**
 * Plays the scrcpy audio stream: decodes opus, aac, flac or raw with avcodec, converts to S16LE 48 kHz stereo with
 * swresample, and plays it on a [SourceDataLine] whose buffer is sized from [outputBufferMs] (at least
 * [MIN_OUTPUT_FRAMES]).
 *
 * Decoding runs on the session's audio reader thread ([onPacket]); it never writes to the line. Decoded samples go
 * into a [JitterBuffer] of [bufferMs], which a dedicated playback thread drains into the line in chunks of half the
 * line buffer. Like v5.0 `audio_regulator.c`, the buffering level is kept around the target by swresample clock
 * compensation (at most 2%), recomputed once a second.
 *
 * The decoder opens on the first media packet, with the last config packet (OpusHead, FLAC STREAMINFO or the AAC
 * AudioSpecificConfig, already stripped by the server) as extradata. The stream is always 48 kHz stereo (v5.0
 * `demuxer.c` hardcodes it).
 *
 * [close] may come from another thread; it stops the playback thread and frees the native state, serialized with
 * decoding.
 */
class AudioPlayer(
    private val codecId: Int,
    bufferMs: Int = DEFAULT_BUFFER_MS,
    outputBufferMs: Int = DEFAULT_OUTPUT_BUFFER_MS,
    openOutput: (format: AudioFormat, requestedBytes: Int) -> AudioOutput = ::LineOutput,
) : AudioSink {
    private val avCodecId = when (codecId) {
        StreamProtocol.CODEC_OPUS -> avcodec.AV_CODEC_ID_OPUS
        StreamProtocol.CODEC_AAC -> avcodec.AV_CODEC_ID_AAC
        StreamProtocol.CODEC_FLAC -> avcodec.AV_CODEC_ID_FLAC
        StreamProtocol.CODEC_RAW -> avcodec.AV_CODEC_ID_PCM_S16LE
        else -> throw IOException("Unsupported audio codec 0x%08x".format(codecId))
    }
    private val codec: AVCodec = avcodec.avcodec_find_decoder(avCodecId)
        ?: throw IOException("No FFmpeg decoder for ${codecName(codecId)}")

    /** The playback buffer, filled by the decoder and drained by the playback thread. */
    internal val jitter = JitterBuffer(FRAME_BYTES, SAMPLE_RATE, targetFrames = bufferMs.coerceAtLeast(0) * SAMPLE_RATE / 1000)

    /** PCM frames (per channel) produced by the decoder and resampler. */
    @Volatile var decodedFrames: Long = 0
        private set

    private val lock = Any()
    @Volatile private var closed = false
    private var config: ByteArray? = null
    private var openFailure: IOException? = null

    // Native state, allocated on the first media packet ([openDecoder]) and freed by [close].
    private var ctx: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var avFrame: AVFrame? = null
    private var swr: SwrContext? = null
    private var swrOut: BytePointer? = null
    private var swrOutFrames = 0
    private val swrOutPtrs = PointerPointer<BytePointer>(1L)
    private var pcm = ByteArray(0)
    private var loggedSendError = false

    private val output: AudioOutput
    private val playback: Thread

    /** The output buffer actually granted, in bytes (Java Sound may round the requested size). */
    val outputBufferBytes: Int get() = output.bufferBytes

    init {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)
        val requested = maxOf(outputBufferMs * SAMPLE_RATE / 1000, MIN_OUTPUT_FRAMES) * FRAME_BYTES
        output = openOutput(format, requested)
        // Half the granted buffer per write keeps the line at least half full.
        val chunkFrames = (output.bufferBytes / FRAME_BYTES / 2).coerceAtLeast(1)
        playback = Thread({ playLoop(chunkFrames) }, "audio-playback").apply {
            isDaemon = true
            start()
        }
    }

    private fun playLoop(chunkFrames: Int) {
        val chunk = ByteArray(chunkFrames * FRAME_BYTES)
        try {
            while (!closed) {
                jitter.pull(chunk, chunkFrames)
                output.write(chunk, chunk.size)
            }
        } catch (t: Throwable) {
            if (!closed) log.log(System.Logger.Level.WARNING, "Audio playback stopped", t)
        }
    }

    override fun onCodec(codecId: Int) {
        if (codecId != this.codecId) throw IOException("Codec mismatch: ${codecName(codecId)} for a ${codecName(this.codecId)} player")
    }

    override fun onPacket(p: MediaPacket): Unit = synchronized(lock) {
        if (closed) return
        if (p.isConfig) {
            // Before the decoder opens it becomes the extradata; later the decoder already has it (v5.0 ignores it).
            if (ctx == null) config = p.data
            return
        }
        if (openFailure != null) return
        if (ctx == null) {
            try {
                openDecoder()
            } catch (e: IOException) {
                // Thrown once (the session then removes this sink); later packets are ignored.
                openFailure = e
                throw e
            }
        }
        decode(p)
    }

    private fun openDecoder() {
        val c = avcodec.avcodec_alloc_context3(codec) ?: throw IOException("Could not allocate the audio decoder")
        c.sample_rate(SAMPLE_RATE)
        avutil.av_channel_layout_default(c.ch_layout(), CHANNELS)
        // The FLAC decoder does not set the sample format (demuxer.c).
        if (codecId == StreamProtocol.CODEC_FLAC) c.sample_fmt(avutil.AV_SAMPLE_FMT_S16)
        config?.let { extra ->
            val buf = BytePointer(avutil.av_mallocz(extra.size.toLong() + avcodec.AV_INPUT_BUFFER_PADDING_SIZE))
            buf.put(extra, 0, extra.size)
            c.extradata(buf)
            c.extradata_size(extra.size)
        }
        val r = avcodec.avcodec_open2(c, codec, null as PointerPointer<*>?)
        if (r < 0) {
            avcodec.avcodec_free_context(c)
            throw IOException("Could not open the ${codecName(codecId)} decoder ($r)")
        }
        val p = avcodec.av_packet_alloc()
        val f = avutil.av_frame_alloc()
        if (p == null || f == null) {
            p?.let { avcodec.av_packet_free(it) }
            f?.let { avutil.av_frame_free(it) }
            avcodec.avcodec_free_context(c)
            throw IOException("Out of memory for the audio decoder")
        }
        ctx = c
        packet = p
        avFrame = f
    }

    private fun decode(p: MediaPacket) {
        val c = ctx!!
        val pkt = packet!!
        val f = avFrame!!
        if (avcodec.av_new_packet(pkt, p.data.size) < 0) throw IOException("Out of memory for an audio packet")
        try {
            pkt.data().put(p.data, 0, p.data.size)
            pkt.pts(p.ptsUs)
            pkt.dts(p.ptsUs)
            val sent = avcodec.avcodec_send_packet(c, pkt)
            if (sent < 0) {
                // A corrupt packet is not fatal.
                if (!loggedSendError) log.log(System.Logger.Level.WARNING, "Could not send an audio packet ($sent)")
                loggedSendError = true
                return
            }
        } finally {
            avcodec.av_packet_unref(pkt)
        }
        while (avcodec.avcodec_receive_frame(c, f) >= 0) {
            try {
                resampleAndPush(f)
            } finally {
                avutil.av_frame_unref(f)
            }
        }
    }

    private fun resampleAndPush(f: AVFrame) {
        val s = swr ?: createResampler(f).also { swr = it }
        val inFrames = f.nb_samples()
        // Same rate in and out; 256 more for clock compensation (audio_regulator.c).
        val maxOut = (swresample.swr_get_delay(s, SAMPLE_RATE.toLong()) + inFrames + 256).toInt()
        if (maxOut > swrOutFrames) {
            swrOut?.let { avutil.av_free(it) }
            val size = maxOut + 4096
            swrOut = BytePointer(avutil.av_malloc(size.toLong() * FRAME_BYTES)).also { swrOutPtrs.put(0L, it) }
            swrOutFrames = size
            pcm = ByteArray(size * FRAME_BYTES)
        }
        val ret = swresample.swr_convert(s, swrOutPtrs, maxOut, f.extended_data(), inFrames)
        if (ret < 0) throw IOException("Resampling failed ($ret)")
        val frames = minOf(ret, maxOut)
        swrOut!!.get(pcm, 0, frames * FRAME_BYTES)
        decodedFrames += frames
        val pts = f.pts().takeIf { it != avutil.AV_NOPTS_VALUE && it >= 0 }
        jitter.push(pcm, frames, inFrames, pts)?.let { c ->
            val r = swresample.swr_set_compensation(s, c.sampleDelta, c.distance)
            if (r < 0) log.log(System.Logger.Level.WARNING, "Resampling compensation failed ($r)") else jitter.compensationApplied(c)
        }
    }

    private fun createResampler(f: AVFrame): SwrContext {
        val s = swresample.swr_alloc() ?: throw IOException("Out of memory for the resampler")
        val outLayout = AVChannelLayout()
        try {
            avutil.av_channel_layout_default(outLayout, CHANNELS)
            avutil.av_opt_set_chlayout(s, "in_chlayout", f.ch_layout(), 0)
            avutil.av_opt_set_chlayout(s, "out_chlayout", outLayout, 0)
            avutil.av_opt_set_int(s, "in_sample_rate", f.sample_rate().toLong(), 0)
            avutil.av_opt_set_int(s, "out_sample_rate", SAMPLE_RATE.toLong(), 0)
            avutil.av_opt_set_sample_fmt(s, "in_sample_fmt", f.format(), 0)
            avutil.av_opt_set_sample_fmt(s, "out_sample_fmt", avutil.AV_SAMPLE_FMT_S16, 0)
            val r = swresample.swr_init(s)
            if (r < 0) {
                swresample.swr_free(s)
                throw IOException("Could not initialize the resampler ($r)")
            }
        } finally {
            avutil.av_channel_layout_uninit(outLayout)
            outLayout.close()
        }
        return s
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        output.close() // unblocks the playback thread's write
        playback.join(1_000)
        synchronized(lock) {
            ctx?.let { avcodec.avcodec_free_context(it) }
            packet?.let { avcodec.av_packet_free(it) }
            avFrame?.let { avutil.av_frame_free(it) }
            swr?.let { swresample.swr_free(it) }
            swrOut?.let { avutil.av_free(it) }
            ctx = null
            packet = null
            avFrame = null
            swr = null
            swrOut = null
            swrOutPtrs.close()
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        /** S16 stereo. */
        const val FRAME_BYTES = 4
        const val DEFAULT_BUFFER_MS = 50
        /** FLAC encodes blocks of 4096 samples (~85 ms), so v5.0 `cli.c` defaults its buffer to 120 ms. */
        const val DEFAULT_FLAC_BUFFER_MS = 120
        /** v5.0 `options.c` default of `--audio-output-buffer`. */
        const val DEFAULT_OUTPUT_BUFFER_MS = 10
        /**
         * Smallest line buffer, in frames (~21 ms). v5.0 `audio_player.c` makes its output buffer at least 1024
         * samples too; on macOS a 10 ms [SourceDataLine] buffer starves (measured: ~14% of the time silent).
         */
        const val MIN_OUTPUT_FRAMES = 1024

        private val log = System.getLogger("scrcpyviewer.AudioPlayer")

        /**
         * The player for a session's options, or null when playback is off (`no-audio-playback`/`no-playback`; the
         * session still reads the audio stream, for recording).
         */
        fun forOptions(codecId: Int, values: OptionValues): AudioPlayer? {
            val e = values.entries
            if ("no-audio-playback" in e || "no-playback" in e) return null
            val buffer = e["audio-buffer"]?.toIntOrNull()
                ?: if (codecId == StreamProtocol.CODEC_FLAC) DEFAULT_FLAC_BUFFER_MS else DEFAULT_BUFFER_MS
            val output = e["audio-output-buffer"]?.toIntOrNull() ?: DEFAULT_OUTPUT_BUFFER_MS
            return AudioPlayer(codecId, buffer, output)
        }

        fun codecName(codecId: Int): String = when (codecId) {
            StreamProtocol.CODEC_OPUS -> "Opus"
            StreamProtocol.CODEC_AAC -> "AAC"
            StreamProtocol.CODEC_FLAC -> "FLAC"
            StreamProtocol.CODEC_RAW -> "raw PCM"
            else -> "0x%08x".format(codecId)
        }
    }
}

/**
 * A ring buffer of fixed-size audio frames, ported from v5.0 `app/src/util/audiobuf.c` (the actual storage is
 * `capacity + 1` frames so that head == tail means empty). Not thread-safe: [JitterBuffer] locks around it.
 */
class AudioRingBuffer(private val frameBytes: Int, val capacity: Int) {
    init {
        require(frameBytes > 0 && capacity > 0)
    }

    private val allocFrames = capacity + 1
    private val data = ByteArray(allocFrames * frameBytes)
    private var head = 0
    private var tail = 0

    val available: Int get() = (allocFrames + head - tail) % allocFrames
    val free: Int get() = capacity - available

    /** Copies up to [frames] frames into [to] at byte [offset] (or skips them when [to] is null). */
    fun read(to: ByteArray?, offset: Int, frames: Int): Int {
        val count = minOf(frames, available)
        if (count <= 0) return 0
        if (to != null) {
            val right = minOf(allocFrames - tail, count)
            System.arraycopy(data, tail * frameBytes, to, offset, right * frameBytes)
            if (count > right) System.arraycopy(data, 0, to, offset + right * frameBytes, (count - right) * frameBytes)
        }
        tail = (tail + count) % allocFrames
        return count
    }

    /** Copies up to [frames] frames from [from] at byte [offset]; returns how many fit. */
    fun write(from: ByteArray, offset: Int, frames: Int): Int = put(frames) { pos, src, len ->
        System.arraycopy(from, offset + src * frameBytes, data, pos * frameBytes, len * frameBytes)
    }

    fun writeSilence(frames: Int): Int = put(frames) { pos, _, len ->
        data.fill(0, pos * frameBytes, (pos + len) * frameBytes)
    }

    private inline fun put(frames: Int, copy: (pos: Int, src: Int, len: Int) -> Unit): Int {
        val count = minOf(frames, free)
        if (count <= 0) return 0
        val right = minOf(allocFrames - head, count)
        copy(head, 0, right)
        if (count > right) copy(0, right, count - right)
        head = (head + count) % allocFrames
        return count
    }
}

/**
 * Playback buffering between the decoder ([push]) and the output ([pull]), a port of v5.0 `audio_regulator.c`.
 *
 * Playback starts once [targetFrames] are buffered; until then [pull] returns silence. On underrun the missing part
 * is silence and playback goes on: no frame is dropped when the late frames arrive, the clock compensation absorbs
 * the delay (the inserted silence counts as buffering right away). On overrun the oldest frames are dropped: beyond
 * the ring capacity, and beyond `target + 10 ms` before playback starts or `target * 1.1 + 60 ms` once playing.
 *
 * A pts jump of more than 100 ms (silence the device did not capture) is a discontinuity: the buffer is padded with
 * silence up to the target and the regulation restarts with no compensation.
 *
 * Once playing, [push] smooths the buffering level and once per second of input returns the swresample
 * compensation (in frames over [compensationDistance]) that moves it back toward the target.
 *
 * Every call takes one short lock (a copy of at most a chunk), so neither side ever waits on the other for long.
 */
class JitterBuffer(
    private val frameBytes: Int,
    private val sampleRate: Int,
    val targetFrames: Int,
    capacityFrames: Int = targetFrames + sampleRate,
) {
    data class Stats(
        /** Pulls that ran short after audio was received. */
        val underruns: Long,
        /** Silent frames inserted because of underruns. */
        val underrunFrames: Long,
        /** Frames dropped on overrun. */
        val droppedFrames: Long,
        /** Real (non-silent) frames handed to the output. */
        val playedFrames: Long,
    )

    /** `swr_set_compensation(sampleDelta, distance)` to apply. */
    data class Compensation(val sampleDelta: Int, val distance: Int)

    private val lock = Any()
    private val ring = AudioRingBuffer(frameBytes, capacityFrames)
    private var played = false
    private var received = false
    /** Silence inserted by [pull] since the last [push] (only counted once something was received). */
    private var underflow = 0
    private var underruns = 0L
    private var underrunFrames = 0L
    private var droppedFrames = 0L
    private var playedFrames = 0L

    // Regulation, producer side (all under [lock] too).
    private var avg = 0.0
    private var avgCount = 0
    private var framesSinceResync = 0
    private var compensationActive = false
    private var nextExpectedPts = 0L

    val available: Int get() = synchronized(lock) { ring.available }
    /** Playback has started (it never stops: underruns insert silence). */
    val isPlaying: Boolean get() = synchronized(lock) { played }

    fun stats(): Stats = synchronized(lock) { Stats(underruns, underrunFrames, droppedFrames, playedFrames) }

    /**
     * Adds [frames] frames from [pcm], produced from [inputFrames] decoded frames (they differ while compensating)
     * with presentation time [ptsUs] (null: unknown, no discontinuity check). Returns a compensation to apply, or
     * null to keep the current one.
     */
    fun push(pcm: ByteArray, frames: Int, inputFrames: Int = frames, ptsUs: Long? = null): Compensation? = synchronized(lock) {
        var result: Compensation? = null
        if (ptsUs != null) {
            if (nextExpectedPts != 0L && ptsUs - nextExpectedPts > DISCONTINUITY_US) {
                // Typically silence that was not captured: adjust the buffering to the target directly.
                val canRead = ring.available
                if (inputFrames + canRead < targetFrames) ring.writeSilence(targetFrames - canRead - inputFrames)
                avg = targetFrames.toDouble()
                result = Compensation(0, 0)
                compensationActive = false
                framesSinceResync = 0
                underflow = 0
            }
            nextExpectedPts = ptsUs + inputFrames * 1_000_000L / sampleRate
        }

        var offset = 0
        var count = frames
        if (count > ring.capacity) {
            // Very unlikely: a single push larger than the whole ring; keep its newest part.
            offset = (count - ring.capacity) * frameBytes
            count = ring.capacity
        }
        var dropped = 0
        val overflow = count - ring.free
        if (overflow > 0) dropped += ring.read(null, 0, overflow) // drop the oldest to make room
        ring.write(pcm, offset, count)

        var inserted = 0
        val max = if (played) {
            inserted = underflow
            underflow = 0
            targetFrames * 11 / 10 + 60 * sampleRate / 1000
        } else {
            // Not started: do not accumulate more than needed, it would only delay the start.
            targetFrames + 10 * sampleRate / 1000
        }
        val level = ring.available // sampled before the threshold drop, as audio_regulator.c
        if (level > max) dropped += ring.read(null, 0, level - max)
        droppedFrames += dropped
        received = true
        if (!played) return result

        // Compensation, inserted silence and drops change the level instantly; only the natural level is smoothed.
        avg = (avg + (count - inputFrames) + inserted - dropped).coerceAtLeast(0.0)
        if (avgCount < AVERAGE_RANGE) avgCount++
        avg = ((avgCount - 1) * avg + level) / avgCount

        framesSinceResync += count
        if (framesSinceResync >= sampleRate) {
            // Recompute the compensation every second.
            framesSinceResync = 0
            var diff = (targetFrames - avg).toInt()
            // Enable beyond +/-4 ms, disable below +/-1 ms.
            val threshold = if (compensationActive) sampleRate / 1000 else sampleRate * 4 / 1000
            if (abs(diff) < threshold) diff = 0
            else if (diff < 0 && level < targetFrames) diff = 0 // never accelerate below the target
            val distance = compensationDistance(sampleRate)
            val maxDiff = distance / 50 // 2%
            result = Compensation(diff.coerceIn(-maxDiff, maxDiff), distance)
        }
        result
    }

    /** Records that a compensation returned by [push] was applied. */
    fun compensationApplied(c: Compensation): Unit = synchronized(lock) { compensationActive = c.sampleDelta != 0 }

    /** Fills [frames] frames of [out]: buffered audio, or silence before the start and on underrun. Returns real frames. */
    fun pull(out: ByteArray, frames: Int): Int = synchronized(lock) {
        if (!played && ring.available < targetFrames) {
            // Delay the start until the target buffering is reached.
            out.fill(0, 0, frames * frameBytes)
            return 0
        }
        val read = ring.read(out, 0, frames)
        playedFrames += read
        if (read < frames) {
            val silence = frames - read
            out.fill(0, read * frameBytes, frames * frameBytes)
            if (received) {
                // The late frames are kept: the silence counts as extra buffering for the regulation.
                underflow += silence
                underruns++
                underrunFrames += silence
            }
        }
        played = true
        read
    }

    companion object {
        /** Frames of the sliding average of the buffering level (v5.0: 128). */
        private const val AVERAGE_RANGE = 128
        /** A pts gap beyond this is a discontinuity. */
        private const val DISCONTINUITY_US = 100_000L

        /** Compensation is spread over 4 seconds (but recomputed every second). */
        fun compensationDistance(sampleRate: Int): Int = 4 * sampleRate
    }
}
