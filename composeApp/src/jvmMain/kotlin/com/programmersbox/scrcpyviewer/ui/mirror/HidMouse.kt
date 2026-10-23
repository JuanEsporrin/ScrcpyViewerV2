package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.unit.IntOffset
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The UHID mouse of v5.0 `hid/hid_mouse.c` and `uhid/mouse_uhid.c`: relative motion, five buttons, vertical wheel and
 * horizontal pan. Every report is 5 bytes: buttons, x, y, wheel, pan (signed, clamped to [-127, 127]). Button masks
 * use [MouseButton.bit].
 */
class HidMouse {
    private var residualH = 0f
    private var residualV = 0f

    fun open(): ControlMessage.UhidCreate = ControlMessage.UhidCreate(ID, 0, 0, "", REPORT_DESC)

    fun close(): ControlMessage.UhidDestroy = ControlMessage.UhidDestroy(ID)

    /** `sc_hid_mouse_generate_input_from_motion()`. */
    fun motion(dx: Int, dy: Int, buttons: Int): ControlMessage.UhidInput =
        input(buttons, clamp(dx), clamp(dy), 0, 0)

    /** `sc_hid_mouse_generate_input_from_click()`: the new buttons state, no motion. */
    fun click(buttons: Int): ControlMessage.UhidInput = input(buttons, 0, 0, 0, 0)

    /**
     * `sc_hid_mouse_generate_input_from_scroll()`: SDL wheel units (vscroll positive away from the user, hscroll
     * positive to the right). Fractions accumulate until a whole notch; null while there is none.
     */
    fun scroll(hscroll: Float, vscroll: Float): ControlMessage.UhidInput? {
        residualH += hscroll
        residualV += vscroll
        val h = consume(residualH).also { residualH = it.second }.first
        val v = consume(residualV).also { residualV = it.second }.first
        if (h == 0 && v == 0) return null
        return input(0, 0, 0, v, h)
    }

    private fun consume(scroll: Float): Pair<Int, Float> {
        val value = scroll.coerceIn(-127f, 127f)
        val whole = value.toInt() // truncates towards 0
        return whole to (value - whole)
    }

    private fun clamp(v: Int) = v.coerceIn(-127, 127)

    private fun input(buttons: Int, x: Int, y: Int, wheel: Int, pan: Int) =
        ControlMessage.UhidInput(ID, byteArrayOf((buttons and 0x1F).toByte(), x.toByte(), y.toByte(), wheel.toByte(), pan.toByte()))

    companion object {
        const val ID = 2

        /** `SC_HID_MOUSE_REPORT_DESC` of v5.0 `hid_mouse.c`. */
        val REPORT_DESC: ByteArray = intArrayOf(
            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x02, // Usage (Mouse)
            0xA1, 0x01, // Collection (Application)
            0x09, 0x01, // Usage (Pointer)
            0xA1, 0x00, // Collection (Physical)
            0x05, 0x09, // Usage Page (Buttons)
            0x19, 0x01, // Usage Minimum (1)
            0x29, 0x05, // Usage Maximum (5)
            0x15, 0x00, // Logical Minimum (0)
            0x25, 0x01, // Logical Maximum (1)
            0x95, 0x05, // Report Count (5)
            0x75, 0x01, // Report Size (1)
            0x81, 0x02, // Input (Data, Variable, Absolute): 5 buttons bits
            0x95, 0x01, // Report Count (1)
            0x75, 0x03, // Report Size (3)
            0x81, 0x01, // Input (Constant): 3 bits padding
            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x30, // Usage (X)
            0x09, 0x31, // Usage (Y)
            0x09, 0x38, // Usage (Wheel)
            0x15, 0x81, // Logical Minimum (-127)
            0x25, 0x7F, // Logical Maximum (127)
            0x75, 0x08, // Report Size (8)
            0x95, 0x03, // Report Count (3)
            0x81, 0x06, // Input (Data, Variable, Relative): 3 position bytes (X, Y, Wheel)
            0x05, 0x0C, // Usage Page (Consumer Page)
            0x0A, 0x38, 0x02, // Usage (AC Pan)
            0x15, 0x81, // Logical Minimum (-127)
            0x25, 0x7F, // Logical Maximum (127)
            0x75, 0x08, // Report Size (8)
            0x95, 0x01, // Report Count (1)
            0x81, 0x06, // Input (Data, Variable, Relative): 1 byte (AC Pan)
            0xC0, // End Collection
            0xC0, // End Collection
        ).map { it.toByte() }.toByteArray()
    }
}

