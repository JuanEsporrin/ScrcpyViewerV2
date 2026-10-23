package com.programmersbox.scrcpyviewer.options

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptionValuesTest {
    @Test
    fun `validate rejects bad values`() {
        assertTrue("max-size" in OptionValues(mapOf("max-size" to "abc")).validate())
        assertTrue("crop" in OptionValues(mapOf("crop" to "1:2")).validate())
        assertEquals(emptyMap(), OptionValues(mapOf("video-bit-rate" to "8M")).validate())
        assertTrue("video-bit-rate" in OptionValues(mapOf("video-bit-rate" to "8Q")).validate())
    }

    @Test
    fun `validate accepts the QA default`() {
        val values = OptionValues(
            mapOf(
                "max-size" to "1920", "video-bit-rate" to "8M", "max-fps" to "60",
                "no-audio" to null, "stay-awake" to null, "turn-screen-off" to null, "show-touches" to null,
            ),
        )
        assertEquals(emptyMap(), values.validate())
    }

    @Test
    fun `validate checks ranges and formats`() {
        val errors = OptionValues(
            mapOf(
                "max-size" to "70000",
                "min-size-alignment" to "3",
                "capture-orientation" to "45",
                "camera-size" to "0x10",
                "crop" to "0:10:0:0",
                "mouse-bind" to "bhsx",
                "background-color" to "#12",
                "port" to "27183:99999",
                "window-x" to "left",
                "new-display" to "1920x/420",
                "max-fps" to "fast",
            ),
        ).validate()
        assertEquals(
            setOf(
                "max-size", "min-size-alignment", "capture-orientation", "camera-size", "crop",
                "mouse-bind", "background-color", "port", "window-x", "new-display", "max-fps",
            ),
            errors.keys,
        )
    }

    @Test
    fun `validate accepts well formed values`() {
        val values = OptionValues(
            mapOf(
                "crop" to "1224:1440:0:0",
                "capture-orientation" to "@90",
                "camera-ar" to "sensor",
                "mouse-bind" to "bhsn:++++",
                "background-color" to "#222",
                "port" to "27183:27199",
                "window-x" to "auto",
                "new-display" to "/240",
                "audio-bit-rate" to "128K",
                "tunnel-host" to "192.168.1.2",
            ),
        )
        assertEquals(emptyMap(), values.validate())
        assertEquals(emptyMap(), OptionValues(mapOf("new-display" to null)).validate())
    }

    @Test
    fun `validate flags missing values, values on flags and unknown options`() {
        val errors = OptionValues(mapOf("max-size" to null, "no-audio" to "yes", "legacy-flag" to null)).validate()
        assertEquals(setOf("max-size", "no-audio", "legacy-flag"), errors.keys)
    }

    @Test
    fun `validate rejects shell characters in server values`() {
        // server.c validate_string() refuses these, so the server would never start.
        assertTrue("video-encoder" in OptionValues(mapOf("video-encoder" to "c2.foo;rm")).validate())
        assertTrue("video-codec-options" in OptionValues(mapOf("video-codec-options" to "a b")).validate())
        // Client-side text may contain spaces.
        assertFalse("window-title" in OptionValues(mapOf("window-title" to "My phone")).validate())
    }

    @Test
    fun `with and without`() {
        val v = OptionValues(emptyMap()).with("no-audio", null).with("max-size", "1920")
        assertEquals(mapOf("no-audio" to null, "max-size" to "1920"), v.entries)
        assertEquals(mapOf("max-size" to "1920"), v.without("no-audio").entries)
        assertEquals("1024", v.with("max-size", "1024").entries["max-size"])
    }
}
