package com.programmersbox.scrcpyviewer.files

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import com.programmersbox.scrcpyviewer.options.OptionValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Paths
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTransferTest {
    /** Records every adb call; [handler] answers it (it may suspend). */
    private class FakeAdb(
        val handler: suspend (List<String>) -> ProcessResult = { ProcessResult(0, "", "") },
    ) : AdbClient({ Paths.get("adb") }) {
        val calls: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

        override suspend fun exec(vararg args: String): ProcessResult {
            calls += args.toList()
            return handler(args.toList())
        }
    }

    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val scans: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun queue(adb: AdbClient, connected: () -> Boolean = { true }, target: String = DEFAULT_PUSH_TARGET, timeoutMs: Long = 5_000) =
        FileTransferQueue("SER", adb, target, connected, { scans += it }, scope, timeoutMs)

    private val noDirs: (java.nio.file.Path) -> Boolean = { false }

    @Test
    fun `apk is installed, anything else is pushed, case-insensitively`() {
        assertEquals(TransferKind.Install, classify(Paths.get("/x/app.apk"), noDirs))
        assertEquals(TransferKind.Install, classify(Paths.get("/x/APP.APK"), noDirs))
        assertEquals(TransferKind.Install, classify(Paths.get("/x/a.b.Apk"), noDirs))
        assertEquals(TransferKind.Push, classify(Paths.get("/x/app.apks"), noDirs))
        assertEquals(TransferKind.Push, classify(Paths.get("/x/apk"), noDirs))
        assertEquals(TransferKind.Push, classify(Paths.get("/x/photo.jpg"), noDirs))
        assertEquals(TransferKind.Push, classify(Paths.get("/x/app.apk.txt"), noDirs))
    }

    @Test
    fun `directories are pushed even when named apk`() {
        assertEquals(TransferKind.Push, classify(Paths.get("/x/folder.apk")) { true })
        assertEquals(TransferKind.Push, classify(Paths.get("/x/folder")) { true })
    }

    @Test
    fun `push target defaults to sdcard Download and follows the option`() {
        assertEquals("/sdcard/Download/", pushTarget(OptionValues(emptyMap())))
        assertEquals("/sdcard/Download/", pushTarget(OptionValues(mapOf("push-target" to ""))))
        assertEquals("/data/local/tmp/", pushTarget(OptionValues(mapOf("push-target" to "/data/local/tmp/"))))
    }

    @Test
    fun `adb arguments match file_pusher c`() {
        val p = Paths.get("/tmp/my app.apk")
        assertEquals(listOf("-s", "SER", "install", "-r", p.toString()), installArgs("SER", p))
        assertEquals(listOf("-s", "SER", "push", p.toString(), "/sdcard/Download/"), pushArgs("SER", p, "/sdcard/Download/"))
    }

    @Test
    fun `error line extraction`() {
        assertEquals(
            "INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: Update version code 1 is older than current 2",
            adbErrorLine(ProcessResult(1, "Performing Streamed Install\n",
                "adb: failed to install /x/app.apk: Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: Update version code 1 is older than current 2]\n")),
        )
        assertEquals(
            "failed to copy '/x/a.txt' to '/system/a.txt': remote couldn't create file: Read-only file system",
            adbErrorLine(ProcessResult(1, "", "adb: error: failed to copy '/x/a.txt' to '/system/a.txt': remote couldn't create file: Read-only file system\n")),
        )
        assertEquals("device 'SER' not found", adbErrorLine(ProcessResult(1, "", "adb: error: device 'SER' not found\n")))
        assertEquals("something odd", adbErrorLine(ProcessResult(1, "", "\nsomething odd\n\n")))
        assertEquals("adb exited with code 7", adbErrorLine(ProcessResult(7, "", "")))
    }

    @Test
    fun `progress labels`() {
        val apk = TransferRequest(Paths.get("/x/app.apk"), TransferKind.Install)
        val jpg = TransferRequest(Paths.get("/x/photo.jpg"), TransferKind.Push)
        assertEquals("Installing app.apk…", TransferProgress(apk, 1, 1).label)
        assertEquals("Pushing 2 of 3: photo.jpg…", TransferProgress(jpg, 2, 3).label)
        assertEquals("Installed app.apk", TransferResult.Installed("app.apk").message)
        assertEquals("Pushed photo.jpg to /sdcard/Download/", TransferResult.Pushed("photo.jpg", "/sdcard/Download/").message)
        assertEquals("Could not install app.apk: INSTALL_FAILED_X", TransferResult.Failed(apk, "INSTALL_FAILED_X").message)
    }

    @Test
    fun `requests run sequentially in order, push then scan`() = runBlocking<Unit> {
        var running = 0
        var maxRunning = 0
        val lock = Any()
        val adb = FakeAdb {
            synchronized(lock) { running++; maxRunning = maxOf(maxRunning, running) }
            kotlinx.coroutines.delay(30)
            synchronized(lock) { running-- }
            ProcessResult(0, "", "")
        }
        val q = queue(adb, target = "/sdcard/Pictures/")
        val results = async(start = CoroutineStart.UNDISPATCHED) { q.results.take(3).toList() }
        assertTrue(q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk"), Paths.get("/x/b.jpg"), Paths.get("/x/c.txt")), noDirs)))
        val got = withTimeout(5_000) { results.await() }
        assertEquals(
            listOf(
                TransferResult.Installed("a.apk"),
                TransferResult.Pushed("b.jpg", "/sdcard/Pictures/"),
                TransferResult.Pushed("c.txt", "/sdcard/Pictures/"),
            ),
            got,
        )
        assertEquals(1, maxRunning)
        assertEquals(
            listOf(
                listOf("-s", "SER", "install", "-r", Paths.get("/x/a.apk").toString()),
                listOf("-s", "SER", "push", Paths.get("/x/b.jpg").toString(), "/sdcard/Pictures/"),
                listOf("-s", "SER", "push", Paths.get("/x/c.txt").toString(), "/sdcard/Pictures/"),
            ),
            adb.calls.toList(),
        )
        // v5.0 request_scan_file: the push target, once per successful push.
        assertEquals(listOf("/sdcard/Pictures/", "/sdcard/Pictures/"), scans.toList())
        withTimeout(5_000) { q.progress.first { it == null } }
    }

    @Test
    fun `progress counts the batch`() = runBlocking<Unit> {
        val gates = List(3) { CompletableDeferred<Unit>() }
        var n = 0
        val adb = FakeAdb { gates[n++].await(); ProcessResult(0, "", "") }
        val q = queue(adb)
        q.enqueue(requestsFor(listOf(Paths.get("/x/a.jpg"), Paths.get("/x/b.jpg"), Paths.get("/x/c.jpg")), noDirs))
        withTimeout(5_000) { q.progress.first { it != null } }.let { assertEquals("Pushing 1 of 3: a.jpg…", it!!.label) }
        gates[0].complete(Unit)
        withTimeout(5_000) { q.progress.first { it?.index == 2 } }.let { assertEquals("Pushing 2 of 3: b.jpg…", it!!.label) }
        gates[1].complete(Unit)
        gates[2].complete(Unit)
        withTimeout(5_000) { q.progress.first { it == null } }
        assertNull(q.progress.value)
    }

    @Test
    fun `failure reports adb's error and does not scan`() = runBlocking<Unit> {
        val adb = FakeAdb { args ->
            if (args[2] == "install") ProcessResult(1, "Performing Streamed Install\n", "adb: failed to install x: Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n")
            else ProcessResult(1, "", "adb: error: failed to copy 'b' to '/sdcard/Download/b': remote Permission denied\n")
        }
        val q = queue(adb)
        val results = async(start = CoroutineStart.UNDISPATCHED) { q.results.take(2).toList() }
        q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk"), Paths.get("/x/b")), noDirs))
        val got = withTimeout(5_000) { results.await() }
        assertEquals("Could not install a.apk: INSTALL_FAILED_VERSION_DOWNGRADE", got[0].message)
        assertEquals("Could not push b: failed to copy 'b' to '/sdcard/Download/b': remote Permission denied", got[1].message)
        assertTrue(scans.isEmpty())
    }

    @Test
    fun `old adb that prints Failure with exit 0 is a failure`() = runBlocking<Unit> {
        val q = queue(FakeAdb { ProcessResult(0, "Failure [INSTALL_FAILED_INVALID_APK]\n", "") })
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk")), noDirs))
        assertEquals("Could not install a.apk: INSTALL_FAILED_INVALID_APK", withTimeout(5_000) { result.await() }.message)
    }

    @Test
    fun `disconnected device is refused without running adb`() = runBlocking<Unit> {
        val adb = FakeAdb()
        val q = queue(adb, connected = { false })
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        assertFalse(q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk")), noDirs)))
        val r = withTimeout(5_000) { result.await() }
        assertIs<TransferResult.Refused>(r)
        assertEquals("Device not connected: cannot install", r.message)
        assertTrue(adb.calls.isEmpty())
    }

    @Test
    fun `a disconnect mid-queue cancels the rest with one result and no adb`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val connected = java.util.concurrent.atomic.AtomicBoolean(true)
        val adb = FakeAdb { gate.await(); ProcessResult(0, "", "") }
        val q = queue(adb, connected = { connected.get() })
        val results = async(start = CoroutineStart.UNDISPATCHED) { q.results.take(2).toList() }
        q.enqueue(requestsFor(listOf(Paths.get("/x/a.jpg"), Paths.get("/x/b.jpg"), Paths.get("/x/c.apk")), noDirs))
        withTimeout(5_000) { q.progress.first { it != null } }
        connected.set(false)
        gate.complete(Unit)
        val got = withTimeout(5_000) { results.await() }
        assertIs<TransferResult.Pushed>(got[0])
        assertEquals(TransferResult.Cancelled(2), got[1])
        assertEquals("2 transfers cancelled: device disconnected", got[1].message)
        assertEquals("1 transfer cancelled: device disconnected", TransferResult.Cancelled(1).message)
        kotlinx.coroutines.delay(100)
        assertEquals(1, adb.calls.size) // nothing else ran
        assertNull(q.progress.value)
        // The queue still works once the device is back.
        connected.set(true)
        val next = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        q.enqueue(requestsFor(listOf(Paths.get("/x/d.txt")), noDirs))
        assertEquals(TransferResult.Pushed("d.txt", DEFAULT_PUSH_TARGET), withTimeout(5_000) { next.await() })
    }

    @Test
    fun `timeout is reported`() = runBlocking<Unit> {
        val q = queue(FakeAdb { awaitCancellation() }, timeoutMs = 100)
        val result = async(start = CoroutineStart.UNDISPATCHED) { q.results.first() }
        q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk")), noDirs))
        assertEquals("Could not install a.apk: timed out after 0 min", withTimeout(5_000) { result.await() }.message)
    }

    @Test
    fun `closing the window cancels the running adb and drops the rest`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val adb = FakeAdb {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val windowScope = CoroutineScope(Job() + Dispatchers.Default)
        val q = FileTransferQueue("SER", adb, DEFAULT_PUSH_TARGET, { true }, { scans += it }, windowScope)
        q.enqueue(requestsFor(listOf(Paths.get("/x/a.apk"), Paths.get("/x/b.jpg")), noDirs))
        withTimeout(5_000) { started.await() }
        windowScope.cancel()
        withTimeout(5_000) { cancelled.await() }
        kotlinx.coroutines.delay(100)
        assertEquals(1, adb.calls.size)
        assertNull(q.progress.value)
        assertTrue(scans.isEmpty())
    }
}
