package com.programmersbox.scrcpyviewer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object AppDataDir {
    private const val OVERRIDE_PROPERTY = "scrcpyviewer.dataDir"
    private const val DIR_NAME = "ScrcpyViewer"

    /** Per-user data directory; created if missing. */
    fun root(): Path {
        val dir = System.getProperty(OVERRIDE_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: platformDefault()
        Files.createDirectories(dir)
        return dir
    }

    private fun platformDefault(): Path {
        val os = System.getProperty("os.name").lowercase()
        val home = Paths.get(System.getProperty("user.home"))
        return when {
            os.contains("mac") -> home.resolve("Library/Application Support").resolve(DIR_NAME)
            os.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                (appData?.let { Paths.get(it) } ?: home.resolve("AppData/Roaming")).resolve(DIR_NAME)
            }
            else -> {
                val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                (xdg?.let { Paths.get(it) } ?: home.resolve(".local/share")).resolve(DIR_NAME)
            }
        }
    }
}
