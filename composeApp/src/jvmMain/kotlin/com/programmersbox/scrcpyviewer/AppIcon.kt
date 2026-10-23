package com.programmersbox.scrcpyviewer

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Taskbar
import javax.imageio.ImageIO

/** The app icon (composeApp/icons/app-icon.svg), used for every window and, in dev runs, the macOS Dock. */
object AppIcon {
    private val image by lazy {
        AppIcon::class.java.getResourceAsStream("/icon.png")!!.use { ImageIO.read(it) }
    }

    val painter: Painter by lazy { BitmapPainter(image.toComposeImageBitmap()) }

    /** Packaged builds get the Dock icon from the .icns; this covers `run` and IDE launches. */
    fun installTaskbarIcon() {
        runCatching {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().iconImage = image
            }
        }
    }
}
