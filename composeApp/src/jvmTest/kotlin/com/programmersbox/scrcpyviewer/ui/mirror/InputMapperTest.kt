package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.Position
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputMapperTest {
    private var size: IntSize? = IntSize(1344, 2992)
    private val view = Rect(0f, 0f, 500f, 500f)
    private val mapper = InputMapper(frameSize = { size }, viewRect = { view })
    private val center = Offset(250f, 250f)

    @Test
    fun `maps view point to device point with letterbox`() {
        val msgs = mapper.press(center, PointerButton.Primary)
        val touch = msgs.single() as ControlMessage.InjectTouch
        assertEquals(Position(672, 1496, 1344, 2992), touch.position)
        assertEquals(InputMapper.ACTION_DOWN, touch.action)
        assertEquals(ControlMessage.POINTER_ID_MOUSE, touch.pointerId)
        assertEquals(1f, touch.pressure)
        assertEquals(InputMapper.BUTTON_PRIMARY, touch.actionButton)
        assertEquals(InputMapper.BUTTON_PRIMARY, touch.buttons)

        val up = mapper.release(center, PointerButton.Primary).single() as ControlMessage.InjectTouch
        assertEquals(InputMapper.ACTION_UP, up.action)
        assertEquals(0f, up.pressure)
        assertEquals(0, up.buttons)
    }

    @Test
    fun `drops points in letterbox bars`() {
        // The frame is 224.6 px wide, centred: x in [137.7, 362.3) is video, the rest are bars.
        assertTrue(mapper.press(Offset(50f, 250f), PointerButton.Primary).isEmpty())
        assertTrue(mapper.press(Offset(450f, 250f), PointerButton.Primary).isEmpty())
        assertTrue(mapper.scroll(Offset(10f, 10f), Offset(0f, 1f)).isEmpty())
        assertTrue(mapper.move(Offset(50f, 250f)).isEmpty(), "hover in a bar is dropped")
    }

    @Test
    fun `right click is BackOrScreenOn`() {
        assertEquals(listOf<ControlMessage>(ControlMessage.BackOrScreenOn(0)), mapper.press(center, PointerButton.Secondary))
        assertEquals(listOf<ControlMessage>(ControlMessage.BackOrScreenOn(1)), mapper.release(center, PointerButton.Secondary))
    }

    @Test
    fun `wheel maps to scroll sign`() {
        // input_manager.c passes SDL's wheel y (positive = away from the user) as vscroll; Compose's
        // scrollDelta.y is positive towards the user, so a wheel-down notch is vscroll -1.
        val down = mapper.scroll(center, Offset(0f, 1f)).single() as ControlMessage.InjectScroll
        assertEquals(Position(672, 1496, 1344, 2992), down.position)
        assertEquals(0f, down.hscroll)
        assertEquals(-1f, down.vscroll)
        val right = mapper.scroll(center, Offset(1f, 0f)).single() as ControlMessage.InjectScroll
        assertEquals(1f, right.hscroll)
        assertEquals(0f, right.vscroll)
    }

    @Test
    fun `rotated frame size updates mapping`() {
        size = IntSize(2992, 1344)
        val touch = mapper.press(center, PointerButton.Primary).single() as ControlMessage.InjectTouch
        assertEquals(Position(1496, 672, 2992, 1344), touch.position)
        // Now the bars are above and below.
        assertTrue(mapper.press(Offset(250f, 50f), PointerButton.Primary).isEmpty())
    }

    @Test
    fun `drag keeps going outside the frame and clamps`() {
        mapper.press(center, PointerButton.Primary)
        val move = mapper.move(Offset(10f, 250f)).single() as ControlMessage.InjectTouch
        assertEquals(InputMapper.ACTION_MOVE, move.action)
        assertEquals(0, move.position.x)
        val up = mapper.release(Offset(10f, 250f), PointerButton.Primary).single() as ControlMessage.InjectTouch
        assertEquals(InputMapper.ACTION_UP, up.action)
    }

    @Test
    fun `no frame means no input`() {
        size = null
        assertTrue(mapper.press(center, PointerButton.Primary).isEmpty())
    }

    @Test
    fun `keys map to android keycodes and text`() {
        val enter = mapper.awtKey(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_ENTER, '\n', 0)
        assertEquals(listOf<ControlMessage>(ControlMessage.InjectKeycode(0, 66, 0, 0)), enter)
        val a = mapper.awtKey(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_A, 'a', AwtKeyEvent.SHIFT_DOWN_MASK)
        assertEquals(listOf<ControlMessage>(ControlMessage.InjectKeycode(0, 29, 0, 0x41)), a)
        // Mixed mode: letters go as key events, other printable text as InjectText.
        assertTrue(mapper.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, 'a', 0).isEmpty())
        assertEquals(
            listOf<ControlMessage>(ControlMessage.InjectText("!")),
            mapper.awtKey(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED, '!', AwtKeyEvent.SHIFT_DOWN_MASK),
        )
        val up = mapper.awtKey(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_BACK_SPACE, '\b', 0)
        assertEquals(listOf<ControlMessage>(ControlMessage.InjectKeycode(1, 67, 0, 0)), up)
    }
}
