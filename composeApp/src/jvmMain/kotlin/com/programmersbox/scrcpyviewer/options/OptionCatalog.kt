package com.programmersbox.scrcpyviewer.options

import com.programmersbox.scrcpyviewer.options.OptionGroup.Audio
import com.programmersbox.scrcpyviewer.options.OptionGroup.Camera
import com.programmersbox.scrcpyviewer.options.OptionGroup.Connection
import com.programmersbox.scrcpyviewer.options.OptionGroup.Control
import com.programmersbox.scrcpyviewer.options.OptionGroup.Device
import com.programmersbox.scrcpyviewer.options.OptionGroup.Recording
import com.programmersbox.scrcpyviewer.options.OptionGroup.Video
import com.programmersbox.scrcpyviewer.options.OptionGroup.VirtualDisplay
import com.programmersbox.scrcpyviewer.options.OptionType.BitRate
import com.programmersbox.scrcpyviewer.options.OptionType.Choice
import com.programmersbox.scrcpyviewer.options.OptionType.Crop
import com.programmersbox.scrcpyviewer.options.OptionType.Decimal
import com.programmersbox.scrcpyviewer.options.OptionType.Flag
import com.programmersbox.scrcpyviewer.options.OptionType.Formatted
import com.programmersbox.scrcpyviewer.options.OptionType.IntValue
import com.programmersbox.scrcpyviewer.options.OptionType.OptionalValue
import com.programmersbox.scrcpyviewer.options.OptionType.Size
import com.programmersbox.scrcpyviewer.options.OptionType.Text

/**
 * Every non-hidden scrcpy 5.0 option, transcribed from `scrcpy --help` and `app/src/cli.c` (tag v5.0).
 *
 * The list order is the display order and the order used by [CommandLine] and [ServerArgs].
 * Server mappings follow `execute_server()` in `app/src/server.c`: a key is only sent when the value differs from the
 * server default, exactly like the native client.
 *
 * Not listed on purpose:
 * - the hidden options from the global constraints (`--otg`, `-s`, `--list-*`, `--render-driver`, ...);
 * - the short-only aliases `-K`, `-M` and `-G`, which are `--keyboard=uhid`, `--mouse=uhid` and `--gamepad=uhid`
 *   without OTG, and so are already covered by the [Choice] of those options.
 */
object OptionCatalog {
    private val orientations = listOf("0", "90", "180", "270", "flip0", "flip90", "flip180", "flip270")

