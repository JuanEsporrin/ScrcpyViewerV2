package com.programmersbox.scrcpyviewer.files

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import com.programmersbox.scrcpyviewer.options.OptionValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

/** `DEFAULT_PUSH_TARGET` in v5.0 `app/src/file_pusher.c`. */
const val DEFAULT_PUSH_TARGET = "/sdcard/Download/"

/** Long enough for a multi-GB push over Wi-Fi; a hung adb still ends eventually. */
const val TRANSFER_TIMEOUT_MS = 30 * 60 * 1000L

enum class TransferKind { Install, Push }

data class TransferRequest(val path: Path, val kind: TransferKind) {
    val name: String get() = path.fileName?.toString() ?: path.toString()
}

/**
 * `.apk` files are installed, everything else is pushed (`is_apk()` in v5.0 `input_manager.c`, which compares the
 * extension case-sensitively; here `.APK` installs too). A directory is always pushed: `adb push` copies it
 * recursively, as v5.0 does for a dropped folder that is not named `*.apk`.
 */
fun classify(path: Path, isDirectory: (Path) -> Boolean = Files::isDirectory): TransferKind =
    if (!isDirectory(path) && path.fileName?.toString()?.endsWith(".apk", ignoreCase = true) == true) TransferKind.Install
    else TransferKind.Push

fun requestsFor(paths: List<Path>, isDirectory: (Path) -> Boolean = Files::isDirectory): List<TransferRequest> =
    paths.map { TransferRequest(it, classify(it, isDirectory)) }

/** The `push-target` option, or [DEFAULT_PUSH_TARGET]. */
fun pushTarget(values: OptionValues): String =
    values.entries["push-target"]?.takeIf { it.isNotBlank() } ?: DEFAULT_PUSH_TARGET

/** `sc_adb_install()` in v5.0 `app/src/adb/adb.c`. */
fun installArgs(serial: String, path: Path): List<String> = listOf("-s", serial, "install", "-r", path.toString())

/** `sc_adb_push()` in v5.0 `app/src/adb/adb.c`. */
fun pushArgs(serial: String, path: Path, target: String): List<String> = listOf("-s", serial, "push", path.toString(), target)

private val FAILURE = Regex("""Failure \[(.+)]""")

/**
 * The line of adb's output that says what went wrong: the `Failure [...]` reason of `adb install`
 * (`INSTALL_FAILED_VERSION_DOWNGRADE: ...`), else the last `error:` line without its `adb: error: ` prefix, else
 * the last line of stderr, then of stdout.
 */
fun adbErrorLine(result: ProcessResult): String {
    val lines = (result.stderr.lines() + result.stdout.lines()).map { it.trim() }.filter { it.isNotEmpty() }
    lines.firstNotNullOfOrNull { FAILURE.find(it)?.groupValues?.get(1) }?.let { return it }
    lines.lastOrNull { "error:" in it }?.let { return it.substringAfter("error:").trim() }
    return result.stderr.lines().lastOrNull { it.isNotBlank() }?.trim()
        ?: result.stdout.lines().lastOrNull { it.isNotBlank() }?.trim()
        ?: "adb exited with code ${result.exitCode}"
}

/** The item being transferred: [index] of [total] in the current batch (1-based). */
data class TransferProgress(val request: TransferRequest, val index: Int, val total: Int) {
    /** "Installing app.apk…" / "Pushing 2 of 3: photo.jpg…". */
    val label: String
        get() {
            val verb = if (request.kind == TransferKind.Install) "Installing" else "Pushing"
            return if (total > 1) "$verb $index of $total: ${request.name}…" else "$verb ${request.name}…"
        }
}

sealed interface TransferResult {
    val message: String

    data class Installed(val name: String) : TransferResult {
        override val message get() = "Installed $name"
    }

    data class Pushed(val name: String, val target: String) : TransferResult {
        override val message get() = "Pushed $name to $target"
    }

    data class Failed(val request: TransferRequest, val reason: String) : TransferResult {
        override val message
            get() = (if (request.kind == TransferKind.Install) "Could not install " else "Could not push ") + "${request.name}: $reason"
    }

    /** Nothing was run: the device is not connected. */
    data class Refused(override val message: String) : TransferResult
}

/**
 * One device's install / push queue, as v5.0 `file_pusher.c`: requests run one at a time, in order, on [scope]
 * (the window's scope; cancelling it kills the running adb and drops the rest, like `sc_file_pusher_stop`).
 * After a successful push the device is asked to scan [pushTarget] for media ([scanFile], v5.0 `request_scan_file`).
 *
 * [canTransfer] is false while the session is disconnected: requests are then refused instead of running adb
 * against a missing device (v5.0 ignores drops once `im->disconnected`).
 */
class FileTransferQueue(
    private val serial: String,
    private val adb: AdbClient,
    private val pushTarget: String,
    private val canTransfer: () -> Boolean,
    private val scanFile: (String) -> Unit,
    scope: CoroutineScope,
    private val timeoutMs: Long = TRANSFER_TIMEOUT_MS,
) {
    private val queue = Channel<TransferRequest>(Channel.UNLIMITED)
    private val lock = Any()
    private var pending = 0
    private var doneInBatch = 0

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    private val _results = MutableSharedFlow<TransferResult>(extraBufferCapacity = 64)
    val results: SharedFlow<TransferResult> = _results.asSharedFlow()

    init {
        scope.launch {
            try {
                for (request in queue) run(request)
            } finally {
                _progress.value = null
            }
        }.invokeOnCompletion { queue.cancel() }
    }

    /** Queues [requests]; false (and a [TransferResult.Refused]) when the device is not connected. */
    fun enqueue(requests: List<TransferRequest>): Boolean {
        if (requests.isEmpty()) return true
        if (!canTransfer()) {
            val what = when {
                requests.all { it.kind == TransferKind.Install } -> "install"
                requests.all { it.kind == TransferKind.Push } -> "push files"
                else -> "install or push files"
            }
            _results.tryEmit(TransferResult.Refused("Device not connected: cannot $what"))
            return false
        }
        synchronized(lock) {
            for (r in requests) {
                if (queue.trySend(r).isSuccess) pending++
            }
        }
        return true
    }

    fun enqueuePaths(paths: List<Path>): Boolean = enqueue(requestsFor(paths))

    private suspend fun run(request: TransferRequest) {
        val (index, total) = synchronized(lock) {
            pending--
            doneInBatch++
            doneInBatch to doneInBatch + pending
        }
        _progress.value = TransferProgress(request, index, total)
        val result = try {
            if (!canTransfer()) {
                TransferResult.Failed(request, "device disconnected")
            } else {
                val args = when (request.kind) {
                    TransferKind.Install -> installArgs(serial, request.path)
                    TransferKind.Push -> pushArgs(serial, request.path, pushTarget)
                }
                val r = adb.execWithTimeout(timeoutMs, *args.toTypedArray())
                when {
                    r == null -> TransferResult.Failed(request, "timed out after ${timeoutMs / 60_000} min")
                    // Old adb versions print "Failure [...]" and still exit 0.
                    r.exitCode != 0 || FAILURE.containsMatchIn(r.stdout) -> TransferResult.Failed(request, adbErrorLine(r))
                    request.kind == TransferKind.Install -> TransferResult.Installed(request.name)
                    else -> {
                        runCatching { scanFile(pushTarget) }
                        TransferResult.Pushed(request.name, pushTarget)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TransferResult.Failed(request, e.message ?: e.toString())
        }
        _results.tryEmit(result)
        synchronized(lock) {
            if (pending == 0) {
                doneInBatch = 0
                _progress.value = null
            }
        }
    }
}
