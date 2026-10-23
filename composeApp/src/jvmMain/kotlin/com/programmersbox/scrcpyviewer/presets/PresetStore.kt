package com.programmersbox.scrcpyviewer.presets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
private data class StoreFile(val presets: List<Preset> = emptyList(), val defaultPresetId: String? = null)

/**
 * Presets and the default preset id, persisted to [file]. Writes are synchronous (small file) so a
 * new instance always sees the latest state; [scope] is reserved for the owner's lifecycle.
 */
@Suppress("unused")
class PresetStore(private val file: Path, scope: CoroutineScope) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()

    private val _presets: MutableStateFlow<List<Preset>>
    private val _default: MutableStateFlow<String?>

    val presets: StateFlow<List<Preset>> get() = _presets
    val defaultPresetId: StateFlow<String?> get() = _default

    init {
        val loaded = load()
        _presets = MutableStateFlow(loaded.presets)
        _default = MutableStateFlow(loaded.defaultPresetId?.takeIf { id -> loaded.presets.any { it.id == id } })
    }

    fun upsert(p: Preset) = mutate {
        val list = _presets.value
        _presets.value = if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p
    }

    fun delete(id: String) = mutate {
        _presets.value = _presets.value.filterNot { it.id == id }
        if (_default.value == id) _default.value = null
    }

    fun duplicate(id: String): Preset {
        synchronized(lock) {
            val src = _presets.value.first { it.id == id }
            val copy = src.copy(id = UUID.randomUUID().toString(), name = uniqueCopyName(src.name))
            _presets.value += copy
            save()
            return copy
        }
    }

    fun setDefault(id: String?) = mutate {
        _default.value = id?.takeIf { want -> _presets.value.any { it.id == want } }
    }

    private fun uniqueCopyName(base: String): String {
        val names = _presets.value.map { it.name.lowercase() }.toSet()
        var n = 2
        while ("$base ($n)".lowercase() in names) n++
        return "$base ($n)"
    }

    private fun mutate(block: () -> Unit) = synchronized(lock) { block(); save() }

    private fun load(): StoreFile = try {
        if (Files.exists(file)) json.decodeFromString(StoreFile.serializer(), Files.readString(file)) else StoreFile()
    } catch (e: Exception) {
        StoreFile()
    }

    private fun save() {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(StoreFile.serializer(), StoreFile(_presets.value, _default.value)))
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
