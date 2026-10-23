package com.programmersbox.scrcpyviewer.state

import com.programmersbox.scrcpyviewer.assumePosix
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Doc(val n: Int = 0)

class JsonFileTest {
    private val dir = Files.createTempDirectory("jsonfile")
    private val path = dir.resolve("doc.json")
    private fun jf() = JsonFile(path, Doc.serializer()) { Doc() }

    @Test
    fun `corrupt file is backed up and default returned`() {
        Files.writeString(path, "{nope")
        val f = jf()
        assertEquals(Doc(), f.load())
        assertNull(f.error.value)
        assertEquals("{nope", Files.readString(dir.resolve("doc.json.bak")))
        assertTrue(Files.notExists(path))
    }

    @Test
    fun `failed backup sets error and save does not overwrite original`() {
        Files.writeString(path, "{nope")
        // A non-empty directory at the .bak path makes the move fail.
        val bak = dir.resolve("doc.json.bak")
        Files.createDirectories(bak)
        Files.writeString(bak.resolve("x"), "x")
        val f = jf()
        assertEquals(Doc(), f.load())
        assertNotNull(f.error.value)
        f.save(Doc(5))
        assertEquals("{nope", Files.readString(path))
    }

    @Test
    fun `read IOException does not move the file`() {
        assumePosix()
        Files.writeString(path, "{\"n\":1}")
        val perms = PosixFilePermissions.fromString("---------")
        Files.setPosixFilePermissions(path, perms)
        try {
            val f = jf()
            if (Files.isReadable(path)) return // running as a user that ignores permissions
            assertEquals(Doc(), f.load())
            assertNotNull(f.error.value)
            assertTrue(Files.exists(path))
            assertTrue(Files.notExists(dir.resolve("doc.json.bak")))
        } finally {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `save then load round trips`() {
        jf().save(Doc(7))
        assertEquals(Doc(7), jf().load())
    }

    @Test
    fun `a failed write sets error without throwing, and the next good write clears it`() {
        val blocker = dir.resolve("blocker")
        Files.writeString(blocker, "a file where the directory should be")
        val f = JsonFile(blocker.resolve("doc.json"), Doc.serializer()) { Doc() }
        f.save(Doc(1)) // must not throw
        assertNotNull(f.error.value)
        Files.delete(blocker)
        f.save(Doc(2))
        assertNull(f.error.value)
        assertEquals(Doc(2), f.load())
    }
}
