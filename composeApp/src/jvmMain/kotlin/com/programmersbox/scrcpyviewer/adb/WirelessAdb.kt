package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Result of switching a USB device to adb over Wi-Fi. */
sealed interface SwitchResult {
    data class Connected(val address: String) : SwitchResult
    data object NotOnWifi : SwitchResult
    data class TcpipFailed(val message: String) : SwitchResult
    data class ConnectFailed(val address: String, val message: String) : SwitchResult
}

/** Wireless debugging commands (pair, connect, mDNS discovery, `tcpip`) run through [client]. */
open class WirelessAdb(
    private val client: AdbClient,
    private val retryDelayMs: Long = 1000,
    /** Discovery used alongside adb's own (e.g. macOS `dns-sd`); its services are added when adb missed them. */
    private val extraDiscovery: (suspend () -> List<MdnsService>)? = null,
) {
    /** Services from `adb mdns services` plus [extraDiscovery]; failures count as nothing found. */
    open suspend fun mdnsServices(): List<MdnsService> {
        val fromAdb = safely { WirelessParsers.mdnsServices(client.exec("mdns", "services").stdout) }
        val extra = extraDiscovery?.let { safely { it() } }.orEmpty()
        return (fromAdb + extra).distinctBy { it.name to it.type }
    }

    private inline fun safely(block: () -> List<MdnsService>): List<MdnsService> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    open suspend fun pair(address: String, code: String): AdbOutcome = guarded {
        val result = client.execWithTimeout(PAIR_TIMEOUT_MS, "pair", address, code) ?: return@guarded AdbOutcome.Failed("timed out")
        WirelessParsers.pairOutcome(result)
    }

    open suspend fun connect(address: String): AdbOutcome = guarded {
        WirelessParsers.connectOutcome(client.exec("connect", address).stdout)
    }

    /**
     * `adb disconnect` expects `host[:port]`. A device adb connected through mDNS has a
     * `<service>._adb-tls-connect._tcp` serial, so on failure retry with that service's address.
     */
    open suspend fun disconnect(serial: String): AdbOutcome = guarded {
        val first = disconnectOnce(serial)
        if (first == AdbOutcome.Ok || !serial.endsWith(MDNS_CONNECT_SUFFIX)) return@guarded first
        val name = serial.removeSuffix(MDNS_CONNECT_SUFFIX)
        val address = mdnsServices().firstOrNull { it.type == MdnsType.Connect && it.name == name }?.address
            ?: return@guarded first
        disconnectOnce(address)
    }

    private suspend fun disconnectOnce(target: String): AdbOutcome {
        val result = client.exec("disconnect", target)
        return if (result.exitCode == 0) AdbOutcome.Ok else AdbOutcome.Failed((result.stderr + result.stdout).trim())
    }

    /** The device's Wi-Fi IPv4 address, read over its current connection, or null. */
    open suspend fun wifiAddress(serial: String): String? = try {
        WirelessParsers.wlanAddress(client.shell(serial, "ip -f inet addr show wlan0"))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Restarts adbd on [serial] in TCP mode on port 5555, then connects to it over Wi-Fi. */
    open suspend fun switchToWifi(serial: String): SwitchResult {
        val ip = wifiAddress(serial) ?: return SwitchResult.NotOnWifi
        val tcpip = try {
            client.exec("-s", serial, "tcpip", TCPIP_PORT.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SwitchResult.TcpipFailed(e.message ?: e.toString())
        }
        if (tcpip.exitCode != 0) return SwitchResult.TcpipFailed(tcpip.stderr.ifBlank { tcpip.stdout }.trim())
        val address = "$ip:$TCPIP_PORT"
        var last: AdbOutcome = AdbOutcome.Failed("not attempted")
        repeat(CONNECT_ATTEMPTS) { attempt ->
            // adbd takes a moment to restart in TCP mode before it accepts connections.
            if (attempt > 0) delay(retryDelayMs)
            last = connect(address)
            if (last == AdbOutcome.Ok) return SwitchResult.Connected(address)
        }
        return SwitchResult.ConnectFailed(address, (last as? AdbOutcome.Failed)?.message ?: "unknown error")
    }

    private inline fun guarded(block: () -> AdbOutcome): AdbOutcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AdbOutcome.Failed(e.message ?: e.toString())
    }

    private companion object {
        const val PAIR_TIMEOUT_MS = 15_000L
        const val TCPIP_PORT = 5555
        const val CONNECT_ATTEMPTS = 5
        const val MDNS_CONNECT_SUFFIX = "._adb-tls-connect._tcp"
    }
}
