package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Parsers for the macOS `dns-sd` tool's `-B` (browse), `-L` (lookup) and `-G v4` (address) output. */
object DnsSdParsers {
    data class Target(val host: String, val port: Int, val interfaceIndex: Int)

    private val browseLine = Regex("""\s(Add|Rmv)\s+\d+\s+\d+\s+\S+\s+\S+\s+(.+)$""")
    private val lookupLine = Regex("""can be reached at (\S+):(\d+) \(interface (\d+)\)""")
    private val addressLine = Regex("""\sAdd\s+\S+\s+(\d+)\s+\S+\s+(\d+\.\d+\.\d+\.\d+)\s""")

    /** Instance names still advertised: every `Add` without a later `Rmv`, in first-seen order. */
    fun browseNames(lines: List<String>): List<String> {
        val names = LinkedHashSet<String>()
        for (line in lines) {
            val m = browseLine.find(line) ?: continue
            val name = m.groupValues[2].trim()
            if (m.groupValues[1] == "Add") names += name else names -= name
        }
        return names.toList()
    }

    fun target(lines: List<String>): Target? = lines.firstNotNullOfOrNull { line ->
        lookupLine.find(line)?.let { Target(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
    }

    /** The host's IPv4 address on [interfaceIndex], else its first address that is not loopback. */
    fun address(lines: List<String>, interfaceIndex: Int): String? {
        val found = lines.mapNotNull { line -> addressLine.find(line)?.let { it.groupValues[1].toInt() to it.groupValues[2] } }
            .filter { !it.second.startsWith("127.") }
        return (found.firstOrNull { it.first == interfaceIndex } ?: found.firstOrNull())?.second
    }
}

/**
 * Runs `dns-sd` with these arguments for at most `timeoutMs`, stopping early once `stopWhen` is true for the lines
 * read so far, and returns those lines. `dns-sd` never exits by itself.
 */
typealias DnsSdRunner = suspend (args: List<String>, timeoutMs: Long, stopWhen: (List<String>) -> Boolean) -> List<String>

/**
 * mDNS discovery through macOS's system resolver (`dns-sd`). adb's built-in discovery can miss phones, for example
 * on a Mac with both Wi-Fi and Ethernet up, while the system resolver sees every interface.
 */
class DnsSdDiscovery(private val run: DnsSdRunner = ::runDnsSd) {
    private val types = listOf("_adb-tls-pairing._tcp" to MdnsType.Pairing, "_adb-tls-connect._tcp" to MdnsType.Connect)

    /**
     * Browses both adb service types, then resolves every name found. Nothing is cached: a phone keeps its service
     * name but gets a new pairing port each time its pairing popup opens.
     */
    suspend fun services(): List<MdnsService> = coroutineScope {
        val browsed = types.map { (type, kind) ->
            async { DnsSdParsers.browseNames(run(listOf("-B", type, "local."), BROWSE_MS) { false }).map { Triple(it, type, kind) } }
        }.awaitAll().flatten()
        browsed.map { (name, type, kind) -> async { resolve(name, type, kind) } }.awaitAll().filterNotNull()
    }

    private suspend fun resolve(name: String, type: String, kind: MdnsType): MdnsService? {
        val target = DnsSdParsers.target(run(listOf("-L", name, type, "local."), RESOLVE_MS) { DnsSdParsers.target(it) != null })
            ?: return null
        val ip = DnsSdParsers.address(
            run(listOf("-G", "v4", target.host), RESOLVE_MS) { DnsSdParsers.address(it, target.interfaceIndex) != null },
            target.interfaceIndex,
        ) ?: return null
        return MdnsService(name, kind, "$ip:${target.port}")
    }

    private companion object {
        const val BROWSE_MS = 1_000L
        const val RESOLVE_MS = 1_500L
    }
}

private suspend fun runDnsSd(args: List<String>, timeoutMs: Long, stopWhen: (List<String>) -> Boolean): List<String> =
    withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(listOf("dns-sd") + args).redirectErrorStream(true).start()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        val lines = ArrayList<String>()
        // Destroying the process closes its output, which ends the read loop below.
        val killer = launch { delay(timeoutMs); process.destroy() }
        try {
            process.inputStream.bufferedReader().useLines { seq ->
                for (line in seq) {
                    lines += line
                    if (stopWhen(lines)) break
                }
            }
        } catch (_: java.io.IOException) {
            // stream closed by the timeout
        } finally {
            killer.cancel()
            process.destroy()
        }
        lines
    }
