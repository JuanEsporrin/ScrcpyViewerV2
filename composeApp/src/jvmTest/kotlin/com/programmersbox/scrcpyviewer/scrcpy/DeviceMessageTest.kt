package com.programmersbox.scrcpyviewer.scrcpy

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Cases ported from scrcpy v5.0 `app/tests/test_device_msg_deserialize.c`. */
class DeviceMessageTest {
    private fun stream(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun clipboard() {
        val input = stream(b(0, 0x00, 0x00, 0x00, 0x03, 0x41, 0x42, 0x43))
        assertEquals(DeviceMessage.Clipboard("ABC"), readDeviceMessage(input))
        assertNull(readDeviceMessage(input))
    }

    @Test
    fun clipboardBig() {
        val max = (1 shl 18) - 5 // DEVICE_MSG_TEXT_MAX_LENGTH
        val input = b(0, max ushr 24, (max ushr 16) and 0xff, (max ushr 8) and 0xff, max and 0xff) +
            ByteArray(max) { 'a'.code.toByte() }
        assertEquals(1 shl 18, input.size)
        val msg = readDeviceMessage(stream(input))
        assertIs<DeviceMessage.Clipboard>(msg)
        assertEquals(max, msg.text.length)
        assertEquals('a', msg.text[0])
    }

    @Test
    fun ackSetClipboard() {
        val msg = readDeviceMessage(stream(b(1, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)))
        assertEquals(DeviceMessage.AckClipboard(0x0102030405060708L), msg)
    }

    @Test
    fun uhidOutput() {
        val msg = readDeviceMessage(stream(b(2, 0, 42, 0, 5, 1, 2, 3, 4, 5)))
        assertIs<DeviceMessage.UhidOutput>(msg)
        assertEquals(42, msg.id)
        assertContentEquals(b(1, 2, 3, 4, 5), msg.data)
    }

    @Test
    fun unknownTypeFails() {
        assertFailsWith<IOException> { readDeviceMessage(stream(b(99))) }
    }

    @Test
    fun truncatedMessageFails() {
        assertFailsWith<IOException> { readDeviceMessage(stream(b(1, 0, 0))) }
    }
}
