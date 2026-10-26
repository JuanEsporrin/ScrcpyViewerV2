package com.programmersbox.scrcpyviewer.ui.mirror

import com.programmersbox.scrcpyviewer.files.TransferKind
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FileDropTest {
    private val noDirs: (Path) -> Boolean = { false }
    private fun hint(vararg names: String, connected: Boolean = true, target: String = "/sdcard/Download/") =
        dropHint(names.map { Paths.get("/x", it) }, target, connected, noDirs)

    @Test
    fun `labels for apks, files and both`() {
        assertEquals(DropHint("Drop to install APK", TransferKind.Install, true), hint("app.apk"))
        assertEquals("Drop to install 2 APKs", hint("a.apk", "B.APK").label)
        assertEquals(DropHint("Drop to push to /sdcard/Download/", TransferKind.Push, true), hint("photo.jpg"))
        assertEquals("Drop to push 2 files to /sdcard/DCIM/", hint("a.jpg", "b.txt", target = "/sdcard/DCIM/").label)
        assertEquals(DropHint("Drop to install 1 APK and push 2 files", null, true), hint("a.apk", "b.jpg", "c.txt"))
        assertEquals("Drop to install 2 APKs and push 1 file", hint("a.apk", "b.apk", "c.txt").label)
    }

    @Test
    fun `unknown files and disconnected device`() {
        assertEquals("Drop to install APKs or push files to /sdcard/Download/", dropHint(null, "/sdcard/Download/", true).label)
        val off = hint("a.apk", connected = false)
        assertEquals("Device not connected", off.label)
        assertFalse(off.allowed)
    }

    @Test
    fun `file list flavor is read from a transferable`() {
        val files = listOf(File("/x/a.apk"), File("/x/b.jpg"))
        val t = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor): Any = files
        }
        assertEquals(files.map { it.toPath() }, t.filePaths())
        assertNull(StringSelection("text").filePaths())
    }

    @Test
    fun `transfer actions send no control messages`() {
        assertEquals(emptyList(), MirrorAction.InstallApk.messages())
        assertEquals(emptyList(), MirrorAction.PushFile.messages())
    }
}
