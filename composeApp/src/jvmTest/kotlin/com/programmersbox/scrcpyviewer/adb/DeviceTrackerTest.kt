package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceTrackerTest {
    private val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }

    private fun frame(s: String) = "%04x".format(s.length) + s

    /** Accepts connections in order; [handlers] get the socket after the request line has been read. */
    private fun serve(vararg handlers: (Socket) -> Unit) = thread(isDaemon = true) {
        for (h in handlers) {
            val s = try { server.accept() } catch (_: IOException) { return@thread }
            sockets += s
            val input = s.getInputStream()
            val len = String(input.readNBytes(4)).toInt(16)
            input.readNBytes(len)
            h(s)
        }
    }

    private fun send(s: Socket, text: String) = s.getOutputStream().apply { write(text.toByteArray()); flush() }

    private open class FakeClient(port: Int) : AdbClient({ Paths.get("adb") }, "127.0.0.1", port) {
        val startServerCalls = AtomicInteger()
        val shellCalls = AtomicInteger()
        override suspend fun startServer() { startServerCalls.incrementAndGet() }
        override suspend fun shell(serial: String, cmd: String): String {
            shellCalls.incrementAndGet()
            return "Pixel 8 Pro\n15\n35\n"
        }
    }

    private val one = "39121FDJH004AB device product:husky model:Pixel_8_Pro device:husky transport_id:3"
    private val two = "$one\nZY22H8KLMN unauthorized transport_id:6"

    @Test
    fun `emits each push`() = runBlocking {
        serve({ s -> send(s, "OKAY" + frame(one) + frame(two)); Thread.sleep(2000) })
        val client = FakeClient(server.localPort)
        val tracker = DeviceTracker(client, scope)
        withTimeout(5000) {
            val l1 = tracker.devices.first { it.size == 1 }
            assertEquals("39121FDJH004AB", l1.single().serial)
            val l2 = tracker.devices.first { it.size == 2 }
            assertEquals(listOf("39121FDJH004AB", "ZY22H8KLMN"), l2.map { it.serial })
            assertTrue(tracker.connected.value)
        }
    }

    @Test
    fun `reconnects after server closes`() = runBlocking {
        serve(
            { s -> Thread.sleep(300); send(s, "OKAY" + frame(one)); Thread.sleep(300); s.close() },
            { s -> send(s, "OKAY" + frame(two)); Thread.sleep(2000) },
        )
        val client = FakeClient(server.localPort)
        val tracker = DeviceTracker(client, scope, initialBackoffMs = 20, maxBackoffMs = 100)
        val seen = Collections.synchronizedList(mutableListOf<Boolean>())
        val job = tracker.connected.onEach { seen += it }.launchIn(scope)
        withTimeout(5000) {
            tracker.devices.first { it.size == 2 }
            while (seen.size < 4 || seen.last() != true) kotlinx.coroutines.delay(10)
        }
        job.cancel()
        val collapsed = seen.fold(listOf<Boolean>()) { acc, b -> if (acc.lastOrNull() == b) acc else acc + b }
        assertEquals(listOf(false, true, false, true), collapsed)
        assertTrue(client.startServerCalls.get() >= 1)
    }

    @Test
    fun `fetches info once per device`() = runBlocking {
        serve({ s -> send(s, "OKAY" + frame(one) + frame(two)); Thread.sleep(2000) })
        val client = FakeClient(server.localPort)
        val tracker = DeviceTracker(client, scope)
        withTimeout(5000) {
            val withInfo = tracker.devices.first { it.firstOrNull()?.info != null && it.size == 2 }
            assertEquals(DeviceInfo("Pixel 8 Pro", "15", 35), withInfo.first().info)
        }
        Thread.sleep(200)
        assertEquals(1, client.shellCalls.get())
    }
}
