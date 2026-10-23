package com.programmersbox.scrcpyviewer.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundledToolsTest {
    private val windows = System.getProperty("os.name").lowercase().contains("win")
    private val adbName = if (windows) "adb.exe" else "adb"

    private fun fakeResources() = Files.createTempDirectory("res").also {
        Files.writeString(it.resolve(adbName), "dummy-adb")
        Files.writeString(it.resolve("scrcpy-server"), "dummy-server")
    }

    @Test
    fun `adbExecutable copies to data dir and is executable`() {
        val data = Files.createTempDirectory("data")
        val adb = BundledTools(fakeResources(), data).adbExecutable()
        assertTrue(Files.exists(adb))
        assertTrue(adb.startsWith(data.resolve("tools")))
        if (!windows) assertTrue(Files.isExecutable(adb))
    }

    @Test
    fun `adbExecutable reuses existing copy`() {
        val tools = BundledTools(fakeResources(), Files.createTempDirectory("data"))
        val first = tools.adbExecutable()
        val mtime = Files.getLastModifiedTime(first)
        Thread.sleep(20)
        val second = tools.adbExecutable()
        assertEquals(first, second)
        assertEquals(mtime, Files.getLastModifiedTime(second))
    }

    @Test
    fun `serverJar resolves from common dir`() {
        val bundled = Files.createTempDirectory("bundled")
        Files.createDirectories(bundled.resolve("common"))
        Files.writeString(bundled.resolve("common/scrcpy-server"), "x")
        System.setProperty("scrcpyviewer.bundledDir", bundled.toString())
        try {
            val tools = BundledTools(null, Files.createTempDirectory("data"))
            assertEquals(bundled.resolve("common/scrcpy-server"), tools.serverJar)
            assertEquals("5.0", tools.serverVersion)
        } finally {
            System.clearProperty("scrcpyviewer.bundledDir")
        }
    }
}
