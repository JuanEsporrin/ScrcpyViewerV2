package com.programmersbox.scrcpyviewer.options

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandLineTest {
    @Test
    fun `CommandLine matches mockup`() {
        val values = OptionValues(
            linkedMapOf(
                // Deliberately shuffled: output order must come from the catalog.
                "show-touches" to null, "no-audio" to null, "max-fps" to "60", "turn-screen-off" to null,
                "video-bit-rate" to "8M", "stay-awake" to null, "max-size" to "1920",
            ),
        )
        val order = OptionCatalog.all.map { it.flag }
        val mockupOrder = listOf("max-size", "video-bit-rate", "max-fps", "no-audio", "stay-awake", "turn-screen-off", "show-touches")
        assertEquals(mockupOrder, mockupOrder.sortedBy { order.indexOf(it) })
        assertEquals(
            "scrcpy -s 39121FDJH004AB --max-size=1920 --video-bit-rate=8M --max-fps=60 --no-audio --stay-awake --turn-screen-off --show-touches",
            CommandLine.build("39121FDJH004AB", values),
        )
    }

    @Test
    fun `no serial and empty values`() {
        assertEquals("scrcpy", CommandLine.build(null, OptionValues(emptyMap())))
    }

    @Test
    fun `values with spaces are single quoted`() {
        val cmd = CommandLine.build(null, OptionValues(mapOf("window-title" to "My phone")))
        assertEquals("scrcpy --window-title='My phone'", cmd)
        val quote = CommandLine.build(null, OptionValues(mapOf("window-title" to "Bob's phone")))
        assertEquals("scrcpy --window-title='Bob'\\''s phone'", quote)
    }

    @Test
    fun `optional value without argument is a bare flag`() {
        assertEquals("scrcpy --new-display", CommandLine.build(null, OptionValues(mapOf("new-display" to null))))
        assertTrue(CommandLine.build(null, OptionValues(mapOf("new-display" to "1920x1080"))).endsWith("--new-display=1920x1080"))
    }

    @Test
    fun `unknown options are skipped`() {
        assertEquals("scrcpy --no-audio", CommandLine.build(null, OptionValues(mapOf("legacy-flag" to null, "no-audio" to null))))
    }
}
