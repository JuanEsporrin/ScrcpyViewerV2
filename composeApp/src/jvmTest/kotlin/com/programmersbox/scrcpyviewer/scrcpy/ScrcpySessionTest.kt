package com.programmersbox.scrcpyviewer.scrcpy

import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.options.OptionValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Device ends of the local socket pairs a [LocalPairLauncher] hands to the session. */
class DeviceEnds(val video: Socket?, val audio: Socket?, val control: Socket?)

/** Returns connected loopback socket pairs; the test drives the device ends. */
class LocalPairLauncher(
    private val video: Boolean = true,
    private val audio: Boolean = false,
    private val control: Boolean = true,
) : ConnectionStarter {
    val device = CompletableDeferred<DeviceEnds>()
    @Volatile var cleanups = 0

    override suspend fun start(serial: String, values: OptionValues): ServerConnection = withContext(Dispatchers.IO) {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { ss ->
            fun pair(enabled: Boolean): Pair<Socket?, Socket?> {
                if (!enabled) return null to null
                val host = Socket(InetAddress.getLoopbackAddress(), ss.localPort)
                return host to ss.accept()
            }
            val (v, dv) = pair(video)
            val (a, da) = pair(audio)
            val (c, dc) = pair(control)
            device.complete(DeviceEnds(dv, da, dc))
            ServerConnection(v, a, c, DeviceMeta("Fake"), FakeProcess()) {
                cleanups++
                listOfNotNull(v, a, c).forEach { it.close() }
            }
        }
    }
}

class RecordingVideoSink(private val throwOnPacket: Boolean = false) : VideoSink {
    val metas = CopyOnWriteArrayList<VideoCodecMeta>()
    val sessions = CopyOnWriteArrayList<IntSize>()
    val packets = CopyOnWriteArrayList<MediaPacket>()
    @Volatile var closed = false
    override fun onMeta(meta: VideoCodecMeta) { metas += meta }
    override fun onSession(width: Int, height: Int) { sessions += IntSize(width, height) }
    override fun onPacket(p: MediaPacket) {
        if (throwOnPacket) error("sink bug")
        packets += p
    }
    override fun close() { closed = true }
}

private fun DataOutputStream.writeSession(w: Int, h: Int) {
    writeInt(0x80000000.toInt()); writeInt(w); writeInt(h); flush()
}

private fun DataOutputStream.writePacket(pts: Long, data: ByteArray) {
    writeLong(pts); writeInt(data.size); write(data); flush()
}

class ScrcpySessionTest {
    init {
        TestLogs.install()
    }

    private val errors = CopyOnWriteArrayList<Throwable>()
    private val parentJob = Job()
    private val parent = CoroutineScope(parentJob + Dispatchers.Default + CoroutineExceptionHandler { _, t -> errors += t })

    @AfterTest
    fun noEscapedExceptions() {
        parentJob.cancel()
        assertTrue(errors.isEmpty(), "exceptions escaped: $errors")
    }

    private fun session(
        launcher: ConnectionStarter,
        values: Map<String, String?> = emptyMap(),
        sink: VideoSink = RecordingVideoSink(),
    ) = ScrcpySession(
        serial = "SER",
        values = OptionValues(values),
        launcher = launcher,
        decoderFactory = { _, _ -> sink },
        audioFactory = { _, _ -> null },
        scope = parent,
    )

    private suspend fun ScrcpySession.await(status: SessionStatus) =
        withTimeout(5_000) {
            this@await.status.first { it == status || it == SessionStatus.Failed }
            assertEquals(status, this@await.status.value, "error: ${error.value}")
        }

    @Test
    fun `EOF on video sets Disconnected without throwing`() = runBlocking {
        val launcher = LocalPairLauncher()
        val sink = RecordingVideoSink()
        val s = session(launcher, sink = sink)
        s.await(SessionStatus.Running)
        val dev = launcher.device.await()
        val out = DataOutputStream(dev.video!!.getOutputStream())
        out.writeInt(StreamProtocol.CODEC_H264)
        out.writeSession(1080, 2400)
        dev.video.close()
        s.await(SessionStatus.Disconnected)
        withTimeout(5_000) { while (launcher.cleanups == 0 || !sink.closed) delay(10) }
        s.close()
        assertEquals(1, launcher.cleanups)
        assertTrue(parentJob.isActive)
    }

    private suspend fun audioSession(codec: Int, values: Map<String, String?>): Pair<ScrcpySession, DeviceEnds> {
        val launcher = LocalPairLauncher(audio = true)
        val s = session(launcher, values)
        s.await(SessionStatus.Running)
        val dev = launcher.device.await()
        DataOutputStream(dev.audio!!.getOutputStream()).apply { writeInt(codec); flush() }
        return s to dev
    }

