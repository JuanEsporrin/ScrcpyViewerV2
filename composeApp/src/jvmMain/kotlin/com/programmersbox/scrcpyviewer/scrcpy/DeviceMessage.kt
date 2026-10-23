package com.programmersbox.scrcpyviewer.scrcpy

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/** Server-to-client messages of scrcpy 5.0 (`app/src/device_msg.c`), big-endian. */
sealed interface DeviceMessage {
    data class Clipboard(val text: String) : DeviceMessage
    data class AckClipboard(val sequence: Long) : DeviceMessage
    class UhidOutput(val id: Int, val data: ByteArray) : DeviceMessage

    companion object {
        const val TYPE_CLIPBOARD = 0
        const val TYPE_ACK_CLIPBOARD = 1
        const val TYPE_UHID_OUTPUT = 2
    }
}

/** Reads one device message; null on EOF before the type byte. Throws on an unknown type or a truncated message. */
fun readDeviceMessage(input: DataInputStream): DeviceMessage? {
    val type = input.read()
    if (type < 0) return null
    return when (type) {
        DeviceMessage.TYPE_CLIPBOARD -> {
            val len = input.readInt()
            if (len < 0) throw IOException("Invalid clipboard length: $len")
            DeviceMessage.Clipboard(String(readBytes(input, len), Charsets.UTF_8))
        }
        DeviceMessage.TYPE_ACK_CLIPBOARD -> DeviceMessage.AckClipboard(input.readLong())
        DeviceMessage.TYPE_UHID_OUTPUT -> {
            val id = input.readUnsignedShort()
            val size = input.readUnsignedShort()
            DeviceMessage.UhidOutput(id, readBytes(input, size))
        }
        else -> throw IOException("Unknown device message type: $type")
    }
}

private fun readBytes(input: DataInputStream, len: Int): ByteArray {
    val buf = ByteArray(len)
    try {
        input.readFully(buf)
    } catch (e: EOFException) {
        throw IOException("Truncated device message", e)
    }
    return buf
}
