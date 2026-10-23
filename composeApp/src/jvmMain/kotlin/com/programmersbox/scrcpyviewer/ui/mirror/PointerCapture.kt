package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import kotlinx.coroutines.flow.StateFlow
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The mirror window's pointer capture for the relative (UHID) mouse: [MouseCapture] state, deltas from screen
 * positions ([PointerDeltaTracker]) and recentring with [robot].
 *
 * The capture only starts in a focused window ([requestCapture] waits for [focusGained] otherwise), and recentring
 * only happens while focused, so an unfocused window never traps the pointer. Capture keys (left Alt / left Meta)
 * are also fed to [hidKeyboard], which tracks them as shortcut modifiers: MOD+key never reaches the device.
 *
 * Call from the AWT event thread.
 */
class WindowPointerCapture(
    private val capture: MouseCapture,
    private val mapper: InputMapper,
    private val hidKeyboard: HidKeyboard?,
    private val send: (List<ControlMessage>) -> Unit,
    private val isFocused: () -> Boolean,
    private val robot: Robot? = null,
) {
    val active: StateFlow<Boolean> get() = capture.active

    private val tracker = PointerDeltaTracker()
    private var pending = false

    /** Capture as the scrcpy client does at start (first frame, or at once without video), once focused. */
    fun requestCapture() {
        if (isFocused()) activate() else pending = true
    }

    fun focusGained() {
        if (pending) {
            pending = false
            activate()
        }
    }

    fun focusLost() {
        capture.focusLost()
        tracker.reset()
    }

    /** Returns true when the key event is a capture key (consumed). */
    fun key(id: Int, keyCode: Int, location: Int): Boolean {
        if (!capture.key(id, keyCode, location)) return false
        // Only updates the keyboard's shortcut-modifier state: capture keys never produce HID reports.
        hidKeyboard?.awtKey(id, keyCode, location)
        return true
    }

    /** A pointer event on the video view of [viewSize] px; [density] converts px to screen points. */
    fun pointer(ev: PointerEvent, window: Window, viewSize: IntSize, density: Float) {
        val kind = when (ev.type) {
            PointerEventType.Move -> PointerKind.Move
            PointerEventType.Press -> PointerKind.Press
            PointerEventType.Release -> PointerKind.Release
            PointerEventType.Scroll -> PointerKind.Scroll
            else -> null
        }
        val awt = ev.awtEventOrNull
        val local = ev.changes.firstOrNull()?.position
        if (kind == null) {
            if (ev.type == PointerEventType.Exit && capture.active.value && awt != null && local != null) {
                recentre(window, awt, local, viewSize, density, force = true)
            }
            return
        }
        if (capture.pointer(kind)) {
            tracker.reset()
            return
        }
        if (kind != PointerKind.Move) {
            send(mapper.pointer(ev))
            return
        }
        if (awt == null || local == null) return
        val d = tracker.move(awt.xOnScreen, awt.yOnScreen)
        send(mapper.relativeMove(d.x, d.y))
        recentre(window, awt, local, viewSize, density, force = false)
    }

    private fun activate() {
        tracker.reset()
        capture.setActive(true)
    }

    /**
     * Moves the pointer back to the centre of the video view once it is more than a quarter of the view away (or
     * always with [force], when it left the view), so it never hits the screen edge. [local] is the event position
     * in the view (px), [awt] the same event in screen coordinates (points). The target is kept inside the window.
     */
    private fun recentre(window: Window, awt: MouseEvent, local: Offset, viewSize: IntSize, density: Float, force: Boolean) {
        val r = robot ?: return
        if (!isFocused() || viewSize.width <= 0 || viewSize.height <= 0) return
        val originX = awt.xOnScreen - local.x / density
        val originY = awt.yOnScreen - local.y / density
        val w = window.bounds
        val cx = (originX + viewSize.width / 2f / density).roundToInt().coerceIn(w.x + 40, maxOf(w.x + 40, w.x + w.width - 40))
        val cy = (originY + viewSize.height / 2f / density).roundToInt().coerceIn(w.y + 40, maxOf(w.y + 40, w.y + w.height - 40))
        val limit = min(viewSize.width, viewSize.height) / 4f / density
        if (!force && abs(awt.xOnScreen - cx) <= limit && abs(awt.yOnScreen - cy) <= limit) return
        r.mouseMove(cx, cy)
        tracker.warped(IntOffset(cx, cy))
    }

    companion object {
        /** A Robot for recentring, or null where there is none (headless, or not permitted). */
        fun robotOrNull(): Robot? = runCatching { Robot() }.getOrNull()
    }
}

/** Transparent cursor shown over the video while the mouse is captured; created on first use (not headless). */
internal val blankPointerIcon: PointerIcon by lazy {
    runCatching {
        PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank"))
    }.getOrDefault(PointerIcon.Default)
}
