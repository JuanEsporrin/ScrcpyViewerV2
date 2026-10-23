package com.programmersbox.scrcpyviewer.options

/**
 * The options chosen for a mirror session: flag (no dashes) to value. Flags have a `null` value; an
 * [OptionType.OptionalValue] option uses `null` when passed without a value.
 */
data class OptionValues(val entries: Map<String, String?>) {

    /** Returns flag to error message for every invalid entry. Empty when all entries are valid. */
    fun validate(): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        for ((flag, value) in entries) {
            val spec = OptionCatalog.byFlag(flag)
            if (spec == null) {
                errors[flag] = "Unknown option"
                continue
            }
            val error = spec.type.validate(value)
                ?: if (spec.target == Target.Server && value != null) serverStringError(value) else null
            if (error != null) errors[flag] = error
        }
        return errors
    }

    fun with(flag: String, value: String?): OptionValues = OptionValues(entries + (flag to value))

    fun without(flag: String): OptionValues = OptionValues(entries - flag)
}

/** Characters refused by `validate_string()` in scrcpy `app/src/server.c`: server params are passed through a shell. */
private const val SERVER_FORBIDDEN = " ;'\"*$?&`#\\|<>[]{}()!~\r\n"

private fun serverStringError(value: String): String? {
    val bad = value.firstOrNull { it in SERVER_FORBIDDEN } ?: return null
    val shown = if (bad == ' ') "spaces" else "'$bad'"
    return "Must not contain $shown"
}
