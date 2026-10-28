package com.programmersbox.scrcpyviewer.ui.window

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowChromeTest {
    @Test
    fun `macOS and Windows default to custom`() = assertEquals(WindowChrome.Custom, windowChrome(null, isLinux = false, jbr = true))

    @Test
    fun `Linux defaults to system`() = assertEquals(WindowChrome.System, windowChrome(null, isLinux = true, jbr = true))

    @Test
    fun `explicit setting wins over OS default`() {
        assertEquals(WindowChrome.Custom, windowChrome(false, isLinux = true, jbr = true))
        assertEquals(WindowChrome.System, windowChrome(true, isLinux = false, jbr = true))
    }

    @Test
    fun `no JBR always system`() = assertEquals(WindowChrome.System, windowChrome(false, isLinux = false, jbr = false))
}
