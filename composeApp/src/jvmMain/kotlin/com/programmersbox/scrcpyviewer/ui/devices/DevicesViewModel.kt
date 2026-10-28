package com.programmersbox.scrcpyviewer.ui.devices

import com.programmersbox.scrcpyviewer.adb.Device
import com.programmersbox.scrcpyviewer.adb.AdbOutcome
import com.programmersbox.scrcpyviewer.adb.DeviceState
import com.programmersbox.scrcpyviewer.adb.SwitchResult
import com.programmersbox.scrcpyviewer.adb.WirelessAdb
import com.programmersbox.scrcpyviewer.adb.optionsKey
import com.programmersbox.scrcpyviewer.options.CommandLine
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionGroup
import com.programmersbox.scrcpyviewer.options.OptionSpec
import com.programmersbox.scrcpyviewer.options.OptionType
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.DeviceOptionsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    /** Every preset, for the footer's preset menu. */
    val presets: List<Preset> = emptyList(),
    /** The selected device's base preset (still existing), or null for "No preset". */
    val basePresetId: String? = null,
    /** Why the device options (`devices.json`) cannot be saved, or null. */
    val persistenceError: String? = null,
    /** Serials currently being switched from USB to Wi-Fi. */
    val switching: Set<String> = emptySet(),
)

/** A snackbar message from a Wi-Fi action; [mirrorSerial] adds a Mirror action for that device. */
data class WifiNotice(val text: String, val mirrorSerial: String? = null)

private data class Selection(
    val devices: List<Device>,
    val connected: Boolean,
    val serial: String?,
    /** Where the selected device's options are stored (see [optionsKey]). */
    val key: String?,
    val switching: Set<String>,
)
private data class Filters(val query: String, val group: OptionGroup?)

