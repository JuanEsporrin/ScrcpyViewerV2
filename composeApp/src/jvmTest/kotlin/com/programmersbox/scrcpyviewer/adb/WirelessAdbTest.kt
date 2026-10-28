package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class WirelessAdbTest {
    /** Answers `exec` from [onExec] and `shell` with [shellOut]; records every exec's args. */
    private class FakeClient(
        private val shellOut: String = "",
        private val onExec: (List<String>) -> ProcessResult = { ProcessResult(0, "", "") },
    ) : AdbClient({ error("unused") as Path }) {
        val execCalls = mutableListOf<List<String>>()
        override suspend fun shell(serial: String, cmd: String): String = shellOut
        override suspend fun exec(vararg args: String): ProcessResult {
            execCalls += args.toList()
            return onExec(args.toList())
        }
    }

    private val wlan = "30: wlan0: <UP>\n    inet 192.168.1.31/24 brd 192.168.1.255 scope global wlan0\n"

    @Test
    fun `switchToWifi runs tcpip then connects`() = runBlocking {
        val client = FakeClient(wlan) { args ->
            if (args[0] == "connect") ProcessResult(0, "connected to 192.168.1.31:5555\n", "") else ProcessResult(0, "", "")
        }
        assertEquals(SwitchResult.Connected("192.168.1.31:5555"), WirelessAdb(client, retryDelayMs = 0).switchToWifi("S1"))
        assertEquals(listOf(listOf("-s", "S1", "tcpip", "5555"), listOf("connect", "192.168.1.31:5555")), client.execCalls)
    }

    @Test
    fun `switchToWifi with no wlan ip runs nothing`() = runBlocking {
        val client = FakeClient("")
        assertEquals(SwitchResult.NotOnWifi, WirelessAdb(client, retryDelayMs = 0).switchToWifi("S1"))
        assertEquals(emptyList(), client.execCalls)
    }

    @Test
    fun `switchToWifi retries connect up to 5 times`() = runBlocking {
        val client = FakeClient(wlan) { args ->
            if (args[0] == "connect") ProcessResult(0, "failed to connect to '192.168.1.31:5555': Connection refused\n", "")
            else ProcessResult(0, "", "")
        }
        assertEquals(
            SwitchResult.ConnectFailed("192.168.1.31:5555", "Connection refused"),
            WirelessAdb(client, retryDelayMs = 0).switchToWifi("S1"),
        )
        assertEquals(5, client.execCalls.count { it[0] == "connect" })
    }

    @Test
    fun `switchToWifi tcpip failure`() = runBlocking {
        val client = FakeClient(wlan) { ProcessResult(1, "", "error: closed\n") }
        assertEquals(SwitchResult.TcpipFailed("error: closed"), WirelessAdb(client, retryDelayMs = 0).switchToWifi("S1"))
    }

    @Test
    fun `disconnect falls back to the mdns service address`() = runBlocking {
        val mdns = "adb-ABC-x._adb-tls-connect._tcp"
        val client = FakeClient { args ->
            when {
                args == listOf("mdns", "services") ->
                    ProcessResult(0, "List of discovered mdns services\nadb-ABC-x\t_adb-tls-connect._tcp\t192.168.1.31:37215\n", "")
                args == listOf("disconnect", mdns) -> ProcessResult(1, "", "error: no such device '$mdns:5555'\n")
                else -> ProcessResult(0, "disconnected 192.168.1.31:37215\n", "")
            }
        }
        assertEquals(AdbOutcome.Ok, WirelessAdb(client).disconnect(mdns))
        assertEquals(listOf("disconnect", "192.168.1.31:37215"), client.execCalls.last())
    }

    @Test
    fun `mdnsServices swallows failures`() = runBlocking {
        val client = FakeClient { throw IOException("boom") }
        assertEquals(emptyList(), WirelessAdb(client).mdnsServices())
    }

    @Test
    fun `options key prefers ro serialno for tcp devices only`() {
        val usb = Device("ABC", DeviceState.Device, "device", null, null, 1, false, DeviceInfo("P", "15", 35, "XYZ"))
        val tcp = Device("192.168.1.31:5555", DeviceState.Device, "device", null, null, 2, true, DeviceInfo("P", "15", 35, "ABC"))
        val tcpNoInfo = tcp.copy(info = null)
        assertEquals("ABC", usb.optionsKey())
        assertEquals("ABC", tcp.optionsKey())
        assertEquals("192.168.1.31:5555", tcpNoInfo.optionsKey())
    }
}
