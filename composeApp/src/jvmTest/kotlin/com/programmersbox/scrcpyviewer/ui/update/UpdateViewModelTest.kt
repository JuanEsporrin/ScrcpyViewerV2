package com.programmersbox.scrcpyviewer.ui.update

import androidx.compose.ui.platform.UriHandler
import com.programmersbox.scrcpyviewer.update.InstallTarget
import com.programmersbox.scrcpyviewer.update.Release
import com.programmersbox.scrcpyviewer.update.ReleaseAsset
import com.programmersbox.scrcpyviewer.update.ReleaseSource
import com.programmersbox.scrcpyviewer.update.UpdateDownloader
import com.programmersbox.scrcpyviewer.update.UpdateInstaller
import com.programmersbox.scrcpyviewer.update.UpdateManager
import com.programmersbox.scrcpyviewer.update.UpdatePlatform
import com.programmersbox.scrcpyviewer.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateViewModelTest {
    private val updatesDir = Files.createTempDirectory("updates-vm")
    private val managerScope = CoroutineScope(Job())
    private val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val updatable = InstallTarget.Updatable(
        UpdatePlatform.MacArm64,
        Paths.get("/Applications/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer"),
        Paths.get("/Applications/ScrcpyViewer.app"),
    )

    private val release = Release(
        tagName = "v1.5.0",
        htmlUrl = "https://github.com/jakepurple13/ScrcpyViewerV2/releases/tag/v1.5.0",
        assets = listOf(ReleaseAsset("ScrcpyViewer-1.5.0.dmg", 100, "https://example.invalid/a.dmg", "sha256:00")),
    )

    @AfterTest
    fun cleanup() {
        managerScope.cancel()
        vmScope.cancel()
        updatesDir.toFile().deleteRecursively()
    }

    private fun vm(target: InstallTarget = updatable): UpdateViewModel {
        val manager = UpdateManager(
            source = ReleaseSource { release },
            downloader = UpdateDownloader(updatesDir),
            installer = UpdateInstaller(start = {}, hasSetsid = false),
            target = target,
            updatesDir = updatesDir,
            scope = managerScope,
            currentVersion = "1.4.0",
            platform = UpdatePlatform.MacArm64,
            bundledAdbInUse = { null },
        )
        return UpdateViewModel(manager, vmScope)
    }

    private fun UpdateViewModel.checkAndAwaitAvailable(): UpdateUiState {
        check()
        return runBlocking { withTimeout(5_000) { state.first { it.state is UpdateState.Available } } }
    }

    @Test
    fun `popup shows for a newer release and Later hides it`() {
        val vm = vm()
        assertFalse(vm.state.value.showPopup)
        val ui = vm.checkAndAwaitAvailable()
        assertTrue(ui.showPopup)
        assertEquals("v1.5.0", ui.available?.tagName)
        assertEquals("1.4.0", ui.currentVersion)
        assertTrue(ui.canSelfUpdate)
        vm.later()
        assertFalse(vm.state.value.showPopup)
        assertEquals("v1.5.0", vm.state.value.available?.tagName)
    }

    @Test
    fun `dev build cannot self update`() {
        val vm = vm(InstallTarget.NotUpdatable(InstallTarget.Reason.DevBuild))
        val ui = vm.checkAndAwaitAvailable()
        assertFalse(ui.canSelfUpdate)
        assertFalse(ui.macNotInApplications)
    }

    @Test
    fun `mac outside Applications is flagged`() {
        val vm = vm(InstallTarget.NotUpdatable(InstallTarget.Reason.MacNotInApplications))
        assertTrue(vm.state.value.macNotInApplications)
    }

    @Test
    fun `previous failure reaches the ui state`() {
        Files.writeString(updatesDir.resolve("last-error.txt"), "Install was canceled.\n")
        assertEquals("Install was canceled.", vm().state.value.previousFailure)
    }

    @Test
    fun `release page url is the release or the latest page`() {
        val vm = vm()
        assertEquals("https://github.com/jakepurple13/ScrcpyViewerV2/releases/latest", vm.state.value.releasePageUrl)
        assertEquals(release.htmlUrl, vm.checkAndAwaitAvailable().releasePageUrl)
    }

    @Test
    fun `openSafely swallows a failing uri handler`() {
        val handler = object : UriHandler {
            override fun openUri(uri: String) = error("no browser")
        }
        handler.openSafely("https://example.invalid")
    }

    @Test
    fun `openSafely passes the url on`() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        handler.openSafely("https://example.invalid/a")
        assertEquals(listOf("https://example.invalid/a"), opened)
    }
}
