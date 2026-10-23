package com.programmersbox.scrcpyviewer.state

import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.presets.PresetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceOptionsStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir = Files.createTempDirectory("devices")
    private val presets = PresetStore(dir.resolve("presets.json"), scope)
    private val file = dir.resolve("devices.json")
    private val optsA = OptionValues(mapOf("no-audio" to null, "max-size" to "800"))
    private val optsB = OptionValues(mapOf("max-size" to "1024"))
    private val a = Preset("a", "A", options = optsA)
    private val b = Preset("b", "B", options = optsB)

    private fun store() = DeviceOptionsStore(file, presets, scope)

    @Test
    fun `new serial seeded from default preset`() {
        presets.upsert(a)
        presets.setDefault("a")
        val ws = store().workingSet("S1").value
        assertEquals(WorkingSet(optsA, "a"), ws)
        assertFalse(store().isModified("S1"))
    }

    @Test
    fun `new serial without default is empty`() {
        assertEquals(WorkingSet(OptionValues(emptyMap()), null), store().workingSet("S1").value)
    }

    @Test
    fun `edit marks modified`() {
        presets.upsert(a)
        presets.setDefault("a")
        val s = store()
        s.workingSet("S1")
        s.edit("S1", optsA.with("max-size", "400"))
        assertTrue(s.isModified("S1"))
        assertEquals("400", s.workingSet("S1").value.values.entries["max-size"])
    }

    @Test
    fun `saveToBasePreset updates preset and clears modified`() {
        presets.upsert(a)
        presets.setDefault("a")
        val s = store()
        val edited = optsA.with("max-size", "400")
        s.workingSet("S1")
        s.edit("S1", edited)
        s.saveToBasePreset("S1")
        assertEquals(edited, presets.presets.value.single().options)
        assertFalse(s.isModified("S1"))
    }

    @Test
    fun `saveAsNewPreset picks a unique name`() {
        presets.upsert(a)
        val s = store()
        s.workingSet("S1")
        val first = s.saveAsNewPreset("S1", "a")
        val second = s.saveAsNewPreset("S1", "A")
        assertEquals("a (2)", first.name)
        assertEquals("A (3)", second.name)
        assertEquals(second.id, s.workingSet("S1").value.basePresetId)
        assertEquals(listOf("A", "a (2)", "A (3)"), presets.presets.value.map { it.name })
    }

    @Test
    fun `saveAsNewPreset rebases`() {
        presets.upsert(a)
        presets.setDefault("a")
        val s = store()
        val edited = optsA.with("max-size", "400")
        s.workingSet("S1")
        s.edit("S1", edited)
        val p = s.saveAsNewPreset("S1", "Mine")
        assertEquals("Mine", p.name)
        assertEquals(edited, p.options)
        assertEquals(p.id, s.workingSet("S1").value.basePresetId)
        assertFalse(s.isModified("S1"))
        assertEquals(optsA, presets.presets.value.first { it.id == "a" }.options)
    }

    @Test
    fun `applyPreset replaces values`() {
        presets.upsert(a)
        presets.upsert(b)
        val s = store()
        s.applyPreset("S1", "a")
        s.applyPreset("S1", "b")
        assertEquals(WorkingSet(optsB, "b"), s.workingSet("S1").value)
        assertFalse(s.isModified("S1"))
    }

    @Test
    fun `working sets persist per serial`() {
        presets.upsert(a)
        presets.upsert(b)
        val s = store()
        s.applyPreset("S1", "a")
        s.applyPreset("S2", "b")
        s.edit("S2", optsB.with("no-audio", null))
        val s2 = store()
        assertEquals(WorkingSet(optsA, "a"), s2.workingSet("S1").value)
        assertEquals(WorkingSet(optsB.with("no-audio", null), "b"), s2.workingSet("S2").value)
    }

    @Test
    fun `deleted base preset is treated as no preset`() {
        presets.upsert(a)
        val s = store()
        s.applyPreset("S1", "a")
        presets.delete("a")
        assertNull(s.effectiveBasePresetId("S1"))
        assertTrue(s.isModified("S1"))
        s.saveToBasePreset("S1")
        assertEquals(emptyList(), presets.presets.value)
        s.edit("S1", OptionValues(emptyMap()))
        assertFalse(s.isModified("S1"))
    }

    @Test
    fun `corrupt file is backed up and starts empty`() {
        Files.writeString(file, "{not json")
        val s = store()
        assertEquals(OptionValues(emptyMap()), s.workingSet("S1").value.values)
        assertTrue(Files.exists(dir.resolve("devices.json.bak")))
    }

    @Test
    fun `clearPreset keeps values and drops base preset`() {
        presets.upsert(a)
        val st = store()
        st.applyPreset("S1", "a")
        st.clearPreset("S1")
        assertEquals(WorkingSet(optsA, null), st.workingSet("S1").value)
        assertNull(st.effectiveBasePresetId("S1"))
        assertEquals(WorkingSet(optsA, null), store().workingSet("S1").value)
    }

    @Test
    fun `a stored option the catalog no longer has is dropped on load`() {
        Files.writeString(file, """{"S1": {"values": {"push-target": "/sdcard/", "max-size": "800"}, "basePresetId": null}}""")
        assertEquals(OptionValues(mapOf("max-size" to "800")), store().workingSet("S1").value.values)
    }
}
