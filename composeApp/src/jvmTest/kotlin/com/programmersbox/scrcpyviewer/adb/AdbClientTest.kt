package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdbClientTest {
    private fun stub(body: String): Path {
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
}
