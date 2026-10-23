package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.ui.input.pointer.PointerButton
import com.programmersbox.scrcpyviewer.options.OptionValues

/**
 * Mouse buttons as v5.0 `input_events.h`. [bit] is both the HID report bit (`hid_mouse.c`) and the Android
 * `AMOTION_EVENT_BUTTON_*` value (`mouse_sdk.c`): primary 1, secondary 2, tertiary 4, back 8, forward 16.
 */
enum class MouseButton(val bit: Int) {
    Left(1), Right(2), Middle(4), X1(8), X2(16);

    companion object {
        fun of(button: PointerButton): MouseButton? = when (button) {
            PointerButton.Primary -> Left
            PointerButton.Secondary -> Right
            PointerButton.Tertiary -> Middle
            PointerButton.Back -> X1
            PointerButton.Forward -> X2
            else -> null
        }
    }
}

/** What a secondary click does (`enum sc_mouse_binding` in v5.0 `options.h`). */
enum class MouseBinding { Click, Disabled, Back, Home, AppSwitch, ExpandNotificationPanel }

/**
 * `--mouse-bind=xxxx[:xxxx]`: [primary] applies to clicks without Shift, [secondary] with Shift. Each string has one
 * character per click, in order right, middle, 4th, 5th: `+` forward, `-` ignore, `b` BACK (or screen on), `h` HOME,
 * `s` APP_SWITCH, `n` expand the notification panel (`parse_mouse_bindings()` in v5.0 `cli.c`).
 */
data class MouseBindings(val primary: String, val secondary: String) {
    init {
        require(primary.isValidSet() && secondary.isValidSet()) { "invalid mouse bindings: $primary:$secondary" }
    }

    fun primarySet(): List<MouseBinding> = primary.map(::bindingOf)

    fun secondarySet(): List<MouseBinding> = secondary.map(::bindingOf)

    /** `sc_input_manager_get_binding()`: the left button always clicks. */
    fun binding(button: MouseButton, shift: Boolean): MouseBinding {
        val set = if (shift) secondary else primary
        return when (button) {
            MouseButton.Left -> MouseBinding.Click
            MouseButton.Right -> bindingOf(set[0])
            MouseButton.Middle -> bindingOf(set[1])
            MouseButton.X1 -> bindingOf(set[2])
            MouseButton.X2 -> bindingOf(set[3])
        }
    }

    companion object {
        private const val CHARS = "+-bhsn"
        private const val SHORTCUTS = "bhsn"
        private const val FORWARD = "++++"

        private fun String.isValidSet() = length == 4 && all { it in CHARS }

        private fun bindingOf(c: Char): MouseBinding = when (c) {
            '+' -> MouseBinding.Click
            '-' -> MouseBinding.Disabled
            'b' -> MouseBinding.Back
            'h' -> MouseBinding.Home
            's' -> MouseBinding.AppSwitch
            'n' -> MouseBinding.ExpandNotificationPanel
            else -> throw IllegalArgumentException("invalid mouse binding: $c")
        }

        /** The error `parse_mouse_bindings()` would log for [value], or null when it is valid. */
        fun error(value: String): String? {
            if (value.length != 4 && (value.length != 9 || value[4] != ':')) {
                return "Expected 'xxxx' or 'xxxx:xxxx', with each 'x' being in {'+', '-', 'b', 'h', 's', 'n'}"
            }
            val bad = value.filterIndexed { i, c -> i != 4 && c !in CHARS }.firstOrNull() ?: return null
            return "Invalid mouse binding: '$bad' (expected '+', '-', 'b', 'h', 's' or 'n')"
        }

        /** Parses `xxxx` (the same set for Shift) or `xxxx:xxxx`; null when invalid. */
        fun parse(value: String): MouseBindings? {
            if (error(value) != null) return null
            val pri = value.substring(0, 4)
            return MouseBindings(pri, if (value.length == 9) value.substring(5) else pri)
        }

        /** v5.0 default: `bhsn:++++` for the SDK mouse, `++++:bhsn` for every other mode (UHID, AOA, disabled). */
        fun default(sdkMouse: Boolean): MouseBindings =
            if (sdkMouse) MouseBindings(SHORTCUTS, FORWARD) else MouseBindings(FORWARD, SHORTCUTS)

        /** `--mouse-bind` when set and valid, else the default for the effective mouse mode. */
        fun forOptions(values: OptionValues): MouseBindings =
            values.entries["mouse-bind"]?.let(::parse) ?: default(sdkMouse = effectiveMouseMode(values) == MouseMode.Sdk)
    }
}

/** The mouse input mode after `parse_args()` resolved `--mouse` (v5.0 `cli.c`), or null when it stays unresolved. */
enum class MouseMode { Sdk, Uhid, Disabled }

/**
 * `--mouse` as cli.c resolves it: explicit values as given; when absent, and only with control and the display as
 * the video source, SDK with video playback and DISABLED without it. Otherwise (no control, camera) the mode stays
 * AUTO, which is null here and gets no SDK mouse.
 */
fun effectiveMouseMode(values: OptionValues): MouseMode? {
    val e = values.entries
    return when (e["mouse"]) {
        "sdk" -> MouseMode.Sdk
        "uhid" -> MouseMode.Uhid
        "disabled" -> MouseMode.Disabled
        else -> when {
            "no-control" in e || e["video-source"] == "camera" -> null
            values.hasVideoPlayback() -> MouseMode.Sdk
            else -> MouseMode.Disabled
        }
    }
}

/** `opts->video_playback`: false with `--no-video`, `--no-video-playback` or `--no-playback`. */
fun OptionValues.hasVideoPlayback(): Boolean =
    "no-video" !in entries && "no-video-playback" !in entries && "no-playback" !in entries
