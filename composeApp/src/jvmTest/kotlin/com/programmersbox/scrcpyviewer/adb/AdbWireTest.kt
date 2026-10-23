package com.programmersbox.scrcpyviewer.adb

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AdbWireTest {
    private fun stream(s: String) = ByteArrayInputStream(s.toByteArray())

    @Test
    fun `encodeRequest prefixes hex length`() {
        assertContentEquals("0014host:track-devices-l".toByteArray(), AdbWire.encodeRequest("host:track-devices-l"))
    }

    @Test
    fun `readStatus accepts OKAY`() {
        AdbWire.readStatus(stream("OKAY"))
    }

    @Test
    fun `readStatus throws on FAIL with message`() {
        val e = assertFailsWith<AdbException> { AdbWire.readStatus(stream("FAIL0005nope!")) }
        assertEquals("FAIL: nope!", e.message)
    }

    @Test
    fun `readHexLengthPayload handles empty, EOF and data`() {
        assertEquals("", AdbWire.readHexLengthPayload(stream("0000")))
        assertNull(AdbWire.readHexLengthPayload(stream("")))
        assertEquals("hello", AdbWire.readHexLengthPayload(stream("0005hello")))
    }
}
