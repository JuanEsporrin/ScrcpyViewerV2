package com.programmersbox.scrcpyviewer.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubReleaseSourceTest {
    private var server: HttpServer? = null
    private var acceptHeader: String? = null

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing $path" }
            .use { it.readBytes().decodeToString() }

    private fun source(status: Int, body: String): GitHubReleaseSource {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/repos/o/r/releases/latest") { ex ->
            acceptHeader = ex.requestHeaders.getFirst("Accept")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return GitHubReleaseSource(apiBase = "http://127.0.0.1:${s.address.port}", repo = "o/r")
    }

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `returns parsed release`() = runBlocking {
        val release = source(200, resource("update/release-v1.4.0.json")).latest()
        assertEquals("v1.4.0", release.tagName)
        assertEquals("application/vnd.github+json", acceptHeader)
    }

    @Test
    fun `rate limited throws UpdateException`() = runBlocking {
        val e = assertFailsWith<UpdateException> { source(403, "{}").latest() }
        assertEquals("GitHub returned HTTP 403", e.message)
    }

    @Test
    fun `html body throws UpdateException`() = runBlocking {
        val e = assertFailsWith<UpdateException> { source(200, "<html>").latest() }
        assertEquals("Unexpected response from GitHub", e.message)
    }
}
