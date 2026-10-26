package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
class ListDetailPanesTest {
    private fun ListDetailNavigation.list(partitions: Int) = scaffoldValue(partitions)[ListDetailPaneScaffoldRole.List]
    private fun ListDetailNavigation.detail(partitions: Int) = scaffoldValue(partitions)[ListDetailPaneScaffoldRole.Detail]

    @Test
    fun `wide layout shows both panes whatever the destination`() {
        val nav = ListDetailNavigation()
        assertEquals(PaneAdaptedValue.Expanded, nav.list(2))
        assertEquals(PaneAdaptedValue.Expanded, nav.detail(2))
        assertEquals(false, nav.showsBackButton(2))
        nav.openDetail()
        assertEquals(PaneAdaptedValue.Expanded, nav.list(2))
        assertEquals(PaneAdaptedValue.Expanded, nav.detail(2))
        assertEquals(false, nav.showsBackButton(2))
    }

    @Test
    fun `narrow layout starts on the list`() {
        val nav = ListDetailNavigation()
        assertEquals(PaneAdaptedValue.Expanded, nav.list(1))
        assertEquals(PaneAdaptedValue.Hidden, nav.detail(1))
        assertEquals(false, nav.showsBackButton(1))
    }

    @Test
    fun `narrow layout selecting an item shows the detail and back returns to the list`() {
        val nav = ListDetailNavigation()
        nav.openDetail()
        assertEquals(PaneAdaptedValue.Hidden, nav.list(1))
        assertEquals(PaneAdaptedValue.Expanded, nav.detail(1))
        assertEquals(true, nav.showsBackButton(1))
        nav.backToList()
        assertEquals(PaneAdaptedValue.Expanded, nav.list(1))
        assertEquals(PaneAdaptedValue.Hidden, nav.detail(1))
        assertEquals(false, nav.showsBackButton(1))
    }

    @Test
    fun `opening the detail twice needs one back`() {
        val nav = ListDetailNavigation()
        nav.openDetail()
        nav.openDetail()
        nav.backToList()
        assertEquals(PaneAdaptedValue.Expanded, nav.list(1))
    }

    @Test
    fun `drag deltas pass through before the first layout`() {
        val limits = PaneWidthLimits()
        limits.update(minFirst = 200f, maxFirst = 600f)
        assertEquals(37f, limits.consume(37f))
    }

    @Test
    fun `drag deltas are clamped to the min and max list width`() {
        val limits = PaneWidthLimits()
        limits.update(minFirst = 200f, maxFirst = 600f)
        limits.onFirstPaneMeasured(300)
        assertEquals(-50f, limits.consume(-50f)) // 250
        assertEquals(-50f, limits.consume(-80f)) // clamped at 200
        assertEquals(0f, limits.consume(-10f))
        assertEquals(30f, limits.consume(30f)) // 230
        assertEquals(370f, limits.consume(1000f)) // clamped at 600
        assertEquals(0f, limits.consume(5f))
    }

    @Test
    fun `a new layout resyncs the tracked width`() {
        val limits = PaneWidthLimits()
        limits.update(minFirst = 200f, maxFirst = 600f)
        limits.onFirstPaneMeasured(300)
        limits.consume(100f)
        limits.onFirstPaneMeasured(590)
        assertEquals(10f, limits.consume(50f))
    }

    @Test
    fun `when min exceeds max the min wins`() {
        val limits = PaneWidthLimits()
        limits.update(minFirst = 300f, maxFirst = 250f)
        limits.onFirstPaneMeasured(300)
        assertEquals(0f, limits.consume(-40f))
        assertEquals(0f, limits.consume(40f))
    }

    @Test
    fun `correction pulls an out of range width back inside`() {
        val limits = PaneWidthLimits()
        limits.update(minFirst = 200f, maxFirst = 600f)
        assertNull(limits.correction())
        limits.onFirstPaneMeasured(400)
        assertNull(limits.correction())
        limits.onFirstPaneMeasured(150)
        assertEquals(200, limits.correction())
        limits.onFirstPaneMeasured(700)
        assertEquals(600, limits.correction())
    }
}
