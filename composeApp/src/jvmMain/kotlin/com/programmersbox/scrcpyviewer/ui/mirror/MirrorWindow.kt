package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.media.VideoDecoder
import com.programmersbox.scrcpyviewer.media.VideoFrame
import com.programmersbox.scrcpyviewer.options.CommandLine
import com.programmersbox.scrcpyviewer.options.OptionType
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.scrcpy.ControlMessage
import com.programmersbox.scrcpyviewer.scrcpy.ScrcpySession
import com.programmersbox.scrcpyviewer.scrcpy.SessionStatus
import com.programmersbox.scrcpyviewer.scrcpy.StreamProtocol
import com.programmersbox.scrcpyviewer.ui.common.CopyButton
import com.programmersbox.scrcpyviewer.ui.devices.deviceTitle
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.theme.warnColor
import kotlinx.coroutines.flow.filter
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent as AwtKeyEvent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Side toolbar buttons; [keycode] buttons send that Android key down and up. */
enum class MirrorAction(val label: String, val keycode: Int? = null) {
    Back("Back", InputMapper.KEYCODE_BACK),
    Home("Home", InputMapper.KEYCODE_HOME),
    Recents("Recents", InputMapper.KEYCODE_APP_SWITCH),
    VolumeUp("Volume up", InputMapper.KEYCODE_VOLUME_UP),
    VolumeDown("Volume down", InputMapper.KEYCODE_VOLUME_DOWN),
    Rotate("Rotate"),
    Power("Power", InputMapper.KEYCODE_POWER),
    Screenshot("Screenshot"),
    Record("Record"),
}

/** Control messages for a toolbar action; Screenshot and Record (Task 14) send nothing. */
fun MirrorAction.messages(): List<ControlMessage> = when {
    keycode != null -> listOf(
        ControlMessage.InjectKeycode(InputMapper.KEY_ACTION_DOWN, keycode, 0, 0),
        ControlMessage.InjectKeycode(InputMapper.KEY_ACTION_UP, keycode, 0, 0),
    )
    this == MirrorAction.Rotate -> listOf(ControlMessage.RotateDevice)
    else -> emptyList()
}

data class MirrorUiState(
    val model: String,
    /** "Android 15 · USB". */
    val details: String,
    val status: SessionStatus,
    val error: String? = null,
    val frameSize: IntSize? = null,
    val maxSize: Int? = null,
    val codec: String = "H.264",
    val bitRate: String = "8 Mbps",
    val fps: Double = 0.0,
    val fit: Boolean = true,
    val stretch: Boolean = false,
    val canReconnect: Boolean = false,
    val command: String = "scrcpy",
    val background: Color? = null,
    val actionsEnabled: Boolean = true,
)

/** `<model> · <serial>`, or the `window-title` option. */
fun mirrorTitle(model: String, serial: String, values: OptionValues): String =
    values.entries["window-title"]?.takeIf { it.isNotBlank() } ?: "$model · $serial"

/** "8 Mbps" from the `video-bit-rate` value (default 8M). */
fun bitRateLabel(value: String?): String {
    val bits = value?.let { OptionType.BitRate.toBitsPerSecond(it) } ?: 8_000_000L
    return when {
        bits >= 1_000_000 && bits % 1_000_000 == 0L -> "${bits / 1_000_000} Mbps"
        bits >= 1_000_000 -> "%.1f Mbps".format(bits / 1e6)
        else -> "${bits / 1000} Kbps"
    }
}

fun codecLabel(value: String?): String = VideoDecoder.codecName(
    when (value) {
        "h265" -> StreamProtocol.CODEC_H265
        "av1" -> StreamProtocol.CODEC_AV1
        "vp8" -> StreamProtocol.CODEC_VP8
        "vp9" -> StreamProtocol.CODEC_VP9
        else -> StreamProtocol.CODEC_H264
    }
)