class DevicesViewModel(
    devices: StateFlow<List<Device>>,
    connected: StateFlow<Boolean>,
    private val options: DeviceOptionsStore,
    private val presets: PresetStore,
    private val scope: CoroutineScope,
    private val mirrorRequests: MutableSharedFlow<String>,
    private val wireless: WirelessAdb,
) {
    private val switching = MutableStateFlow<Set<String>>(emptySet())
    private val _notices = MutableSharedFlow<WifiNotice>(extraBufferCapacity = 8)

    /** Results of Wi-Fi actions, for the snackbar. */
    val notices: SharedFlow<WifiNotice> = _notices.asSharedFlow()

    private val userSelection = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val group = MutableStateFlow<OptionGroup?>(null)

    private val selection: StateFlow<Selection> = combine(devices, connected, userSelection, switching) { list, conn, chosen, busy ->
        val device = chosen?.let { s -> list.firstOrNull { it.serial == s } }
            ?: list.firstOrNull { it.state == DeviceState.Device }
        Selection(list, conn, device?.serial, device?.optionsKey(), busy)
    }.stateIn(scope, SharingStarted.Eagerly, Selection(devices.value, connected.value, null, null, emptySet()))

    /** The serial shown as selected: the user's choice while it is connected, else the first ready device. */
    val selectedSerial: StateFlow<String?> = selection.map { it.serial }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The key the selected device's options are stored under: shared by a phone's USB and Wi-Fi connections. */
    val selectedOptionsKey: StateFlow<String?> = selection.map { it.key }
        .stateIn(scope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val workingValues: Flow<OptionValues?> = selectedOptionsKey.flatMapLatest { key ->
        if (key == null) flowOf(null) else options.workingSet(key).map { it.values }
    }

    val state: StateFlow<DevicesUiState> = combine(
        selection,
        combine(query, group) { q, g -> Filters(q, g) },
        workingValues,
        presets.presets,
        options.persistenceError,
    ) { sel, filters, values, allPresets, persistenceError ->
        val serial = sel.serial
        val key = sel.key
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
            presetLabel = if (key == null) "No preset" else presetLabel(key),
            command = CommandLine.build(serial, current),
            canMirror = canMirror(sel.devices, serial, errors),
            adbConnected = sel.connected,
            hasBasePreset = key != null && options.effectiveBasePresetId(key) != null,
            isModified = key != null && options.isModified(key),
            presets = allPresets,
            basePresetId = key?.let { options.effectiveBasePresetId(it) },
            persistenceError = persistenceError,
            switching = sel.switching,
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
        val key = selectedOptionsKey.value ?: return
        val spec = OptionCatalog.byFlag(flag) ?: return
        val current = options.workingSet(key).value.values
        options.edit(key, if (checked) current.with(flag, initialValue(spec)) else current.without(flag))
    }

    fun setValue(flag: String, value: String) {
        val key = selectedOptionsKey.value ?: return
        val current = options.workingSet(key).value.values
        if (flag in current.entries) options.edit(key, current.with(flag, value))
    }

    /** Switches the selected device's options to [presetId]; `null` keeps the values and detaches from any preset. */
    fun applyPreset(presetId: String?) {
        val key = selectedOptionsKey.value ?: return
        if (presetId == null) options.clearPreset(key) else options.applyPreset(key, presetId)
    }

    fun save() {
        options.saveToBasePreset(selectedOptionsKey.value ?: return)
    }

    fun saveAsNew(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        options.saveAsNewPreset(selectedOptionsKey.value ?: return, trimmed)
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
        val errors = options.workingSet(device.optionsKey()).value.values.validate()
        if (device.state == DeviceState.Device && errors.isEmpty()) mirrorRequests.tryEmit(serial)
    }

    /** Whether [serial] can be mirrored now: it is ready and its options are valid (the right-click Mirror item). */
    fun canMirror(serial: String): Boolean {
        val device = state.value.devices.firstOrNull { it.serial == serial } ?: return false
        return device.state == DeviceState.Device && options.workingSet(device.optionsKey()).value.values.validate().isEmpty()
    }

    /** Right-click "Connect over Wi-Fi": `tcpip` over USB, then `adb connect`. Ignored while [serial] is switching. */
    fun connectOverWifi(serial: String) {
        val device = state.value.devices.firstOrNull { it.serial == serial } ?: return
        var started = false
        switching.update { if (serial in it) it else { started = true; it + serial } }
        if (!started) return
        val name = deviceTitle(device)
        scope.launch {
            try {
                val notice = when (val result = wireless.switchToWifi(serial)) {
                    is SwitchResult.Connected -> WifiNotice("$name connected at ${result.address}", result.address)
                    SwitchResult.NotOnWifi -> WifiNotice("$name isn't on Wi-Fi. Connect it to the same network as this computer.")
                    is SwitchResult.TcpipFailed -> WifiNotice("Couldn't switch $name to Wi-Fi: ${result.message}")
                    is SwitchResult.ConnectFailed -> WifiNotice("Couldn't connect to ${result.address}: ${result.message}")
                }
                _notices.emit(notice)
            } finally {
                switching.update { it - serial }
            }
        }
    }

    /** Right-click "Disconnect" on a Wi-Fi device. */
    fun disconnect(serial: String) {
        val name = state.value.devices.firstOrNull { it.serial == serial }?.let(::deviceTitle) ?: serial
        scope.launch {
            val result = wireless.disconnect(serial)
            if (result is AdbOutcome.Failed) _notices.emit(WifiNotice("Couldn't disconnect $name: ${result.message}"))
        }
    }

    /** Shows [notice] in the snackbar (the pairing dialog reports its result this way). */
    fun notify(notice: WifiNotice) {
        _notices.tryEmit(notice)
    }

    private fun presetLabel(key: String): String {
        val id = options.effectiveBasePresetId(key) ?: return "No preset"
        val name = presets.presets.value.firstOrNull { it.id == id }?.name ?: return "No preset"
        return if (options.isModified(key)) "$name (modified)" else name
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

/** True when [device] is a USB device that also has a Wi-Fi connection in [devices]. */
fun isOnWifi(device: Device, devices: List<Device>): Boolean =
    !device.isTcp && devices.any { it.isTcp && it.optionsKey() == device.optionsKey() }

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
