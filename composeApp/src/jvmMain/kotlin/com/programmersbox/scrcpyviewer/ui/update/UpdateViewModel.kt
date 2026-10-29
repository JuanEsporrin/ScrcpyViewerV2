package com.programmersbox.scrcpyviewer.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.scrcpyviewer.ui.common.defaultViewModelScope
import com.programmersbox.scrcpyviewer.update.InstallTarget
import com.programmersbox.scrcpyviewer.update.Release
import com.programmersbox.scrcpyviewer.update.UpdateManager
import com.programmersbox.scrcpyviewer.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import org.koin.core.annotation.KoinViewModel
import java.awt.Desktop
import java.net.URI
import java.time.Instant

/** What the update popup, the Settings badge and the Settings section show. */
data class UpdateUiState(
    val state: UpdateState = UpdateState.Idle,
    /** The newest known newer release; kept through a failed check or update. */
    val available: Release? = null,
    val currentVersion: String = "",
    val lastChecked: Instant? = null,
    /** What the last update helper reported when it failed; shown once in Settings. */
    val previousFailure: String? = null,
    val showPopup: Boolean = false,
    val canSelfUpdate: Boolean = false,
    val macNotInApplications: Boolean = false,
)

/** Shortest gap between two download progress updates shown; the downloader reports every 64 KiB. */
private const val PROGRESS_INTERVAL_MILLIS = 100L

@KoinViewModel
class UpdateViewModel(
    private val manager: UpdateManager,
    private val openUri: (URI) -> Unit = ::browse,
    scope: CoroutineScope = defaultViewModelScope(),
) : ViewModel(scope) {
    val state: StateFlow<UpdateUiState> = combine(
        manager.state.transform {
            emit(it)
            if (it is UpdateState.Downloading) delay(PROGRESS_INTERVAL_MILLIS)
        },
        manager.available,
        manager.lastChecked,
        manager.popupDismissed,
    ) { state, available, lastChecked, dismissed ->
        UpdateUiState(
            state = state,
            available = available,
            currentVersion = manager.currentVersion,
            lastChecked = lastChecked,
            previousFailure = manager.previousFailure,
            showPopup = available != null && !dismissed && state !is UpdateState.UpToDate,
            canSelfUpdate = available?.let(manager::canSelfUpdate) ?: false,
            macNotInApplications = manager.target == InstallTarget.NotUpdatable(InstallTarget.Reason.MacNotInApplications),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UpdateUiState(currentVersion = manager.currentVersion))

    fun check() = manager.check(userInitiated = true)

    fun update() = manager.startUpdate()

    fun cancel() = manager.cancel()

    fun later() = manager.dismissPopup()

    fun openReleasePage() {
        state.value.available?.let { openUri(URI(it.htmlUrl)) }
    }
}

private fun browse(uri: URI) {
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(uri)
}
