package com.programmersbox.scrcpyviewer.scrcpy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamProtocolTest {
    private fun stream(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))
    private fun bytes(body: DataOutputStream.() -> Unit) =
        ByteArrayOutputStream().also { DataOutputStream(it).body() }.toByteArray()

    @Test
    fun `readPacket decodes config flag`() {
        // scrcpy 5.0: bit 62 is the config flag (bit 63 marks a session header).
        val p = readPacket(stream(bytes { writeLong(1L shl 62); writeInt(3); write(byteArrayOf(1, 2, 3)) }))!!
        assertTrue(p.isConfig)
        assertFalse(p.isKeyFrame)
        assertContentEquals(byteArrayOf(1, 2, 3), p.data)
    }

    @Test
    fun `readPacket decodes key frame and pts`() {
        // scrcpy 5.0: bit 61 is the key frame flag.
        val p = readPacket(stream(bytes { writeLong((1L shl 61) or 1_000_000L); writeInt(1); write(7) }))!!
        assertTrue(p.isKeyFrame)
        assertFalse(p.isConfig)
        assertEquals(1_000_000L, p.ptsUs)
    }

    @Test
    fun `readPacket decodes plain packet`() {
        val p = readPacket(stream(bytes { writeLong(42L); writeInt(2); write(byteArrayOf(9, 8)) }))!!
        assertFalse(p.isConfig)
        assertFalse(p.isKeyFrame)
        assertEquals(42L, p.ptsUs)
    }

    @Test
    fun `readPacket returns null on EOF`() {
        assertNull(readPacket(stream(ByteArray(0))))
        assertNull(readPacket(stream(ByteArray(5)))) // partial header
        assertNull(readPacket(stream(bytes { writeLong(0); writeInt(4); write(1) }))) // partial payload
    }

    @Test
    fun `readStreamItem decodes session header`() {
        val item = readStreamItem(stream(bytes { writeInt(0x80000001.toInt()); writeInt(1080); writeInt(2400) }))
        assertEquals(StreamItem.Session(1080, 2400, clientResized = true), item)
        val plain = readStreamItem(stream(bytes { writeInt(0x80000000.toInt()); writeInt(2400); writeInt(1080) }))
        assertEquals(StreamItem.Session(2400, 1080, clientResized = false), plain)
    }

    @Test
    fun `readVideoCodecMeta reads h264 1344x2992`() {
        val input = bytes {
            writeInt(0x68323634) // "h264"
            writeInt(0x80000000.toInt()); writeInt(1344); writeInt(2992) // session header
        }
        assertEquals(VideoCodecMeta(0x68323634, 1344, 2992), readVideoCodecMeta(stream(input)))
    }

    @Test
    fun `readAudioCodecId returns raw id, 1 on EOF`() {
        assertEquals(0x6f707573, readAudioCodecId(stream(bytes { writeInt(0x6f707573) })))
        assertEquals(0, readAudioCodecId(stream(bytes { writeInt(0) })))
        assertEquals(1, readAudioCodecId(stream(ByteArray(2))))
    }

    @Test
    fun `readDeviceMeta reads NUL padded name`() {
        val block = ByteArray(64).also { "Pixel 9".toByteArray().copyInto(it) }
        assertEquals(DeviceMeta("Pixel 9"), readDeviceMeta(stream(block)))
        val full = ByteArray(64) { 'x'.code.toByte() } // no NUL: last byte forced to NUL
        assertEquals(63, readDeviceMeta(stream(full)).name.length)
    }
}
