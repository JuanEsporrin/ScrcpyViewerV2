package com.programmersbox.scrcpyviewer.state

import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.OptionValuesSerializer
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.presets.PresetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Path
import java.util.UUID

@Serializable
data class WorkingSet(
    @Serializable(with = OptionValuesSerializer::class) val values: OptionValues,
    val basePresetId: String?,
)

/**
 * Per-device option working sets, persisted to [file] (`devices.json`). A set remembers the preset it
 * started from; if that preset is deleted, the set is treated as having no preset. Writes are synchronous;
 * [scope] is reserved for the owner's lifecycle.
 */
@Suppress("unused")
class DeviceOptionsStore(file: Path, private val presets: PresetStore, scope: CoroutineScope) {
    private val store = JsonFile(file, MapSerializer(String.serializer(), WorkingSet.serializer())) { emptyMap() }
    private val lock = Any()
    private val sets = HashMap<String, MutableStateFlow<WorkingSet>>()

    init {
        store.load().forEach { (serial, ws) -> sets[serial] = MutableStateFlow(ws) }
    }

    /** Non-null when the file on disk could not be read or backed up; saving is then disabled. */
    val persistenceError: StateFlow<String?> get() = store.error

    /** First sight of [serial] seeds its set from the default preset, or empty. */
    fun workingSet(serial: String): StateFlow<WorkingSet> = synchronized(lock) { flowFor(serial) }

    fun edit(serial: String, values: OptionValues) = mutate(serial) { it.copy(values = values) }

    fun applyPreset(serial: String, presetId: String) {
        val preset = presets.presets.value.firstOrNull { it.id == presetId } ?: return
        mutate(serial) { WorkingSet(preset.options, preset.id) }
    }

    /** The base preset id if that preset still exists, else null. */
    fun effectiveBasePresetId(serial: String): String? = synchronized(lock) { basePreset(flowFor(serial).value)?.id }

    /** True when the values differ from the base preset's options (or from no options when there is no base preset). */
    fun isModified(serial: String): Boolean = synchronized(lock) {
        val ws = flowFor(serial).value
        ws.values != (basePreset(ws)?.options ?: OptionValues(emptyMap()))
    }

    /** Overwrites the base preset with the working values. No-op when the set has no (existing) base preset. */
    fun saveToBasePreset(serial: String) = synchronized(lock) {
        val ws = flowFor(serial).value
        val base = basePreset(ws) ?: return@synchronized
        presets.upsert(base.copy(options = ws.values))
    }

    fun saveAsNewPreset(serial: String, name: String): Preset = synchronized(lock) {
        val ws = flowFor(serial).value
        val preset = Preset(UUID.randomUUID().toString(), presets.uniqueName(name), options = ws.values)
        presets.upsert(preset)
        flowFor(serial).value = ws.copy(basePresetId = preset.id)
        persist()
        preset
    }

    private fun basePreset(ws: WorkingSet): Preset? = ws.basePresetId?.let { id -> presets.presets.value.firstOrNull { it.id == id } }

    private fun flowFor(serial: String): MutableStateFlow<WorkingSet> = sets.getOrPut(serial) {
        val default = presets.defaultPresetId.value?.let { id -> presets.presets.value.firstOrNull { it.id == id } }
        MutableStateFlow(WorkingSet(default?.options ?: OptionValues(emptyMap()), default?.id)).also {
            persistWith(serial, it)
        }
    }

    private fun mutate(serial: String, transform: (WorkingSet) -> WorkingSet) = synchronized(lock) {
        val f = flowFor(serial)
        f.value = transform(f.value)
        persist()
    }

    private fun persistWith(serial: String, f: MutableStateFlow<WorkingSet>) =
        store.save(sets.mapValues { it.value.value } + (serial to f.value))

    private fun persist() = store.save(sets.mapValues { it.value.value })
}
