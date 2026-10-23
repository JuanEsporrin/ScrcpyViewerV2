package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType

/**
 * The client-side "show clicks" dot drawn with `show-touches`. Android's show_touches only draws physical finger
 * touches, not the clicks scrcpy injects, so the mirror window marks them itself: a primary-button press on the
 * frame shows a dot at that view point, the dot follows the pointer while the button is held (clamped to the
 * frame), and fades out over [fadeMs] after the release. Presses on the letterbox bars show nothing.
 *
 * [displayedRect] is where the frame is drawn ([InputMapper.displayedRect]), so the dot sits on the same image the
 * input mapping uses. It only draws: what is sent to the device does not change. Times are milliseconds on one
 * monotonic clock. The state is snapshot state, so a Canvas reading [state] redraws when it changes.
 */
class ClickIndicator(private val displayedRect: () -> Rect?, private val fadeMs: Long = DEFAULT_FADE_MS) {
    /** The dot at [position] (view coordinates) with [alpha] from 1 (held) down to 0. */
    data class Dot(val position: Offset, val alpha: Float)

    private var position by mutableStateOf<Offset?>(null)
    private var held by mutableStateOf(false)
    private var releasedAtMs by mutableStateOf<Long?>(null)

    fun press(point: Offset) {
        val rect = displayedRect() ?: return
        if (!rect.contains(point)) return // letterbox bar
        position = point
        held = true
        releasedAtMs = null
    }

    fun move(point: Offset) {
        if (!held) return
        val rect = displayedRect() ?: return
        position = Offset(point.x.coerceIn(rect.left, rect.right), point.y.coerceIn(rect.top, rect.bottom))
    }

    fun release(nowMs: Long) {
        if (!held) return
        held = false
        releasedAtMs = nowMs
    }

    /** Feeds a pointer event of the mirror view (primary button only). */
    @OptIn(ExperimentalComposeUiApi::class)
    fun onPointer(event: PointerEvent, nowMs: Long) {
        val pos = event.changes.firstOrNull()?.position ?: return
        when (event.type) {
            PointerEventType.Press -> if (event.button == PointerButton.Primary) press(pos)
            PointerEventType.Move -> move(pos)
            PointerEventType.Release -> if (event.button == PointerButton.Primary) release(nowMs)
        }
    }

    /** The dot to draw at [nowMs], or null. */
    fun state(nowMs: Long): Dot? {
        val p = position ?: return null
        val released = releasedAtMs ?: return Dot(p, 1f)
        val elapsed = nowMs - released
        if (elapsed >= fadeMs) return null
        return Dot(p, 1f - elapsed.coerceAtLeast(0).toFloat() / fadeMs)
    }

    /** A fade is running at [nowMs] (the view must keep redrawing until it ends). */
    fun isFading(nowMs: Long): Boolean = releasedAtMs?.let { nowMs - it < fadeMs } == true

    /** When the last release happened, to restart the fade animation. */
    val releaseTime: Long? get() = releasedAtMs

    companion object {
        const val DEFAULT_FADE_MS = 300L
    }
}
