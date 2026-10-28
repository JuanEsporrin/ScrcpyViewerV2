package com.programmersbox.scrcpyviewer.ui.window

import com.programmersbox.scrcpyviewer.Os

/** Which title bar a window draws: the app's own (Jewel decorated window) or the OS one. */
enum class WindowChrome { Custom, System }

/** Default for "Use system title bar": off on macOS and Windows, on elsewhere (Linux). */
fun defaultUseSystemTitleBar(isLinux: Boolean = Os.isLinux): Boolean = isLinux

/**
 * Picks the title bar for a window. The custom bar needs a runtime that can load Jewel, so it is
 * always system when [supported] is false, whatever the setting says.
 */
fun windowChrome(
    useSystemTitleBar: Boolean?,
    isLinux: Boolean = Os.isLinux,
    supported: Boolean = customTitleBarSupported,
): WindowChrome {
    if (!supported) return WindowChrome.System
    val useSystem = useSystemTitleBar ?: defaultUseSystemTitleBar(isLinux)
    return if (useSystem) WindowChrome.System else WindowChrome.Custom
}

/** Jewel 0.41 is Java 25 bytecode and its decorated window needs JetBrains Runtime. */
internal fun jewelUsable(jbrAvailable: Boolean, javaFeature: Int): Boolean = jbrAvailable && javaFeature >= 25

/**
 * True when this runtime can draw the custom title bar: JetBrains Runtime 25 or newer, and Jewel's
 * window class loads. False on a stock JDK, on JBR 21 (an IDE run on the compile toolchain), or if
 * anything throws. Reflection because jbr-api is only on the runtime classpath (via skiko), not the
 * compile classpath. Checked once per process.
 */
val customTitleBarSupported: Boolean by lazy {
    runCatching {
        val jbr = Class.forName("com.jetbrains.JBR").getMethod("isAvailable").invoke(null) as Boolean
        // Loading without initialising still verifies the class file version.
        jewelUsable(jbr, Runtime.version().feature()) &&
            Class.forName("org.jetbrains.jewel.window.DecoratedWindowKt", false, WindowChrome::class.java.classLoader) != null
    }.getOrDefault(false)
}
