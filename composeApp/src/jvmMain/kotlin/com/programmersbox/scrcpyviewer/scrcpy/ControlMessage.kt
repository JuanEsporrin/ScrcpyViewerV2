package com.programmersbox.scrcpyviewer.scrcpy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Position in device coordinates plus the screen size those coordinates refer to. */
data class Position(val x: Int, val y: Int, val screenWidth: Int, val screenHeight: Int)

/** Client-to-server messages of scrcpy 5.0 (`app/src/control_msg.c`), big-endian. */
sealed interface ControlMessage {
    fun serialize(): ByteArray

    data class InjectKeycode(val action: Int, val keycode: Int, val repeat: Int, val metastate: Int) : ControlMessage {
        override fun serialize() = build(TYPE_INJECT_KEYCODE) {
            writeByte(action); writeInt(keycode); writeInt(repeat); writeInt(metastate)
        }
    }

    data class InjectText(val text: String) : ControlMessage {
        override fun serialize() = build(TYPE_INJECT_TEXT) { writeString(text, INJECT_TEXT_MAX_LENGTH) }
    }

    data class InjectTouch(
        val action: Int,
        val pointerId: Long,
        val position: Position,
        val pressure: Float,
        val actionButton: Int,
        val buttons: Int,
    ) : ControlMessage {
        override fun serialize() = build(TYPE_INJECT_TOUCH) {
            writeByte(action); writeLong(pointerId); writePosition(position)
            writeShort(floatToU16Fixed(pressure)); writeInt(actionButton); writeInt(buttons)
        }
    }

    /** [hscroll] and [vscroll] are accepted in [-16, 16] and clamped. */
    data class InjectScroll(val position: Position, val hscroll: Float, val vscroll: Float, val buttons: Int) : ControlMessage {
        override fun serialize() = build(TYPE_INJECT_SCROLL) {
            writePosition(position)
            writeShort(floatToI16Fixed((hscroll / 16f).coerceIn(-1f, 1f)))
            writeShort(floatToI16Fixed((vscroll / 16f).coerceIn(-1f, 1f)))
            writeInt(buttons)
        }
    }

    /** [action] is the key action for BACK; the screen only turns on for ACTION_DOWN. */
    data class BackOrScreenOn(val action: Int) : ControlMessage {
        override fun serialize() = build(TYPE_BACK_OR_SCREEN_ON) { writeByte(action) }
    }

    data object ExpandNotificationPanel : ControlMessage {
        override fun serialize() = build(TYPE_EXPAND_NOTIFICATION_PANEL) {}
    }

    data object ExpandSettingsPanel : ControlMessage {
        override fun serialize() = build(TYPE_EXPAND_SETTINGS_PANEL) {}
    }

    data object CollapsePanels : ControlMessage {
        override fun serialize() = build(TYPE_COLLAPSE_PANELS) {}
    }

    /** [copyKey]: 0 none, 1 copy, 2 cut. */
    data class GetClipboard(val copyKey: Int) : ControlMessage {
        override fun serialize() = build(TYPE_GET_CLIPBOARD) { writeByte(copyKey) }
    }

    data class SetClipboard(val sequence: Long, val paste: Boolean, val text: String) : ControlMessage {
        override fun serialize() = build(TYPE_SET_CLIPBOARD) {
            writeLong(sequence); writeByte(if (paste) 1 else 0); writeString(text, CLIPBOARD_TEXT_MAX_LENGTH)
        }
    }

    data class SetDisplayPower(val on: Boolean) : ControlMessage {
        override fun serialize() = build(TYPE_SET_DISPLAY_POWER) { writeByte(if (on) 1 else 0) }
    }

    data object RotateDevice : ControlMessage {
        override fun serialize() = build(TYPE_ROTATE_DEVICE) {}
    }

    class UhidCreate(
        val id: Int,
        val vendorId: Int,
        val productId: Int,
        val name: String,
        val reportDesc: ByteArray,
    ) : ControlMessage {
        override fun serialize() = build(TYPE_UHID_CREATE) {
            writeShort(id); writeShort(vendorId); writeShort(productId)
            writeStringTiny(name, 127)
            writeShort(reportDesc.size); write(reportDesc)
        }
    }

    class UhidInput(val id: Int, val data: ByteArray) : ControlMessage {
        override fun serialize() = build(TYPE_UHID_INPUT) {
            writeShort(id); writeShort(data.size); write(data)
        }
    }

    data class UhidDestroy(val id: Int) : ControlMessage {
        override fun serialize() = build(TYPE_UHID_DESTROY) { writeShort(id) }
    }

    data object OpenHardKeyboardSettings : ControlMessage {
        override fun serialize() = build(TYPE_OPEN_HARD_KEYBOARD_SETTINGS) {}
    }

