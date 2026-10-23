package com.programmersbox.scrcpyviewer.scrcpy

import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.Companion.CLIPBOARD_TEXT_MAX_LENGTH
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage.Companion.INJECT_TEXT_MAX_LENGTH
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Golden bytes ported from scrcpy v5.0 `app/tests/test_control_msg_serialize.c`. */
class ControlMessageTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.map { it.code }.toIntArray()

    private fun check(msg: ControlMessage, vararg expected: Int) {
        val actual = msg.serialize()
        assertEquals(expected.size, actual.size)
        assertContentEquals(b(*expected), actual)
    }

    @Test
    fun injectKeycode() = check(
        ControlMessage.InjectKeycode(action = 1, keycode = 0x42, repeat = 5, metastate = 0x41),
        0, 0x01, 0, 0, 0, 0x42, 0, 0, 0, 0x05, 0, 0, 0, 0x41,
    )

    @Test
    fun injectText() = check(
        ControlMessage.InjectText("hello, world!"),
        1, 0, 0, 0, 0x0d, *ascii("hello, world!"),
    )

    @Test
    fun injectTextLong() {
        val actual = ControlMessage.InjectText("a".repeat(INJECT_TEXT_MAX_LENGTH)).serialize()
        val expected = b(1, 0, 0, 0x01, 0x2c) + ByteArray(INJECT_TEXT_MAX_LENGTH) { 'a'.code.toByte() }
        assertEquals(5 + INJECT_TEXT_MAX_LENGTH, actual.size)
        assertContentEquals(expected, actual)
    }

    @Test
    fun injectTextTruncatesOnCodePointBoundary() {
        // 299 ASCII bytes + a 2-byte char would be 301 bytes: the char is dropped whole.
        val actual = ControlMessage.InjectText("a".repeat(299) + "é").serialize()
        assertEquals(5 + 299, actual.size)
    }

    @Test
    fun injectTouchEvent() = check(
        ControlMessage.InjectTouch(
            action = 0,
            pointerId = 0x1234567887654321L,
            position = Position(100, 200, 1080, 1920),
            pressure = 1.0f,
            actionButton = 1,
            buttons = 1,
        ),
        2,
        0x00,
        0x12, 0x34, 0x56, 0x78, 0x87, 0x65, 0x43, 0x21,
        0x00, 0x00, 0x00, 0x64, 0x00, 0x00, 0x00, 0xc8,
        0x04, 0x38, 0x07, 0x80,
        0xff, 0xff,
        0x00, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x01,
    )

    @Test
    fun injectScrollEvent() = check(
        ControlMessage.InjectScroll(Position(260, 1026, 1080, 1920), hscroll = 16f, vscroll = -16f, buttons = 1),
        3,
        0x00, 0x00, 0x01, 0x04, 0x00, 0x00, 0x04, 0x02,
        0x04, 0x38, 0x07, 0x80,
        0x7F, 0xFF,
        0x80, 0x00,
        0x00, 0x00, 0x00, 0x01,
    )

    @Test
    fun backOrScreenOn() = check(ControlMessage.BackOrScreenOn(action = 1), 4, 0x01)

    @Test
    fun expandNotificationPanel() = check(ControlMessage.ExpandNotificationPanel, 5)

    @Test
    fun expandSettingsPanel() = check(ControlMessage.ExpandSettingsPanel, 6)

    @Test
    fun collapsePanels() = check(ControlMessage.CollapsePanels, 7)

    @Test
    fun getClipboard() = check(ControlMessage.GetClipboard(copyKey = 1), 8, 1)

    @Test
    fun setClipboard() = check(
        ControlMessage.SetClipboard(sequence = 0x0102030405060708L, paste = true, text = "hello, world!"),
        9,
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        1,
        0x00, 0x00, 0x00, 0x0d,
        *ascii("hello, world!"),
    )

    @Test
    fun setClipboardLong() {
        val actual = ControlMessage.SetClipboard(0x0102030405060708L, true, "a".repeat(CLIPBOARD_TEXT_MAX_LENGTH)).serialize()
        val header = b(
            9, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 1,
            CLIPBOARD_TEXT_MAX_LENGTH ushr 24,
            (CLIPBOARD_TEXT_MAX_LENGTH ushr 16) and 0xff,
            (CLIPBOARD_TEXT_MAX_LENGTH ushr 8) and 0xff,
            CLIPBOARD_TEXT_MAX_LENGTH and 0xff,
        )
        val expected = header + ByteArray(CLIPBOARD_TEXT_MAX_LENGTH) { 'a'.code.toByte() }
        assertEquals(ControlMessage.MAX_SIZE, actual.size)
        assertContentEquals(expected, actual)
    }

    @Test
    fun setDisplayPower() = check(ControlMessage.SetDisplayPower(on = true), 10, 0x01)

    @Test
    fun rotateDevice() = check(ControlMessage.RotateDevice, 11)

    @Test
    fun uhidCreate() = check(
        ControlMessage.UhidCreate(
            id = 42, vendorId = 0x1234, productId = 0x5678, name = "ABC",
            reportDesc = b(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
        ),
        12,
        0, 42,
        0x12, 0x34,
        0x56, 0x78,
        3,
        65, 66, 67,
        0, 11,
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11,
    )

    @Test
    fun uhidInput() = check(ControlMessage.UhidInput(42, b(1, 2, 3, 4, 5)), 13, 0, 42, 0, 5, 1, 2, 3, 4, 5)

    @Test
    fun uhidDestroy() = check(ControlMessage.UhidDestroy(42), 14, 0, 42)

    @Test
    fun openHardKeyboardSettings() = check(ControlMessage.OpenHardKeyboardSettings, 15)

    @Test
    fun startApp() = check(ControlMessage.StartApp("firefox"), 16, 7, *ascii("firefox"))

    @Test
    fun resetVideo() = check(ControlMessage.ResetVideo, 17)

    @Test
    fun cameraSetTorch() = check(ControlMessage.CameraSetTorch(on = true), 18, 0x01)

    @Test
    fun cameraZoomIn() = check(ControlMessage.CameraZoomIn, 19)

    @Test
    fun cameraZoomOut() = check(ControlMessage.CameraZoomOut, 20)

    @Test
    fun resizeDisplay() = check(ControlMessage.ResizeDisplay(1920, 1080), 21, 1920 shr 8, 1920 and 0xff, 1080 shr 8, 1080 and 0xff)

    @Test
    fun scanFile() = check(
        ControlMessage.ScanFile("/sdcard/Download"),
        22, 0x00, 0x00, 0x00, 0x10, *ascii("/sdcard/Download"),
    )
}
