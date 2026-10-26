package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drags the real divider in an offscreen scene (1 px = 1 dp). */
@OptIn(ExperimentalComposeUiApi::class)
class ResizableListDetailTest {
    private var listWidth by mutableIntStateOf(-1)
    private var detailWidth by mutableIntStateOf(-1)
    private var time = 0L

    private fun ImageComposeScene.frame() {
        time += 16_000_000
        render(time)
    }

    private fun ImageComposeScene.drag(fromX: Float, toX: Float, y: Float = 300f) {
        sendPointerEvent(PointerEventType.Move, Offset(fromX, y), type = PointerType.Mouse)
        sendPointerEvent(PointerEventType.Press, Offset(fromX, y), type = PointerType.Mouse, button = PointerButton.Primary)
        frame()
        val steps = 20
        for (i in 1..steps) {
            sendPointerEvent(PointerEventType.Move, Offset(fromX + (toX - fromX) * i / steps, y), type = PointerType.Mouse)
            frame()
        }
        sendPointerEvent(PointerEventType.Release, Offset(toX, y), type = PointerType.Mouse, button = PointerButton.Primary)
        repeat(60) { frame() }
    }

    private fun onEdt(block: () -> Unit) {
        var error: Throwable? = null
        SwingUtilities.invokeAndWait { try { block() } catch (t: Throwable) { error = t } }
        error?.let { throw it }
    }

    private fun scene(width: Int) = ImageComposeScene(width, 600, Density(1f)) {
        ResizableListDetail(
            panes = rememberListDetailPanes(),
            listMinWidth = 240.dp,
            listPreferredWidth = 300.dp,
            detailMinWidth = 640.dp,
            list = { Box(Modifier.fillMaxSize().onSizeChanged { listWidth = it.width }) },
            detail = { Box(Modifier.fillMaxSize().onSizeChanged { detailWidth = it.width }) },
        )
    }

    @Test
    fun `divider starts at the preferred width and drags within the min widths`() = onEdt {
        val scene = scene(1200)
        try {
            repeat(5) { scene.frame() }
            assertEquals(300, listWidth)
            // Handle is centred in the 16 px gap after the list.
            scene.drag(fromX = 308f, toX = 400f)
            // The drag slop swallows the first few px.
            assertTrue(listWidth in 370..392, "list grew with the drag: $listWidth")
            scene.drag(fromX = listWidth + 8f, toX = 20f)
            assertEquals(240, listWidth, "list stops at its min width")
            scene.drag(fromX = listWidth + 8f, toX = 1180f)
            assertEquals(1200 - 16 - 640, listWidth, "detail keeps its min width")
            assertEquals(640, detailWidth)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `hoisted panes keep the divider when the screen leaves and returns`() = onEdt {
        var shown by mutableStateOf(true)
        val scene = ImageComposeScene(1200, 600, Density(1f)) {
            val panes = rememberListDetailPanes() // hoisted, like App does above NavDisplay
            if (shown) {
                ResizableListDetail(
                    panes = panes,
                    listMinWidth = 240.dp,
                    listPreferredWidth = 300.dp,
                    detailMinWidth = 640.dp,
                    list = { Box(Modifier.fillMaxSize().onSizeChanged { listWidth = it.width }) },
                    detail = { Box(Modifier.fillMaxSize()) },
                )
            }
        }
        try {
            repeat(5) { scene.frame() }
            scene.drag(fromX = 308f, toX = 500f)
            val dragged = listWidth
            assertTrue(dragged > 400, "dragged to $dragged")
            shown = false
            repeat(5) { scene.frame() }
            listWidth = -1
            shown = true
            repeat(5) { scene.frame() }
            assertEquals(dragged, listWidth)
        } finally {
            scene.close()
        }
    }
}
