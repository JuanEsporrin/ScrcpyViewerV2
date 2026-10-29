package com.programmersbox.scrcpyviewer.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateDownloaderTest {
    private var server: HttpServer? = null
    private val dir: Path = Files.createTempDirectory("updates")
    private val version = Version(listOf(1, 5, 0))
    private val payload = ByteArray(200_000) { it.toByte() }

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        dir.toFile().deleteRecursively()
    }

    private fun sha(bytes: ByteArray) =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun serve(slow: Boolean = false): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/f") { ex ->
            ex.sendResponseHeaders(200, payload.size.toLong())
            if (slow) {
                try {
                    ex.responseBody.write(payload, 0, 100_000)
                    ex.responseBody.flush()
                    Thread.sleep(5_000)
                } catch (_: Exception) {
                } finally {
                    ex.close()
                }
            } else {
                ex.responseBody.use { it.write(payload) }
            }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/f"
    }

    private fun asset(url: String, digest: String?) =
        ReleaseAsset("app.dmg", payload.size.toLong(), url, digest)

    private fun leftovers(): List<Path> =
        dir.resolve("1.5.0").takeIf { it.exists() }?.listDirectoryEntries().orEmpty()

    @Test
    fun `downloads and verifies`() = runBlocking {
        val url = serve()
        val progress = mutableListOf<Pair<Long, Long>>()
        val path = UpdateDownloader(dir).download(asset(url, sha(payload)), version) { r, t -> progress += r to t }
        assertTrue(path.exists())
        assertContentEquals(payload, path.readBytes())
        assertEquals(listOf(path), leftovers())
        assertEquals(200_000L to 200_000L, progress.last())
    }

    @Test
    fun `checksum mismatch deletes file`() = runBlocking {
        val url = serve()
        assertFailsWith<UpdateException> {
            UpdateDownloader(dir).download(asset(url, sha(byteArrayOf(1))), version) { _, _ -> }
        }
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `missing digest rejected`() = runBlocking {
        val url = serve()
        val e = assertFailsWith<UpdateException> {
            UpdateDownloader(dir).download(asset(url, null), version) { _, _ -> }
        }
        assertEquals("Release file has no checksum", e.message)
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `cancel deletes partial`() = runBlocking {
        val url = serve(slow = true)
        val started = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Default).async {
            UpdateDownloader(dir).download(asset(url, sha(payload)), version) { _, _ -> started.complete(Unit) }
        }
        withTimeout(10_000) { started.await() }
        withTimeout(4_000) { job.cancelAndJoin() }
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `removes older version folders`() = runBlocking {
        val url = serve()
        dir.resolve("1.4.9").createDirectories().resolve("x.dmg").writeBytes(byteArrayOf(1))
        UpdateDownloader(dir).download(asset(url, sha(payload)), version) { _, _ -> }
        assertFalse(dir.resolve("1.4.9").exists())
    }
}
