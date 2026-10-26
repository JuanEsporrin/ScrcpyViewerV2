package com.programmersbox.scrcpyviewer.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class TopLevelNavigatorTest {
    @Test
    fun `starts on Devices with a single entry`() {
        val nav = TopLevelNavigator()
        assertEquals(TopLevelRoute.Devices, nav.current)
        assertEquals(listOf<TopLevelRoute>(TopLevelRoute.Devices), nav.backStack.toList())
    }

    @Test
    fun `selecting a tab replaces the current entry`() {
        val nav = TopLevelNavigator()
        nav.select(TopLevelRoute.Presets)
        assertEquals(TopLevelRoute.Presets, nav.current)
        assertEquals(listOf<TopLevelRoute>(TopLevelRoute.Presets), nav.backStack.toList())
        nav.select(TopLevelRoute.Settings)
        assertEquals(listOf<TopLevelRoute>(TopLevelRoute.Settings), nav.backStack.toList())
        nav.select(TopLevelRoute.Devices)
        assertEquals(listOf<TopLevelRoute>(TopLevelRoute.Devices), nav.backStack.toList())
    }

    @Test
    fun `reselecting the current tab does not add an entry`() {
        val nav = TopLevelNavigator(TopLevelRoute.Presets)
        nav.select(TopLevelRoute.Presets)
        nav.select(TopLevelRoute.Presets)
        assertEquals(listOf<TopLevelRoute>(TopLevelRoute.Presets), nav.backStack.toList())
    }

    @Test
    fun `back stack never empties so there is no top-level back`() {
        val nav = TopLevelNavigator()
        assertEquals(false, nav.canGoBack)
        nav.select(TopLevelRoute.Settings)
        assertEquals(false, nav.canGoBack)
        assertEquals(1, nav.backStack.size)
    }

    @Test
    fun `rail order lists every route once`() {
        assertEquals(listOf(TopLevelRoute.Devices, TopLevelRoute.Presets, TopLevelRoute.Settings), TopLevelRoute.all)
    }
}
