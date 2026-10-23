package com.programmersbox.scrcpyviewer.options

/** Option groups, in the order of the filter chips on the Devices screen. */
enum class OptionGroup(val label: String) {
    Video("Video"),
    Audio("Audio"),
    Control("Control"),
    Device("Device"),
    Window("Window"),
    Recording("Recording"),
    Camera("Camera"),
    VirtualDisplay("Virtual display"),
    Connection("Connection"),
}

/** Where an option takes effect. */
enum class Target {
    /** Sent to scrcpy-server as `key=value` arguments. */
    Server,

    /** Handled by our client after connect (control messages, decoding, recording, connection setup). */
    Client,

    /** Mirror-window behaviour (scrcpy's SDL window options, mapped to the Compose window). */
    Window,
}

/**
 * The value shape of an option. [validate] returns a user-facing error message, or `null` when the value is valid.
 * The rules follow the `parse_*` functions in scrcpy v5.0 `app/src/cli.c` and the server's `Options.java`.
 */
sealed interface OptionType {
    fun validate(value: String?): String?

    /** A switch without a value. Its value is always `null`. */
    data object Flag : OptionType {
        override fun validate(value: String?): String? =
            if (value == null) null else "This option takes no value"
    }

    /** A decimal integer in `min..max` (inclusive bounds when set). */
    data class IntValue(val min: Int?, val max: Int?) : OptionType {
        override fun validate(value: String?): String? {
            val v = value.required() ?: return MISSING
            val n = v.toLongOrNull() ?: return "Expected a whole number"
            if (min != null && n < min) return "Must be at least $min"
            if (max != null && n > max) return "Must be at most $max"
            return null
        }
    }

    /** Free text. */
    data object Text : OptionType {
        override fun validate(value: String?): String? = if (value.required() == null) MISSING else null
    }

    /** One of [values]. */
    data class Choice(val values: List<String>) : OptionType {
        override fun validate(value: String?): String? {
            val v = value.required() ?: return MISSING
            return if (v in values) null else "Expected one of: ${values.joinToString(", ")}"
        }
    }

    /** Bits per second with an optional `K` (x1000) or `M` (x1000000) suffix, e.g. `8M`, `128K`. */
    data object BitRate : OptionType {
        private val pattern = Regex("""^(\d+)([KkMm]?)$""")

        override fun validate(value: String?): String? {
            val v = value.required() ?: return MISSING
            return if (toBitsPerSecond(v) == null) "Expected a bit rate such as 8M or 128K" else null
        }

        /** Converts `8M` to `8000000`, like `sc_str_parse_integer_with_suffix`. `null` when invalid or out of range. */
        fun toBitsPerSecond(value: String): Long? {
            val m = pattern.matchEntire(value) ?: return null
            val base = m.groupValues[1].toLongOrNull() ?: return null
            val mul = when (m.groupValues[2].lowercase()) {
                "m" -> 1_000_000L
                "k" -> 1_000L
                else -> 1L
            }
            if (base > Int.MAX_VALUE / mul) return null
            return base * mul
        }
    }

    /** `<width>x<height>`, both positive. */
    data object Size : OptionType {
        override fun validate(value: String?): String? {
            val v = value.required() ?: return MISSING
            return if (isSize(v)) null else "Expected <width>x<height>, for example 1920x1080"
        }
    }

    /** `width:height:x:y`, with a positive size and a non-negative offset. */
    data object Crop : OptionType {
        override fun validate(value: String?): String? {
            val v = value.required() ?: return MISSING
            val parts = v.split(":").map { it.toIntOrNull() }
            if (parts.size != 4 || parts.any { it == null }) return "Expected width:height:x:y"
            val (w, h, x, y) = parts.map { it!! }
            if (w <= 0 || h <= 0) return "Width and height must be positive"
            if (x < 0 || y < 0) return "Offsets must not be negative"
            return null
        }
    }

    /** A floating-point number, parsed by the server with `Float.parseFloat`. */
    data object Decimal : OptionType {
        override fun validate(value: String?): String? {
            val v = value.required() ?: return MISSING
            val f = v.toFloatOrNull() ?: return "Expected a number"
            return if (f.isFinite()) null else "Expected a number"
        }
    }

    /**
     * A value with a format that the other types do not cover (port range, mouse bindings, color, ...).
     * [hint] describes the format; [example] is a valid value.
     */
    class Formatted(val hint: String, val example: String, private val check: (String) -> Boolean) : OptionType {
        override fun validate(value: String?): String? {
            val v = value.required() ?: return MISSING
            return if (check(v)) null else "Expected $hint"
        }

        override fun toString(): String = "Formatted($hint)"
    }

    /** An option that may be passed with or without a value, e.g. `--new-display[=...]`. `null` or empty means no value. */
    data class OptionalValue(val inner: OptionType) : OptionType {
        override fun validate(value: String?): String? =
            if (value.isNullOrEmpty()) null else inner.validate(value)
    }

}

private const val MISSING = "A value is required"

private fun String?.required(): String? = this?.takeIf { it.isNotBlank() }

/** `<width>x<height>` with both dimensions positive (server `Options.parseSize`). */
internal fun isSize(v: String): Boolean {
    val parts = v.split("x")
    if (parts.size != 2) return false
    val w = parts[0].toIntOrNull() ?: return false
    val h = parts[1].toIntOrNull() ?: return false
    return w > 0 && h > 0
}

/**
 * One scrcpy 5.0 command-line option.
 *
 * @param flag the long option name without dashes, e.g. `max-size`.
 * @param toServer maps the option's value to scrcpy-server `key=value` pairs. Empty for non-server options and for
 * values that `server.c` does not send because they equal the server default.
 */
data class OptionSpec(
    val flag: String,
    val label: String,
    val group: OptionGroup,
    val type: OptionType,
    val help: String,
    val target: Target,
    val toServer: (String?) -> List<Pair<String, String>>,
)
