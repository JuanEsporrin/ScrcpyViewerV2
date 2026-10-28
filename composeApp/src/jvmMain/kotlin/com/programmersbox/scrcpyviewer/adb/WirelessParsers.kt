package com.programmersbox.scrcpyviewer.adb

import com.programmersbox.scrcpyviewer.Os
import kotlin.random.Random

enum class MdnsType { Pairing, Connect }

/** One line of `adb mdns services`: a phone advertising a pairing or connect port. */
data class MdnsService(val name: String, val type: MdnsType, val address: String) {
    /** The address without its port. */
    val host: String get() = address.substringBeforeLast(':')
}

/** Result of `adb pair`, `adb connect` or `adb disconnect`. */
sealed interface AdbOutcome {
    data object Ok : AdbOutcome
    data object WrongCode : AdbOutcome
    data class Failed(val message: String) : AdbOutcome
}

/** Parsers for the text adb prints for its wireless commands. */
object WirelessParsers {
    private val inet = Regex("""inet (\d+\.\d+\.\d+\.\d+)/""")
    private val serviceName = Regex("^adb-(.+)-[^-]+$")
    private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** Pairing and connect services from `adb mdns services`; the header and other service types are dropped. */
    fun mdnsServices(stdout: String): List<MdnsService> = stdout.lines().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size != 3) return@mapNotNull null
        val type = when (parts[1].trimEnd('.')) {
            "_adb-tls-pairing._tcp" -> MdnsType.Pairing
            "_adb-tls-connect._tcp" -> MdnsType.Connect
            else -> return@mapNotNull null
        }
        MdnsService(parts[0], type, parts[2])
    }

    fun pairOutcome(result: ProcessResult): AdbOutcome {
        if ("Successfully paired" in result.stdout) return AdbOutcome.Ok
        if ("Wrong password" in result.stdout + result.stderr) return AdbOutcome.WrongCode
        val line = firstLine(result.stderr) ?: firstLine(result.stdout) ?: "adb pair exited with ${result.exitCode}"
        return AdbOutcome.Failed(line.removePrefix("error: ").removePrefix("Failed: "))
    }

    /** `adb connect` exits 0 even when it fails, so only its output decides. */
    fun connectOutcome(stdout: String): AdbOutcome {
        val lines = stdout.lines().map { it.trim() }
        if (lines.any { it.startsWith("connected to") || it.startsWith("already connected to") }) return AdbOutcome.Ok
        val line = firstLine(stdout) ?: return AdbOutcome.Failed("no output from adb connect")
        val i = line.lastIndexOf(": ")
        return AdbOutcome.Failed(if (i >= 0) line.substring(i + 2) else line)
    }

    /**
     * macOS reports "No route to host" when Local Network privacy blocks the adb server this app started, even though
     * the network is fine; add where to allow it.
     */
    fun withLocalNetworkHint(message: String, mac: Boolean = Os.isMac): String =
        if (mac && "No route to host" in message) {
            "$message. Allow Local Network access for this app in System Settings › Privacy & Security › Local Network."
        } else {
            message
        }

    /** The IPv4 address in `ip -f inet addr show wlan0` output, or null when the interface has none. */
    fun wlanAddress(ipAddrOutput: String): String? = inet.find(ipAddrOutput)?.groupValues?.get(1)

    /** `adb-<serial>-<suffix>` gives `<serial>`; any other name is returned unchanged. */
    fun serialFromServiceName(name: String): String = serviceName.find(name)?.groupValues?.get(1) ?: name

    /** The Wi-Fi QR text Android's "Pair device with QR code" scanner expects. */
    fun qrPayload(name: String, password: String): String = "WIFI:T:ADB;S:$name;P:$password;;"

    fun randomAlnum(length: Int, random: Random = Random.Default): String =
        buildString(length) { repeat(length) { append(ALNUM[random.nextInt(ALNUM.length)]) } }

    private fun firstLine(text: String): String? = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
}
