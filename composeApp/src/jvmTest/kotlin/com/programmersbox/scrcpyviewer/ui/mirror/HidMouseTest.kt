package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.IntOffset
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HidMouseTest {
    private val mouse = HidMouse()

    private fun ControlMessage.UhidInput.bytes(): List<Int> {
        assertEquals(HidMouse.ID, id)
        return data.map { it.toInt() }
    }

    @Test
    fun `report descriptor is byte identical to hid_mouse c`() {
        val expected = intArrayOf(
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x03, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
            0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
            0xC0, 0xC0,
        ).map { it.toByte() }.toByteArray()
        assertContentEquals(expected, HidMouse.REPORT_DESC)
    }

    @Test
    fun `open and close messages as mouse_uhid c`() {
        val open = mouse.open()
        val bytes = open.serialize()
        assertContentEquals(byteArrayOf(12, 0, 2, 0, 0, 0, 0, 0, 0, HidMouse.REPORT_DESC.size.toByte()), bytes.copyOf(10))
        assertContentEquals(HidMouse.REPORT_DESC, bytes.copyOfRange(10, bytes.size))
        assertContentEquals(byteArrayOf(14, 0, 2), mouse.close().serialize())
    }

    @Test
    fun `motion with buttons, clamped to int8`() {
        // The example in hid_mouse.c: x=5, y=-4 with the left button pressed.
        assertEquals(listOf(1, 5, -4, 0, 0), mouse.motion(5, -4, MouseButton.Left.bit).bytes())
        assertEquals(listOf(0, 127, -127, 0, 0), mouse.motion(300, -300, 0).bytes())
        assertContentEquals(byteArrayOf(13, 0, 2, 0, 5, 0, 127, -127, 0, 0), mouse.motion(128, -128, 0).serialize())
    }

    @Test
    fun `click reports the buttons state`() {
        val all = MouseButton.entries.fold(0) { acc, b -> acc or b.bit }
        assertEquals(listOf(0x1F, 0, 0, 0, 0), mouse.click(all).bytes())
        assertEquals(listOf(0x02, 0, 0, 0, 0), mouse.click(MouseButton.Right.bit).bytes())
        assertEquals(listOf(0x04, 0, 0, 0, 0), mouse.click(MouseButton.Middle.bit).bytes())
        assertEquals(listOf(0x18, 0, 0, 0, 0), mouse.click(MouseButton.X1.bit or MouseButton.X2.bit).bytes())
    }

    @Test
    fun `scroll accumulates the residual and truncates towards zero`() {
        assertEquals(listOf(0, 0, 0, 1, -2), mouse.scroll(hscroll = -2f, vscroll = 1f)!!.bytes())
        assertNull(mouse.scroll(0f, 0.6f), "not enough for a notch")
        assertEquals(listOf(0, 0, 0, 1, 0), mouse.scroll(0f, 0.6f)!!.bytes(), "0.6 + 0.6 = 1.2")
        assertNull(mouse.scroll(0f, -0.5f), "residual 0.2 - 0.5 = -0.3")
        assertEquals(listOf(0, 0, 0, -1, 0), mouse.scroll(0f, -0.8f)!!.bytes())
        assertEquals(listOf(0, 0, 0, 127, 0), mouse.scroll(0f, 500f)!!.bytes(), "clamped, the excess dropped")
        assertNull(mouse.scroll(0f, 0f))
    }

    @Test
    fun `delta tracker follows moves and survives a warp`() {
        val t = PointerDeltaTracker()
        assertEquals(IntOffset.Zero, t.move(100, 100), "first event only sets the origin")
        assertEquals(IntOffset(10, -5), t.move(110, 95))
        t.warped(IntOffset(50, 50))
        // A stale event from before the warp still counts from the pre-warp position.
        assertEquals(IntOffset(2, 0), t.move(112, 95))
        // The first event at the warp target counts from the centre.
        assertEquals(IntOffset(3, 1), t.move(53, 51))
        assertEquals(IntOffset(-3, 0), t.move(50, 51))
        t.reset()
        assertEquals(IntOffset.Zero, t.move(0, 0))
    }

    @Test
    fun `delta tracker keeps working when the warp did nothing`() {
        val t = PointerDeltaTracker()
        t.move(500, 500)
        t.warped(IntOffset(100, 100))
        assertEquals(IntOffset(1, 1), t.move(501, 501))
        assertEquals(IntOffset(1, 0), t.move(502, 501))
    }

    @Test
    fun `capture key pressed and released alone toggles the capture`() {
        val c = MouseCapture()
        val left = AwtKeyEvent.KEY_LOCATION_LEFT
        c.setActive(true)
        assertTrue(c.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_ALT, left), "capture keys are consumed")
        assertTrue(c.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_ALT, left))
        assertFalse(c.active.value)
        assertTrue(c.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_META, left))
        assertTrue(c.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_META, left))
        assertTrue(c.active.value)
        // Right Alt and other keys are not capture keys.
        assertFalse(c.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_ALT, AwtKeyEvent.KEY_LOCATION_RIGHT))
        assertFalse(c.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_ALT, AwtKeyEvent.KEY_LOCATION_RIGHT))
        assertTrue(c.active.value)
    }

    @Test
    fun `another key in between cancels the toggle`() {
        val c = MouseCapture()
        val left = AwtKeyEvent.KEY_LOCATION_LEFT
        c.setActive(true)
        // Cmd down, A down/up, Cmd up: a key release resets the pending capture key (mouse_capture.c).
        c.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_META, left)
        assertFalse(c.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_A, AwtKeyEvent.KEY_LOCATION_STANDARD))
        assertFalse(c.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_A, AwtKeyEvent.KEY_LOCATION_STANDARD))
        c.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_META, left)
        assertTrue(c.active.value)
        // Two capture keys: the second press cancels.
        c.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_META, left)
        c.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_ALT, left)
        c.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_ALT, left)
        c.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_META, left)
        assertTrue(c.active.value)
    }

    @Test
    fun `uncaptured pointer events are consumed and a button release captures`() {
        val c = MouseCapture()
        assertTrue(c.pointer(PointerKind.Move))
        assertTrue(c.pointer(PointerKind.Press))
        assertFalse(c.active.value)
        assertTrue(c.pointer(PointerKind.Release))
        assertTrue(c.active.value)
        assertFalse(c.pointer(PointerKind.Move))
        assertFalse(c.pointer(PointerKind.Press))
        c.focusLost()
        assertFalse(c.active.value)
    }
}
