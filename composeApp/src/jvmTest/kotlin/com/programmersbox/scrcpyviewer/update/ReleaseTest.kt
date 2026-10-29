package com.programmersbox.scrcpyviewer.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseTest {
    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing $path" }
            .use { it.readBytes().decodeToString() }

    @Test
    fun `parses real release`() {
        val r = parseRelease(resource("update/release-v1.4.0.json"))
        assertEquals("v1.4.0", r.tagName)
        assertEquals(Version(listOf(1, 4, 0)), r.version)
        assertEquals(
            "sha256:75fef6be39739c67321ad7f8dddfa168be646f13554dbfbafccc539ead7a3888",
            r.assetFor(UpdatePlatform.MacArm64)!!.digest
        )
        assertEquals("scrcpyviewer_1.4.0_amd64.deb", r.assetFor(UpdatePlatform.LinuxX64)!!.name)
        assertEquals("ScrcpyViewer-1.4.0.msi", r.assetFor(UpdatePlatform.WindowsX64)!!.name)
        assertNull(r.assetFor(UpdatePlatform.Unsupported))
    }

    @Test
    fun `version compare`() {
        assertTrue(Version.parse("v1.10.0")!! > Version.parse("1.9.3")!!)
        assertEquals(0, Version.parse("1.4")!!.compareTo(Version.parse("1.4.0")!!))
        assertNull(Version.parse("v1.5.0-beta"))
        assertNull(Version.parse("nightly"))
        assertNotNull(Version.parse("V2.0"))
    }

    @Test
    fun `platform detect`() {
        assertEquals(UpdatePlatform.MacArm64, UpdatePlatform.detect("Mac OS X", "aarch64"))
        assertEquals(UpdatePlatform.Unsupported, UpdatePlatform.detect("Mac OS X", "x86_64"))
        assertEquals(UpdatePlatform.WindowsX64, UpdatePlatform.detect("Windows 11", "amd64"))
        assertEquals(UpdatePlatform.LinuxX64, UpdatePlatform.detect("Linux", "amd64"))
        assertEquals(UpdatePlatform.LinuxX64, UpdatePlatform.detect("Linux", "x86_64"))
        assertEquals(UpdatePlatform.Unsupported, UpdatePlatform.detect("Linux", "aarch64"))
    }
}
