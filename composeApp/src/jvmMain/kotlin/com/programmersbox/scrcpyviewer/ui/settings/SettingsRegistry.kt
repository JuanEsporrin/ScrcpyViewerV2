package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.runtime.Composable
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState

enum class SettingsCategoryId { Appearance, Mirroring, Adb, Updates, Help }

/** [Inline] puts the control right of the text; [Block] puts it full-width below. */
enum class EntryLayout { Inline, Block }

/** Everything an entry's control can read or call. */
class SettingsBindings(
    val state: SettingsUiState,
    val updates: UpdateUiState,
    val actions: SettingsActions,
    val isLinux: Boolean,
    val customTitleBarSupported: Boolean,
)

/** All Settings callbacks; every one defaults to a no-op so tests and previews pass only what they need. */
class SettingsActions(
    val onTheme: (ThemeMode) -> Unit = {},
    val onUseSystemTitleBar: (Boolean) -> Unit = {},
    val onDefaultPreset: (String?) -> Unit = {},
    val onUseBundledAdb: () -> Unit = {},
    val onUseCustomAdb: (String) -> Unit = {},
    val onBrowseAdb: () -> Unit = {},
    val onBrowseCaptureDir: () -> Unit = {},
    val onShowOnboarding: () -> Unit = {},
    val onCheckUpdates: () -> Unit = {},
    val onUpdate: () -> Unit = {},
    val onCancelUpdate: () -> Unit = {},
    val onSelectCategory: (SettingsCategoryId) -> Unit = {},
    val onQuery: (String) -> Unit = {},
)

/** One setting. [keywords] are searched but never shown. */
class SettingEntry(
    val id: String,
    val title: String,
    val description: String? = null,
    val keywords: List<String> = emptyList(),
    val layout: EntryLayout = EntryLayout.Inline,
    val content: @Composable (SettingsBindings) -> Unit = {},
)

class SettingsCategory(val id: SettingsCategoryId, val title: String, val entries: List<SettingEntry>)
