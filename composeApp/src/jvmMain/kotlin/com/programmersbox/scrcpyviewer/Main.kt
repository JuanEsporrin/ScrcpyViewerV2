package com.programmersbox.scrcpyviewer

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.programmersbox.scrcpyviewer.ui.App

/** Dependency holder; later tasks add fields. */
class AppGraph

fun main() = application {
    val graph = AppGraph()
    Window(onCloseRequest = ::exitApplication, title = "Scrcpy Viewer") {
        App(graph)
    }
}
