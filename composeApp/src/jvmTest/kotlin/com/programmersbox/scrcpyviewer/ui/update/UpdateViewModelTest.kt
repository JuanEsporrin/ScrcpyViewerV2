package com.programmersbox.scrcpyviewer.ui.update

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
import java.net.URI
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
    private val opened = mutableListOf<URI>()

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
        return UpdateViewModel(manager, opened::add, vmScope)
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
    fun `openReleasePage opens the html url`() {
        val vm = vm()
        vm.openReleasePage()
        assertTrue(opened.isEmpty(), "nothing to open before a release is known")
        vm.checkAndAwaitAvailable()
        vm.openReleasePage()
        assertEquals(listOf(URI(release.htmlUrl)), opened)
    }
}