    val all: List<OptionSpec> = listOf(
        // ---- Video ----
        server("max-size", "Max size", Video, IntValue(0, 0xFFFF),
            "Limit both the width and height of the video (0 = unlimited). For camera mirroring, selects the camera size.") {
            nonZero(it, "max_size")
        },
        server("video-bit-rate", "Video bit rate", Video, BitRate,
            "Encode the video at the given bit rate in bits/s. Suffixes K and M are supported. Default is 8M.") {
            bitRate(it, "video_bit_rate")
        },
        server("max-fps", "Max FPS", Video, Decimal,
            "Limit the frame rate of screen capture (officially supported since Android 10).") {
            listOf("max_fps" to it!!)
        },
        server("video-codec", "Video codec", Video, Choice(listOf("h264", "h265", "av1", "vp8", "vp9")),
            "Select a video codec. Default is h264.") {
            unlessDefault(it, "video_codec", "h264")
        },
        server("video-encoder", "Video encoder", Video, Text,
            "Use a specific MediaCodec video encoder (depending on the video codec).") {
            listOf("video_encoder" to it!!)
        },
        server("video-codec-options", "Video codec options", Video, Text,
            "Comma-separated key[:type]=value options for the device video encoder (type: int, long, float, string).") {
            listOf("video_codec_options" to it!!)
        },
        server("video-source", "Video source", Video, Choice(listOf("display", "camera")),
            "Select the video source. Camera mirroring requires Android 12+. Default is display.") {
            unlessDefault(it, "video_source", "display")
        },
        server("display-id", "Display id", Video, IntValue(0, Int.MAX_VALUE),
            "Specify the device display id to mirror. Default is 0.") {
            nonZero(it, "display_id")
        },
        server("crop", "Crop", Video, Crop,
            "Crop the device screen on the server (width:height:x:y, in the device natural orientation).") {
            listOf("crop" to it!!)
        },
        server("capture-orientation", "Capture orientation", Video,
            Choice(orientations + "@" + orientations.map { "@$it" }),
            "Set the capture video orientation. A leading '@' locks the rotation; '@' alone locks the initial orientation. Default is 0.") {
            unlessDefault(it, "capture_orientation", "0")
        },
        server("angle", "Angle", Video, Decimal,
            "Rotate the video content by a custom angle, in degrees (clockwise).") {
            listOf("angle" to it!!)
        },
        server("min-size-alignment", "Min size alignment", Video, Choice(listOf("1", "2", "4", "8", "16")),
            "Minimum video size alignment (power of 2) that the video width and height must be multiples of. Default is 1.") {
            unlessDefault(it, "min_size_alignment", "1")
        },
        client("display-orientation", "Display orientation", Video, Choice(orientations),
            "Set the initial display orientation (clockwise; flip applies a horizontal flip first). Default is 0."),
        client("orientation", "Orientation", Video, Choice(orientations),
            "Same as display orientation and record orientation together."),
        client("video-buffer", "Video buffer (ms)", Video, IntValue(0, 3_600_000),
            "Add a buffering delay before displaying video frames, to compensate for jitter. Default is 0."),
        server("no-video", "No video", Video, Flag,
            "Disable video forwarding. Also disables powering on the device on start.") {
            // power_on=false is derived in ServerArgs, like cli.c does for !video.
            listOf("video" to "false")
        },
        client("no-video-playback", "No video playback", Video, Flag,
            "Disable video playback on the computer. Without recording, video forwarding is disabled too."),
        client("no-playback", "No playback", Video, Flag,
            "Disable video and audio playback on the computer (same as no video playback + no audio playback)."),
        server("no-downsize-on-error", "No downsize on error", Video, Flag,
            "Do not retry with a lower definition on MediaCodec error.") {
            listOf("downsize_on_error" to "false")
        },
        server("ignore-video-encoder-constraints", "Ignore encoder constraints", Video, Flag,
            "Do not consider video encoder capabilities (useful if the reported capabilities are incorrect).") {
            listOf("ignore_video_encoder_constraints" to "true")
        },

        // ---- Audio ----
        server("no-audio", "No audio", Audio, Flag, "Disable audio forwarding.") {
            listOf("audio" to "false")
        },
        server("audio-source", "Audio source", Audio,
            Choice(
                listOf(
                    "output", "playback", "mic", "mic-unprocessed", "mic-camcorder", "mic-voice-recognition",
                    "mic-voice-communication", "voice-call", "voice-call-uplink", "voice-call-downlink",
                    "voice-performance",
                ),
            ),
            "Select the audio source. Default is output (or playback with audio dup, mic with the camera).") {
            // Only sent while audio is enabled; ServerArgs drops it otherwise (server.c checks params->audio).
            unlessDefault(it, "audio_source", "output")
        },
        server("audio-bit-rate", "Audio bit rate", Audio, BitRate,
            "Encode the audio at the given bit rate in bits/s. Suffixes K and M are supported. Default is 128K.") {
            bitRate(it, "audio_bit_rate")
        },
        server("audio-codec", "Audio codec", Audio, Choice(listOf("opus", "aac", "flac", "raw")),
            "Select an audio codec. Default is opus.") {
            unlessDefault(it, "audio_codec", "opus")
        },
        server("audio-encoder", "Audio encoder", Audio, Text,
            "Use a specific MediaCodec audio encoder (depending on the audio codec).") {
            listOf("audio_encoder" to it!!)
        },
        server("audio-codec-options", "Audio codec options", Audio, Text,
            "Comma-separated key[:type]=value options for the device audio encoder (type: int, long, float, string).") {
            listOf("audio_codec_options" to it!!)
        },
        server("audio-dup", "Audio dup", Audio, Flag,
            "Duplicate audio (capture and keep playing on the device). Requires the playback audio source.") {
            listOf("audio_dup" to "true")
        },
        client("audio-buffer", "Audio buffer (ms)", Audio, IntValue(0, 3_600_000),
            "Audio buffering delay. Lower values decrease latency but may cause glitches. Default is 50 (120 for FLAC)."),
        client("audio-output-buffer", "Audio output buffer (ms)", Audio, IntValue(0, 1000),
            "Size of the audio output buffer. Do not change this unless you have a good reason. Default is 10."),
        client("no-audio-playback", "No audio playback", Audio, Flag,
            "Disable audio playback on the computer. Without recording, audio forwarding is disabled too."),
        client("require-audio", "Require audio", Audio, Flag,
            "Fail if audio is enabled but does not work, instead of mirroring only the video."),

        // ---- Control ----
        server("no-control", "No control", Control, Flag, "Disable device control (mirror the device in read-only).") {
            listOf("control" to "false")
        },
        client("keyboard", "Keyboard", Control, Choice(listOf("disabled", "sdk", "uhid")),
            "How to send keyboard inputs: disabled, sdk (Android API) or uhid (simulated physical HID keyboard)."),
        client("mouse", "Mouse", Control, Choice(listOf("disabled", "sdk", "uhid")),
            "How to send mouse inputs: disabled, sdk (Android API) or uhid (simulated physical HID mouse, captured)."),
        client("gamepad", "Gamepad", Control, Choice(listOf("disabled", "uhid")),
            "How to send gamepad inputs: disabled or uhid (simulated physical HID gamepads)."),
        client("mouse-bind", "Mouse bindings", Control,
            Formatted("xxxx or xxxx:xxxx, each x in + - b h s n", "bhsn:++++") { it.matches(MOUSE_BIND) },
            "Bindings of secondary clicks (right, middle, 4th, 5th): + forward, - ignore, b BACK, h HOME, s APP_SWITCH, n notifications. The second set applies with Shift."),
        client("no-mouse-hover", "No mouse hover", Control, Flag,
            "Do not forward mouse hover (motion without clicks) events. SDK mouse only."),
        client("no-key-repeat", "No key repeat", Control, Flag,
            "Do not forward repeated key events when a key is held down. SDK keyboard only."),
        client("prefer-text", "Prefer text", Control, Flag,
            "Inject alpha characters and space as text events instead of key events. SDK keyboard only."),
        client("raw-key-events", "Raw key events", Control, Flag,
            "Inject key events for all input keys, and ignore text events. SDK keyboard only."),
        client("legacy-paste", "Legacy paste", Control, Flag,
            "Inject computer clipboard text as a sequence of key events on Ctrl+V."),
        // server.c sends clipboard_autosync=false; the client must also skip its own sync before Ctrl+V.
        server("no-clipboard-autosync", "No clipboard autosync", Control, Flag,
            "Disable automatic clipboard synchronization between the computer and the device.") {
            listOf("clipboard_autosync" to "false")
        },

        // ---- Device ----
        server("stay-awake", "Stay awake", Device, Flag,
            "Keep the device on while mirroring, when the device is plugged in.") {
            listOf("stay_awake" to "true")
        },
        // Client: scrcpy.c sends SET_DISPLAY_POWER(off) after connect.
        client("turn-screen-off", "Turn screen off", Device, Flag, "Turn the device screen off immediately."),
        server("show-touches", "Show touches", Device, Flag,
            "Enable \"show touches\" on start, restore the initial value on exit (physical touches only).") {
            listOf("show_touches" to "true")
        },
        server("screen-off-timeout", "Screen off timeout (s)", Device, IntValue(0, Int.MAX_VALUE / 1000),
            "Set the screen off timeout while mirroring (restored on exit).") {
            listOf("screen_off_timeout" to (it!!.toLong() * 1000).toString())
        },
        server("keep-active", "Keep active", Device, Flag, "Keep the screen on by simulating user activity.") {
            listOf("keep_active" to "true")
        },
        server("power-off-on-close", "Power off on close", Device, Flag,
            "Turn the device screen off when closing the mirror.") {
            listOf("power_off_on_close" to "true")
        },
        server("no-power-on", "No power on", Device, Flag, "Do not power on the device on start.") {
            listOf("power_on" to "false")
        },
        server("no-cleanup", "No cleanup", Device, Flag,
            "Do not remove the server or restore the device state (show touches, stay awake, power mode) on exit.") {
            listOf("cleanup" to "false")
        },
        // Client: scrcpy.c sends a START_APP control message after connect.
        client("start-app", "Start app", Device, Text,
            "Start an Android app by its exact package name. Prefix '?' to match the app name, '+' to force-stop first."),
        client("push-target", "Push target", Device, Text,
            "Target directory for files pushed by drag and drop. Default is /sdcard/Download/."),

        // ---- Window (SDL window options mapped to the mirror window) ----
        window("always-on-top", "Always on top", Flag, "Keep the mirror window above other windows."),
        window("fullscreen", "Fullscreen", Flag, "Start in fullscreen."),
        window("window-title", "Window title", Text, "Set a custom window title."),
        window("window-x", "Window X", windowPosition(), "Initial window horizontal position. Default is auto."),
        window("window-y", "Window Y", windowPosition(), "Initial window vertical position. Default is auto."),
        window("window-width", "Window width", IntValue(0, 0xFFFF), "Initial window width. Default is 0 (automatic)."),
        window("window-height", "Window height", IntValue(0, 0xFFFF), "Initial window height. Default is 0 (automatic)."),
        window("no-window-aspect-ratio-lock", "No aspect ratio lock", Flag, "Disable window aspect ratio lock."),
        window("render-fit", "Render fit", Choice(listOf("letterbox", "stretched", "unscaled")),
            "How the video fits the window. Default is letterbox (unscaled with flex display)."),
        window("background-color", "Background color",
            Formatted("#RGB or #RRGGBB", "#222") { it.matches(HEX_COLOR) },
            "Window background color. Default is #222 (dark gray)."),
        window("print-fps", "Show FPS", Flag, "Show the frame rate in the status line."),

        // ---- Recording ----
        client("record", "Record", Recording, Text,
            "Record to a file. The format comes from the record format option or the file extension."),
        client("record-format", "Record format", Recording,
            Choice(listOf("mp4", "mkv", "m4a", "mka", "opus", "aac", "flac", "wav")),
            "Force the recording format."),
        // cli.c rejects flip orientations for recording, so only rotations are offered.
        client("record-orientation", "Record orientation", Recording, Choice(listOf("0", "90", "180", "270")),
            "Set the record orientation (clockwise). Default is 0."),
        client("time-limit", "Time limit (s)", Recording, IntValue(0, Int.MAX_VALUE),
            "Set the maximum mirroring time, in seconds."),

        // ---- Camera ----
        server("camera-id", "Camera id", Camera, Text, "Specify the device camera id to mirror.") {
            listOf("camera_id" to it!!)
        },
        server("camera-facing", "Camera facing", Camera, Choice(listOf("front", "back", "external")),
            "Select the device camera by its facing direction.") {
            listOf("camera_facing" to it!!)
        },
        server("camera-size", "Camera size", Camera, Size, "Specify an explicit camera capture size (<width>x<height>).") {
            listOf("camera_size" to it!!)
        },
        server("camera-ar", "Camera aspect ratio", Camera,
            Formatted("sensor, <num>:<den> or a number", "4:3", ::isCameraAspectRatio),
            "Select the camera size by its aspect ratio (+/- 10%): sensor, <num>:<den> (e.g. 4:3) or <value> (e.g. 1.6).") {
            listOf("camera_ar" to it!!)
        },
        server("camera-fps", "Camera FPS", Camera, IntValue(0, 0xFFFF),
            "Specify the camera capture frame rate (Android default is 30).") {
            nonZero(it, "camera_fps")
        },
        server("camera-high-speed", "Camera high speed", Camera, Flag,
            "Enable high-speed camera capture mode (requires an explicit camera FPS).") {
            listOf("camera_high_speed" to "true")
        },
        server("camera-torch", "Camera torch", Camera, Flag, "Turn on the camera torch when the camera starts.") {
            listOf("camera_torch" to "true")
        },
        server("camera-zoom", "Camera zoom", Camera, Decimal, "Specify the camera zoom initial value.") {
            listOf("camera_zoom" to it!!)
        },

        // ---- Virtual display ----
        server("new-display", "New display", VirtualDisplay,
            OptionalValue(Formatted("[<width>x<height>][/<dpi>]", "1920x1080/420", ::isNewDisplay)),
            "Create a new display with the given size and density. Without a value, use the main display size and DPI.") {
            listOf("new_display" to (it ?: ""))
        },
        server("flex-display", "Flex display", VirtualDisplay, Flag,
            "Continuously resize the virtual display to match the window. Requires a new display.") {
            listOf("flex_display" to "true")
        },
        server("display-ime-policy", "Display IME policy", VirtualDisplay, Choice(listOf("local", "fallback", "hide")),
            "Where the IME is displayed: local display, fallback (default) display, or hidden. Secondary displays only.") {
            listOf("display_ime_policy" to it!!)
        },
        server("no-vd-destroy-content", "Keep apps on close", VirtualDisplay, Flag,
            "When the virtual display closes, move its apps to the main display instead of destroying them.") {
            listOf("vd_destroy_content" to "false")
        },
        server("no-vd-system-decorations", "No system decorations", VirtualDisplay, Flag,
            "Disable virtual display system decorations.") {
            listOf("vd_system_decorations" to "false")
        },

        // ---- Connection ----
        // These shape the adb tunnel; the caller passes the resulting tunnel mode to ServerArgs.build.
        client("port", "Port", Connection,
            Formatted("port or port:port (0-65535)", "27183:27199", ::isPortRange),
            "TCP port (range) used by the client to listen. Default is 27183:27199."),
        client("force-adb-forward", "Force adb forward", Connection, Flag,
            "Do not attempt to use \"adb reverse\" to connect to the device."),
        client("tunnel-host", "Tunnel host", Connection,
            Formatted("an IPv4 address", "127.0.0.1") { it.matches(IPV4) },
            "IP address of the adb tunnel to reach the server. Implies force adb forward. Default is localhost."),
        client("tunnel-port", "Tunnel port", Connection, IntValue(0, 0xFFFF),
            "TCP port of the adb tunnel to reach the server. Implies force adb forward. Default is 0 (not forced)."),
    )