/** "#RGB" / "#RRGGBB" (with or without #) as a color. */
fun parseHexColor(value: String?): Color? {
    val hex = value?.removePrefix("#") ?: return null
    val full = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6 -> hex
        else -> return null
    }
    return full.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/**
 * One device's mirror window: [session] keeps running on the app scope; closing the window calls
 * [MirrorController.close]. Keys typed while the window is focused go to the device; Cmd+V (Ctrl+V off macOS)
 * pastes the computer clipboard through `SetClipboard(paste=true)` (or as text with `legacy-paste`).
 */
@Composable
fun MirrorWindow(
    serial: String,
    session: ScrcpySession,
    controller: MirrorController,
    themeMode: ThemeMode,
) {
    val values = session.values
    val e = values.entries
    val devices by controller.devices.collectAsState()
    // Keep showing the last known device details after it is unplugged.
    var lastDevice by remember { mutableStateOf<Device?>(null) }
    val current = devices.firstOrNull { it.serial == serial }
    LaunchedEffect(current) { if (current != null) lastDevice = current }
    val device = current ?: lastDevice
    val model = device?.let(::deviceTitle) ?: serial

    val status by session.status.collectAsState()
    val error by session.error.collectAsState()
    val frameSize by session.frameSize.collectAsState()
    val fps by session.fps.collectAsState()
    val frame = session.frames.collectAsState()
    val reconnectable by controller.reconnectable.collectAsState()

    val renderFit = e["render-fit"]
    var fit by remember { mutableStateOf(renderFit != "unscaled") }
    val stretch = renderFit == "stretched"
    val aspectLock = "no-window-aspect-ratio-lock" !in e
    val explicitSize = e["window-width"]?.toIntOrNull()?.takeIf { it > 0 } != null ||
        e["window-height"]?.toIntOrNull()?.takeIf { it > 0 } != null

    val windowState = rememberWindowState(
        placement = if ("fullscreen" in e) WindowPlacement.Fullscreen else WindowPlacement.Floating,
        position = run {
            val x = e["window-x"]?.toIntOrNull()
            val y = e["window-y"]?.toIntOrNull()
            if (x == null && y == null) WindowPosition.PlatformDefault else WindowPosition((x ?: 100).dp, (y ?: 100).dp)
        },
        size = DpSize(
            (e["window-width"]?.toIntOrNull()?.takeIf { it > 0 } ?: 480).dp,
            (e["window-height"]?.toIntOrNull()?.takeIf { it > 0 } ?: 900).dp,
        ),
    )

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val currentFit by rememberUpdatedState(fit)
    val mapper = remember(session) {
        InputMapper.forOptions(
            values,
            // Map against the image on screen (the canvas letterboxes that one), which can lag the session size
            // for a frame after a rotation.
            frameSize = { session.frames.value?.let { IntSize(it.width, it.height) } ?: session.frameSize.value },
            viewRect = { Rect(0f, 0f, viewSize.width.toFloat(), viewSize.height.toFloat()) },
            stretch = { stretch && currentFit },
        )
    }
    val send: (List<ControlMessage>) -> Unit = { msgs -> msgs.forEach(session::send) }

    val state = MirrorUiState(
        model = model,
        details = listOfNotNull(
            device?.info?.androidVersion?.let { "Android $it" },
            device?.let { if (it.isTcp) "Wi-Fi" else "USB" },
        ).joinToString(" · "),
        status = status,
        error = error,
        frameSize = frameSize,
        maxSize = e["max-size"]?.toIntOrNull()?.takeIf { it > 0 },
        codec = codecLabel(e["video-codec"]),
        bitRate = bitRateLabel(e["video-bit-rate"]),
        fps = fps,
        fit = fit,
        stretch = stretch,
        canReconnect = serial in reconnectable,
        command = CommandLine.build(serial, values),
        background = parseHexColor(e["background-color"]),
        actionsEnabled = status == SessionStatus.Running && "no-control" !in e,
    )

    Window(
        onCloseRequest = { controller.close(serial) },
        state = windowState,
        title = mirrorTitle(model, serial, values),
        alwaysOnTop = "always-on-top" in e,
    ) {
        LaunchedEffect(serial) {
            controller.focusRequests.filter { it == serial }.collect {
                window.toFront()
                window.requestFocus()
            }
        }
        if ("print-fps" in e) {
            LaunchedEffect(session) { session.fps.collect { if (it > 0) println("[$serial] %.1f fps".format(it)) } }
        }
        val awtWindow = window
        DisposableEffect(session) {
            val paste = if (System.getProperty("os.name").lowercase().contains("mac")) AwtKeyEvent.META_DOWN_MASK else AwtKeyEvent.CTRL_DOWN_MASK
            var pasteDown = false
            val dispatcher = KeyEventDispatcher { ev ->
                val inWindow = ev.component === awtWindow || SwingUtilities.getWindowAncestor(ev.component) === awtWindow
                if (!inWindow || "no-control" in e || e["keyboard"] == "disabled") return@KeyEventDispatcher false
                if (ev.keyCode == AwtKeyEvent.VK_V && ev.modifiersEx and paste != 0 && ev.id == AwtKeyEvent.KEY_PRESSED) {
                    pasteDown = true
                    val text = runCatching {
                        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
                    }.getOrNull()
                    if (!text.isNullOrEmpty()) {
                        if ("legacy-paste" in e) text.chunked(100).forEach { session.send(ControlMessage.InjectText(it)) }
                        else session.send(ControlMessage.SetClipboard(0, paste = true, text = text))
                    }
                    return@KeyEventDispatcher true
                }
                if (pasteDown && (ev.keyCode == AwtKeyEvent.VK_V || ev.id == AwtKeyEvent.KEY_TYPED)) {
                    if (ev.id == AwtKeyEvent.KEY_RELEASED) pasteDown = false
                    return@KeyEventDispatcher true
                }
                val msgs = mapper.awtKey(ev.id, ev.keyCode, ev.keyChar, ev.modifiersEx, ev.keyLocation)
                send(msgs)
                msgs.isNotEmpty()
            }
            val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            kfm.addKeyEventDispatcher(dispatcher)
            onDispose { kfm.removeKeyEventDispatcher(dispatcher) }
        }

        val density = LocalDensity.current
        var stage by remember { mutableStateOf(IntSize.Zero) }
        var autoSized by remember { mutableStateOf(explicitSize) }
        var lastStage by remember { mutableStateOf(IntSize.Zero) }
        var lastFrame by remember { mutableStateOf<IntSize?>(null) }
        // Initial size from the first frame (fit to the screen), then the aspect ratio lock on every resize.
        LaunchedEffect(stage, frameSize, fit) {
            val fs = frameSize ?: return@LaunchedEffect
            if (stage == IntSize.Zero || windowState.placement != WindowPlacement.Floating) return@LaunchedEffect
            val target: IntSize? = when {
                !autoSized -> {
                    autoSized = true
                    val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    val chromeW = windowState.size.width.value * density.density - stage.width
                    val chromeH = windowState.size.height.value * density.density - stage.height
                    val maxW = bounds.width * density.density * 0.9f - chromeW
                    val maxH = bounds.height * density.density * 0.9f - chromeH
                    val scale = min(1f, min(maxW / fs.width, maxH / fs.height))
                    IntSize((fs.width * scale).roundToInt(), (fs.height * scale).roundToInt())
                }
                fit && aspectLock -> aspectLockedStage(lastStage, stage, lastFrame, fs)
                else -> null
            }
            lastFrame = fs
            lastStage = target ?: stage
            if (target != null && (abs(target.width - stage.width) > 2 || abs(target.height - stage.height) > 2)) {
                val dw = (target.width - stage.width) / density.density
                val dh = (target.height - stage.height) / density.density
                windowState.size = DpSize(windowState.size.width + dw.dp, windowState.size.height + dh.dp)
            }
        }

        ScrcpyViewerTheme(themeMode) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                MirrorContent(
                    state = state,
                    frame = frame,
                    latestFrame = { session.frames.value },
                    onFitChange = { fit = it },
                    onAction = { send(it.messages()) },
                    onReconnect = { controller.reconnect(serial) },
                    onPointer = { if ("no-control" !in e && e["mouse"] != "disabled") send(mapper.pointer(it)) },
                    onViewSize = { viewSize = it },
                    onStageSize = { stage = it },
                )
            }
        }
    }
}

