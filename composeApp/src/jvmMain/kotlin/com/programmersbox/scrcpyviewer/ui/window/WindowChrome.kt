package com.programmersbox.scrcpyviewer.ui.window

import com.programmersbox.scrcpyviewer.Os

/** Which title bar a window draws: the app's own (Jewel decorated window) or the OS one. */
enum class WindowChrome { Custom, System }

/** Default for "Use system title bar": off on macOS and Windows, on elsewhere (Linux). */
fun defaultUseSystemTitleBar(isLinux: Boolean = Os.isLinux): Boolean = isLinux

/**
 * Picks the title bar for a window. The custom bar needs JetBrains Runtime, so it is always
 * system when [jbr] is false, whatever the setting says.
 */
fun windowChrome(
    useSystemTitleBar: Boolean?,
    isLinux: Boolean = Os.isLinux,
    jbr: Boolean = isJbr(),
): WindowChrome {
    if (!jbr) return WindowChrome.System
    val useSystem = useSystemTitleBar ?: defaultUseSystemTitleBar(isLinux)
    return if (useSystem) WindowChrome.System else WindowChrome.Custom
}

/**
 * True when running on JetBrains Runtime; false if the JBR API is missing or throws. Reflection
 * because jbr-api is only on the runtime classpath (via skiko), not the compile classpath.
 */
fun isJbr(): Boolean = runCatching {
    Class.forName("com.jetbrains.JBR").getMethod("isAvailable").invoke(null) as Boolean
}.getOrDefault(false)
