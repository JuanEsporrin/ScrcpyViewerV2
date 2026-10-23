package com.programmersbox.scrcpyviewer

import org.junit.Assume.assumeTrue

val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

/** Skips the calling test on Windows (POSIX permissions, `sh` stub scripts). */
fun assumePosix() = assumeTrue("POSIX-only test", !isWindows)
