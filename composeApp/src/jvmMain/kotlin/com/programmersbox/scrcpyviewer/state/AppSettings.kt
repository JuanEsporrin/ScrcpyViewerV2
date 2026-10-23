package com.programmersbox.scrcpyviewer.state

import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.System,
    val adbPathOverride: String? = null,
    /** Resolved when absent from the file: `~/Movies/ScrcpyViewer` on macOS, `~/Videos/ScrcpyViewer` elsewhere. */
    val captureDir: String = defaultCaptureDir(),
)

internal fun defaultCaptureDir(): String {
    val home = Paths.get(System.getProperty("user.home"))
    val mac = System.getProperty("os.name").lowercase().contains("mac")
    return home.resolve(if (mac) "Movies" else "Videos").resolve("ScrcpyViewer").toString()
}

/** App settings persisted to [file] (`settings.json`); defaults when the file is missing or corrupt. */
class AppSettings(file: Path) {
    private val store = JsonFile(file, Settings.serializer()) { Settings() }
    private val lock = Any()
    private val _settings = MutableStateFlow(store.load())

    val settings: StateFlow<Settings> get() = _settings

    /** Non-null when the file on disk could not be read or backed up; saving is then disabled. */
    val persistenceError: StateFlow<String?> get() = store.error

    fun update(transform: (Settings) -> Settings) = synchronized(lock) {
        _settings.value = transform(_settings.value)
        store.save(_settings.value)
    }
}
