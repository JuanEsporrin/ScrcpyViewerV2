package com.programmersbox.scrcpyviewer.ui.settings

import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.presets.PresetStore
import com.programmersbox.scrcpyviewer.state.AppSettings
import com.programmersbox.scrcpyviewer.state.Settings
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

const val UNKNOWN_VERSION = "Unknown"

data class SettingsUiState(
    val settings: Settings = Settings(),
    val presets: List<Preset> = emptyList(),
    val defaultPresetId: String? = null,
    /** Version of the bundled adb; null while it is being read. */
    val bundledAdbVersion: String? = null,
    /** Version of the custom adb ([Settings.adbPathOverride]); null while being read or when there is none. */
    val customAdbVersion: String? = null,
    /** Why the last chosen custom adb path was rejected; the path is not saved. */
    val adbPathError: String? = null,
    val persistenceError: String? = null,
)

/** Error text when [path] cannot be used as the adb executable, else null. */
fun validateAdbPath(path: Path): String? = when {
    !Files.exists(path) -> "File not found: $path"
    !Files.isRegularFile(path) -> "Not a file: $path"
    !Files.isExecutable(path) -> "Not executable: $path"
    else -> null
}

/** "1.0.41 (35.0.2)" from `adb version` output; null when it has no recognizable version line. */
fun parseAdbVersion(output: String): String? {
    val lines = output.lines()
    val protocol = lines.firstNotNullOfOrNull { Regex("""Android Debug Bridge version (\S+)""").find(it)?.groupValues?.get(1) } ?: return null
    val build = lines.firstNotNullOfOrNull { Regex("""^Version (\d+(?:\.\d+)*)""").find(it.trim())?.groupValues?.get(1) }
    return if (build != null) "$protocol ($build)" else protocol
}

/**
 * [adbVersion] returns the parsed version of the adb at a path (null on failure); [bundledAdb] resolves the bundled
 * executable (may throw); [restartTracker] is called when the effective adb path changes.
 */
class SettingsViewModel(
    private val appSettings: AppSettings,
    private val presets: PresetStore,
    private val bundledAdb: () -> Path,
    private val adbVersion: suspend (Path) -> String?,
    private val restartTracker: () -> Unit,
    private val scope: CoroutineScope,
) {
    private val bundledVersion = MutableStateFlow<String?>(null)
    private val customVersion = MutableStateFlow<String?>(null)
    private val adbError = MutableStateFlow<String?>(null)

    val state: StateFlow<SettingsUiState> = combine(
        appSettings.settings, presets.presets, presets.defaultPresetId, bundledVersion, customVersion,
    ) { settings, list, defaultId, bundled, custom ->
        SettingsUiState(settings, list, defaultId, bundled, if (settings.adbPathOverride != null) custom else null)
    }.combine(adbError) { s, e -> s.copy(adbPathError = e) }
        .combine(appSettings.persistenceError) { s, e -> s.copy(persistenceError = e) }
        .stateIn(scope, SharingStarted.Eagerly, SettingsUiState())

    init {
        scope.launch { bundledVersion.value = versionOf { bundledAdb() } }
        appSettings.settings.value.adbPathOverride?.let(::loadCustomVersion)
    }

    fun setTheme(mode: ThemeMode) = appSettings.update { it.copy(themeMode = mode) }

    /** `null` clears the default preset. */
    fun setDefaultPreset(id: String?) = presets.setDefault(id)

    fun setCaptureDir(path: String) = appSettings.update { it.copy(captureDir = path) }

    /** Switches back to the bundled adb. */
    fun useBundledAdb() {
        adbError.value = null
        if (appSettings.settings.value.adbPathOverride == null) return
        appSettings.update { it.copy(adbPathOverride = null) }
        customVersion.value = null
        restartTracker()
    }

    /** Uses [path] as the adb executable if it is a runnable file; otherwise shows an error and keeps the current setting. */
    fun useCustomAdb(path: String) {
        val trimmed = path.trim()
        val error = if (trimmed.isEmpty()) "Enter the path to an adb executable" else validateAdbPath(Paths.get(trimmed))
        adbError.value = error
        if (error != null) return
        val changed = appSettings.settings.value.adbPathOverride != trimmed
        appSettings.update { it.copy(adbPathOverride = trimmed) }
        loadCustomVersion(trimmed)
        if (changed) restartTracker()
    }

    private fun loadCustomVersion(path: String) {
        customVersion.value = null
        scope.launch {
            val version = versionOf { Paths.get(path) }
            if (appSettings.settings.value.adbPathOverride == path) customVersion.value = version
        }
    }

    private suspend fun versionOf(path: () -> Path): String =
        runCatching { adbVersion(path()) }.getOrNull() ?: UNKNOWN_VERSION
}
