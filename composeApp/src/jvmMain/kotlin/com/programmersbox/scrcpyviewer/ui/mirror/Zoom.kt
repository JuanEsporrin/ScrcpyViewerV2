package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a zoom control or shortcut asks for. */
enum class ZoomCommand { In, Out, Fit, Actual }

/**
 * Zoom of the mirror image. A zoom is a scale (1 = one frame pixel per screen pixel); `null` is the separate
 * "Fit to window" mode.
 */
object Zoom {
    val STEPS = listOf(0.25f, 0.33f, 0.5f, 0.67f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
    private const val EPS = 1e-3f

    /** The step closest to [scale]. */
    fun nearest(scale: Float): Float = STEPS.minBy { abs(it - scale) }

    /**
     * The zoom after [command] from [current] (`null` = fit, whose scale on screen is [fitted]). In and Out move one
     * step and clamp at the ends; from fit they start at the step nearest to [fitted]. Fit leaves zoom mode; Actual
     * is 100 %, or back to fit when already there.
     */
    fun step(current: Float?, fitted: Float, command: ZoomCommand): Float? = when (command) {
        ZoomCommand.Fit -> null
        ZoomCommand.Actual -> if (current != null && abs(current - 1f) < EPS) null else 1f
        ZoomCommand.In -> {
            val base = current ?: nearest(fitted)
            STEPS.firstOrNull { it > base + EPS } ?: STEPS.last()
        }
        ZoomCommand.Out -> {
            val base = current ?: nearest(fitted)
            STEPS.lastOrNull { it < base - EPS } ?: STEPS.first()
        }
    }

    fun label(scale: Float): String = "${(scale * 100).roundToInt()}%"

    /**
     * Where the image of [oriented] size is drawn in a [stage] at [zoom], in stage coordinates: centred on an axis
     * where it is smaller than the stage, else shifted by the [scroll] offset.
     */
    fun rect(stage: IntSize, oriented: IntSize, zoom: Float, scroll: Offset): Rect {
        val w = oriented.width * zoom
        val h = oriented.height * zoom
        val left = if (w <= stage.width) (stage.width - w) / 2f else -scroll.x
        val top = if (h <= stage.height) (stage.height - h) / 2f else -scroll.y
        return Rect(left, top, left + w, top + h)
    }

    /**
     * Cmd (macOS) or Ctrl + `=`/`+` zoom in, `-` zoom out, `0` fit. Null for any other key. [modifiersEx] is the AWT
     * extended modifier mask.
     */
    fun shortcut(keyCode: Int, modifiersEx: Int, mac: Boolean): ZoomCommand? {
        val mod = if (mac) AwtKeyEvent.META_DOWN_MASK else AwtKeyEvent.CTRL_DOWN_MASK
        if (modifiersEx and mod == 0) return null
        return when (keyCode) {
            AwtKeyEvent.VK_EQUALS, AwtKeyEvent.VK_PLUS, AwtKeyEvent.VK_ADD -> ZoomCommand.In
            AwtKeyEvent.VK_MINUS, AwtKeyEvent.VK_SUBTRACT -> ZoomCommand.Out
            AwtKeyEvent.VK_0, AwtKeyEvent.VK_NUMPAD0 -> ZoomCommand.Fit
            else -> null
        }
    }
}