    @Test
    fun `audio disabled or failing on the device keeps mirroring and flags no audio`() = runBlocking {
        for (codec in listOf(StreamProtocol.CODEC_DISABLED, StreamProtocol.CODEC_ERROR)) {
            val (s, dev) = audioSession(codec, emptyMap())
            withTimeout(5_000) { s.audioDisabledByDevice.first { it } }
            assertEquals(SessionStatus.Running, s.status.value)
            s.close()
            listOfNotNull(dev.video, dev.audio, dev.control).forEach { it.close() }
        }
    }

    @Test
    fun `require-audio fails the session when the device has no audio`() = runBlocking {
        for (codec in listOf(StreamProtocol.CODEC_DISABLED, StreamProtocol.CODEC_ERROR)) {
            val (s, dev) = audioSession(codec, mapOf("require-audio" to null))
            withTimeout(5_000) { s.status.first { it == SessionStatus.Failed } }
            assertTrue(s.error.value.orEmpty().contains("require-audio"), "error: ${s.error.value}")
            s.close()
            listOfNotNull(dev.video, dev.audio, dev.control).forEach { it.close() }
        }
    }

    @Test
    fun `two sessions get distinct scid and port`() = runBlocking {
        val adbA = FakeAdb()
        val adbB = FakeAdb()
        val a = session(ServerLauncher(adbA, fakeTools(), stopTimeoutMs = 100))
        val b = session(ServerLauncher(adbB, fakeTools(), stopTimeoutMs = 100))
        a.await(SessionStatus.Running)
        b.await(SessionStatus.Running)
        fun scid(adb: FakeAdb) = adb.shellCommand().first { it.startsWith("scid=") }
        assertNotEquals(scid(adbA), scid(adbB))
        assertNotEquals(adbA.reversePort, adbB.reversePort)
        a.close()
        b.close()
    }

    @Test
    fun `closing one session leaves another Running`() = runBlocking {
        val la = LocalPairLauncher()
        val lb = LocalPairLauncher()
        val a = session(la)
        val b = session(lb)
        a.await(SessionStatus.Running)
        b.await(SessionStatus.Running)
        a.close()
        assertEquals(SessionStatus.Disconnected, a.status.value)
        delay(200)
        assertEquals(SessionStatus.Running, b.status.value)
        assertEquals(0, lb.cleanups)
        b.send(ControlMessage.RotateDevice)
        val input = lb.device.await().control!!.getInputStream()
        assertEquals(ControlMessage.TYPE_ROTATE_DEVICE, withContext(Dispatchers.IO) { input.read() })
        b.close()
    }

    @Test
    fun `send writes serialized bytes to control socket`() = runBlocking {
        val launcher = LocalPairLauncher()
        val s = session(launcher)
        s.await(SessionStatus.Running)
        val msg = ControlMessage.InjectKeycode(action = 0, keycode = 3, repeat = 0, metastate = 0)
        // A message whose serialize() throws is dropped and does not stop the writer.
        val bad = ControlMessage.InjectTouch(0, -1, Position(1, 1, 10, 10), pressure = 2f, actionButton = 0, buttons = 0)
        s.send(bad)
        s.send(msg)
        val expected = msg.serialize()
        val got = ByteArray(expected.size)
        withContext(Dispatchers.IO) { DataInputStream(launcher.device.await().control!!.getInputStream()).readFully(got) }
        assertContentEquals(expected, got)
        assertEquals(SessionStatus.Running, s.status.value)
        s.close()
    }

    @Test
    fun `turn-screen-off sends SetDisplayPower(false) after Running`() = runBlocking {
        val launcher = LocalPairLauncher()
        val s = session(launcher, values = mapOf("turn-screen-off" to null))
        s.await(SessionStatus.Running)
        val got = ByteArray(2)
        withContext(Dispatchers.IO) { DataInputStream(launcher.device.await().control!!.getInputStream()).readFully(got) }
        assertContentEquals(ControlMessage.SetDisplayPower(false).serialize(), got)
        s.close()
    }

    @Test
    fun `session headers mid-stream update frameSize and reach sinks`() = runBlocking {
        val launcher = LocalPairLauncher()
        val sink = RecordingVideoSink()
        val s = session(launcher, sink = sink)
        s.await(SessionStatus.Running)
        val out = DataOutputStream(launcher.device.await().video!!.getOutputStream())
        out.writeInt(StreamProtocol.CODEC_H264)
        out.writeSession(1080, 2400)
        withTimeout(5_000) { s.frameSize.first { it == IntSize(1080, 2400) } }
        out.writePacket(1000, byteArrayOf(1, 2))
        out.writeSession(2400, 1080)
        withTimeout(5_000) { s.frameSize.first { it == IntSize(2400, 1080) } }
        withTimeout(5_000) { while (sink.sessions.isEmpty()) delay(10) }
        assertEquals(listOf(VideoCodecMeta(StreamProtocol.CODEC_H264, 1080, 2400)), sink.metas)
        assertEquals(1, sink.packets.size)
        assertEquals(listOf(IntSize(2400, 1080)), sink.sessions)
        s.close()
        assertTrue(sink.closed)
    }

