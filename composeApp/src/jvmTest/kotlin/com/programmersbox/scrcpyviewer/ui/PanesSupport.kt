package com.programmersbox.scrcpyviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.programmersbox.scrcpyviewer.ui.common.ListDetailPanes
import com.programmersbox.scrcpyviewer.ui.common.rememberListDetailPanes

/** Pane state for screenshots: an optional dragged list width (px) and the single-pane destination. */
@Composable
fun screenshotPanes(listWidthPx: Int? = null, detailOpen: Boolean = false): ListDetailPanes {
    val panes = rememberListDetailPanes()
    remember(panes) {
        if (listWidthPx != null) panes.expansion.setFirstPaneWidth(listWidthPx)
        if (detailOpen) panes.navigation.openDetail()
    }
    return panes
}