    private val byFlagIndex: Map<String, OptionSpec> = all.associateBy { it.flag }

    fun byFlag(flag: String): OptionSpec? = byFlagIndex[flag]
}

private val MOUSE_BIND = Regex("""^[-+bhsn]{4}(:[-+bhsn]{4})?$""")
private val HEX_COLOR = Regex("""^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$""")
private val IPV4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")

private fun server(
    flag: String, label: String, group: OptionGroup, type: OptionType, help: String,
    toServer: (String?) -> List<Pair<String, String>>,
) = OptionSpec(flag, label, group, type, help, Target.Server, toServer)

private fun client(flag: String, label: String, group: OptionGroup, type: OptionType, help: String) =
    OptionSpec(flag, label, group, type, help, Target.Client) { emptyList() }

private fun window(flag: String, label: String, type: OptionType, help: String) =
    OptionSpec(flag, label, OptionGroup.Window, type, help, Target.Window) { emptyList() }

/** `key=<number>` unless the number is 0 (server.c `if (params->x)`). */
private fun nonZero(value: String?, key: String): List<Pair<String, String>> {
    val n = value!!.toLong()
    return if (n == 0L) emptyList() else listOf(key to n.toString())
}

private fun bitRate(value: String?, key: String): List<Pair<String, String>> {
    val bits = BitRate.toBitsPerSecond(value!!) ?: return emptyList()
    return if (bits == 0L) emptyList() else listOf(key to bits.toString())
}

