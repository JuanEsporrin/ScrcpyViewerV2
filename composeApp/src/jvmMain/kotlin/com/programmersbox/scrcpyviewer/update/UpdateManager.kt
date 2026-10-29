package com.programmersbox.scrcpyviewer.update

import com.programmersbox.scrcpyviewer.APP_VERSION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val read: Long, val total: Long) : UpdateState
    /** The helper has started; the app should quit so it can install. */
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/**
 * Checks for, downloads and hands off app updates. It lives as long as the app: the popup, the badge and
 * Settings share it, and `main()` quits once it reaches [UpdateState.Installing].
 */
class UpdateManager(
    private val source: ReleaseSource,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
    val target: InstallTarget,
    private val updatesDir: Path,
    private val scope: CoroutineScope,
    val currentVersion: String = APP_VERSION,
    private val platform: UpdatePlatform = UpdatePlatform.detect(),
    private val bundledAdbInUse: () -> Path?,
    private val now: () -> Instant = Instant::now,
) {
    // Guards every state change, so a check or update started while another is running is ignored.
    private val lock = Any()
    private var downloadJob: Job? = null
    private var launching = false

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _available = MutableStateFlow<Release?>(null)

    /** The newest known release newer than [currentVersion]; it survives a failed check or update. */
    val available: StateFlow<Release?> = _available.asStateFlow()

    private val _lastChecked = MutableStateFlow<Instant?>(null)
    val lastChecked: StateFlow<Instant?> = _lastChecked.asStateFlow()

    private val _popupDismissed = MutableStateFlow(false)

    /** True once "Later" is chosen; for this launch only. */
    val popupDismissed: StateFlow<Boolean> = _popupDismissed.asStateFlow()

    /** What the last update helper wrote to `last-error.txt`; the file is deleted once read. */
    val previousFailure: String? = runCatching {
        val file = updatesDir.resolve(LAST_ERROR)
        if (Files.exists(file)) Files.readString(file).trim().also { Files.delete(file) } else null
    }.getOrNull()

    fun canSelfUpdate(release: Release): Boolean =
        target is InstallTarget.Updatable && release.assetFor(platform) != null

    /** Asks the release source for the latest release. A failure is shown only when [userInitiated]. */
    fun check(userInitiated: Boolean) {
        val previous = synchronized(lock) {
            val current = _state.value
            if (current is UpdateState.Checking || current is UpdateState.Downloading || current is UpdateState.Installing) {
                return
            }
            _state.value = UpdateState.Checking
            current
        }
        scope.launch {
            try {
                val release = source.latest()
                val current = Version.parse(currentVersion)
                val newer = release.version?.let { current == null || it > current } == true
                synchronized(lock) {
                    _available.value = if (newer) release else null
                    _lastChecked.value = now()
                    _state.value = if (newer) UpdateState.Available(release) else UpdateState.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) {
                    _state.value = if (userInitiated) {
                        UpdateState.Failed("Couldn't check for updates: ${e.message}", _available.value)
                    } else {
                        log.log(System.Logger.Level.WARNING, "Update check failed", e)
                        previous
                    }
                }
            }
        }
    }

    /** Downloads the release shown in [UpdateState.Available] (or a failed one), then starts the helper. */
    fun startUpdate() {
        synchronized(lock) {
            val release = when (val current = _state.value) {
                is UpdateState.Available -> current.release
                is UpdateState.Failed -> current.release
                else -> null
            } ?: return
            if (!canSelfUpdate(release)) return
            val asset = release.assetFor(platform) ?: return
            val version = release.version ?: return
            _state.value = UpdateState.Downloading(release, 0, asset.size)
            downloadJob = scope.launch { download(release, asset, version) }
        }
    }

    private suspend fun download(release: Release, asset: ReleaseAsset, version: Version) {
        val ctx = currentCoroutineContext()
        val job = ctx.job
        try {
            val file = downloader.download(asset, version) { read, total ->
                synchronized(lock) { if (job.isActive) _state.value = UpdateState.Downloading(release, read, total) }
            }
            synchronized(lock) {
                ctx.ensureActive()
                launching = true
            }
            // Once the helper may be running, finish the launch even if the scope is cancelled.
            withContext(NonCancellable + Dispatchers.IO) {
                installer.launch(
                    InstallRequest(
                        target = target as InstallTarget.Updatable,
                        installer = file,
                        updatesDir = updatesDir,
                        pid = ProcessHandle.current().pid(),
                        bundledAdb = bundledAdbInUse(),
                    ),
                )
            }
            synchronized(lock) { _state.value = UpdateState.Installing(release) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) {
                if (job.isActive) _state.value = UpdateState.Failed(e.message ?: "Update failed", release)
            }
        } finally {
            synchronized(lock) { launching = false }
        }
    }

    /** Stops the download and goes back to [UpdateState.Available]. Too late once the helper is being started. */
    fun cancel() {
        synchronized(lock) {
            val current = _state.value as? UpdateState.Downloading ?: return
            if (launching) return
            downloadJob?.cancel()
            downloadJob = null
            _state.value = UpdateState.Available(current.release)
        }
    }

    fun dismissPopup() {
        _popupDismissed.value = true
    }

    private companion object {
        val log: System.Logger = System.getLogger("scrcpyviewer.Update")
    }
}
