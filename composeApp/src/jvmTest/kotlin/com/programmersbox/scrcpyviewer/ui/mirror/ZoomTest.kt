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
import kotlin.test.assertNull

class ZoomTest {
    private val fitted = 0.37f

    @Test
    fun `zoom in steps up through the list`() {
        assertEquals(1.25f, Zoom.step(1f, fitted, ZoomCommand.In))
        assertEquals(0.5f, Zoom.step(0.33f, fitted, ZoomCommand.In))
        assertEquals(2f, Zoom.step(1.5f, fitted, ZoomCommand.In))
    }

    @Test
    fun `zoom out steps down through the list`() {
        assertEquals(0.75f, Zoom.step(1f, fitted, ZoomCommand.Out))
        assertEquals(0.67f, Zoom.step(0.75f, fitted, ZoomCommand.Out))
        assertEquals(1f, Zoom.step(1.25f, fitted, ZoomCommand.Out))
    }

    @Test
    fun `steps clamp at both ends`() {
        assertEquals(4f, Zoom.step(4f, fitted, ZoomCommand.In))
        assertEquals(0.25f, Zoom.step(0.25f, fitted, ZoomCommand.Out))
    }

    @Test
    fun `from fit starts at the nearest step to the fitted scale`() {
        // 0.37 is nearest to 33 %: in goes to 50, out goes to 25.
        assertEquals(0.5f, Zoom.step(null, 0.37f, ZoomCommand.In))
        assertEquals(0.25f, Zoom.step(null, 0.37f, ZoomCommand.Out))
        // 0.9 is nearest to 100 %.
        assertEquals(1.25f, Zoom.step(null, 0.9f, ZoomCommand.In))
        assertEquals(0.75f, Zoom.step(null, 0.9f, ZoomCommand.Out))
        // Fitted beyond the ends stays clamped.
        assertEquals(4f, Zoom.step(null, 9f, ZoomCommand.In))
        assertEquals(0.25f, Zoom.step(null, 0.05f, ZoomCommand.Out))
    }

    @Test
    fun `fit and actual size commands`() {
        assertNull(Zoom.step(0.5f, fitted, ZoomCommand.Fit))
        assertEquals(1f, Zoom.step(null, fitted, ZoomCommand.Actual))
        // Actual toggles back to fit when already at 100 %.
        assertNull(Zoom.step(1f, fitted, ZoomCommand.Actual))
    }

    @Test
    fun `percent label`() {
        assertEquals("67%", Zoom.label(0.67f))
        assertEquals("200%", Zoom.label(2f))
        assertEquals("37%", Zoom.label(0.37f))
    }

    // ---- shortcuts ----

    @Test
    fun `shortcut keys with the platform modifier`() {
        val cmd = AwtKeyEvent.META_DOWN_MASK
        val ctrl = AwtKeyEvent.CTRL_DOWN_MASK
        assertEquals(ZoomCommand.In, Zoom.shortcut(AwtKeyEvent.VK_EQUALS, cmd, mac = true))
        assertEquals(ZoomCommand.In, Zoom.shortcut(AwtKeyEvent.VK_PLUS, cmd or AwtKeyEvent.SHIFT_DOWN_MASK, mac = true))
        assertEquals(ZoomCommand.In, Zoom.shortcut(AwtKeyEvent.VK_ADD, ctrl, mac = false))
        assertEquals(ZoomCommand.Out, Zoom.shortcut(AwtKeyEvent.VK_MINUS, ctrl, mac = false))
        assertEquals(ZoomCommand.Out, Zoom.shortcut(AwtKeyEvent.VK_SUBTRACT, cmd, mac = true))
        assertEquals(ZoomCommand.Fit, Zoom.shortcut(AwtKeyEvent.VK_0, cmd, mac = true))
        assertEquals(ZoomCommand.Fit, Zoom.shortcut(AwtKeyEvent.VK_NUMPAD0, ctrl, mac = false))
    }

