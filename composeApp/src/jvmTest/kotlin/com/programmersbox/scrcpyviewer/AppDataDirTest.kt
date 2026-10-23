package com.programmersbox.scrcpyviewer

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppDataDirTest {
    @Test
    fun `root honors override property`() {
        val tmp = Files.createTempDirectory("scrcpyviewer-test")
        val target = tmp.resolve("data")
        System.setProperty("scrcpyviewer.dataDir", target.toString())
        try {
            assertEquals(target, AppDataDir.root())
            assertTrue(Files.isDirectory(target))
        } finally {
            System.clearProperty("scrcpyviewer.dataDir")
        }
    }
}
