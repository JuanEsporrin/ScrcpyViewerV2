package com.programmersbox.scrcpyviewer.media

/** Task 12's sample.h264 (320x240) split into the config packet (SPS + PPS) and 5 frame packets. */
internal class H264Sample(val config: ByteArray, val frames: List<ByteArray>)

/** Annex B NAL units (start codes kept). */
private fun nalUnits(data: ByteArray): List<ByteArray> {
    val starts = ArrayList<Int>()
    var i = 0
    while (i + 3 <= data.size) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
            starts += if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
            i += 3
        } else i++
    }
    return starts.mapIndexed { n, s -> data.copyOfRange(s, starts.getOrElse(n + 1) { data.size }) }
}

private fun nalType(nal: ByteArray): Int = (if (nal[2] == 1.toByte()) nal[3] else nal[4]).toInt() and 0x1f

internal fun loadH264Sample(): H264Sample {
    val nals = nalUnits(checkNotNull(H264Sample::class.java.getResourceAsStream("/sample.h264")).readBytes())
    val config = nals.filter { nalType(it) == 7 || nalType(it) == 8 }
    // Each frame packet ends with its VCL NAL (types 1..5); SEI etc. go with the next frame.
    val frames = ArrayList<ByteArray>()
    var pending = ByteArray(0)
    for (nal in nals - config.toSet()) {
        pending += nal
        if (nalType(nal) in 1..5) {
            frames += pending
            pending = ByteArray(0)
        }
    }
    return H264Sample(config.reduce { a, b -> a + b }, frames)
}
