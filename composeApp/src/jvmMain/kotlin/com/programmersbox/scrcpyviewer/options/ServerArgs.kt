package com.programmersbox.scrcpyviewer.options

/** Builds the `key=value` arguments that follow `com.genymobile.scrcpy.Server 5.0` on the `app_process` command line. */
object ServerArgs {
    /**
     * Stream metadata the client protocol needs. The scrcpy 5.0 server spells the codec/session header switch
     * `send_stream_meta` (Options.java); there is no `send_codec_meta` key.
     */
    private val metaDefaults = listOf(
        "send_frame_meta" to "true",
        "send_device_meta" to "true",
        "send_stream_meta" to "true",
    )

    /**
     * The result starts with `scid`, `log_level=info` and `tunnel_forward`, then the stream metadata defaults
     * (skipped when an option already sets the key), then the option args in [OptionCatalog] order.
     *
     * Before mapping, the option dependencies that `cli.c` resolves on the client are applied, so the server sees
     * what the native client would send:
     * - `--no-video` also sets `power_on=false`;
     * - without recording, `--no-video-playback`/`--no-playback` disable video and
     *   `--no-audio-playback`/`--no-playback` disable audio;
     * - an unset audio source becomes `playback` with `--audio-dup` and `mic` with `--video-source=camera`;
     * - the audio source is not sent while audio is disabled.
     *
     * Invalid values must be rejected with [OptionValues.validate] first.
     */
    fun build(values: OptionValues, scid: Int, tunnelForward: Boolean): List<String> {
        val effective = resolve(values.entries)
        val mapped = OptionCatalog.all
            .filter { it.target == Target.Server && it.flag in effective }
            .flatMap { it.toServer(effective[it.flag]) }
        val mappedKeys = mapped.map { it.first }.toSet()

        return buildList {
            add("scid=%08x".format(scid))
            add("log_level=info")
            add("tunnel_forward=$tunnelForward")
            metaDefaults.filter { it.first !in mappedKeys }.forEach { (k, v) -> add("$k=$v") }
            mapped.distinctBy { it.first }.forEach { (k, v) -> add("$k=$v") }
        }
    }

    /** Applies the `parse_args_with_getopt()` post-processing from scrcpy v5.0 `app/src/cli.c` that affects the server. */
    private fun resolve(entries: Map<String, String?>): Map<String, String?> {
        val e = entries.toMutableMap()
        val recording = "record" in e

        if ("no-video" in e) e["no-power-on"] = null

        val video = "no-video" !in e
        val videoPlayback = video && "no-video-playback" !in e && "no-playback" !in e
        val audio = "no-audio" !in e
        val audioPlayback = audio && "no-audio-playback" !in e && "no-playback" !in e
        // Checked after power_on, so a derived video=false keeps power_on (same order as cli.c).
        if (video && !videoPlayback && !recording) e["no-video"] = null
        if (audio && !audioPlayback && !recording) e["no-audio"] = null

        if ("no-audio" in e) {
            e.remove("audio-source")
        } else if ("audio-source" !in e) {
            when {
                e["video-source"] == "camera" -> e["audio-source"] = "mic"
                "audio-dup" in e -> e["audio-source"] = "playback"
            }
        }
        return e
    }
}
