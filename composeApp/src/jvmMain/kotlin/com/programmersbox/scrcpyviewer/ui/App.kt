package com.programmersbox.scrcpyviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.AppGraph
import com.programmersbox.scrcpyviewer.ui.common.LocalSnackbarHostState
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelNavigator
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelRoute
import com.programmersbox.scrcpyviewer.ui.navigation.TopLevelNavDisplay
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.devices.DevicesScreen
import com.programmersbox.scrcpyviewer.ui.presets.AwtPresetFileDialogs
import com.programmersbox.scrcpyviewer.ui.presets.PresetsScreen
import com.programmersbox.scrcpyviewer.ui.settings.AwtSettingsFileDialogs
import com.programmersbox.scrcpyviewer.ui.settings.SettingsScreen
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme
import com.programmersbox.scrcpyviewer.ui.mirror.recordResultMessage
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.LaunchedEffect

private val TopLevelRoute.label: String
    get() = when (this) {
        TopLevelRoute.Devices -> "Devices"
        TopLevelRoute.Presets -> "Presets"
        TopLevelRoute.Settings -> "Settings"
    }

private val TopLevelRoute.icon: ImageVector
    get() = when (this) {
        TopLevelRoute.Devices -> Icons.Filled.PhoneAndroid
        TopLevelRoute.Presets -> Icons.Filled.Tune
        TopLevelRoute.Settings -> Icons.Filled.Settings
    }

/**
 * The main window's content. [navigator] and the pane states are hoisted (tests pass their own);
 * the pane states live above the navigation so divider positions survive tab switches.
 */
@Composable
fun App(
    graph: AppGraph,
    navigator: TopLevelNavigator = remember { TopLevelNavigator() },
    devicesPanes: ListDetailPanes = rememberListDetailPanes(),
    presetsPanes: ListDetailPanes = rememberListDetailPanes(),
) {
    val settings by graph.settings.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val fileDialogs = remember { AwtPresetFileDialogs() }
    val settingsDialogs = remember { AwtSettingsFileDialogs() }
    // Recordings that end with no mirror window open for their device (closed window, app-wide view).
    LaunchedEffect(Unit) {
        graph.mirrorController.recordingResults
            .filter { it.serial !in graph.mirrorController.open.value }
            .collect { snackbar.showSnackbar(recordResultMessage(it.result)) }
    }
    ScrcpyViewerTheme(settings.themeMode) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            CompositionLocalProvider(LocalSnackbarHostState provides snackbar) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    TopLevelRoute.all.forEach { route ->
                        NavigationRailItem(
                            selected = route == navigator.current,
                            onClick = { navigator.select(route) },
                            icon = { Icon(route.icon, contentDescription = route.label) },
                            label = { Text(route.label) },
                        )
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    TopLevelNavDisplay(navigator) { route ->
                        when (route) {
                            TopLevelRoute.Devices -> DevicesScreen(graph.devicesViewModel, graph.wirelessAdb, panes = devicesPanes)
                            TopLevelRoute.Presets -> PresetsScreen(graph.presetsViewModel, fileDialogs, panes = presetsPanes)
                            TopLevelRoute.Settings -> SettingsScreen(graph.settingsViewModel, settingsDialogs)
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
            }
        }
    }
}