/**
 * Relative motion from absolute pointer positions (screen coordinates), for a pointer that is moved back to the
 * centre of the view now and then ([warped]) so it never reaches the screen edge.
 *
 * Events already queued when the pointer was warped still carry pre-warp positions; an event closer to the
 * pre-warp position than to the warp target counts from the former. So does every event when the warp did not
 * happen (no `java.awt.Robot`), which keeps the motion right without recentring.
 */
class PointerDeltaTracker {
    private var last: IntOffset? = null
    private var preWarp: IntOffset? = null

    fun reset() {
        last = null
        preWarp = null
    }

    /** Delta since the previous position; zero for the first one. */
    fun move(x: Int, y: Int): IntOffset {
        val p = IntOffset(x, y)
        val before = preWarp
        val center = last
        if (before != null && center != null && dist2(p, before) < dist2(p, center)) {
            preWarp = p
            return p - before
        }
        preWarp = null
        last = p
        return if (center == null) IntOffset.Zero else p - center
    }

    /** The pointer was moved to [target]. */
    fun warped(target: IntOffset) {
        preWarp = preWarp ?: last
        last = target
    }

    private fun dist2(a: IntOffset, b: IntOffset): Long {
        val dx = (a.x - b.x).toLong()
        val dy = (a.y - b.y).toLong()
        return dx * dx + dy * dy
    }
}

/** Pointer events as [MouseCapture] sorts them. */
enum class PointerKind { Move, Press, Release, Scroll }

/**
 * The mouse capture of v5.0 `mouse_capture.c` for the relative (UHID) mouse. While not [active], pointer events are
 * consumed and a button release captures. A capture key (left Alt or left Meta/Cmd, the default `--shortcut-mod`)
 * pressed and released alone toggles the capture; capture keys are never forwarded. Losing focus releases it.
 */
class MouseCapture {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** The capture key pressed alone so far (keyCode, location), or null. */
    private var pending: Pair<Int, Int>? = null

    fun setActive(capture: Boolean) {
        _active.value = capture
    }

    fun focusLost() = setActive(false)

    /** Returns true when the key event is consumed. */
    fun key(id: Int, keyCode: Int, location: Int): Boolean {
        val captureKey = isCaptureKey(keyCode, location)
        when (id) {
            AwtKeyEvent.KEY_PRESSED -> {
                if (!captureKey) return false
                val key = keyCode to location
                pending = when (pending) {
                    null -> key
                    key -> key // auto-repeat
                    else -> null // another capture key: cancel
                }
                return true
            }
            AwtKeyEvent.KEY_RELEASED -> {
                val cap = pending
                pending = null
                if (!captureKey) return false
                if (cap == keyCode to location) setActive(!_active.value)
                return true
            }
            else -> return false
        }
    }

    /** Returns true when the pointer event is consumed (not captured yet). */
    fun pointer(kind: PointerKind): Boolean {
        if (_active.value) return false
        if (kind == PointerKind.Release) setActive(true)
        return true
    }

    companion object {
        fun isCaptureKey(keyCode: Int, location: Int): Boolean =
            location == AwtKeyEvent.KEY_LOCATION_LEFT &&
                (keyCode == AwtKeyEvent.VK_ALT || keyCode == AwtKeyEvent.VK_META || keyCode == AwtKeyEvent.VK_WINDOWS)

        /** The release keys for the status line hint. */
        fun keyLabel(): String = if (isMacOs()) "Option or Cmd" else "Alt or Super"
    }
}
