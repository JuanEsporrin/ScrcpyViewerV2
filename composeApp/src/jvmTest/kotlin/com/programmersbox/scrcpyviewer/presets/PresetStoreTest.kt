package com.programmersbox.scrcpyviewer.presets

import com.programmersbox.scrcpyviewer.options.OptionValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PresetStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val file = Files.createTempDirectory("presets").resolve("presets.json")
    private val a = Preset("a", "A", "d", OptionValues(mapOf("no-audio" to null, "max-size" to "800")))

    @Test
    fun `store persists across instances`() {
        val s = PresetStore(file, scope)
        s.upsert(a)
        s.setDefault("a")
        val dup = s.duplicate("a")
        val s2 = PresetStore(file, scope)
        assertEquals(listOf(a, dup), s2.presets.value)
        assertEquals("A (2)", dup.name)
        assertEquals("a", s2.defaultPresetId.value)
    }

    @Test
    fun `delete of default clears default`() {
        val s = PresetStore(file, scope)
        s.upsert(a)
        s.setDefault("a")
        s.delete("a")
        assertNull(s.defaultPresetId.value)
        assertNull(PresetStore(file, scope).defaultPresetId.value)
        assertEquals(emptyList(), PresetStore(file, scope).presets.value)
    }

    @Test
    fun `corrupt file loads empty`() {
        Files.writeString(file, "garbage")
        assertEquals(emptyList(), PresetStore(file, scope).presets.value)
    }

    @Test
    fun `corrupt file is backed up before being overwritten`() {
        Files.writeString(file, "garbage")
        val s = PresetStore(file, scope)
        s.upsert(a)
        val bak = file.resolveSibling("presets.json.bak")
        assertEquals("garbage", Files.readString(bak))
        assertEquals(listOf(a), PresetStore(file, scope).presets.value)
        Files.writeString(file, "garbage2")
        PresetStore(file, scope)
        assertEquals("garbage2", Files.readString(bak))
    }
}
