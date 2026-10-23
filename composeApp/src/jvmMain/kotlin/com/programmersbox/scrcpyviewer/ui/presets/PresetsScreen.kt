package com.programmersbox.scrcpyviewer.ui.presets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.options.OptionCatalog
import com.programmersbox.scrcpyviewer.options.OptionGroup
import com.programmersbox.scrcpyviewer.options.OptionValues
import com.programmersbox.scrcpyviewer.presets.Preset
import com.programmersbox.scrcpyviewer.ui.common.CopyButton
import com.programmersbox.scrcpyviewer.ui.common.LocalSnackbarHostState
import com.programmersbox.scrcpyviewer.ui.common.OptionRow
import com.programmersbox.scrcpyviewer.ui.devices.initialValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files

private val ToolbarPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

/** Where Apply is unavailable, this explains why. */
const val APPLY_DISABLED_HINT = "Select a device on the Devices page"

@Composable
fun PresetsScreen(viewModel: PresetsViewModel, files: PresetFileDialogs, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val import by viewModel.importState.collectAsState()
    val snackbar = LocalSnackbarHostState.current
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar?.showSnackbar(it) } }
    var pasting by remember { mutableStateOf(false) }

    /** Runs [block]; shows the failure in the snackbar instead of crashing the UI on a file error. */
    suspend fun reportErrors(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            snackbar?.showSnackbar("File error: ${e.message ?: e::class.simpleName}")
        }
    }

    val scope = rememberCoroutineScope()
    fun export(ids: List<String>, suggested: String) {
        val path = files.chooseExportFile(suggested) ?: return
        scope.launchIo { reportErrors { Files.writeString(path, viewModel.exportJson(ids)) } }
    }

    PresetsContent(
        state = state,
        onSelect = viewModel::select,
        onNew = viewModel::new,
        onEdit = viewModel::edit,
        onDuplicate = viewModel::duplicate,
        onDelete = viewModel::delete,
        onSetDefault = viewModel::setDefault,
        onApply = { viewModel.applyToSelectedDevice(it) },
        onExport = { p -> export(listOf(p.id), exportFileName(p.name)) },
        onExportAll = { export(state.presets.map { it.id }, exportFileName(null)) },
        onImportFile = {
            val path = files.chooseImportFile()
            if (path != null) scope.launchIo { reportErrors { viewModel.beginImport(Files.readString(path)) } }
        },
        onImportPaste = { pasting = true },
        modifier = modifier,
    )
    if (pasting) PasteJsonDialog(onDismiss = { pasting = false }, onImport = { pasting = false; viewModel.beginImport(it) })
    import?.let {
        ImportDialog(it, viewModel::setChoice, viewModel::setInclude, viewModel::confirmImport, viewModel::cancelImport)
    }
}

private fun CoroutineScope.launchIo(block: suspend () -> Unit) {
    launch(Dispatchers.IO) { block() }
}

