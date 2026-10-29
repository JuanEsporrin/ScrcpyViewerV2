package com.programmersbox.scrcpyviewer.update

import com.programmersbox.scrcpyviewer.withFakeAppKoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateManagerTest {
    private val updatesDir: Path = Files.createTempDirectory("updates")
    private val scope = CoroutineScope(Job())
    private val checkedAt = Instant.parse("2026-10-29T12:00:00Z")

    private val updatable = InstallTarget.Updatable(
        UpdatePlatform.MacArm64,
        Paths.get("/Applications/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer"),
        Paths.get("/Applications/ScrcpyViewer.app"),
    )

    @AfterTest
    fun cleanup() {
        scope.cancel()
        updatesDir.toFile().deleteRecursively()
    }

    private fun release(tag: String) = Release(
        tagName = tag,
        htmlUrl = "https://github.com/jakepurple13/ScrcpyViewerV2/releases/tag/$tag",
        assets = listOf(
            ReleaseAsset(
                name = "ScrcpyViewer-${tag.removePrefix("v")}.dmg",
                size = 100,
                downloadUrl = "https://example.invalid/$tag.dmg",
                digest = "sha256:00",
            ),
        ),
    )

    /** Records calls; [block] decides what each download does. */
    private inner class FakeDownloader(
        val block: suspend (onProgress: (Long, Long) -> Unit) -> Path = { onProgress ->
            onProgress(100, 100)
            updatesDir.resolve("ScrcpyViewer-1.5.0.dmg")
        },
    ) : UpdateDownloader(updatesDir) {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()

        override suspend fun download(asset: ReleaseAsset, version: Version, onProgress: (Long, Long) -> Unit): Path {
            calls.incrementAndGet()
            started.complete(Unit)
            return block(onProgress)
        }
    }

    private fun manager(
        source: ReleaseSource = ReleaseSource { release("v1.5.0") },
        downloader: UpdateDownloader = FakeDownloader(),
        installer: UpdateInstaller = UpdateInstaller(start = {}, hasSetsid = false),
        target: InstallTarget = updatable,
    ) = UpdateManager(
        source = source,
        downloader = downloader,
        installer = installer,
        target = target,
        updatesDir = updatesDir,
        scope = scope,
        currentVersion = "1.4.0",
        platform = UpdatePlatform.MacArm64,
        bundledAdbInUse = { null },
        now = { checkedAt },
    )

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    private fun UpdateManager.awaitState(predicate: (UpdateState) -> Boolean): UpdateState =
        await { state.first(predicate) }

    private fun UpdateManager.checkAndAwaitAvailable() {
        check(userInitiated = false)
        awaitState { it is UpdateState.Available }
    }

    @Test
    fun `newer release becomes Available`() {
        val m = manager()
        m.check(userInitiated = false)
        val state = m.awaitState { it !is UpdateState.Idle && it !is UpdateState.Checking }
        assertEquals("v1.5.0", assertIs<UpdateState.Available>(state).release.tagName)
        assertEquals("v1.5.0", m.available.value?.tagName)
        assertEquals(checkedAt, m.lastChecked.value)
        assertFalse(m.popupDismissed.value)
    }

    @Test
    fun `same version is UpToDate`() {
        val m = manager(source = ReleaseSource { release("v1.4.0") })
        m.check(userInitiated = true)
        assertEquals(UpdateState.UpToDate, m.awaitState { it !is UpdateState.Idle && it !is UpdateState.Checking })
        assertNull(m.available.value)
        assertEquals(checkedAt, m.lastChecked.value)
    }

    @Test
    fun `launch check failure is silent`() {
        val m = manager(source = ReleaseSource { throw UpdateException("GitHub returned HTTP 403") })
        m.check(userInitiated = false)
        assertEquals(UpdateState.Idle, m.awaitState { it !is UpdateState.Checking })
        assertNull(m.available.value)
        assertNull(m.lastChecked.value)
    }

    @Test
    fun `manual check failure shows message`() {
        val m = manager(source = ReleaseSource { throw UpdateException("GitHub returned HTTP 403") })
        m.check(userInitiated = true)
        assertEquals(
            UpdateState.Failed("Couldn't check for updates: GitHub returned HTTP 403", null),
            m.awaitState { it is UpdateState.Failed },
        )
    }

    @Test
    fun `manual check failure keeps the known release`() {
        var fail = false
        val m = manager(source = ReleaseSource {
            if (fail) throw UpdateException("GitHub returned HTTP 403") else release("v1.5.0")
        })
        m.checkAndAwaitAvailable()
        fail = true
        m.check(userInitiated = true)
        val failed = assertIs<UpdateState.Failed>(m.awaitState { it is UpdateState.Failed })
        assertEquals("v1.5.0", failed.release?.tagName)
        assertEquals("v1.5.0", m.available.value?.tagName)
    }

    @Test
    fun `check ignored while downloading`() {
        val checks = AtomicInteger()
        val downloader = FakeDownloader { awaitCancellation() }
        val m = manager(
            source = ReleaseSource {
                checks.incrementAndGet()
                release("v1.5.0")
            },
            downloader = downloader,
        )
        m.checkAndAwaitAvailable()
        m.startUpdate()
        assertIs<UpdateState.Downloading>(m.state.value)
        await { downloader.started.await() }

        m.check(userInitiated = true)
        m.startUpdate()

        assertIs<UpdateState.Downloading>(m.state.value)
        assertEquals(1, checks.get())
        assertEquals(1, downloader.calls.get())
    }

    @Test
    fun `cancel returns to Available`() {
        val downloader = FakeDownloader { awaitCancellation() }
        val m = manager(downloader = downloader)
        m.checkAndAwaitAvailable()
        m.startUpdate()
        await { downloader.started.await() }
        m.cancel()
        assertEquals("v1.5.0", assertIs<UpdateState.Available>(m.state.value).release.tagName)
    }

    @Test
    fun `successful download launches installer and goes Installing`() {
        val started = mutableListOf<List<String>>()
        val finish = CompletableDeferred<Unit>()
        val m = manager(
            downloader = FakeDownloader { onProgress ->
                onProgress(40, 100)
                finish.await()
                updatesDir.resolve("ScrcpyViewer-1.5.0.dmg")
            },
            installer = UpdateInstaller(start = { started += it }, hasSetsid = false),
        )
        m.checkAndAwaitAvailable()
        val release = m.available.value!!
        m.startUpdate()
        assertEquals(UpdateState.Downloading(release, 40, 100), m.awaitState { it == UpdateState.Downloading(release, 40, 100) })
        assertEquals(0, started.size)
        finish.complete(Unit)
        assertEquals(UpdateState.Installing(release), m.awaitState { it is UpdateState.Installing })

        assertEquals(1, started.size)
        // The helper script bakes in the PID of the request it was given.
        val script = updatesDir.resolve("update.sh").readText()
        assertTrue("while kill -0 ${ProcessHandle.current().pid()} " in script)
    }

    @Test
    fun `installer failure becomes Failed and never Installing`() {
        val m = manager(installer = UpdateInstaller(start = { throw IOException("cannot run /bin/sh") }, hasSetsid = false))
        m.checkAndAwaitAvailable()
        m.startUpdate()
        val failed = assertIs<UpdateState.Failed>(m.awaitState { it is UpdateState.Failed || it is UpdateState.Installing })
        assertEquals("cannot run /bin/sh", failed.message)
        assertEquals("v1.5.0", failed.release?.tagName)
    }

    @Test
    fun `checksum failure becomes Failed with release kept`() {
        val m = manager(downloader = FakeDownloader { throw UpdateException("Downloaded file is corrupted (checksum mismatch)") })
        m.checkAndAwaitAvailable()
        m.startUpdate()
        val failed = assertIs<UpdateState.Failed>(m.awaitState { it is UpdateState.Failed })
        assertEquals("Downloaded file is corrupted (checksum mismatch)", failed.message)
        assertEquals("v1.5.0", failed.release?.tagName)
        assertNotNull(m.available.value)
    }

    @Test
    fun `startUpdate retries from Failed`() {
        var fail = true
        val m = manager(downloader = FakeDownloader {
            if (fail) throw UpdateException("Download failed: HTTP 500")
            updatesDir.resolve("ScrcpyViewer-1.5.0.dmg")
        })
        m.checkAndAwaitAvailable()
        m.startUpdate()
        m.awaitState { it is UpdateState.Failed }
        fail = false
        m.startUpdate()
        m.awaitState { it is UpdateState.Installing }
    }

    @Test
    fun `startUpdate refused when NotUpdatable`() {
        val downloader = FakeDownloader()
        val m = manager(downloader = downloader, target = InstallTarget.NotUpdatable(InstallTarget.Reason.DevBuild))
        m.checkAndAwaitAvailable()
        assertFalse(m.canSelfUpdate(m.available.value!!))
        m.startUpdate()
        assertIs<UpdateState.Available>(m.state.value)
        assertEquals(0, downloader.calls.get())
    }

    @Test
    fun `dismissPopup sets popupDismissed but keeps available`() {
        val m = manager()
        m.checkAndAwaitAvailable()
        m.dismissPopup()
        assertTrue(m.popupDismissed.value)
        assertEquals("v1.5.0", m.available.value?.tagName)
        assertIs<UpdateState.Available>(m.state.value)
    }

    @Test
    fun `previous failure read and deleted`() {
        val file = updatesDir.resolve("last-error.txt")
        file.writeText("Install was canceled.\n")
        val m = manager()
        assertEquals("Install was canceled.", m.previousFailure)
        assertFalse(file.exists())
    }

    @Test
    fun `no previous failure is null`() {
        assertNull(manager().previousFailure)
    }

    @Test
    fun `koin provides an idle UpdateManager`() {
        withFakeAppKoin { koin ->
            val m = koin.get<UpdateManager>()
            assertEquals(UpdateState.Idle, m.state.value)
        }
    }
}
