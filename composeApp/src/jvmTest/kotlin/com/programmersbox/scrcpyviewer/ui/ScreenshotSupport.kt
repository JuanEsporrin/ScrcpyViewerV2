package com.programmersbox.scrcpyviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

/** Renders [content] offscreen at [width]x[height] px and writes a PNG to [out]. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun renderToPng(width: Int, height: Int, out: Path, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(width, height, Density(1f), content = content)
    try {
        val image = scene.render()
        val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed" }
        Files.createDirectories(out.parent)
        Files.write(out, data.bytes)
    } finally {
        scene.close()
    }
}
