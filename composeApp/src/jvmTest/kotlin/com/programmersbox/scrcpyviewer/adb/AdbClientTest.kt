package com.programmersbox.scrcpyviewer.adb

import com.programmersbox.scrcpyviewer.assumePosix
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlin.concurrent.thread
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdbClientTest {
    private fun stub(body: String): Path {
        assumePosix() // sh stub scripts
        val f = Files.createTempFile("fake-adb", ".sh")
        Files.writeString(f, "#!/bin/sh\n$body\n")
        f.toFile().setExecutable(true)
        f.toFile().deleteOnExit()
        return f
    }

    @Test
    fun `exec captures stdout stderr and exit code`() = runBlocking {
        val r = AdbClient({ stub("echo out; echo err >&2; exit 3") }).exec("x")
        assertEquals(ProcessResult(3, "out\n", "err\n"), r)
    }

    @Test
    fun `startServer returns while a forked daemon keeps the output open`() = runBlocking {
        val marker = Files.createTempFile("daemon-pid", ".txt")
        val adb = stub("sleep 30 &\necho \$! > '$marker'\nexit 0")
        var daemon: ProcessHandle? = null
        try {
            // Generous timeout: if startServer still waited on the daemon's output it would throw the timeout error.
            AdbClient({ adb }, startServerTimeoutMs = 20_000).startServer()
            // The stub wrote the daemon pid before exiting; the daemon must still be running, so startServer
            // did not wait for it (no time bound needed).
            daemon = ProcessHandle.of(Files.readString(marker).trim().toLong()).orElse(null)
            assertTrue(daemon != null && daemon.isAlive, "daemon should still be alive when startServer returns")
        } finally {
            daemon?.destroyForcibly()
            Files.deleteIfExists(marker)
        }
    }

    @Test
    fun `startServer records whether it started the daemon`() = runBlocking {
        val reused = AdbClient({ stub("exit 0") })
        reused.startServer()
        assertEquals(false, reused.startedServer)
        val started = AdbClient({ stub("echo '* daemon not running; starting now at tcp:5037' >&2\necho '* daemon started successfully' >&2") })
        started.startServer()
        assertEquals(true, started.startedServer)
    }

    @Test
    fun `restartServer kills then starts the daemon`(): Unit = runBlocking {
        val log = Files.createTempFile("adb-calls", ".txt")
        val adb = stub("echo \"\$1\" >> '$log'\n[ \"\$1\" = start-server ] && echo '* daemon started successfully' >&2\nexit 0")
        val client = AdbClient({ adb })
        client.restartServer()
        assertEquals(listOf("kill-server", "start-server"), Files.readAllLines(log))
        assertEquals(true, client.startedServer)
        Files.deleteIfExists(log)
    }

    @Test
    fun `startServer is bounded when adb hangs`() = runBlocking {
        val adb = stub("exec sleep 5")
        val start = System.nanoTime()
        assertFailsWith<java.io.IOException> { AdbClient({ adb }, startServerTimeoutMs = 300).startServer() }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
    }

    @Test
    fun `exec is cancellable`() = runBlocking {
        val adb = stub("exec sleep 5")
        val job = async(start = CoroutineStart.UNDISPATCHED) { AdbClient({ adb }).exec("x") }
        delay(200)
        val start = System.nanoTime()
        job.cancel()
        job.join()
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000)
    }

    @Test
    fun `execWithTimeout gives up on a hung adb`() = runBlocking {
        val adb = stub("exec sleep 5")
        val start = System.nanoTime()
        assertNull(AdbClient({ adb }).execWithTimeout(300, "x"))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000)
        assertEquals(ProcessResult(0, "ok\n", ""), AdbClient({ stub("echo ok") }).execWithTimeout(5_000, "x"))
    }

    @Test
    fun `shell times out when the device never answers`() = runBlocking {
        // A fake adb server: OKAY for the transport and the shell request, then silence.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { s ->
                        s.getOutputStream().write("OKAYOKAY".toByteArray())
                        s.getOutputStream().flush()
                        Thread.sleep(5_000)
                    }
                }
            }
            val client = AdbClient({ error("no binary") }, port = server.localPort, shellReadTimeoutMs = 300)
            val start = System.nanoTime()
            assertFailsWith<SocketTimeoutException> { client.shell("SER", "getprop") }
            assertTrue((System.nanoTime() - start) / 1_000_000 < 3000)
            accepted.interrupt()
        }
    }
}
