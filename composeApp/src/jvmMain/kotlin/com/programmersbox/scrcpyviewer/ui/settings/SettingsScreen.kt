package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.window.defaultUseSystemTitleBar
import java.nio.file.Paths

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, files: SettingsFileDialogs, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    SettingsContent(
        state = state,
        onTheme = viewModel::setTheme,
        onUseSystemTitleBar = viewModel::setUseSystemTitleBar,
        onDefaultPreset = viewModel::setDefaultPreset,
        onUseBundledAdb = viewModel::useBundledAdb,
        onUseCustomAdb = viewModel::useCustomAdb,
        onBrowseAdb = { files.chooseAdbFile()?.let { viewModel.useCustomAdb(it.toString()) } },
        onBrowseCaptureDir = { files.chooseFolder(Paths.get(state.settings.captureDir))?.let { viewModel.setCaptureDir(it.toString()) } },
        modifier = modifier,
    )
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    onTheme: (ThemeMode) -> Unit,
    onUseSystemTitleBar: (Boolean) -> Unit,
    onDefaultPreset: (String?) -> Unit,
    onUseBundledAdb: () -> Unit,
    onUseCustomAdb: (String) -> Unit,
    onBrowseAdb: () -> Unit,
    onBrowseCaptureDir: () -> Unit,
    modifier: Modifier = Modifier,
    isLinux: Boolean = Os.isLinux,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            state.persistenceError?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(
                        "Settings can't be saved: $it", color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
            Section("Theme") {
                SingleChoiceSegmentedButtonRow {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = state.settings.themeMode == mode,
                            onClick = { onTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(mode.name) }
                    }
                }
            }
            val systemTitleBar = state.settings.useSystemTitleBar ?: defaultUseSystemTitleBar(isLinux)
            Section("Window", "Applies to windows opened after the change. Restart to apply it to this window.") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(checked = systemTitleBar, onCheckedChange = onUseSystemTitleBar)
                    Text("Use system title bar")
                }
                if (isLinux && !systemTitleBar) {
                    Text(
                        "Window can't be resized from its edges.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AdbSection(state, onUseBundledAdb, onUseCustomAdb, onBrowseAdb)
            Section("Default preset", "Applied to a device the first time it is selected.") {
                DefaultPresetDropdown(state, onDefaultPreset)
            }
            Section("Screenshots and recordings folder") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.settings.captureDir, onValueChange = {}, readOnly = true, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onBrowseCaptureDir) { Text("Choose…") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

@Composable
private fun AdbSection(state: SettingsUiState, onUseBundled: () -> Unit, onUseCustom: (String) -> Unit, onBrowse: () -> Unit) {
    val override = state.settings.adbPathOverride
    var draft by remember(override) { mutableStateOf(override.orEmpty()) }
    Section("adb") {
        Column {
            Row(
                Modifier.fillMaxWidth().selectable(selected = override == null, role = Role.RadioButton, onClick = onUseBundled),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = override == null, onClick = null)
                Text("Bundled (version ${state.bundledAdbVersion ?: "…"})", Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
            }
            Row(
                Modifier.fillMaxWidth().selectable(selected = override != null, role = Role.RadioButton, onClick = onBrowse),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = override != null, onClick = null)
                Text("Custom path", Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
            }
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, singleLine = true,
                placeholder = { Text("/path/to/adb") },
                isError = state.adbPathError != null,
                supportingText = {
                    val error = state.adbPathError
                    when {
                        error != null -> Text(error)
                        override != null -> Text("Version ${state.customAdbVersion ?: "…"}")
                    }
                },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onBrowse, modifier = Modifier.padding(top = 8.dp)) { Text("Browse…") }
            OutlinedButton(onClick = { onUseCustom(draft) }, modifier = Modifier.padding(top = 8.dp)) { Text("Use") }
        }
    }
}

@Composable
private fun DefaultPresetDropdown(state: SettingsUiState, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = state.presets.firstOrNull { it.id == state.defaultPresetId }?.name ?: "None"
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.widthIn(min = 240.dp)) {
            Text(label, Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("None") }, onClick = { open = false; onSelect(null) })
            state.presets.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; onSelect(p.id) })
            }
        }
    }
}
