package com.programmersbox.scrcpyviewer.options

/** Renders options as the equivalent native `scrcpy` command, for display and copying. */
object CommandLine {
    private val safe = Regex("""^[A-Za-z0-9_@%+=:,./-]*$""")

    /** `scrcpy -s <serial> --flag --key=value ...` in [OptionCatalog] order. Unknown flags are skipped. */
    fun build(serial: String?, values: OptionValues): String = buildList {
        add("scrcpy")
        if (serial != null) {
            add("-s")
            add(quote(serial))
        }
        for (spec in OptionCatalog.all) {
            if (spec.flag !in values.entries) continue
            val value = values.entries[spec.flag]
            add(if (value.isNullOrEmpty()) "--${spec.flag}" else "--${spec.flag}=${quote(value)}")
        }
    }.joinToString(" ")

    /** Single-quotes values that contain spaces or other shell characters (POSIX quoting, `'` as `'\''`). */
    private fun quote(value: String): String =
        if (safe.matches(value)) value else "'" + value.replace("'", "'\\''") + "'"
}
