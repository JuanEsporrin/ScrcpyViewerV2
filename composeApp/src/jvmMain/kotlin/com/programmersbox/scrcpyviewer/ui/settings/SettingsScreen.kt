package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.ui.update.UpdateViewModel
import java.nio.file.Paths

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    updateViewModel: UpdateViewModel,
    files: SettingsFileDialogs,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val nav by viewModel.nav.collectAsState()
    val updates by updateViewModel.state.collectAsState()
    val actions = SettingsActions(
        onTheme = viewModel::setTheme,
        onUseSystemTitleBar = viewModel::setUseSystemTitleBar,
        onDefaultPreset = viewModel::setDefaultPreset,
        onUseBundledAdb = viewModel::useBundledAdb,
        onUseCustomAdb = viewModel::useCustomAdb,
        onBrowseAdb = { files.chooseAdbFile()?.let { viewModel.useCustomAdb(it.toString()) } },
        onBrowseCaptureDir = { files.chooseFolder(Paths.get(state.settings.captureDir))?.let { viewModel.setCaptureDir(it.toString()) } },
        onShowOnboarding = viewModel::showOnboarding,
        onCheckUpdates = updateViewModel::check,
        onUpdate = updateViewModel::update,
        onCancelUpdate = updateViewModel::cancel,
        onSelectCategory = viewModel::selectCategory,
        onQuery = viewModel::setQuery,
    )
    SettingsContent(state, updates, nav, actions, modifier)
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    updates: UpdateUiState,
    nav: SettingsNav,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    categories: List<SettingsCategory> = settingsCategories,
    isLinux: Boolean = Os.isLinux,
    customTitleBarSupported: Boolean = com.programmersbox.scrcpyviewer.ui.window.customTitleBarSupported,
) {
    val matches = searchSettings(categories, nav.query)
    val shown = nav.shown(matches.map { it.category.id })
    val match = matches.firstOrNull { it.category.id == shown }
    Row(modifier.fillMaxSize()) {
        SettingsListPane(
            matches = matches, selected = shown, query = nav.query, searching = nav.query.isNotBlank(),
            updateAvailable = updates.available != null, onQuery = actions.onQuery, onSelect = actions.onSelectCategory,
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsDetailPane(
            category = match?.category, entries = match?.entries.orEmpty(), query = nav.query,
            bindings = SettingsBindings(state, updates, actions, isLinux, customTitleBarSupported),
            modifier = Modifier.weight(1f),
        )
    }
}
