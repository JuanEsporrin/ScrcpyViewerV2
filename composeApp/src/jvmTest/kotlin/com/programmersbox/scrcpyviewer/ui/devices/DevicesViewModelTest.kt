package com.programmersbox.scrcpyviewer.ui.devices

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceInfo
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.options.OptionGroup
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.DeviceOptionsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevicesViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir = Files.createTempDirectory("devices-vm")
    private val presets = PresetStore(dir.resolve("presets.json"), scope)
    private val options = DeviceOptionsStore(dir.resolve("devices.json"), presets, scope)

    private fun device(serial: String, state: DeviceState = DeviceState.Device, model: String? = null, info: DeviceInfo? = null) =
        Device(serial, state, state.name.lowercase(), model, null, null, false, info)

    private val ready = device("R1")
    private val readyToo = device("R2")
    private val unauthorized = device("U1", DeviceState.Unauthorized)

    private val devices = MutableStateFlow(listOf(unauthorized, ready, readyToo))
    private val connected = MutableStateFlow(true)
    private val mirrorRequests = MutableSharedFlow<String>(replay = 8)

    private fun vm() = DevicesViewModel(devices, connected, options, presets, scope, mirrorRequests)

    @Test
    fun `first ready device auto-selected`() {
        val vm = vm()
        assertEquals("R1", vm.state.value.selectedSerial)
        assertEquals("R1", vm.selectedSerial.value)
        assertTrue(vm.state.value.canMirror)
    }

    @Test
    fun `selection is kept and falls back when device disappears`() {
        val vm = vm()
        vm.select("R2")
        assertEquals("R2", vm.state.value.selectedSerial)
        devices.value = listOf(ready)
        assertEquals("R1", vm.state.value.selectedSerial)
        devices.value = emptyList()
        assertNull(vm.state.value.selectedSerial)
        assertFalse(vm.state.value.canMirror)
    }

    @Test
    fun `selecting unauthorized device disables mirror`() {
        val vm = vm()
        vm.select("U1")
        assertEquals("U1", vm.state.value.selectedSerial)
        assertFalse(vm.state.value.canMirror)
        vm.mirror()
        assertEquals(emptyList(), mirrorRequests.replayCache)
    }

    @Test
    fun `mirror emits serial of selected device and openDevice selects first`() {
        val vm = vm()
        vm.mirror()
        vm.openDevice("R2")
        assertEquals(listOf("R1", "R2"), mirrorRequests.replayCache)
        assertEquals("R2", vm.state.value.selectedSerial)
    }

    @Test
    fun `search filters by label and flag`() {
        val vm = vm()
        vm.setQuery("touch")
        val flags = vm.state.value.visibleOptions.map { it.flag }
        assertTrue("show-touches" in flags)
        assertTrue("no-audio" !in flags)
        vm.setQuery("NO AUDIO")
        assertTrue("no-audio" in vm.state.value.visibleOptions.map { it.flag })
        vm.setQuery("")
        assertTrue(vm.state.value.visibleOptions.size > 50)
    }

    @Test
    fun `group filter`() {
        val vm = vm()
        vm.setGroup(OptionGroup.Audio)
        val visible = vm.state.value.visibleOptions
        assertTrue(visible.isNotEmpty())
        assertTrue(visible.all { it.group == OptionGroup.Audio })
        vm.setGroup(null)
        assertTrue(vm.state.value.visibleOptions.any { it.group != OptionGroup.Audio })
    }

    @Test
    fun `toggle updates command`() {
        val vm = vm()
        assertEquals("scrcpy -s R1", vm.state.value.command)
        vm.toggle("no-audio", true)
        assertEquals("scrcpy -s R1 --no-audio", vm.state.value.command)
        vm.toggle("max-size", true)
        vm.setValue("max-size", "1024")
        assertEquals("scrcpy -s R1 --max-size=1024 --no-audio", vm.state.value.command)
        vm.toggle("no-audio", false)
        assertEquals("scrcpy -s R1 --max-size=1024", vm.state.value.command)
        assertTrue(vm.state.value.canMirror)
    }

    @Test
    fun `toggling a choice option selects its first value`() {
        val vm = vm()
        vm.toggle("video-codec", true)
        assertEquals("h264", vm.state.value.values.entries["video-codec"])
        assertTrue(vm.state.value.errors.isEmpty())
    }

    @Test
    fun `invalid value disables mirror and shows error`() {
        val vm = vm()
        vm.toggle("max-size", true)
        assertFalse(vm.state.value.canMirror)
        assertTrue("max-size" in vm.state.value.errors)
        vm.setValue("max-size", "abc")
        assertFalse(vm.state.value.canMirror)
        assertEquals("Expected a whole number", vm.state.value.errors["max-size"])
        vm.setValue("max-size", "800")
        assertTrue(vm.state.value.errors.isEmpty())
        assertTrue(vm.state.value.canMirror)
    }

    @Test
    fun `preset label shows modified`() {
        presets.upsert(Preset("q", "QA default", options = OptionValues(mapOf("no-audio" to null))))
        presets.setDefault("q")
        val vm = vm()
        assertEquals("QA default", vm.state.value.presetLabel)
        assertTrue(vm.state.value.hasBasePreset)
        vm.toggle("show-touches", true)
        assertEquals("QA default (modified)", vm.state.value.presetLabel)
        vm.save()
        assertEquals("QA default", vm.state.value.presetLabel)
    }

    @Test
    fun `no preset label and save as new`() {
        val vm = vm()
        assertEquals("No preset", vm.state.value.presetLabel)
        assertFalse(vm.state.value.hasBasePreset)
        vm.toggle("no-audio", true)
        vm.saveAsNew("Mine")
        assertEquals("Mine", vm.state.value.presetLabel)
        assertEquals(1, presets.presets.value.size)
    }

    @Test
    fun `summary and titles`() {
        assertEquals("2 ready · 1 needs attention", deviceSummary(listOf(ready, readyToo, unauthorized)))
        assertEquals("1 ready · 2 need attention", deviceSummary(listOf(ready, unauthorized, device("O", DeviceState.Offline))))
        assertEquals("1 ready", deviceSummary(listOf(ready)))
        assertEquals("No devices", deviceSummary(emptyList()))
        assertEquals("Pixel 8", deviceTitle(device("S", model = "Pixel_6", info = DeviceInfo("Pixel 8", "14", 34))))
        assertEquals("Pixel 6", deviceTitle(device("S", model = "Pixel_6")))
        assertEquals("S", deviceTitle(device("S")))
    }

    @Test
    fun `openDevice on unauthorized selects but emits nothing`() {
        val vm = vm()
        vm.openDevice("U1")
        assertEquals("U1", vm.state.value.selectedSerial)
        assertEquals(emptyList(), mirrorRequests.replayCache)
    }

    @Test
    fun `openDevice on offline device emits nothing`() {
        devices.value = devices.value + device("O1", DeviceState.Offline)
        val vm = vm()
        vm.openDevice("O1")
        assertEquals("O1", vm.state.value.selectedSerial)
        assertEquals(emptyList(), mirrorRequests.replayCache)
    }

    @Test
    fun `invalid value blocks openDevice and mirror`() {
        val vm = vm()
        vm.toggle("max-size", true)
        vm.setValue("max-size", "abc")
        vm.mirror()
        vm.openDevice("R1")
        assertEquals(emptyList(), mirrorRequests.replayCache)
    }

    @Test
    fun `isModified follows edits`() {
        presets.upsert(Preset("q", "QA", options = OptionValues(mapOf("no-audio" to null))))
        presets.setDefault("q")
        val vm = vm()
        assertFalse(vm.state.value.isModified)
        vm.toggle("show-touches", true)
        assertTrue(vm.state.value.isModified)
    }

    private val qaPreset = Preset("q", "QA default", options = OptionValues(mapOf("no-audio" to null)))
    private val recording = Preset("r", "recording", options = OptionValues(mapOf("record" to "out.mp4")))

    @Test
    fun `applyPreset swaps values and label and is not modified`() {
        presets.upsert(qaPreset)
        presets.upsert(recording)
        val vm = vm()
        vm.applyPreset("q")
        vm.toggle("show-touches", true)
        assertTrue(vm.state.value.isModified)
        vm.applyPreset("r")
        val st = vm.state.value
        assertEquals(recording.options, st.values)
        assertEquals("recording", st.presetLabel)
        assertEquals("r", st.basePresetId)
        assertFalse(st.isModified)
        assertEquals(listOf("QA default", "recording"), st.presets.map { it.name })
    }

    @Test
    fun `applyPreset null keeps values and shows No preset`() {
        presets.upsert(qaPreset)
        val vm = vm()
        vm.applyPreset("q")
        vm.applyPreset(null)
        val st = vm.state.value
        assertEquals(qaPreset.options, st.values)
        assertEquals("No preset", st.presetLabel)
        assertNull(st.basePresetId)
        assertFalse(st.hasBasePreset)
    }

    @Test
    fun `applyPreset does nothing with no selection`() {
        presets.upsert(qaPreset)
        devices.value = emptyList()
        val vm = vm()
        vm.applyPreset("q")
        assertNull(vm.state.value.selectedSerial)
        assertEquals("No preset", vm.state.value.presetLabel)
        devices.value = listOf(ready)
        assertEquals(OptionValues(emptyMap()), vm.state.value.values)
    }

    @Test
    fun `a devices json that cannot be saved shows in the state`() {
        // A file where the data directory should be: every save fails.
        val blocker = Files.writeString(dir.resolve("blocker"), "x")
        val broken = DeviceOptionsStore(blocker.resolve("devices.json"), presets, scope)
        val vm = DevicesViewModel(devices, connected, broken, presets, scope, mirrorRequests)
        vm.toggle("no-audio", true)
        assertTrue(vm.state.value.persistenceError?.contains("devices.json") == true, "${vm.state.value.persistenceError}")
    }
}
