package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Talks to the adb server over its socket protocol and runs the adb binary for everything else. */
open class AdbClient(
    private val adbPath: () -> Path,
    private val host: String = "127.0.0.1",
    private val port: Int = 5037,
) {
    open suspend fun startServer() {
        val result = exec("start-server")
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

    /** Runs the adb binary with [args] (push, reverse, forward, start-server...). */
    open suspend fun exec(vararg args: String): ProcessResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(adbPath().toString()) + args).start()
        process.outputStream.close()
        try {
            coroutineScope {
                val stdout = async { process.inputStream.readBytes() }
                val stderr = async { process.errorStream.readBytes() }
                val exit = process.waitFor()
                ProcessResult(exit, String(stdout.await(), Charsets.UTF_8), String(stderr.await(), Charsets.UTF_8))
            }
        } finally {
            process.destroy()
        }
    }

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
