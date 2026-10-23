package com.programmersbox.scrcpyviewer.scrcpy

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/** Device name from the 64-byte NUL-padded `device meta` block (`DesktopConnection.sendDeviceMeta`). */
data class DeviceMeta(val name: String)

/**
 * Video stream header of scrcpy 5.0: the 4-byte codec id, then the first session packet which carries the size.
 * (scrcpy 5.0 no longer sends width/height next to the codec id; see [StreamItem.Session].)
 */
data class VideoCodecMeta(val codecId: Int, val width: Int, val height: Int)

data class MediaPacket(
    val ptsUs: Long,
    val isConfig: Boolean,
    val isKeyFrame: Boolean,
    val data: ByteArray,
)

/** A 12-byte session header (flag bit 63): the video size changed; [clientResized] is set when the client asked. */
sealed interface StreamItem {
    data class Session(val width: Int, val height: Int, val clientResized: Boolean) : StreamItem
    data class Packet(val packet: MediaPacket) : StreamItem
}

object StreamProtocol {
    const val DEVICE_NAME_FIELD_LENGTH = 64
    const val PACKET_HEADER_SIZE = 12

    const val CODEC_H264 = 0x68323634
    const val CODEC_H265 = 0x68323635
    const val CODEC_AV1 = 0x00617631
    const val CODEC_VP8 = 0x00767038
    const val CODEC_VP9 = 0x00767039
    const val CODEC_OPUS = 0x6f707573
    const val CODEC_AAC = 0x00616163
    const val CODEC_FLAC = 0x666c6163
    const val CODEC_RAW = 0x00726177

    /** Codec id written when the device disables a stream (0) or hits a configuration error (1). */
    const val CODEC_DISABLED = 0
    const val CODEC_ERROR = 1

    const val FLAG_SESSION = 1L shl 63
    const val FLAG_CONFIG = 1L shl 62
    const val FLAG_KEY_FRAME = 1L shl 61
    const val PTS_MASK = FLAG_KEY_FRAME - 1
}

fun readDeviceMeta(input: DataInputStream): DeviceMeta {
    val buf = ByteArray(StreamProtocol.DEVICE_NAME_FIELD_LENGTH)
    input.readFully(buf)
    buf[buf.size - 1] = 0 // in case of garbage, as scrcpy does
    val end = buf.indexOf(0.toByte())
    return DeviceMeta(String(buf, 0, end, Charsets.UTF_8))
}

/** Reads the video codec id and the session packet that follows it. Throws if the device disabled the stream. */
fun readVideoCodecMeta(input: DataInputStream): VideoCodecMeta {
    val codecId = input.readInt()
    if (codecId == StreamProtocol.CODEC_DISABLED || codecId == StreamProtocol.CODEC_ERROR) {
        throw IOException("Video stream ${if (codecId == StreamProtocol.CODEC_ERROR) "configuration error" else "disabled"} on the device")
    }
    val first = readStreamItem(input) as? StreamItem.Session
        ?: throw IOException("Unexpected packet (not a session header)")
    if (first.width == 0 || first.height == 0) throw IOException("Invalid session video size: ${first.width}x${first.height}")
    return VideoCodecMeta(codecId, first.width, first.height)
}

/** Returns the codec id, 0 when the device disabled audio, and 1 on error (configuration error or no data). */
fun readAudioCodecId(input: DataInputStream): Int =
    try {
        input.readInt()
    } catch (_: IOException) {
        StreamProtocol.CODEC_ERROR
    }

/** Reads a video-stream item (session or media packet); null on EOF. */
fun readStreamItem(input: DataInputStream): StreamItem? {
    val header = ByteArray(StreamProtocol.PACKET_HEADER_SIZE)
    try {
        input.readFully(header)
    } catch (_: EOFException) {
        return null
    }
    val view = java.nio.ByteBuffer.wrap(header) // big-endian
    val first = view.getLong(0)
    if (first and StreamProtocol.FLAG_SESSION != 0L) {
        return StreamItem.Session(
            width = view.getInt(4),
            height = view.getInt(8),
            clientResized = (header[3].toInt() and 1) != 0,
        )
    }
    val length = view.getInt(8)
    if (length <= 0) throw IOException("Invalid packet length: $length")
    val data = ByteArray(length)
    try {
        input.readFully(data)
    } catch (_: EOFException) {
        return null
    }
    val config = first and StreamProtocol.FLAG_CONFIG != 0L
    return StreamItem.Packet(
        MediaPacket(
            ptsUs = if (config) 0L else first and StreamProtocol.PTS_MASK,
            isConfig = config,
            isKeyFrame = first and StreamProtocol.FLAG_KEY_FRAME != 0L,
            data = data,
        )
    )
}

/** Reads a media packet (audio stream: no session packets); null on EOF. Throws on a session header. */
fun readPacket(input: DataInputStream): MediaPacket? =
    when (val item = readStreamItem(input)) {
        null -> null
        is StreamItem.Packet -> item.packet
        is StreamItem.Session -> throw IOException("Unexpected session header; use readStreamItem for video")
    }