/**
 * Stage size that keeps the frame's aspect ratio after the user resized the stage from [old] to [new]: the
 * dimension that changed most wins. When the frame turned (rotation), the longer side is kept.
 */
fun aspectLockedStage(old: IntSize, new: IntSize, oldFrame: IntSize?, frame: IntSize): IntSize {
    if (frame.width <= 0 || frame.height <= 0 || new.width <= 0 || new.height <= 0) return new
    val aspect = frame.width.toDouble() / frame.height
    val rotated = oldFrame != null && (oldFrame.width > oldFrame.height) != (frame.width > frame.height)
    return if (rotated) {
        val long = maxOf(new.width, new.height)
        if (frame.width >= frame.height) IntSize(long, (long / aspect).roundToInt()) else IntSize((long * aspect).roundToInt(), long)
    } else if (abs(new.width - old.width) >= abs(new.height - old.height)) {
        IntSize(new.width, (new.width / aspect).roundToInt())
    } else {
        IntSize((new.height * aspect).roundToInt(), new.height)
    }
}

/** The mirror window's content (stateless, so it can be rendered offscreen). */
@Composable
fun MirrorContent(
    state: MirrorUiState,
    frame: State<VideoFrame?>,
    onFitChange: (Boolean) -> Unit,
    onAction: (MirrorAction) -> Unit,
    onReconnect: () -> Unit,
    onPointer: (PointerEvent) -> Unit,
    onViewSize: (IntSize) -> Unit,
    onStageSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
    /** The frame to draw, read at draw time ([frame] only triggers the redraw). */
    latestFrame: () -> VideoFrame? = { frame.value },
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize()) {
        TopStrip(state, onFitChange)
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(state.background ?: colors.surfaceDim)
                    .onSizeChanged(onStageSize),
            ) {
                Stage(state, frame, latestFrame, onPointer, onViewSize)
                if (state.status == SessionStatus.Disconnected || state.status == SessionStatus.Failed) {
                    DisconnectedScrim(state, onReconnect)
                } else if (state.status == SessionStatus.Connecting || state.frameSize == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            SideToolbar(state.actionsEnabled, onAction)
        }
        HorizontalDivider(color = colors.outlineVariant)
        StatusLine(state)
    }
}

