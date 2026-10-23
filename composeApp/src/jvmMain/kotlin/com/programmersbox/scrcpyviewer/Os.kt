package com.programmersbox.scrcpyviewer

/** The host operating system, from the `os.name` system property. */
object Os {
    private val name: String get() = System.getProperty("os.name").orEmpty().lowercase()

    val isMac: Boolean get() = name.contains("mac")
    val isWindows: Boolean get() = name.contains("win")
    /** Neither macOS nor Windows (Linux and other Unixes). */
    val isLinux: Boolean get() = !isMac && !isWindows
}
