package com.programmersbox.scrcpyviewer

import org.junit.Assume.assumeTrue

val isWindows: Boolean get() = Os.isWindows

/** Skips the calling test on Windows (POSIX permissions, `sh` stub scripts). */
fun assumePosix() = assumeTrue("POSIX-only test", !isWindows)
