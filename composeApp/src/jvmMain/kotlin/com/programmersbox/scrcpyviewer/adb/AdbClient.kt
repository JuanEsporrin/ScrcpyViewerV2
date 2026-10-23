package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Talks to the adb server over its socket protocol and runs the adb binary for everything else. */
open class AdbClient(
    private val adbPath: () -> Path,
    private val host: String = "127.0.0.1",
    private val port: Int = 5037,
    private val startServerTimeoutMs: Long = 10_000,
) {
    open suspend fun startServer() {
        val result = runAdb(listOf("start-server"), startServerTimeoutMs)
        if (result.exitCode != 0) throw IOException("adb start-server failed (${result.exitCode}): ${result.stderr.trim()}")
    }

    /** Runs `shell:<cmd>` on [serial] and returns its output. */
    open suspend fun shell(serial: String, cmd: String): String = withContext(Dispatchers.IO) {
        openSocket().use { socket ->
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(AdbWire.encodeRequest("host:transport:$serial"))
            out.flush()
            AdbWire.readStatus(input)
            out.write(AdbWire.encodeRequest("shell:$cmd"))
            out.flush()
            AdbWire.readStatus(input)
            String(input.readBytes(), Charsets.UTF_8)
        }
    }

    /** Runs the adb binary with [args] (push, reverse, forward...). Cancellable; no time limit. */
    open suspend fun exec(vararg args: String): ProcessResult = runAdb(args.toList(), null)

    /**
     * Starts the adb binary with [args] as a long-running process (the scrcpy server's `adb shell`). stdin is
     * empty and stderr is merged into stdout; the caller must drain [Process.getInputStream] and destroy the process.
     */
    open fun startProcess(vararg args: String): Process =
        ProcessBuilder(listOf(adbPath().toString()) + args)
            .redirectInput(ProcessBuilder.Redirect.from(nullFile()))
            .redirectErrorStream(true)
            .start()

    /**
     * Output goes to temp files rather than pipes: `adb start-server` forks a daemon that inherits and holds
     * pipe ends open, so reading a pipe to EOF could block forever. The wait is interruptible, and the process
     * is destroyed on timeout or cancellation.
     */
    private suspend fun runAdb(args: List<String>, timeoutMs: Long?): ProcessResult = withContext(Dispatchers.IO) {
        val outFile = Files.createTempFile("adb-out", ".txt")
        val errFile = Files.createTempFile("adb-err", ".txt")
        var process: Process? = null
        try {
            val started = ProcessBuilder(listOf(adbPath().toString()) + args)
                .redirectInput(ProcessBuilder.Redirect.from(nullFile()))
                .redirectOutput(outFile.toFile())
                .redirectError(errFile.toFile())
                .start()
            process = started
            val exit = runInterruptible {
                if (timeoutMs == null) {
                    started.waitFor()
                } else if (started.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    started.exitValue()
                } else {
                    throw IOException("adb ${args.joinToString(" ")} timed out after $timeoutMs ms")
                }
            }
            ProcessResult(exit, Files.readString(outFile), Files.readString(errFile))
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            Files.deleteIfExists(outFile)
            Files.deleteIfExists(errFile)
        }
    }

    private fun nullFile() = java.io.File(if (System.getProperty("os.name").lowercase().contains("win")) "NUL" else "/dev/null")

    /**
     * Cold flow of `host:track-devices-l` payloads. Completes when the server closes the stream and
     * throws on connection or protocol errors. Cancelling the collector closes the socket.
     */
    open fun trackDevices(): Flow<String> = flow {
        coroutineScope {
            val socket = openSocket()
            val closer = launch(Dispatchers.IO) {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { socket.close() }
                }
            }
            try {
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                out.write(AdbWire.encodeRequest("host:track-devices-l"))
                out.flush()
                AdbWire.readStatus(input)
                while (true) {
                    val payload = AdbWire.readHexLengthPayload(input) ?: break
                    emit(payload)
                }
            } finally {
                closer.cancel()
                runCatching { socket.close() }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun openSocket(): Socket = Socket().also {
        try {
            it.connect(InetSocketAddress(host, port), 3000)
        } catch (e: Throwable) {
            runCatching { it.close() }
            throw e
        }
    }
}
