package com.programmersbox.scrcpyviewer.update

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** Whether the running install can replace itself, and the paths the update helper needs. */
sealed interface InstallTarget {
    /** [appBundle] is the `.app` folder on macOS and null elsewhere. */
    data class Updatable(val platform: UpdatePlatform, val launcher: Path, val appBundle: Path?) : InstallTarget

    data class NotUpdatable(val reason: Reason) : InstallTarget

    enum class Reason { DevBuild, UnsupportedPlatform, MacNotInApplications, LinuxNotFromPackage }
}

fun detectInstallTarget(
    appPath: String? = System.getProperty("jpackage.app-path"),
    platform: UpdatePlatform = UpdatePlatform.detect(),
    dpkgOwns: (Path) -> Boolean = ::dpkgOwnsPath,
): InstallTarget {
    if (appPath.isNullOrBlank()) return InstallTarget.NotUpdatable(InstallTarget.Reason.DevBuild)
    if (platform == UpdatePlatform.Unsupported) {
        return InstallTarget.NotUpdatable(InstallTarget.Reason.UnsupportedPlatform)
    }
    val launcher = Paths.get(appPath)
    var appBundle: Path? = null
    when (platform) {
        UpdatePlatform.MacArm64 -> {
            val normalized = appPath.replace('\\', '/')
            if (normalized.startsWith("/Volumes/") || "/AppTranslocation/" in normalized) {
                return InstallTarget.NotUpdatable(InstallTarget.Reason.MacNotInApplications)
            }
            appBundle = generateSequence(launcher) { it.parent }
                .firstOrNull { it.fileName?.toString()?.endsWith(".app") == true }
        }
        UpdatePlatform.LinuxX64 -> {
            if (!dpkgOwns(launcher)) return InstallTarget.NotUpdatable(InstallTarget.Reason.LinuxNotFromPackage)
        }
        else -> Unit
    }
    return InstallTarget.Updatable(platform, launcher, appBundle)
}

/** True when `dpkg -S <path>` succeeds and names the `scrcpyviewer` package. */
internal fun dpkgOwnsPath(path: Path): Boolean = try {
    val process = ProcessBuilder("dpkg", "-S", path.toString())
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        false
    } else {
        process.exitValue() == 0 &&
            process.inputStream.bufferedReader().readText().startsWith("scrcpyviewer:")
    }
} catch (_: Exception) {
    false
}