/** Stateless Presets screen (Concept C: preset list, detail with option rows and exported file). */
@Composable
fun PresetsContent(
    state: PresetsUiState,
    onSelect: (String) -> Unit,
    onNew: () -> Preset,
    onEdit: (String, String, String, OptionValues) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSetDefault: (String?) -> Unit,
    onApply: (String) -> Unit,
    onExport: (Preset) -> Unit,
    onExportAll: () -> Unit,
    onImportFile: () -> Unit,
    onImportPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize()) {
        state.persistenceError?.let { ErrorBanner("Presets can't be saved: $it") }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            PresetList(
                state, onSelect = { editingId = null; onSelect(it) },
                onNew = { editingId = onNew().id }, onExportAll = onExportAll,
                onImportFile = onImportFile, onImportPaste = onImportPaste,
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            val selected = state.selected
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    selected == null -> Text(
                        "No presets yet. Create one with + New preset, or import a file.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    editingId == selected.id -> PresetEditor(
                        selected,
                        onSave = { name, description, values -> onEdit(selected.id, name, description, values); editingId = null },
                        onCancel = { editingId = null },
                    )
                    else -> PresetDetail(
                        selected, state, onEdit = { editingId = selected.id },
                        onDuplicate = onDuplicate, onDelete = onDelete, onSetDefault = onSetDefault,
                        onApply = onApply, onExport = onExport,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

@Composable
private fun PresetList(
    state: PresetsUiState,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onExportAll: () -> Unit,
    onImportFile: () -> Unit,
    onImportPaste: () -> Unit,
) {
    Column(
        Modifier.width(390.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        var importMenu by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onNew, contentPadding = ToolbarPadding) { Text("+ New preset", maxLines = 1) }
            Box {
                OutlinedButton({ importMenu = true }, contentPadding = ToolbarPadding) { Text("Import") }
                DropdownMenu(importMenu, { importMenu = false }) {
                    DropdownMenuItem(text = { Text("From file…") }, onClick = { importMenu = false; onImportFile() })
                    DropdownMenuItem(text = { Text("Paste JSON…") }, onClick = { importMenu = false; onImportPaste() })
                }
            }
            OutlinedButton(onExportAll, enabled = state.presets.isNotEmpty(), contentPadding = ToolbarPadding) { Text("Export all", maxLines = 1) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.presets.forEach { preset ->
                PresetRow(preset, selected = preset.id == state.selected?.id, isDefault = preset.id == state.defaultId) { onSelect(preset.id) }
            }
        }
    }
}

@Composable
private fun PresetRow(preset: Preset, selected: Boolean, isDefault: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val n = preset.options.entries.size
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (isDefault) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = null,
            tint = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(preset.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$n ${if (n == 1) "option" else "options"}" + if (isDefault) " · default for new devices" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PresetDetail(
    preset: Preset,
    state: PresetsUiState,
    onEdit: () -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSetDefault: (String?) -> Unit,
    onApply: (String) -> Unit,
    onExport: (Preset) -> Unit,
) {
    var confirmDelete by remember(preset.id) { mutableStateOf(false) }
    val isDefault = preset.id == state.defaultId
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(preset.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.width(6.dp))
            if (isDefault) FilterChip(true, { onSetDefault(null) }, label = { Text("Default") })
            else AssistChip({ onSetDefault(preset.id) }, label = { Text("Set as default") })
            Box(Modifier.weight(1f))
            TextButton(onEdit) { Text("Edit") }
            TextButton({ onDuplicate(preset.id) }) { Text("Duplicate") }
            TextButton({ onExport(preset) }) { Text("Export") }
            TextButton({ confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            Button({ onApply(preset.id) }, enabled = state.canApply) { Text("Apply") }
        }
        if (!state.canApply) {
            Text(APPLY_DISABLED_HINT, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
        }
        if (preset.description.isNotBlank()) {
            Text(preset.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlagGrid(preset.options)
        Text("EXPORTED FILE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        JsonPanel(state.selectedJson)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${preset.name}?") },
            text = { Text("Devices that started from this preset keep their current options.") },
            confirmButton = { TextButton({ confirmDelete = false; onDelete(preset.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FlagGrid(values: OptionValues) {
    if (values.entries.isEmpty()) {
        Text("No options. Use Edit to add some.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val order = OptionCatalog.all.withIndex().associate { it.value.flag to it.index }
    val rows = values.entries.entries.sortedBy { order[it.key] ?: Int.MAX_VALUE }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { (flag, value) ->
                    val label = OptionCatalog.byFlag(flag)?.label ?: flag
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (value == null) "--$flag" else "--$flag=$value",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun JsonPanel(json: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Box {
            SelectionContainer {
                Text(
                    json,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                )
            }
            CopyButton(text = { json }, label = "Copy JSON", modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
    }
}

@Composable
private fun PresetEditor(preset: Preset, onSave: (String, String, OptionValues) -> Unit, onCancel: () -> Unit) {
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var description by remember(preset.id) { mutableStateOf(preset.description) }
    var values by remember(preset.id) { mutableStateOf(preset.options) }
    val errors = values.validate()
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Edit preset", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onCancel) { Text("Cancel") }
            Button({ onSave(name, description, values) }, enabled = name.isNotBlank() && errors.isEmpty()) { Text("Save") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, isError = name.isBlank(), modifier = Modifier.weight(1f))
            OutlinedTextField(description, { description = it }, singleLine = true, label = { Text("Description") }, modifier = Modifier.weight(2f))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            OptionGroup.entries.forEach { group ->
                val specs = OptionCatalog.all.filter { it.group == group }
                if (specs.isEmpty()) return@forEach
                Text(group.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                specs.forEach { spec ->
                    OptionRow(
                        spec = spec,
                        fieldKey = preset.id,
                        checked = spec.flag in values.entries,
                        value = values.entries[spec.flag],
                        error = errors[spec.flag],
                        onCheckedChange = { on -> values = if (on) values.with(spec.flag, initialValue(spec)) else values.without(spec.flag) },
                        onValueChange = { v -> if (spec.flag in values.entries) values = values.with(spec.flag, v) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PasteJsonDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste JSON") },
        text = {
            OutlinedTextField(
                text, { text = it },
                placeholder = { Text("Paste the contents of a .scrcpypresets.json file") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
        },
        confirmButton = { TextButton({ onImport(text) }, enabled = text.isNotBlank()) { Text("Import") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
