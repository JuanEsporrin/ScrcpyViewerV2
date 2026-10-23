package com.programmersbox.scrcpyviewer.options

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptionCatalogTest {
    /** Hidden options from Global Constraints, without leading dashes. */
    private val hidden = listOf(
        "otg", "serial", "s", "d", "e", "select-usb", "select-tcpip", "tcpip",
        "list-apps", "list-cameras", "list-camera-sizes", "list-displays", "list-encoders",
        "h", "help", "v", "version", "V", "verbosity",
        "v4l2-sink", "v4l2-buffer", "render-driver", "no-mipmaps", "hwdec", "shortcut-mod",
        "pause-on-exit", "no-window", "window-borderless", "disable-screensaver",
        "no-terminal-title", "kill-adb-on-close",
    )

    /** Every `.longopt` in scrcpy v5.0 app/src/cli.c, in table order. */
    private val cliLongOptions = listOf(
        "always-on-top", "angle", "audio-bit-rate", "audio-buffer", "audio-codec",
        "audio-codec-options", "audio-dup", "audio-encoder", "audio-source", "audio-output-buffer",
        "video-bit-rate", "background-color", "camera-ar", "camera-facing", "camera-fps",
        "camera-high-speed", "camera-id", "camera-size", "camera-torch", "camera-zoom",
        "capture-orientation", "crop", "select-usb", "disable-screensaver", "display-id",
        "display-ime-policy", "display-orientation", "select-tcpip", "fullscreen",
        "force-adb-forward", "gamepad", "help", "hwdec", "ignore-video-encoder-constraints",
        "keep-active", "keyboard", "kill-adb-on-close", "legacy-paste", "list-apps",
        "list-cameras", "list-camera-sizes", "list-displays", "list-encoders", "max-size",
        "max-fps", "min-size-alignment", "mouse", "mouse-bind", "no-control", "no-playback",
        "new-display", "no-audio", "no-audio-playback", "no-cleanup", "no-clipboard-autosync",
        "no-downsize-on-error", "no-key-repeat", "no-mipmaps", "no-mouse-hover", "no-power-on",
        "no-terminal-title", "no-vd-destroy-content", "no-vd-system-decorations", "no-video",
        "no-video-playback", "no-window", "no-window-aspect-ratio-lock", "orientation", "otg",
        "port", "pause-on-exit", "power-off-on-close", "prefer-text", "print-fps", "push-target",
        "record", "raw-key-events", "record-format", "record-orientation", "render-driver",
        "render-fit", "require-audio", "serial", "turn-screen-off", "screen-off-timeout",
        "shortcut-mod", "start-app", "show-touches", "tcpip", "time-limit", "tunnel-host",
        "tunnel-port", "version", "verbosity", "v4l2-sink", "v4l2-buffer", "video-buffer",
        "video-codec", "video-codec-options", "video-encoder", "video-source", "stay-awake",
        "window-borderless", "window-title", "window-x", "window-y", "window-width",
        "window-height", "flex-display",
    )

    private val windowOptions = setOf(
        "always-on-top", "fullscreen", "window-title", "window-x", "window-y",
        "window-width", "window-height", "print-fps", "no-window-aspect-ratio-lock",
    )

    @Test
    fun `catalog has no hidden flags`() {
        val flags = OptionCatalog.all.map { it.flag }.toSet()
        for (h in hidden) assertFalse(h in flags, "hidden flag present: $h")
    }

    @Test
    fun `catalog flags are unique`() {
        val flags = OptionCatalog.all.map { it.flag }
        assertEquals(flags.size, flags.toSet().size, "duplicates: ${flags.groupingBy { it }.eachCount().filter { it.value > 1 }}")
    }

    @Test
    fun `catalog covers every non-hidden cli option`() {
        val expected = cliLongOptions.toSet() - hidden.toSet()
        assertEquals(expected, OptionCatalog.all.map { it.flag }.toSet())
    }

    @Test
    fun `flags have no leading dashes`() {
        for (spec in OptionCatalog.all) assertFalse(spec.flag.startsWith("-"), spec.flag)
    }

    @Test
    fun `every Server option maps to at least one key`() {
        val overrides = mapOf(
            "camera-ar" to "4:3",
            "new-display" to "1920x1080/420",
        )
        for (spec in OptionCatalog.all.filter { it.target == Target.Server }) {
            val sample = overrides[spec.flag] ?: sampleValue(spec.type)
            assertNull(spec.type.validate(sample), "sample for ${spec.flag} must be valid: $sample")
            val args = spec.toServer(sample)
            assertTrue(args.isNotEmpty(), "${spec.flag}=$sample maps to no server key")
        }
    }

    @Test
    fun `client and window options map to no server key`() {
        for (spec in OptionCatalog.all.filter { it.target != Target.Server }) {
            assertEquals(emptyList(), spec.toServer(sampleValue(spec.type)), spec.flag)
        }
    }

    @Test
    fun `sdl window options target Window`() {
        for (flag in windowOptions) assertEquals(Target.Window, OptionCatalog.byFlag(flag)?.target, flag)
    }

    @Test
    fun `turn-screen-off is a client option`() {
        assertEquals(Target.Client, OptionCatalog.byFlag("turn-screen-off")?.target)
    }

    @Test
    fun `input modes exclude aoa`() {
        for (flag in listOf("keyboard", "mouse", "gamepad")) {
            val type = assertNotNull(OptionCatalog.byFlag(flag)).type
            assertTrue(type is OptionType.Choice, flag)
            assertFalse("aoa" in type.values, flag)
            assertNotNull(type.validate("aoa"), "$flag=aoa must be rejected")
        }
    }

    @Test
    fun `byFlag finds and misses`() {
        assertEquals("max-size", OptionCatalog.byFlag("max-size")?.flag)
        assertNull(OptionCatalog.byFlag("otg"))
        assertNull(OptionCatalog.byFlag("--max-size"))
    }

    @Test
    fun `every group has at least one option`() {
        val used = OptionCatalog.all.map { it.group }.toSet()
        assertEquals(OptionGroup.entries.toSet(), used)
    }

    private fun sampleValue(type: OptionType): String? = when (type) {
        OptionType.Flag -> null
        is OptionType.IntValue -> ((type.min ?: 0) + 1).coerceAtMost(type.max ?: Int.MAX_VALUE).toString()
        OptionType.Text -> "x"
        is OptionType.Choice -> type.values.last()
        OptionType.BitRate -> "8M"
        OptionType.Size -> "1920x1080"
        OptionType.Crop -> "1:2:3:4"
        OptionType.Decimal -> "1.5"
        is OptionType.Formatted -> type.example
        is OptionType.OptionalValue -> null
    }
}
