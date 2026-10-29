package com.programmersbox.scrcpyviewer.ui.settings

import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Adb
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Appearance
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Help
import com.programmersbox.scrcpyviewer.ui.settings.SettingsCategoryId.Mirroring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsNavTest {
    @Test
    fun `defaults to Appearance`() = assertEquals(Appearance, SettingsNav().selected)

    @Test
    fun `clearing search restores the category selected before searching`() {
        val nav = SettingsNav().select(Adb).withQuery("fold").withQuery("")
        assertEquals(Adb, nav.selected)
        assertNull(nav.restoreTo)
    }

    @Test
    fun `category picked during search stays after clearing`() {
        val nav = SettingsNav().select(Adb).withQuery("a").select(Help).withQuery("")
        assertEquals(Help, nav.selected)
    }

    @Test
    fun `shown falls back to first visible`() {
        assertEquals(Mirroring, SettingsNav(selected = Adb).shown(listOf(Mirroring, Help)))
        assertNull(SettingsNav().shown(emptyList()))
    }

    @Test
    fun `typing more keeps a still-visible selection`() {
        val nav = SettingsNav().select(Mirroring).withQuery("f").withQuery("fo")
        assertEquals(Mirroring, nav.shown(listOf(Appearance, Mirroring)))
        assertEquals(Mirroring, nav.restoreTo)
    }

    @Test
    fun `whitespace query is not a search`() {
        val nav = SettingsNav().withQuery("  ")
        assertNull(nav.restoreTo)
        assertEquals("  ", nav.query)
    }
}