    data class StartApp(val name: String) : ControlMessage {
        override fun serialize() = build(TYPE_START_APP) { writeStringTiny(name, 255) }
    }

    data object ResetVideo : ControlMessage {
        override fun serialize() = build(TYPE_RESET_VIDEO) {}
    }

    data class CameraSetTorch(val on: Boolean) : ControlMessage {
        override fun serialize() = build(TYPE_CAMERA_SET_TORCH) { writeByte(if (on) 1 else 0) }
    }

    data object CameraZoomIn : ControlMessage {
        override fun serialize() = build(TYPE_CAMERA_ZOOM_IN) {}
    }

    data object CameraZoomOut : ControlMessage {
        override fun serialize() = build(TYPE_CAMERA_ZOOM_OUT) {}
    }

    data class ResizeDisplay(val width: Int, val height: Int) : ControlMessage {
        override fun serialize() = build(TYPE_RESIZE_DISPLAY) { writeShort(width); writeShort(height) }
    }

    data class ScanFile(val path: String) : ControlMessage {
        override fun serialize() = build(TYPE_SCAN_FILE) { writeString(path, SCAN_FILE_PATH_MAX_LENGTH) }
    }

    companion object {
        const val MAX_SIZE = 1 shl 18
        const val INJECT_TEXT_MAX_LENGTH = 300
        /** type (1) + sequence (8) + paste flag (1) + length (4) are subtracted from [MAX_SIZE]. */
        const val CLIPBOARD_TEXT_MAX_LENGTH = MAX_SIZE - 14
        const val SCAN_FILE_PATH_MAX_LENGTH = 256

        const val POINTER_ID_MOUSE = -1L
        const val POINTER_ID_GENERIC_FINGER = -2L
        const val POINTER_ID_VIRTUAL_FINGER = -3L

        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TEXT = 1
        const val TYPE_INJECT_TOUCH = 2
        const val TYPE_INJECT_SCROLL = 3
        const val TYPE_BACK_OR_SCREEN_ON = 4
        const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        const val TYPE_EXPAND_SETTINGS_PANEL = 6
        const val TYPE_COLLAPSE_PANELS = 7
        const val TYPE_GET_CLIPBOARD = 8
        const val TYPE_SET_CLIPBOARD = 9
        const val TYPE_SET_DISPLAY_POWER = 10
        const val TYPE_ROTATE_DEVICE = 11
        const val TYPE_UHID_CREATE = 12
        const val TYPE_UHID_INPUT = 13
        const val TYPE_UHID_DESTROY = 14
        const val TYPE_OPEN_HARD_KEYBOARD_SETTINGS = 15
        const val TYPE_START_APP = 16
        const val TYPE_RESET_VIDEO = 17
        const val TYPE_CAMERA_SET_TORCH = 18
        const val TYPE_CAMERA_ZOOM_IN = 19
        const val TYPE_CAMERA_ZOOM_OUT = 20
        const val TYPE_RESIZE_DISPLAY = 21
        const val TYPE_SCAN_FILE = 22
    }
}

private inline fun build(type: Int, body: DataOutputStream.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).apply {
        writeByte(type)
        body()
    }
    return bytes.toByteArray()
}

private fun DataOutputStream.writePosition(p: Position) {
    writeInt(p.x); writeInt(p.y); writeShort(p.screenWidth); writeShort(p.screenHeight)
}

/** Longest prefix of [text] in UTF-8 that is at most [maxBytes] and ends on a code point boundary. */
private fun utf8Truncated(text: String, maxBytes: Int): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return bytes
    var len = maxBytes
    while (len > 0 && (bytes[len].toInt() and 0xC0) == 0x80) len-- // bytes[len] is a continuation byte
    return bytes.copyOf(len)
}

/** 4-byte length + UTF-8 bytes. */
private fun DataOutputStream.writeString(text: String, maxBytes: Int) {
    val bytes = utf8Truncated(text, maxBytes)
    writeInt(bytes.size); write(bytes)
}

/** 1-byte length + UTF-8 bytes. */
private fun DataOutputStream.writeStringTiny(text: String, maxBytes: Int) {
    val bytes = utf8Truncated(text, maxBytes)
    writeByte(bytes.size); write(bytes)
}

/** Float in [0, 1] to unsigned 16-bit fixed point (`sc_float_to_u16fp`). */
internal fun floatToU16Fixed(f: Float): Int {
    require(f in 0f..1f) { "pressure out of range: $f" }
    val u = (f * 65536f).toLong()
    return if (u >= 0xffff) 0xffff else u.toInt()
}

/** Float in [-1, 1] to signed 16-bit fixed point (`sc_float_to_i16fp`). */
internal fun floatToI16Fixed(f: Float): Int {
    val i = (f * 32768f).toInt()
    return if (i >= 0x7fff) 0x7fff else i
}
