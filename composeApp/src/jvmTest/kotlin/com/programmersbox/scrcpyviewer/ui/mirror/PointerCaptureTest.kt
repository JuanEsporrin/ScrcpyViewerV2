package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointerCaptureTest {
    private var focused = false
    private val sent = ArrayList<ControlMessage>()
    private val keyboard = HidKeyboard(lockState = { 0 }, forwardLockKeys = false)
    private val mapper = InputMapper.forOptions(
        OptionValues(mapOf("mouse" to "uhid", "keyboard" to "uhid")),
        { IntSize(1080, 2400) }, { Rect(0f, 0f, 500f, 500f) }, { false }, hidMouse = HidMouse(),
    )
    private val capture = WindowPointerCapture(MouseCapture(), mapper, keyboard, { sent += it }, isFocused = { focused })

    @Test
    fun `capture waits for focus`() {
        capture.requestCapture()
        assertFalse(capture.active.value, "an unfocused window must not grab the pointer")
        focused = true
        capture.focusGained()
        assertTrue(capture.active.value)
        focused = false
        capture.focusLost()
        assertFalse(capture.active.value)
        focused = true
        capture.focusGained()
        assertFalse(capture.active.value, "regaining focus does not recapture; a click does (mouse_capture.c)")
    }

    @Test
    fun `focused window captures at once`() {
        focused = true
        capture.requestCapture()
        assertTrue(capture.active.value)
    }

    @Test
    fun `uhid keyboard and mouse - MOD+key still does not reach the device`() {
        focused = true
        capture.requestCapture()
        val left = AwtKeyEvent.KEY_LOCATION_LEFT
        val std = AwtKeyEvent.KEY_LOCATION_STANDARD
        // The window routes capture keys to the capture first; it must pass them on to the HID keyboard's state.
        assertTrue(capture.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_META, left))
        assertFalse(capture.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_H, std))
        assertTrue(keyboard.awtKey(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_H, std).isEmpty(), "Cmd+H is a shortcut")
        assertFalse(capture.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_H, std))
        keyboard.awtKey(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_H, std)
        assertTrue(capture.key(AwtKeyEvent.KEY_RELEASED, AwtKeyEvent.VK_META, left))
        assertTrue(capture.active.value, "a key in between cancels the toggle")
        // After the release, plain keys go through again.
        assertFalse(capture.key(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_H, std))
        val h = keyboard.awtKey(AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.VK_H, std).single() as ControlMessage.UhidInput
        assertEquals(0x0B, h.data[2].toInt())
        assertTrue(sent.isEmpty(), "capture keys produce no reports")
    }
}
