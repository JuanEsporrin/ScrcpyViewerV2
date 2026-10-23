package com.programmersbox.scrcpyviewer.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection

/** Where [CopyButton] shows its "Copied" message. `null` outside the app shell (previews, tests). */
val LocalSnackbarHostState = compositionLocalOf<SnackbarHostState?> { null }

/** Copies [text] to the system clipboard and shows a "Copied" snackbar. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CopyButton(text: () -> String, label: String = "Copy", modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    TextButton(
        onClick = {
            val value = text()
            scope.launch {
                clipboard.setClipEntry(ClipEntry(StringSelection(value)))
                snackbar?.showSnackbar("Copied")
            }
        },
        modifier = modifier,
    ) {
        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(label, modifier = Modifier.padding(start = 6.dp), maxLines = 1, softWrap = false)
    }
}
