package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClickIndicatorTest {
    // The same displayed-image mapping as the input: a 1344x2992 frame letterboxed in a 500x500 view, so the frame
    // covers x in [137.7, 362.3).
    private val mapper = InputMapper(frameSize = { IntSize(1344, 2992) }, viewRect = { Rect(0f, 0f, 500f, 500f) })
    private val dots = ClickIndicator(mapper::displayedRect)

    @Test
    fun `down shows the dot at the view point, drag moves it, up fades it out`() {
        assertNull(dots.state(0))
        dots.press(Offset(250f, 250f))
        assertEquals(ClickIndicator.Dot(Offset(250f, 250f), 1f), dots.state(0))
        dots.move(Offset(300f, 100f))
        assertEquals(ClickIndicator.Dot(Offset(300f, 100f), 1f), dots.state(10))
        dots.release(nowMs = 1_000)
        assertTrue(dots.isFading(1_000))
        assertEquals(1f, dots.state(1_000)!!.alpha)
        assertEquals(0.5f, dots.state(1_150)!!.alpha, 0.001f)
        assertEquals(Offset(300f, 100f), dots.state(1_150)!!.position, "a fading dot stays where it was released")
        assertNull(dots.state(1_300))
        assertFalse(dots.isFading(1_300))
    }

    @Test
    fun `a press on the letterbox shows nothing`() {
        dots.press(Offset(50f, 250f))
        assertNull(dots.state(0))
        dots.move(Offset(250f, 250f)) // not held: ignored
        assertNull(dots.state(0))
    }

    @Test
    fun `a drag into the letterbox stays on the frame`() {
        dots.press(Offset(200f, 250f))
        dots.move(Offset(20f, 250f))
        val rect = mapper.displayedRect()!!
        assertEquals(Offset(rect.left, 250f), dots.state(0)!!.position)
    }

    @Test
    fun `nothing before the first frame`() {
        val none = ClickIndicator({ null })
        none.press(Offset(10f, 10f))
        assertNull(none.state(0))
    }
}