@Composable
private fun TopStrip(state: MirrorUiState, onFitChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val dot = when (state.status) {
            SessionStatus.Running -> colors.primary
            SessionStatus.Connecting -> warnColor()
            else -> colors.error
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(state.model, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1)
        if (state.details.isNotEmpty()) {
            Text(
                state.details,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        FilterChip(selected = state.fit, onClick = { onFitChange(!state.fit) }, label = { Text("Fit to window") })
        CopyButton(text = { state.command }, label = "Copy command")
    }
}

@Composable
private fun Stage(
    state: MirrorUiState,
    frame: State<VideoFrame?>,
    latestFrame: () -> VideoFrame?,
    onPointer: (PointerEvent) -> Unit,
    onViewSize: (IntSize) -> Unit,
) {
    val pointer by rememberUpdatedState(onPointer)
    val input = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                pointer(event)
                event.changes.forEach { it.consume() }
            }
        }
    }
    val fs = state.frameSize
    if (state.fit || fs == null) {
        Canvas(Modifier.fillMaxSize().onSizeChanged(onViewSize).then(input)) {
            frame.value // subscribe: a new frame redraws
            drawLatestFrame(latestFrame, FilterQuality.Low) { f ->
                val dst = InputMapper.contentRect(IntSize(f.width, f.height), Rect(0f, 0f, size.width, size.height), state.stretch)
                IntOffset(dst.left.roundToInt(), dst.top.roundToInt()) to IntSize(dst.width.roundToInt(), dst.height.roundToInt())
            }
        }
    } else {
        // 1:1 (one frame pixel per screen pixel) in a scroll container.
        val density = LocalDensity.current
        val v = rememberScrollState()
        val h = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().verticalScroll(v).horizontalScroll(h), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier.size(with(density) { DpSize(fs.width.toDp(), fs.height.toDp()) })
                        .onSizeChanged(onViewSize).then(input),
                ) {
                    frame.value // subscribe: a new frame redraws
                    drawLatestFrame(latestFrame, FilterQuality.None) {
                        IntOffset.Zero to IntSize(size.width.roundToInt(), size.height.roundToInt())
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(v), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            HorizontalScrollbar(rememberScrollbarAdapter(h), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}

/**
 * Draws the latest frame inside [VideoFrame.use], so a released bitmap is never touched. A frame released between
 * the read and the draw (a newer one was just published) is retried with the newer one.
 */
private fun DrawScope.drawLatestFrame(
    latest: () -> VideoFrame?,
    quality: FilterQuality,
    placement: (VideoFrame) -> Pair<IntOffset, IntSize>,
) {
    repeat(3) {
        val f = latest() ?: return
        val drawn = f.use { bitmap ->
            val (offset, size) = placement(f)
            drawImage(bitmap.asComposeImageBitmap(), dstOffset = offset, dstSize = size, filterQuality = quality)
        }
        if (drawn != null) return
    }
}

@Composable
private fun DisconnectedScrim(state: MirrorUiState, onReconnect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh, tonalElevation = 3.dp) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 20.dp).width(260.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Disconnected", style = MaterialTheme.typography.titleMedium)
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 4)
                }
                if (!state.canReconnect) {
                    Text("Waiting for the device…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Button(onClick = onReconnect, enabled = state.canReconnect) { Text("Reconnect") }
            }
        }
    }
}