    @Test
    fun `a throwing sink is removed and the session keeps running`() = runBlocking {
        val launcher = LocalPairLauncher()
        val bad = RecordingVideoSink(throwOnPacket = true)
        val s = session(launcher, sink = bad)
        s.await(SessionStatus.Running)
        val good = RecordingVideoSink()
        val out = DataOutputStream(launcher.device.await().video!!.getOutputStream())
        out.writeInt(StreamProtocol.CODEC_H264)
        out.writeSession(100, 200)
        withTimeout(5_000) { s.frameSize.first { it != null } }
        s.addVideoSink(good) // a late sink gets the current meta replayed
        out.writePacket(1, byteArrayOf(9))
        out.writePacket(2, byteArrayOf(8))
        withTimeout(5_000) { while (good.packets.size < 2) delay(10) }
        assertTrue(bad.closed)
        assertEquals(listOf(VideoCodecMeta(StreamProtocol.CODEC_H264, 100, 200)), good.metas)
        assertEquals(SessionStatus.Running, s.status.value)
        s.close()
    }

    @Test
    fun `launch failure sets Failed with the error`() = runBlocking {
        val s = session(ConnectionStarter { _, _ -> throw java.io.IOException("adb push failed") })
        s.await(SessionStatus.Failed)
        assertEquals("adb push failed", s.error.value)
        s.close()
        assertEquals(SessionStatus.Failed, s.status.value)
    }

    @Test
    fun `close while connecting cancels the launch`() = runBlocking {
        val adb = FakeAdb(deviceConnects = false)
        val s = session(ServerLauncher(adb, fakeTools(), stopTimeoutMs = 100))
        withTimeout(5_000) { while (adb.processes.isEmpty()) delay(10) }
        withTimeout(5_000) { s.close() }
        assertEquals(SessionStatus.Disconnected, s.status.value)
        assertTrue(adb.processes.single().destroyed)
    }

    @Test
    fun `cancelling the parent scope tears the session down`() = runBlocking {
        val launcher = LocalPairLauncher()
        val scopeJob = Job()
        val s = ScrcpySession("SER", OptionValues(emptyMap()), launcher, { _, _ -> null }, { _, _ -> null },
            CoroutineScope(scopeJob + Dispatchers.Default))
        s.await(SessionStatus.Running)
        scopeJob.cancel()
        withTimeout(5_000) { scopeJob.join() }
        assertEquals(1, launcher.cleanups)
        assertEquals(SessionStatus.Disconnected, s.status.value)
        withTimeout(5_000) { s.close() } // already torn down: returns
    }

    @Test
    fun `a launcher failing immediately never hangs close`() = runBlocking {
        repeat(200) {
            val s = session(ConnectionStarter { _, _ -> throw java.io.IOException("boom") })
            withTimeout(2_000) { s.close() }
            assertTrue(s.status.value == SessionStatus.Failed || s.status.value == SessionStatus.Disconnected)
        }
    }

    @Test
    fun `parent cancel racing a reader EOF cleans up exactly once`() = runBlocking {
        repeat(50) {
            val launcher = LocalPairLauncher()
            val scopeJob = Job()
            val s = ScrcpySession("SER", OptionValues(emptyMap()), launcher, { _, _ -> null }, { _, _ -> null },
                CoroutineScope(scopeJob + Dispatchers.Default))
            s.await(SessionStatus.Running)
            val dev = launcher.device.await()
            val eof = Thread { dev.video!!.close() }
            eof.start()
            scopeJob.cancel()
            eof.join()
            withTimeout(5_000) { s.close() }
            withTimeout(5_000) { scopeJob.join() }
            assertEquals(1, launcher.cleanups)
            assertEquals(SessionStatus.Disconnected, s.status.value)
            listOfNotNull(dev.audio, dev.control).forEach { it.close() }
        }
    }

    @Test
    fun `a session on an already cancelled scope does not hang close`() = runBlocking {
        val launcher = LocalPairLauncher()
        val s = ScrcpySession("SER", OptionValues(emptyMap()), launcher, { _, _ -> null }, { _, _ -> null },
            CoroutineScope(Job().apply { cancel() }))
        withTimeout(5_000) { s.close() }
        assertEquals(SessionStatus.Disconnected, s.status.value)
        assertEquals(0, launcher.cleanups)
    }
}
