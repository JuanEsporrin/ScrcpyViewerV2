package com.programmersbox.scrcpyviewer.ui.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.programmersbox.scrcpyviewer.Os
import com.programmersbox.scrcpyviewer.files.TransferKind
import com.programmersbox.scrcpyviewer.files.requestsFor
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Native file pickers for Install APK… and Push file…, behind an interface so tests never open a window. */
interface TransferFileDialogs {
    /** APKs to install (multi-select); empty when cancelled. */
    fun chooseApks(parent: Frame?): List<Path>

    /** Files to push (multi-select); empty when cancelled. */
    fun chooseFiles(parent: Frame?, pushTarget: String): List<Path>
}

/** `java.awt.FileDialog` (the native dialog on macOS). The `.apk` filter is ignored by Windows' dialog. */
object AwtTransferFileDialogs : TransferFileDialogs {
    override fun chooseApks(parent: Frame?): List<Path> {
        val dialog = FileDialog(parent, "Install APK", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.setFilenameFilter { _, name -> name.endsWith(".apk", ignoreCase = true) }
        if (Os.isWindows) dialog.file = "*.apk" // the only filter the Windows dialog honours
        return show(dialog)
    }

    override fun chooseFiles(parent: Frame?, pushTarget: String): List<Path> {
        val dialog = FileDialog(parent, "Push to $pushTarget", FileDialog.LOAD)
        dialog.isMultipleMode = true
        return show(dialog)
    }

    private fun show(dialog: FileDialog): List<Path> {
        dialog.isVisible = true
        return dialog.files.orEmpty().map { it.toPath() }
    }
}

/** The files of a drag (`javaFileListFlavor`), or null when they cannot be read (yet). */
fun Transferable.filePaths(): List<Path>? = runCatching {
    if (!isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
    (getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>()?.map { it.toPath() }
}.getOrNull()

/** What a drop of [paths] does, for the overlay shown while dragging. */
data class DropHint(val label: String, val kind: TransferKind?, val allowed: Boolean)

private fun count(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"

/**
 * "Drop to install APK" / "Drop to push to /sdcard/Download/" / "Drop to install 1 APK and push 2 files".
 * [paths] is null when the platform does not tell the files before the drop.
 */
fun dropHint(paths: List<Path>?, pushTarget: String, connected: Boolean, isDirectory: (Path) -> Boolean = Files::isDirectory): DropHint {
    if (!connected) return DropHint("Device not connected", null, allowed = false)
    if (paths.isNullOrEmpty()) return DropHint("Drop to install APKs or push files to $pushTarget", null, allowed = true)
    val requests = requestsFor(paths, isDirectory)
    val apks = requests.count { it.kind == TransferKind.Install }
    val files = requests.size - apks
    return when {
        files == 0 -> DropHint(if (apks == 1) "Drop to install APK" else "Drop to install $apks APKs", TransferKind.Install, true)
        apks == 0 -> DropHint(
            if (files == 1) "Drop to push to $pushTarget" else "Drop to push ${count(files, "file")} to $pushTarget",
            TransferKind.Push, true,
        )
        else -> DropHint("Drop to install ${count(apks, "APK")} and push ${count(files, "file")}", null, true)
    }
}

/** Shown over the stage while files are dragged over the window. */
@Composable
fun DropOverlay(hint: DropHint, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val accent = if (hint.allowed) colors.primary else colors.error
    Box(
        modifier.fillMaxSize()
            .background(colors.scrim.copy(alpha = 0.55f))
            .padding(12.dp)
            .border(2.dp, accent, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh, tonalElevation = 3.dp) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 20.dp).widthIn(max = 300.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val icon = when {
                    !hint.allowed -> Icons.Filled.Block
                    hint.kind == TransferKind.Install -> Icons.Filled.Android
                    else -> Icons.Filled.UploadFile
                }
                Box(
                    Modifier.size(56.dp).background(if (hint.allowed) colors.primaryContainer else colors.errorContainer, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, Modifier.size(32.dp), tint = if (hint.allowed) colors.onPrimaryContainer else colors.onErrorContainer)
                }
                Text(hint.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }
}
