package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.Position
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.math.floor
import kotlin.math.min

/** How `keyboard_sdk.c` splits keys between key events and text events (`--prefer-text`, `--raw-key-events`). */
enum class KeyInjectMode { Mixed, Text, Raw }

/**
 * Turns pointer and key events of the mirror view into scrcpy control messages, as v5.0 `app/src/input_manager.c`,
 * `mouse_sdk.c` and `keyboard_sdk.c` do for the SDK mouse and keyboard.
 *
 * [viewRect] is the area (in the pointer events' coordinates) the video is fitted into. The frame of [frameSize]
 * (the size of the image on screen; it changes on rotation) is letterboxed inside it, or stretched to fill it when
 * [stretch] returns true. Device positions carry that frame size as the screen size.
 *
 * - Left button: `InjectTouch` with the mouse pointer id (-1). A press, hover or scroll on the letterbox bars is
 *   dropped; once a button is held, moves and the release are clamped to the frame so the gesture always ends.
 * - Right button: `BackOrScreenOn` (down and up). Middle button: HOME key down and up.
 * - Wheel: `InjectScroll`; Compose's delta is positive towards the user, SDL's (and Android's) the other way, so
 *   vscroll is `-delta.y` and hscroll `delta.x`.
 * - Motion without a button: `HOVER_MOVE` unless [hover] is false (`--no-mouse-hover`).
 */
