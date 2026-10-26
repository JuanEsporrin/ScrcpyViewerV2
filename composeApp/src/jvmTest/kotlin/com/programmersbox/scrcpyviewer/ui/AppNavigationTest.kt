package com.programmersbox.scrcpyviewer.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.programmersbox.scrcpyviewer.fakeAppGraph
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelNavigator
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The whole App on a fake adb: switching rail tabs keeps the device selection and the dragged divider. */
class AppNavigationTest {
    @Test
    fun `selection and divider survive switching to Presets and back`() {
        val graph = fakeAppGraph()
        try {
            runBlocking { withTimeout(5_000) { graph.deviceTracker.devices.first { it.size == 3 } } }
            graph.devicesViewModel.select("39121FDJH004AB")
            val navigator = TopLevelNavigator()
            var devicesPanes: ListDetailPanes? = null
            onEdt {
                val driver = SceneDriver(ImageComposeScene(1280, 800, Density(1f)) {
                    val panes = rememberListDetailPanes().also { devicesPanes = it }
                    App(graph, navigator = navigator, devicesPanes = panes)
                })
                try {
                    driver.frames(10)
                    val panes = assertNotNull(devicesPanes)
                    val before = assertNotNull(panes.listWidthPx, "two-pane layout at 1280 px")
                    // The rail is 80 px wide; the handle is centred in the 16 px gap after the list.
                    val handleX = 80f + before + 8f
                    driver.drag(fromX = handleX, toX = handleX + 150f, y = 400f)
                    val dragged = assertNotNull(panes.listWidthPx)
                    assertTrue(dragged > before + 100, "divider moved: $before -> $dragged")

                    navigator.select(TopLevelRoute.Presets)
                    driver.frames(40)
                    assertEquals(TopLevelRoute.Presets, navigator.current)
                    navigator.select(TopLevelRoute.Devices)
                    driver.frames(40)

                    assertEquals("39121FDJH004AB", graph.devicesViewModel.selectedSerial.value)
                    assertEquals(dragged, panes.listWidthPx)
                } finally {
                    driver.scene.close()
                }
            }
        } finally {
            graph.scope.coroutineContext[Job]?.cancel()
        }
    }
}
