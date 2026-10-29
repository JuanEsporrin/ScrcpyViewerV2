package com.programmersbox.scrcpyviewer.update

import com.programmersbox.scrcpyviewer.APP_VERSION
import dev.nucleusframework.updater.DownloadProgress
import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateEvent
import dev.nucleusframework.updater.UpdateInfo
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.provider.GenericProvider
import dev.nucleusframework.updater.provider.GitHubProvider
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Provided
import java.io.File
import java.nio.file.Path

/**
 * The part of Nucleus' updater that [UpdateManager] uses. Given to Koin at start; tests give one that never touches
 * the network ([NucleusUpdater] is a final class).
 */
@Provided
interface UpdateBackend {
    /**
     * Whether Nucleus can install an update into this copy of the app. False in dev runs (`./gradlew run`) and in
     * builds that are not one of the published installers.
     */
    val canInstall: Boolean

    suspend fun check(): UpdateResult

    /** Downloads and verifies (SHA-512) the installer; the last emission carries the file. */
    fun download(info: UpdateInfo): Flow<DownloadProgress>

    /** Starts Nucleus' detached install script and exits the JVM. Does not return when the script starts. */
    fun installAndRestart(file: File)

    /** The `old -> new` marker Nucleus writes before installing, removed once read. */
    fun consumeUpdateEvent(): UpdateEvent?
}

/**
 * [NucleusUpdater] reading GitHub releases (`latest-mac.yml`, `latest.yml`, `latest-linux.yml`). The
 * `scrcpyviewer.updateFeed` system property points it at another folder of update metadata instead (HTTPS, or HTTP
 * on loopback), for local update tests.
 *
 * [cacheDir] holds the last downloaded installer, which Nucleus keeps so the next update can download only the
 * blocks that changed.
 */
class NucleusUpdateBackend(
    private val cacheDir: Path,
    private val feed: String? = System.getProperty("scrcpyviewer.updateFeed")?.takeIf { it.isNotBlank() },
    private val repo: String = updateRepo(),
) : UpdateBackend {
    private fun updater(type: String?): NucleusUpdater {
        val cache = cacheDir.toFile()
        val source = feed?.let(::GenericProvider)
            ?: GitHubProvider(owner = repo.substringBefore('/'), repo = repo.substringAfter('/'))
        return NucleusUpdater {
            currentVersion = APP_VERSION
            provider = source
            cacheDir = cache
            if (type != null) executableType = type
        }
    }

    /** Knows from the packaging (the plugin's `nucleus.executable.type`) which installer this copy came from. */
    private val installer = updater(type = null)

    override val canInstall: Boolean = installer.isUpdateSupported()

    /**
     * Nucleus answers "no update" whenever it cannot install one. The check must still find newer releases so the
     * UI can offer "Download from GitHub", so a copy that cannot install checks as this OS's published installer.
     */
    private val checker = if (canInstall) installer else updater(publishedExecutableType())

    override suspend fun check(): UpdateResult = checker.checkForUpdates()

    override fun download(info: UpdateInfo): Flow<DownloadProgress> = installer.downloadUpdate(info)

    override fun installAndRestart(file: File) = installer.installAndRestart(file)

    override fun consumeUpdateEvent(): UpdateEvent? = installer.consumeUpdateEvent()
}

/** The installer CI publishes for this OS, as a Nucleus executable type; null on other systems. */
internal fun publishedExecutableType(osName: String = System.getProperty("os.name")): String? {
    val os = osName.lowercase()
    return when {
        "mac" in os -> "zip"
        "win" in os -> "nsis"
        "linux" in os -> "deb"
        else -> null
    }
}