private fun unlessDefault(value: String?, key: String, default: String): List<Pair<String, String>> =
    if (value == default) emptyList() else listOf(key to value!!)

/** `auto` or an integer in -32767..32767 (cli.c `parse_window_position`). */
private fun windowPosition() = Formatted("auto or a number", "auto") { v ->
    v == "auto" || v.toIntOrNull()?.let { it in -0x7FFF..0x7FFF } == true
}

private fun isPortRange(v: String): Boolean {
    val parts = v.split(":")
    return parts.size in 1..2 && parts.all { p -> p.toIntOrNull()?.let { it in 0..0xFFFF } == true }
}

/** Server `Options.parseCameraAspectRatio`: `sensor`, `<num>:<den>` or a float. */
private fun isCameraAspectRatio(v: String): Boolean {
    if (v == "sensor") return true
    val parts = v.split(":")
    if (parts.size == 2) return parts.all { p -> p.toIntOrNull()?.let { it > 0 } == true }
    val f = v.toFloatOrNull() ?: return false
    return f.isFinite() && f > 0f
}

/** Server `Options.parseNewDisplay`: `<w>x<h>`, `<w>x<h>/<dpi>` or `/<dpi>`. */
private fun isNewDisplay(v: String): Boolean {
    val tokens = v.split("/")
    if (tokens.size > 2) return false
    if (tokens[0].isNotEmpty() && !isSize(tokens[0])) return false
    if (tokens.size == 2 && tokens[1].toIntOrNull()?.let { it > 0 } != true) return false
    return true
}
