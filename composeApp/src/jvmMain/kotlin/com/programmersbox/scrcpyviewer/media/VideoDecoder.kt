package com.programmersbox.scrcpyviewer.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import com.programmersbox.scrcpyviewer.scrcpy.MediaPacket
import com.programmersbox.scrcpyviewer.scrcpy.StreamProtocol
import com.programmersbox.scrcpyviewer.scrcpy.VideoCodecMeta
import com.programmersbox.scrcpyviewer.scrcpy.VideoSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.IOException

/**
 * Decodes the scrcpy video stream with FFmpeg (JavaCPP) and converts frames to BGRA [ImageBitmap]s.
 *
 * Like v5.0 `app/src/packet_merger.c`, an H.264/H.265 config packet is kept and prepended to the next media
 * packet (it is also sent on its own, as the demuxer does). Frames are converted with `sws_scale` into a native
 * BGRA buffer, copied into a reused byte array and installed into one of [POOL_SIZE] Skia bitmaps used in turn,
 * so the bitmap the UI is drawing is not overwritten by the next frame. The buffers are reallocated whenever the
 * frame size changes (a mid-stream session header, i.e. rotation, or a frame of another size).
 *
 * Every frame is also passed to [onFrame] and the decoded rate (frames per second, measured over about one
 * second) to [onFps]. All calls come from the session's video reader thread; [close] may come from another
 * thread and is serialized with decoding.
 */
