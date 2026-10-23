package com.programmersbox.scrcpyviewer.options

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerArgsTest {
    private val scid = 0x1234abcd

    private fun build(vararg entries: Pair<String, String?>, tunnelForward: Boolean = false) =
        ServerArgs.build(OptionValues(mapOf(*entries)), scid, tunnelForward)

    @Test
    fun `ServerArgs maps QA default`() {
        val args = build(
            "max-size" to "1920", "max-fps" to "60", "no-audio" to null,
            "show-touches" to null, "stay-awake" to null,
        )
        assertEquals(listOf("scid=1234abcd", "log_level=info", "tunnel_forward=false"), args.take(3))
        for (expected in listOf("max_size=1920", "max_fps=60", "audio=false", "show_touches=true", "stay_awake=true")) {
            assertTrue(expected in args, "missing $expected in $args")
        }
    }

    @Test
    fun `ServerArgs adds stream meta defaults`() {
        val args = build()
        assertEquals(
            listOf(
                "scid=1234abcd", "log_level=info", "tunnel_forward=true",
                "send_frame_meta=true", "send_device_meta=true", "send_stream_meta=true",
            ),
            ServerArgs.build(OptionValues(emptyMap()), scid, tunnelForward = true),
        )
        assertEquals(6, args.size)
    }

    @Test
    fun `scid is zero padded`() {
        assertEquals("scid=0000002a", ServerArgs.build(OptionValues(emptyMap()), 42, false).first())
    }

    @Test
    fun `ServerArgs omits client-only and window options`() {
        val base = build()
        val args = build("turn-screen-off" to null, "always-on-top" to null, "record" to "a.mp4", "keyboard" to "uhid")
        assertEquals(base, args)
    }

    @Test
    fun `mapped args follow catalog order`() {
        val args = build("stay-awake" to null, "no-audio" to null, "max-size" to "1920").drop(6)
        val order = OptionCatalog.all.map { it.flag }
        assertTrue(order.indexOf("max-size") < order.indexOf("no-audio"))
        assertTrue(order.indexOf("no-audio") < order.indexOf("stay-awake"))
        assertEquals(listOf("max_size=1920", "audio=false", "stay_awake=true"), args)
    }

    @Test
    fun `values are converted like the scrcpy client`() {
        val args = build(
            "video-bit-rate" to "8M", "audio-bit-rate" to "128K", "screen-off-timeout" to "30",
            "video-codec" to "h265", "audio-codec" to "aac", "video-source" to "camera",
            "camera-facing" to "back", "crop" to "100:200:0:0", "capture-orientation" to "@90",
            "no-control" to null, "no-clipboard-autosync" to null, "new-display" to null,
        )
        for (expected in listOf(
            "video_bit_rate=8000000", "audio_bit_rate=128000", "screen_off_timeout=30000",
            "video_codec=h265", "audio_codec=aac", "video_source=camera", "camera_facing=back",
            "crop=100:200:0:0", "capture_orientation=@90", "control=false",
            "clipboard_autosync=false", "new_display=",
        )) assertTrue(expected in args, "missing $expected in $args")
    }

    @Test
    fun `default values are not sent`() {
        // server.c only emits these when they differ from the default.
        val args = build(
            "max-size" to "0", "video-bit-rate" to "0", "video-codec" to "h264", "audio-codec" to "opus",
            "video-source" to "display", "audio-source" to "output", "min-size-alignment" to "1",
            "display-id" to "0", "capture-orientation" to "0", "camera-fps" to "0",
        )
        assertEquals(build(), args)
    }

    @Test
    fun `no-video also disables power on`() {
        val args = build("no-video" to null)
        assertTrue("video=false" in args)
        assertTrue("power_on=false" in args)
    }

    @Test
    fun `no playback without recording disables the stream`() {
        val args = build("no-playback" to null)
        assertTrue("video=false" in args && "audio=false" in args, "$args")
        assertFalse("power_on=false" in args, "only explicit --no-video turns power_on off")
        val recording = build("no-playback" to null, "record" to "out.mkv")
        assertFalse("video=false" in recording || "audio=false" in recording, "$recording")
        val audioOnly = build("no-audio-playback" to null)
        assertTrue("audio=false" in audioOnly && "video=false" !in audioOnly, "$audioOnly")
    }

    @Test
    fun `audio source auto resolution`() {
        assertTrue("audio_source=playback" in build("audio-dup" to null))
        assertTrue("audio_dup=true" in build("audio-dup" to null))
        assertTrue("audio_source=mic" in build("video-source" to "camera"))
        assertTrue("audio_source=mic-camcorder" in build("video-source" to "camera", "audio-source" to "mic-camcorder"))
        assertFalse(build("no-audio" to null, "audio-source" to "mic").any { it.startsWith("audio_source=") })
    }
}