class InputMapper(
    private val frameSize: () -> IntSize?,
    private val viewRect: () -> Rect,
    private val stretch: () -> Boolean = { false },
    private val hover: Boolean = true,
    private val keyMode: KeyInjectMode = KeyInjectMode.Mixed,
    private val forwardKeyRepeat: Boolean = true,
) {
    private var buttons = 0
    private var lastKeyDown = -1
    private var repeat = 0

    /** Maps a Compose pointer event (press, release, move, scroll). Other event types give nothing. */
    @OptIn(ExperimentalComposeUiApi::class)
    fun pointer(event: PointerEvent): List<ControlMessage> {
        val change = event.changes.firstOrNull() ?: return emptyList()
        val pos = change.position
        return when (event.type) {
            PointerEventType.Press -> event.button?.let { press(pos, it) } ?: emptyList()
            PointerEventType.Release -> event.button?.let { release(pos, it) } ?: emptyList()
            PointerEventType.Move -> move(pos)
            PointerEventType.Scroll -> scroll(pos, change.scrollDelta)
            else -> emptyList()
        }
    }

    fun press(position: Offset, button: PointerButton): List<ControlMessage> = mouseButton(position, button, down = true)

    fun release(position: Offset, button: PointerButton): List<ControlMessage> = mouseButton(position, button, down = false)

    fun move(position: Offset): List<ControlMessage> {
        if (buttons == 0) {
            if (!hover) return emptyList()
            val p = toDevice(position, clamp = false) ?: return emptyList()
            return listOf(touch(ACTION_HOVER_MOVE, p, pressure = 0f, actionButton = 0))
        }
        val p = toDevice(position, clamp = true) ?: return emptyList()
        return listOf(touch(ACTION_MOVE, p, pressure = 1f, actionButton = 0))
    }

    fun scroll(position: Offset, delta: Offset): List<ControlMessage> {
        val p = toDevice(position, clamp = false) ?: return emptyList()
        return listOf(ControlMessage.InjectScroll(p, hscroll = delta.x, vscroll = 0f - delta.y, buttons = buttons))
    }

    private fun mouseButton(position: Offset, button: PointerButton, down: Boolean): List<ControlMessage> {
        val action = if (down) KEY_ACTION_DOWN else KEY_ACTION_UP
        when (button) {
            PointerButton.Secondary -> return listOf(ControlMessage.BackOrScreenOn(action))
            PointerButton.Tertiary -> return listOf(ControlMessage.InjectKeycode(action, KEYCODE_HOME, 0, 0))
            PointerButton.Primary -> Unit
            else -> return emptyList()
        }
        if (down) {
            val p = toDevice(position, clamp = false) ?: return emptyList()
            buttons = buttons or BUTTON_PRIMARY
            return listOf(touch(ACTION_DOWN, p, pressure = 1f, actionButton = BUTTON_PRIMARY))
        }
        if (buttons and BUTTON_PRIMARY == 0) return emptyList() // the press was dropped
        buttons = buttons and BUTTON_PRIMARY.inv()
        val p = toDevice(position, clamp = true) ?: return emptyList()
        return listOf(touch(ACTION_UP, p, pressure = 0f, actionButton = BUTTON_PRIMARY))
    }

    private fun touch(action: Int, p: Position, pressure: Float, actionButton: Int) = ControlMessage.InjectTouch(
        action = action,
        pointerId = ControlMessage.POINTER_ID_MOUSE,
        position = p,
        pressure = pressure.coerceIn(0f, 1f),
        actionButton = actionButton,
        buttons = buttons,
    )

    /** Device position of a view point; null outside the frame (unless [clamp]) or before the first frame. */
    fun toDevice(point: Offset, clamp: Boolean): Position? {
        val size = frameSize() ?: return null
        if (size.width <= 0 || size.height <= 0) return null
        val content = contentRect(size, viewRect(), stretch())
        if (content.width <= 0f || content.height <= 0f) return null
        var x = floor((point.x - content.left).toDouble() * size.width / content.width + EPSILON).toInt()
        var y = floor((point.y - content.top).toDouble() * size.height / content.height + EPSILON).toInt()
        val inside = x in 0 until size.width && y in 0 until size.height
        if (!inside) {
            if (!clamp) return null
            x = x.coerceIn(0, size.width - 1)
            y = y.coerceIn(0, size.height - 1)
        }
        return Position(x, y, size.width, size.height)
    }

    /** Maps a Compose key event through its AWT event. */
    fun key(event: KeyEvent): List<ControlMessage> {
        val awt = event.nativeKeyEvent as? AwtKeyEvent ?: return emptyList()
        return awtKey(awt.id, awt.keyCode, awt.keyChar, awt.modifiersEx, awt.keyLocation)
    }

    /** `keyboard_sdk.c`: key presses/releases become `InjectKeycode`, typed characters `InjectText`. */
    fun awtKey(
        id: Int,
        keyCode: Int,
        keyChar: Char,
        modifiersEx: Int,
        location: Int = AwtKeyEvent.KEY_LOCATION_STANDARD,
    ): List<ControlMessage> = when (id) {
        AwtKeyEvent.KEY_PRESSED, AwtKeyEvent.KEY_RELEASED -> keyEvent(id == AwtKeyEvent.KEY_PRESSED, keyCode, modifiersEx, location)
        AwtKeyEvent.KEY_TYPED -> textEvent(keyChar, modifiersEx)
        else -> emptyList()
    }

    private fun keyEvent(down: Boolean, keyCode: Int, mods: Int, location: Int): List<ControlMessage> {
        if (down) {
            if (keyCode == lastKeyDown) {
                if (!forwardKeyRepeat) return emptyList()
                repeat++
            } else {
                repeat = 0
            }
            lastKeyDown = keyCode
        } else {
            if (keyCode == lastKeyDown) lastKeyDown = -1
        }
        val android = convertKeycode(keyCode, mods, location) ?: return emptyList()
        return listOf(
            ControlMessage.InjectKeycode(
                action = if (down) KEY_ACTION_DOWN else KEY_ACTION_UP,
                keycode = android,
                repeat = if (down) repeat else 0,
                metastate = metaState(mods),
            )
        )
    }

    private fun textEvent(c: Char, mods: Int): List<ControlMessage> {
        if (keyMode == KeyInjectMode.Raw) return emptyList()
        if (c.isISOControl() || c == AwtKeyEvent.CHAR_UNDEFINED) return emptyList()
        // Shortcuts (Ctrl/Cmd + key) are key events, not text.
        if (mods and (AwtKeyEvent.CTRL_DOWN_MASK or AwtKeyEvent.META_DOWN_MASK) != 0) return emptyList()
        if (keyMode == KeyInjectMode.Mixed && (c in 'a'..'z' || c in 'A'..'Z' || c == ' ')) return emptyList()
        return listOf(ControlMessage.InjectText(c.toString()))
    }

    private fun convertKeycode(keyCode: Int, mods: Int, location: Int): Int? {
        if (keyCode == AwtKeyEvent.VK_ENTER && location == AwtKeyEvent.KEY_LOCATION_NUMPAD) return KEYCODE_NUMPAD_ENTER
        SPECIAL_KEYS[keyCode]?.let { base ->
            return if (location == AwtKeyEvent.KEY_LOCATION_RIGHT) RIGHT_VARIANTS[base] ?: base else base
        }
        if (mods and AwtKeyEvent.SHIFT_DOWN_MASK == 0) KP_NAV_KEYS[keyCode]?.let { return it }
        if (keyMode == KeyInjectMode.Text && mods and AwtKeyEvent.CTRL_DOWN_MASK == 0) return null
        ALPHA_SPACE_KEYS[keyCode]?.let { return it }
        if (keyMode == KeyInjectMode.Raw) NUMBERS_PUNCT_KEYS[keyCode]?.let { return it }
        return null
    }

    private fun metaState(mods: Int): Int {
        var m = 0
        // AWT does not tell left from right modifiers; report the left one like a single-modifier keyboard.
        if (mods and AwtKeyEvent.SHIFT_DOWN_MASK != 0) m = m or META_SHIFT_LEFT_ON or META_SHIFT_ON
        if (mods and AwtKeyEvent.CTRL_DOWN_MASK != 0) m = m or META_CTRL_LEFT_ON or META_CTRL_ON
        if (mods and AwtKeyEvent.ALT_DOWN_MASK != 0) m = m or META_ALT_LEFT_ON or META_ALT_ON
        if (mods and AwtKeyEvent.ALT_GRAPH_DOWN_MASK != 0) m = m or META_ALT_RIGHT_ON or META_ALT_ON
        if (mods and AwtKeyEvent.META_DOWN_MASK != 0) m = m or META_META_LEFT_ON or META_META_ON
        return m
    }

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_HOVER_MOVE = 7
        const val KEY_ACTION_DOWN = 0
        const val KEY_ACTION_UP = 1
        const val BUTTON_PRIMARY = 1

        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val KEYCODE_POWER = 26
        const val KEYCODE_APP_SWITCH = 187
        private const val KEYCODE_NUMPAD_ENTER = 160

        private const val META_SHIFT_ON = 0x01
        private const val META_ALT_ON = 0x02
        private const val META_ALT_LEFT_ON = 0x10
        private const val META_ALT_RIGHT_ON = 0x20
        private const val META_SHIFT_LEFT_ON = 0x40
        private const val META_CTRL_ON = 0x1000
        private const val META_CTRL_LEFT_ON = 0x2000
        private const val META_META_ON = 0x10000
        private const val META_META_LEFT_ON = 0x20000

        private const val EPSILON = 1e-6

        /** Rectangle the frame is drawn in: aspect-fit (letterbox) inside [view], or [view] itself when stretched. */
        fun contentRect(frame: IntSize, view: Rect, stretch: Boolean = false): Rect {
            if (stretch || frame.width <= 0 || frame.height <= 0) return view
            val scale = min(view.width / frame.width, view.height / frame.height)
            val w = frame.width * scale
            val h = frame.height * scale
            val left = view.left + (view.width - w) / 2f
            val top = view.top + (view.height - h) / 2f
            return Rect(left, top, left + w, top + h)
        }

        /** `--keyboard`/`--prefer-text`/`--raw-key-events`/`--no-key-repeat`/`--no-mouse-hover` for a session. */
        fun forOptions(values: OptionValues, frameSize: () -> IntSize?, viewRect: () -> Rect, stretch: () -> Boolean): InputMapper {
            val e = values.entries
            val mode = when {
                "raw-key-events" in e -> KeyInjectMode.Raw
                "prefer-text" in e -> KeyInjectMode.Text
                else -> KeyInjectMode.Mixed
            }
            return InputMapper(frameSize, viewRect, stretch, hover = "no-mouse-hover" !in e, keyMode = mode, forwardKeyRepeat = "no-key-repeat" !in e)
        }

        // keyboard_sdk.c special_keys (all modes). Right-hand modifiers come from the key location.
        private val SPECIAL_KEYS = mapOf(
            AwtKeyEvent.VK_ENTER to 66,
            AwtKeyEvent.VK_ESCAPE to 111,
            AwtKeyEvent.VK_BACK_SPACE to 67,
            AwtKeyEvent.VK_TAB to 61,
            AwtKeyEvent.VK_PAGE_UP to 92,
            AwtKeyEvent.VK_DELETE to 112,
            AwtKeyEvent.VK_HOME to 122,
            AwtKeyEvent.VK_END to 123,
            AwtKeyEvent.VK_PAGE_DOWN to 93,
            AwtKeyEvent.VK_RIGHT to 22,
            AwtKeyEvent.VK_LEFT to 21,
            AwtKeyEvent.VK_DOWN to 20,
            AwtKeyEvent.VK_UP to 19,
            AwtKeyEvent.VK_CONTROL to 113,
            AwtKeyEvent.VK_SHIFT to 59,
            AwtKeyEvent.VK_ALT to 57,
            AwtKeyEvent.VK_META to 117,
        )
        private val RIGHT_VARIANTS = mapOf(113 to 114, 59 to 60, 57 to 58, 117 to 118)

        // kp_nav_keys (Num Lock off; AWT reports them as VK_KP_* or as the plain navigation keys above).
        private val KP_NAV_KEYS = mapOf(
            AwtKeyEvent.VK_INSERT to 124,
            AwtKeyEvent.VK_KP_DOWN to 20,
            AwtKeyEvent.VK_KP_LEFT to 21,
            AwtKeyEvent.VK_KP_RIGHT to 22,
            AwtKeyEvent.VK_KP_UP to 19,
        )

        // alphaspace_keys (not in text mode, unless Ctrl is held): A..Z are AKEYCODE 29..54.
        private val ALPHA_SPACE_KEYS: Map<Int, Int> =
            (AwtKeyEvent.VK_A..AwtKeyEvent.VK_Z).associateWith { 29 + (it - AwtKeyEvent.VK_A) } + (AwtKeyEvent.VK_SPACE to 62)

        // numbers_punct_keys (raw mode only).
        private val NUMBERS_PUNCT_KEYS: Map<Int, Int> =
            (AwtKeyEvent.VK_0..AwtKeyEvent.VK_9).associateWith { 7 + (it - AwtKeyEvent.VK_0) } +
                (AwtKeyEvent.VK_NUMPAD0..AwtKeyEvent.VK_NUMPAD9).associateWith { 144 + (it - AwtKeyEvent.VK_NUMPAD0) } +
                mapOf(
                    AwtKeyEvent.VK_NUMBER_SIGN to 18,
                    AwtKeyEvent.VK_QUOTE to 75,
                    AwtKeyEvent.VK_ASTERISK to 17,
                    AwtKeyEvent.VK_PLUS to 81,
                    AwtKeyEvent.VK_COMMA to 55,
                    AwtKeyEvent.VK_MINUS to 69,
                    AwtKeyEvent.VK_PERIOD to 56,
                    AwtKeyEvent.VK_SLASH to 76,
                    AwtKeyEvent.VK_SEMICOLON to 74,
                    AwtKeyEvent.VK_EQUALS to 70,
                    AwtKeyEvent.VK_AT to 77,
                    AwtKeyEvent.VK_OPEN_BRACKET to 71,
                    AwtKeyEvent.VK_BACK_SLASH to 73,
                    AwtKeyEvent.VK_CLOSE_BRACKET to 72,
                    AwtKeyEvent.VK_BACK_QUOTE to 68,
                    AwtKeyEvent.VK_DIVIDE to 154,
                    AwtKeyEvent.VK_MULTIPLY to 155,
                    AwtKeyEvent.VK_SUBTRACT to 156,
                    AwtKeyEvent.VK_ADD to 157,
                    AwtKeyEvent.VK_DECIMAL to 158,
                )
    }
}