class VideoDecoder(
    private val codecId: Int,
    private val onFrame: (ImageBitmap) -> Unit = {},
    private val onFps: (Double) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
) : VideoSink {
    private val _frameSize = MutableStateFlow<IntSize?>(null)
    /** Size of the session (from the headers) or of the last decoded frame, whichever changed last. */
    val frameSize: StateFlow<IntSize?> = _frameSize.asStateFlow()

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    private val _fps = MutableStateFlow(0.0)
    val fps: StateFlow<Double> = _fps.asStateFlow()

    @Volatile var decodedFrames: Long = 0
        private set

    private val lock = Any()
    private var closed = false
    private var ctx: AVCodecContext? = null
    private val packet: AVPacket = avcodec.av_packet_alloc()
    private val avFrame: AVFrame = avutil.av_frame_alloc()
    private val mergeConfig = codecId == StreamProtocol.CODEC_H264 || codecId == StreamProtocol.CODEC_H265
    private var pendingConfig: ByteArray? = null

    // Conversion state, reallocated by [ensureBuffers] when the size changes.
    private var sws: SwsContext? = null
    private var swsFormat = -1
    private var bufWidth = 0
    private var bufHeight = 0
    private var native: BytePointer? = null
    private val dstData = PointerPointer<BytePointer>(4)
    private val dstLinesize = IntPointer(4L)
    private var pixels = ByteArray(0)
    private var bitmaps: List<Bitmap> = emptyList()
    private var images: List<ImageBitmap> = emptyList()
    private var next = 0

    private var fpsWindowStart = clock()
    private var fpsWindowFrames = 0
    private var loggedSendError = false

    init {
        open()
    }

    private fun open() {
        val avId = when (codecId) {
            StreamProtocol.CODEC_H264 -> avcodec.AV_CODEC_ID_H264
            StreamProtocol.CODEC_H265 -> avcodec.AV_CODEC_ID_HEVC
            StreamProtocol.CODEC_AV1 -> avcodec.AV_CODEC_ID_AV1
            StreamProtocol.CODEC_VP8 -> avcodec.AV_CODEC_ID_VP8
            StreamProtocol.CODEC_VP9 -> avcodec.AV_CODEC_ID_VP9
            else -> throw IOException("Unsupported video codec 0x%08x".format(codecId))
        }
        val codec = avcodec.avcodec_find_decoder(avId) ?: throw IOException("No FFmpeg decoder for ${codecName(codecId)}")
        val c = avcodec.avcodec_alloc_context3(codec) ?: throw IOException("Could not allocate the decoder context")
        c.flags(c.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
        val r = avcodec.avcodec_open2(c, codec, null as PointerPointer<*>?)
        if (r < 0) {
            avcodec.avcodec_free_context(c)
            throw IOException("Could not open the ${codecName(codecId)} decoder ($r)")
        }
        ctx = c
    }

    override fun onMeta(meta: VideoCodecMeta) {
        _frameSize.value = IntSize(meta.width, meta.height)
    }

    override fun onSession(width: Int, height: Int): Unit = synchronized(lock) {
        if (closed) return
        _frameSize.value = IntSize(width, height)
        // The next frame has the new size; reallocate the conversion buffers now.
        ensureBuffers(width, height, swsFormat.takeIf { it >= 0 } ?: avutil.AV_PIX_FMT_YUV420P)
    }

    override fun onPacket(p: MediaPacket): Unit = synchronized(lock) {
        if (closed) return
        val data = if (mergeConfig) {
            if (p.isConfig) {
                pendingConfig = p.data
                p.data
            } else {
                val config = pendingConfig
                pendingConfig = null
                if (config != null) config + p.data else p.data
            }
        } else p.data
        decode(data, p)
    }

    private fun decode(data: ByteArray, p: MediaPacket) {
        val c = ctx ?: return
        if (avcodec.av_new_packet(packet, data.size) < 0) throw IOException("Out of memory for a video packet")
        try {
            packet.data().put(data, 0, data.size)
            packet.pts(if (p.isConfig) avutil.AV_NOPTS_VALUE else p.ptsUs)
            packet.dts(packet.pts())
            if (p.isKeyFrame) packet.flags(packet.flags() or avcodec.AV_PKT_FLAG_KEY)
            val sent = avcodec.avcodec_send_packet(c, packet)
            if (sent < 0) {
                // A corrupt packet is not fatal: the next key frame recovers.
                if (!loggedSendError) log.log(System.Logger.Level.WARNING, "Could not send a video packet ($sent)")
                loggedSendError = true
                return
            }
        } finally {
            avcodec.av_packet_unref(packet)
        }
        while (avcodec.avcodec_receive_frame(c, avFrame) >= 0) {
            try {
                publish(avFrame)
            } finally {
                avutil.av_frame_unref(avFrame)
            }
        }
    }

    private fun publish(f: AVFrame) {
        val w = f.width()
        val h = f.height()
        if (w <= 0 || h <= 0) return
        if (w != bufWidth || h != bufHeight || f.format() != swsFormat) {
            ensureBuffers(w, h, f.format())
            _frameSize.value = IntSize(w, h)
        }
        val ctx = sws ?: return
        swscale.sws_scale(ctx, f.data(), f.linesize(), 0, h, dstData, dstLinesize)
        val stride = dstLinesize.get(0)
        val src = native!!
        if (stride == w * 4) {
            src.position(0).get(pixels, 0, w * h * 4)
        } else {
            for (y in 0 until h) src.position(y.toLong() * stride).get(pixels, y * w * 4, w * 4)
            src.position(0)
        }
        val index = next
        next = (next + 1) % POOL_SIZE
        bitmaps[index].installPixels(pixels)
        val image = images[index]
        decodedFrames++
        _frame.value = image
        onFrame(image)
        countFps()
    }

    private fun countFps() {
        fpsWindowFrames++
        val now = clock()
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1_000_000_000L) {
            val value = fpsWindowFrames * 1e9 / elapsed
            fpsWindowStart = now
            fpsWindowFrames = 0
            _fps.value = value
            onFps(value)
        }
    }

    /** (Re)allocates the sws context, the native BGRA buffer, the byte array and the bitmap pool for [w]x[h]. */
    private fun ensureBuffers(w: Int, h: Int, format: Int) {
        if (w <= 0 || h <= 0) return
        if (w == bufWidth && h == bufHeight && format == swsFormat && sws != null) return
        sws = swscale.sws_getCachedContext(
            sws, w, h, format, w, h, avutil.AV_PIX_FMT_BGRA, swscale.SWS_BILINEAR, null, null, null as DoublePointer?,
        ) ?: throw IOException("Could not create the color converter for ${w}x$h")
        swsFormat = format
        if (w != bufWidth || h != bufHeight) {
            native?.let { avutil.av_free(it) }
            val size = avutil.av_image_get_buffer_size(avutil.AV_PIX_FMT_BGRA, w, h, 1)
            val buf = BytePointer(avutil.av_malloc(size.toLong())).capacity(size.toLong())
            native = buf
            avutil.av_image_fill_arrays(dstData, dstLinesize, buf, avutil.AV_PIX_FMT_BGRA, w, h, 1)
            pixels = ByteArray(w * h * 4)
            // The old bitmaps are not closed: the UI may still be drawing the last one. They are freed by the GC.
            val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            bitmaps = List(POOL_SIZE) { Bitmap().apply { allocPixels(info) } }
            images = bitmaps.map { it.asComposeImageBitmap() }
            next = 0
            bufWidth = w
            bufHeight = h
        }
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        ctx?.let { avcodec.avcodec_free_context(it) }
        ctx = null
        avcodec.av_packet_free(packet)
        avutil.av_frame_free(avFrame)
        sws?.let { swscale.sws_freeContext(it) }
        sws = null
        native?.let { avutil.av_free(it) }
        native = null
        dstData.close()
        dstLinesize.close()
        // The bitmaps stay alive: the UI may still draw the last frame (e.g. under the Disconnected scrim).
    }

    companion object {
        /** Bitmaps used in turn; the one on screen is never the one being written. */
        const val POOL_SIZE = 3
        private val log = System.getLogger("scrcpyviewer.VideoDecoder")

        fun codecName(codecId: Int): String = when (codecId) {
            StreamProtocol.CODEC_H264 -> "H.264"
            StreamProtocol.CODEC_H265 -> "H.265"
            StreamProtocol.CODEC_AV1 -> "AV1"
            StreamProtocol.CODEC_VP8 -> "VP8"
            StreamProtocol.CODEC_VP9 -> "VP9"
            else -> "0x%08x".format(codecId)
        }
    }
}