    @Test
    fun `no shortcut without the platform modifier`() {
        assertNull(Zoom.shortcut(AwtKeyEvent.VK_EQUALS, 0, mac = true))
        assertNull(Zoom.shortcut(AwtKeyEvent.VK_MINUS, AwtKeyEvent.CTRL_DOWN_MASK, mac = true)) // Ctrl is not Cmd on a Mac
        assertNull(Zoom.shortcut(AwtKeyEvent.VK_0, AwtKeyEvent.META_DOWN_MASK, mac = false))
        assertNull(Zoom.shortcut(AwtKeyEvent.VK_A, AwtKeyEvent.META_DOWN_MASK, mac = true))
    }

    @Test
    fun `zoom shortcut keys inject nothing when they do reach the mapper`() {
        // The window consumes them first; the mapper must not turn Cmd/Ctrl + these into device keys either way.
        val mapper = InputMapper(frameSize = { IntSize(100, 200) }, viewRect = { Rect(0f, 0f, 100f, 200f) })
        for (mods in listOf(AwtKeyEvent.META_DOWN_MASK, AwtKeyEvent.CTRL_DOWN_MASK)) {
            for (code in listOf(AwtKeyEvent.VK_EQUALS, AwtKeyEvent.VK_PLUS, AwtKeyEvent.VK_MINUS, AwtKeyEvent.VK_0)) {
                assertEquals(emptyList(), mapper.awtKey(AwtKeyEvent.KEY_PRESSED, code, '=', mods), "pressed $code")
                assertEquals(emptyList(), mapper.awtKey(AwtKeyEvent.KEY_TYPED, code, '=', mods), "typed $code")
            }
        }
    }

    // ---- geometry ----

    private val frame = IntSize(1000, 2000)

    private fun mapperFor(rect: Rect) = InputMapper(frameSize = { frame }, viewRect = { rect })

    @Test
    fun `zoomed rect is centred when smaller than the stage`() {
        val r = Zoom.rect(stage = IntSize(800, 1200), oriented = frame, zoom = 0.5f, scroll = Offset.Zero)
        assertEquals(Rect(150f, 100f, 650f, 1100f), r)
    }

    @Test
    fun `zoomed rect follows the scroll offset when larger than the stage`() {
        val r = Zoom.rect(stage = IntSize(800, 1200), oriented = frame, zoom = 2f, scroll = Offset(300f, 1000f))
        assertEquals(Rect(-300f, -1000f, 1700f, 3000f), r)
    }

    @Test
    fun `maps a point at 200 percent with a scroll offset`() {
        val rect = Zoom.rect(IntSize(800, 1200), frame, 2f, Offset(300f, 1000f))
        // Stage point (400, 500) is image point (700, 1500) in zoomed px = frame (350, 750).
        val touch = mapperFor(rect).press(Offset(400f, 500f), PointerButton.Primary).single() as ControlMessage.InjectTouch
        assertEquals(Position(350, 750, 1000, 2000), touch.position)
    }

    @Test
    fun `maps a point at 50 percent centred, and drops the letterbox`() {
        val rect = Zoom.rect(IntSize(800, 1200), frame, 0.5f, Offset.Zero)
        val mapper = mapperFor(rect)
        // Rect is (150,100)-(650,1100): its centre is the frame centre.
        val touch = mapper.press(Offset(400f, 600f), PointerButton.Primary).single() as ControlMessage.InjectTouch
        assertEquals(Position(500, 1000, 1000, 2000), touch.position)
        assertEquals(emptyList(), mapper.press(Offset(100f, 600f), PointerButton.Primary))
        assertEquals(Rect(150f, 100f, 650f, 1100f), mapper.displayedRect())
    }

    @Test
    fun `click indicator uses the zoomed rect`() {
        val rect = Zoom.rect(IntSize(800, 1200), frame, 0.5f, Offset.Zero)
        val dots = ClickIndicator(mapperFor(rect)::displayedRect)
        dots.press(Offset(100f, 600f)) // letterbox
        assertNull(dots.state(0))
        dots.press(Offset(400f, 600f))
        assertEquals(Offset(400f, 600f), dots.state(0)?.position)
    }
}
