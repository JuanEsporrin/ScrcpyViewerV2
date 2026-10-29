package com.programmersbox.scrcpyviewer.ui.settings

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.programmersbox.scrcpyviewer.ui.theme.ThemeMode
import com.programmersbox.scrcpyviewer.ui.update.UpdateUiState
import com.programmersbox.scrcpyviewer.ui.update.UpdatesContent

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

/** One setting. [keywords] are searched but never shown; [notes] are conditional hints shown under the description. */
class SettingEntry(
    val id: String,
    val title: String,
    val description: String? = null,
    val keywords: List<String> = emptyList(),
    val layout: EntryLayout = EntryLayout.Inline,
    val notes: @Composable (SettingsBindings) -> Unit = {},
    val content: @Composable (SettingsBindings) -> Unit = {},
)

class SettingsCategory(val id: SettingsCategoryId, val title: String, val entries: List<SettingEntry>)

/** Every Settings category and setting. The list pane, the detail pane and search all read this. */
val settingsCategories: List<SettingsCategory> = listOf(
    SettingsCategory(
        SettingsCategoryId.Appearance, "Appearance",
        listOf(
            SettingEntry(
                "theme", "Theme", "Light, dark, or follow the system.",
                keywords = listOf("dark mode", "light", "color", "colour"),
                content = { ThemeControl(it) },
            ),
            SettingEntry(
                "title-bar", "Use system title bar",
                "Applies to windows opened after the change. Restart to apply it to this window.",
                keywords = listOf("window", "frame", "decorations", "chrome"),
                notes = { TitleBarNotes(it) },
                content = { TitleBarSwitch(it) },
            ),
        ),
    ),
    SettingsCategory(
        SettingsCategoryId.Mirroring, "Mirroring",
        listOf(
            SettingEntry(
                "default-preset", "Default preset", "Applied to a device the first time it is selected.",
                keywords = listOf("preset", "profile", "defaults"),
                content = { DefaultPresetControl(it) },
            ),
            SettingEntry(
                "capture-dir", "Screenshots and recordings folder", "Where screenshots and screen recordings are saved.",
                keywords = listOf("screenshot", "recording", "capture", "save", "directory", "path"),
                layout = EntryLayout.Block,
                content = { CaptureDirControl(it) },
            ),
        ),
    ),
    SettingsCategory(
        SettingsCategoryId.Adb, "adb",
        listOf(
            SettingEntry(
                "adb-path", "adb executable", "Use the bundled adb or point to your own.",
                keywords = listOf("android debug bridge", "platform-tools", "binary", "path", "custom"),
                layout = EntryLayout.Block,
                content = { AdbPicker(it) },
            ),
        ),
    ),
    SettingsCategory(
        SettingsCategoryId.Updates, "Updates",
        listOf(
            SettingEntry(
                "updates", "App updates", "Check for new versions and download them.",
                keywords = listOf("version", "upgrade", "release", "download", "github"),
                layout = EntryLayout.Block,
                content = { UpdatesContent(it.updates, it.actions.onCheckUpdates, it.actions.onUpdate, it.actions.onCancelUpdate) },
            ),
        ),
    ),
    SettingsCategory(
        SettingsCategoryId.Help, "Help",
        listOf(
            SettingEntry(
                "welcome-guide", "Welcome guide", "Setup steps for USB debugging, Wi-Fi and mirroring.",
                keywords = listOf("onboarding", "tutorial", "setup", "usb", "wifi"),
                content = { OutlinedButton(onClick = it.actions.onShowOnboarding) { Text("Show welcome guide") } },
            ),
        ),
    ),
)
