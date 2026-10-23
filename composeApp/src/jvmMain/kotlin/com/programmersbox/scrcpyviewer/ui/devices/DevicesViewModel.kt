package com.programmersbox.scrcpyviewer.ui.devices

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.options.CommandLine
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionGroup
import com.programmersbox.scrcpyviewer.options.OptionSpec
import com.programmersbox.scrcpyviewer.options.OptionType
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.DeviceOptionsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DevicesUiState(
    val devices: List<Device> = emptyList(),
    val selectedSerial: String? = null,
    val query: String = "",
    val groupFilter: OptionGroup? = null,
    val visibleOptions: List<OptionSpec> = emptyList(),
    val values: OptionValues = OptionValues(emptyMap()),
    val errors: Map<String, String> = emptyMap(),
    /** "QA default", "QA default (modified)" or "No preset". */
    val presetLabel: String = "No preset",
    val command: String = "scrcpy",
    val canMirror: Boolean = false,
    val adbConnected: Boolean = false,
    /** True when the selected device's options started from a preset that still exists (Save is possible). */
    val hasBasePreset: Boolean = false,
    /** True when the working options differ from the base preset (or from nothing, with no base preset). */
    val isModified: Boolean = false,
)

private data class Selection(val devices: List<Device>, val connected: Boolean, val serial: String?)
private data class Filters(val query: String, val group: OptionGroup?)

class DevicesViewModel(
    devices: StateFlow<List<Device>>,
    connected: StateFlow<Boolean>,
    private val options: DeviceOptionsStore,
    private val presets: PresetStore,
    scope: CoroutineScope,
    private val mirrorRequests: MutableSharedFlow<String>,
) {
    private val userSelection = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val group = MutableStateFlow<OptionGroup?>(null)

    private val selection: StateFlow<Selection> = combine(devices, connected, userSelection) { list, conn, chosen ->
        val serial = chosen?.takeIf { s -> list.any { it.serial == s } }
            ?: list.firstOrNull { it.state == DeviceState.Device }?.serial
        Selection(list, conn, serial)
    }.stateIn(scope, SharingStarted.Eagerly, Selection(devices.value, connected.value, null))

    /** The serial shown as selected: the user's choice while it is connected, else the first ready device. */
    val selectedSerial: StateFlow<String?> = selection.map { it.serial }
        .stateIn(scope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val workingValues: Flow<OptionValues?> = selectedSerial.flatMapLatest { serial ->
        if (serial == null) flowOf(null) else options.workingSet(serial).map { it.values }
    }

    val state: StateFlow<DevicesUiState> = combine(
        selection,
        combine(query, group) { q, g -> Filters(q, g) },
        workingValues,
        presets.presets,
    ) { sel, filters, values, _ ->
        val serial = sel.serial
        val current = values ?: OptionValues(emptyMap())
        val errors = current.validate()
        DevicesUiState(
            devices = sel.devices,
            selectedSerial = serial,
            query = filters.query,
            groupFilter = filters.group,
            visibleOptions = filterOptions(filters.query, filters.group),
            values = current,
            errors = errors,
            presetLabel = if (serial == null) "No preset" else presetLabel(serial),
            command = CommandLine.build(serial, current),
            canMirror = canMirror(sel.devices, serial, errors),
            adbConnected = sel.connected,
            hasBasePreset = serial != null && options.effectiveBasePresetId(serial) != null,
            isModified = serial != null && options.isModified(serial),
        )
    }.stateIn(scope, SharingStarted.Eagerly, DevicesUiState(devices = devices.value, adbConnected = connected.value))

    fun select(serial: String) {
        userSelection.value = serial
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun setGroup(g: OptionGroup?) {
        group.value = g
    }

    /** Checking an option gives it a starting value; unchecking removes it. */
    fun toggle(flag: String, checked: Boolean) {
        val serial = selectedSerial.value ?: return
        val spec = OptionCatalog.byFlag(flag) ?: return
        val current = options.workingSet(serial).value.values
        options.edit(serial, if (checked) current.with(flag, initialValue(spec)) else current.without(flag))
    }

    fun setValue(flag: String, value: String) {
        val serial = selectedSerial.value ?: return
        val current = options.workingSet(serial).value.values
        if (flag in current.entries) options.edit(serial, current.with(flag, value))
    }

    fun save() {
        options.saveToBasePreset(selectedSerial.value ?: return)
    }

    fun saveAsNew(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        options.saveAsNewPreset(selectedSerial.value ?: return, trimmed)
    }

    /** Requests a mirror window for the selected device. Does nothing unless [DevicesUiState.canMirror]. */
    fun mirror() {
        if (state.value.canMirror) state.value.selectedSerial?.let { mirrorRequests.tryEmit(it) }
    }

    /** Double-click on a device row: select it and mirror it when it is ready and its options are valid. */
    fun openDevice(serial: String) {
        select(serial)
        val sel = selection.value
        val device = sel.devices.firstOrNull { it.serial == serial } ?: return
        val errors = options.workingSet(serial).value.values.validate()
        if (device.state == DeviceState.Device && errors.isEmpty()) mirrorRequests.tryEmit(serial)
    }

    private fun presetLabel(serial: String): String {
        val id = options.effectiveBasePresetId(serial) ?: return "No preset"
        val name = presets.presets.value.firstOrNull { it.id == id }?.name ?: return "No preset"
        return if (options.isModified(serial)) "$name (modified)" else name
    }

    private fun canMirror(devices: List<Device>, serial: String?, errors: Map<String, String>): Boolean =
        serial != null && errors.isEmpty() && devices.firstOrNull { it.serial == serial }?.state == DeviceState.Device

    private fun filterOptions(query: String, group: OptionGroup?): List<OptionSpec> {
        val q = query.trim()
        return OptionCatalog.all.filter { spec ->
            (group == null || spec.group == group) &&
                (q.isEmpty() || spec.label.contains(q, ignoreCase = true) || spec.flag.contains(q, ignoreCase = true))
        }
    }
}

/** "2 ready · 1 needs attention", with the second part left out when every device is ready. */
fun deviceSummary(devices: List<Device>): String {
    if (devices.isEmpty()) return "No devices"
    val ready = devices.count { it.state == DeviceState.Device }
    val attention = devices.size - ready
    val parts = buildList {
        if (ready > 0 || attention == 0) add("$ready ready")
        if (attention > 0) add(if (attention == 1) "1 needs attention" else "$attention need attention")
    }
    return parts.joinToString(" · ")
}

/** Model from the device's properties, else the model from the adb line (`_` as space), else the serial. */
fun deviceTitle(device: Device): String =
    device.info?.model?.takeIf { it.isNotBlank() }
        ?: device.model?.takeIf { it.isNotBlank() }?.replace('_', ' ')
        ?: device.serial

/** The value a freshly checked option starts with: null for flags and optional values, the first choice, else empty text. */
fun initialValue(spec: OptionSpec): String? = when (val type = spec.type) {
    OptionType.Flag -> null
    is OptionType.Choice -> type.values.first()
    is OptionType.OptionalValue -> null
    else -> ""
}
