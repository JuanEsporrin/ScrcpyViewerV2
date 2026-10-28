package com.programmersbox.scrcpyviewer.ui.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowChromeTest {
    @Test
    fun `macOS and Windows default to custom`() = assertEquals(WindowChrome.Custom, windowChrome(null, isLinux = false, supported = true))

    @Test
    fun `Linux defaults to system`() = assertEquals(WindowChrome.System, windowChrome(null, isLinux = true, supported = true))

    @Test
    fun `explicit setting wins over OS default`() {
        assertEquals(WindowChrome.Custom, windowChrome(false, isLinux = true, supported = true))
        assertEquals(WindowChrome.System, windowChrome(true, isLinux = false, supported = true))
    }

    @Test
    fun `unsupported runtime always system`() = assertEquals(WindowChrome.System, windowChrome(false, isLinux = false, supported = false))

    @Test
    fun `JBR 21 cannot load Jewel`() = assertFalse(jewelUsable(jbrAvailable = true, javaFeature = 21))

    @Test
    fun `JBR 25 can load Jewel`() = assertTrue(jewelUsable(jbrAvailable = true, javaFeature = 25))

    @Test
    fun `stock JDK 25 cannot use the custom bar`() = assertFalse(jewelUsable(jbrAvailable = false, javaFeature = 25))

    @Test
    fun `probe is false and does not throw below Java 25`() {
        // Tests run on the compile toolchain (21), where Jewel classes can't load.
        if (Runtime.version().feature() < 25) assertFalse(customTitleBarSupported)
    }
}
