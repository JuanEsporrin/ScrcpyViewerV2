package com.programmersbox.scrcpyviewer.media

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.AudioSink
import com.programmersbox.scrcpyviewer.scrcpy.MediaPacket
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.ServerLauncher
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.tools.BundledTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real-device audio check; excluded from the default `jvmTest` run (see `MirrorDeviceTest`). Plays the device audio
 * (silence is fine) for ~10 s and checks the decoded PCM rate and the jitter buffer. It cannot check what is heard.
 */
class AudioPlayerDeviceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adbPath: Path = System.getenv("PATH").split(java.io.File.pathSeparator)
        .map { Paths.get(it, "adb") }.first { Files.isExecutable(it) }
    private val launcher = ServerLauncher(AdbClient(adbPath = { adbPath }), BundledTools(dataDir = Files.createTempDirectory("audio-device-test")))
    private val serial = System.getProperty("scrcpyviewer.deviceSerials")?.split(',')?.first() ?: "MBX6P240401889"

    /** Measured duration: `-PaudioSeconds=120` for a long drift run (default 10). */
    private val durationSeconds = System.getProperty("scrcpyviewer.audioSeconds")?.toIntOrNull() ?: 10

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `plays device audio at 48 kHz without sustained underruns`() = runBlocking {
        val playerRef = AtomicReference<AudioPlayer?>()
        val timing = AtomicReference<ArrivalTimingSink?>()
        val session = ScrcpySession(
            serial, OptionValues(mapOf("max-size" to "640")), launcher,
            decoderFactory = { _, _ -> null },
            audioFactory = { codec, _ ->
                val player = AudioPlayer(codec, bufferMs = 50, outputBufferMs = AudioPlayer.DEFAULT_OUTPUT_BUFFER_MS)
                playerRef.set(player)
                ArrivalTimingSink(player).also(timing::set)
            },
            scope = scope,
        )
        try {
            withTimeout(20_000) {
                session.status.first { it == SessionStatus.Running }
                while ((playerRef.get()?.decodedFrames ?: 0L) == 0L) {
                    check(session.status.value == SessionStatus.Running) { "session ended: ${session.error.value}" }
                    delay(10)
                }
            }
            val p = playerRef.get()!!
            delay(2_000) // let playback start and settle
            val startFrames = p.decodedFrames
            val startStats = p.jitter.stats()
            val startNs = System.nanoTime()
            // Sample the buffering level (the playback latency) once a second.
            val levels = ArrayList<Int>()
            repeat(durationSeconds) {
                delay(1_000)
                levels += p.jitter.available
            }
            val seconds = (System.nanoTime() - startNs) / 1e9
            val frames = p.decodedFrames - startFrames
            val stats = p.jitter.stats()
            val underruns = stats.underruns - startStats.underruns
            val underrunFrames = stats.underrunFrames - startStats.underrunFrames
            println(
                "[$serial] %.2f s: %d PCM frames (%.0f/s), underruns=%d (%d frames), dropped=%d, played=%d, buffered=%d, playing=%s, output buffer=%d B"
                    .format(seconds, frames, frames / seconds, underruns, underrunFrames,
                        stats.droppedFrames - startStats.droppedFrames, stats.playedFrames - startStats.playedFrames,
                        p.jitter.available, p.jitter.isPlaying, p.outputBufferBytes),
            )
            timing.get()?.let { println("[$serial] packet arrival gaps: max=${it.maxGapMs} ms, >60 ms: ${it.lateGaps}") }
            println("[$serial] buffered frames per second: min=${levels.min()} max=${levels.max()} avg=${levels.average().toInt()}")
            val expected = seconds * AudioPlayer.SAMPLE_RATE
            assertTrue(abs(frames - expected) <= expected * 0.10, "~48 kHz: $frames frames in %.2f s".format(seconds))
            // The line consumed in real time (a starving line pulls less than 48 kHz).
            val played = stats.playedFrames - startStats.playedFrames
            assertTrue(abs(played - expected) <= expected * 0.05, "line played $played frames in %.2f s".format(seconds))
            // No sustained underruns: under 1% of the time played as underrun silence.
            assertTrue(underrunFrames < expected * 0.01, "underrun frames: $underrunFrames ($underruns underruns)")
            assertTrue(session.status.value == SessionStatus.Running, "error: ${session.error.value}")
        } finally {
            withTimeout(10_000) { session.close() }
        }
    }
}

/** Measures the wall-clock gaps between audio packets (transport jitter), then forwards to [player]. */
private class ArrivalTimingSink(private val player: AudioPlayer) : AudioSink by player {
    @Volatile var maxGapMs = 0L
    @Volatile var lateGaps = 0
    private var last = 0L

    override fun onPacket(p: MediaPacket) {
        val now = System.nanoTime()
        if (last != 0L && !p.isConfig) {
            val gap = (now - last) / 1_000_000
            if (gap > maxGapMs) maxGapMs = gap
            if (gap > 60) lateGaps++
        }
        if (!p.isConfig) last = now
        player.onPacket(p)
    }
}