private val toolbarIcons: Map<MirrorAction, ImageVector> = mapOf(
    MirrorAction.Back to Icons.AutoMirrored.Filled.ArrowBack,
    MirrorAction.Home to Icons.Outlined.Circle,
    MirrorAction.Recents to Icons.Outlined.CropSquare,
    MirrorAction.VolumeUp to Icons.AutoMirrored.Filled.VolumeUp,
    MirrorAction.VolumeDown to Icons.AutoMirrored.Filled.VolumeDown,
    MirrorAction.Rotate to Icons.Filled.ScreenRotation,
    MirrorAction.Power to Icons.Filled.PowerSettingsNew,
    MirrorAction.Screenshot to Icons.Filled.PhotoCamera,
    MirrorAction.Record to Icons.Filled.FiberManualRecord,
)

@Composable
private fun SideToolbar(enabled: Boolean, onAction: (MirrorAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.width(64.dp).fillMaxHeight().background(colors.surfaceContainer).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val groups = listOf(
            listOf(MirrorAction.Back, MirrorAction.Home, MirrorAction.Recents),
            listOf(MirrorAction.VolumeUp, MirrorAction.VolumeDown, MirrorAction.Rotate, MirrorAction.Power),
            listOf(MirrorAction.Screenshot, MirrorAction.Record),
        )
        groups.forEachIndexed { i, group ->
            if (i > 0) HorizontalDivider(Modifier.width(28.dp).padding(vertical = 4.dp), color = colors.outlineVariant)
            group.forEach { action ->
                // Screenshot and Record arrive with Task 14.
                val available = action != MirrorAction.Screenshot && action != MirrorAction.Record
                ToolbarButton(action, enabled && available) { onAction(action) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolbarButton(action: MirrorAction, enabled: Boolean, onClick: () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                Text(
                    action.label,
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(toolbarIcons.getValue(action), contentDescription = action.label)
        }
    }
}

@Composable
private fun StatusLine(state: MirrorUiState) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.typography.labelMedium
        val fs = state.frameSize
        val size = if (fs == null) "—" else "${fs.width} × ${fs.height}" + (state.maxSize?.let { " shown at $it max" } ?: "")
        Text(size, style = style, color = colors.onSurfaceVariant)
        Text("${state.codec} · ${state.bitRate}", style = style, color = colors.onSurfaceVariant)
        Text("%.1f fps".format(state.fps), style = style, color = colors.onSurfaceVariant)
    }
}
