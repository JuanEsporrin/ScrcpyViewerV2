package com.programmersbox.scrcpyviewer.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One JSON document on disk with safe loading and atomic writes. A corrupt file is moved to `.bak` and
 * [load] returns the default; if it cannot be read or backed up, [error] is set and [save] becomes a
 * no-op so the unreadable file is never overwritten.
 */
internal class JsonFile<T>(
    private val file: Path,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _error = MutableStateFlow<String?>(null)

    /** Non-null when the file on disk could not be read or backed up; persisting is then disabled. */
    val error: StateFlow<String?> get() = _error

    fun load(): T {
        if (!Files.exists(file)) return default()
        val text = try {
            Files.readString(file)
        } catch (e: IOException) {
            _error.value = "Could not read ${file.fileName}: ${e.message}"
            return default()
        }
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            backUpCorrupt(e)
        } catch (e: IllegalArgumentException) {
            backUpCorrupt(e)
        }
    }

    fun save(value: T) {
        if (_error.value != null) return
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(serializer, value))
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun backUpCorrupt(cause: Exception): T {
        try {
            Files.move(file, file.resolveSibling(file.fileName.toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            _error.value = "${file.fileName} is unreadable (${cause.message}) and could not be backed up: ${e.message}"
        }
        return default()
    }
}
