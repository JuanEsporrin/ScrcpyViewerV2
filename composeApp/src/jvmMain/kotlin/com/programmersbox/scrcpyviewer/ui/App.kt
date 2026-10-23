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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.AppGraph
import com.programmersbox.scrcpyviewer.ui.common.LocalSnackbarHostState
import com.programmersbox.scrcpyviewer.ui.devices.DevicesScreen
import com.programmersbox.scrcpyviewer.ui.theme.ScrcpyViewerTheme

private enum class Destination(val label: String, val icon: ImageVector) {
    Devices("Devices", Icons.Filled.PhoneAndroid),
    Presets("Presets", Icons.Filled.Tune),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun App(graph: AppGraph) {
    val settings by graph.settings.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    ScrcpyViewerTheme(settings.themeMode) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            var selected by rememberSaveable { mutableStateOf(Destination.Devices) }
            CompositionLocalProvider(LocalSnackbarHostState provides snackbar) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Destination.entries.forEach { dest ->
                        NavigationRailItem(
                            selected = dest == selected,
                            onClick = { selected = dest },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    when (selected) {
                        Destination.Devices -> DevicesScreen(graph.devicesViewModel)
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(selected.label) }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
            }
        }
    }
}
