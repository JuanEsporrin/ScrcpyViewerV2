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
}
