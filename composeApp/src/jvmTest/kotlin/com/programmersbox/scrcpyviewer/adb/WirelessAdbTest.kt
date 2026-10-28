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
    fun `mdnsServices adds services only the extra discovery found`() = runBlocking {
        val client = FakeClient {
            ProcessResult(0, "List of discovered mdns services\nadb-A-x\t_adb-tls-connect._tcp\t10.0.0.5:37000\n", "")
        }
        val extra = listOf(
            MdnsService("adb-A-x", MdnsType.Connect, "10.0.0.5:37000"),
            MdnsService("adb-B-y", MdnsType.Pairing, "10.0.28.169:41877"),
        )
        assertEquals(
            listOf(MdnsService("adb-A-x", MdnsType.Connect, "10.0.0.5:37000"), MdnsService("adb-B-y", MdnsType.Pairing, "10.0.28.169:41877")),
            WirelessAdb(client, extraDiscovery = { extra }).mdnsServices(),
        )
    }

    /** Fails connects with "No route to host" until the server is restarted; [ours] says who started the server. */
    private class BlockedServerClient(private var ours: Boolean) : AdbClient({ error("unused") as Path }) {
        val calls = mutableListOf<String>()
        override val startedServer: Boolean get() = ours
        override suspend fun restartServer() {
            calls += "restart"
            ours = true
        }
        override suspend fun exec(vararg args: String): ProcessResult {
            calls += args.joinToString(" ")
            return if (ours && "restart" in calls) ProcessResult(0, "connected to ${args.last()}\n", "")
            else ProcessResult(0, "failed to connect to '${args.last()}': No route to host\n", "")
        }
    }

    @Test
    fun `connect restarts a server this app did not start when it is blocked`() = runBlocking {
        val client = BlockedServerClient(ours = false)
        val wireless = WirelessAdb(client, mac = true, canRestartServer = { true })
        assertEquals(AdbOutcome.Ok, wireless.connect("10.0.24.47:5555"))
        assertEquals(listOf("connect 10.0.24.47:5555", "restart", "connect 10.0.24.47:5555"), client.calls)
    }

    @Test
    fun `connect does not restart the app's own server`() = runBlocking {
        val client = BlockedServerClient(ours = true)
        val wireless = WirelessAdb(client, mac = true, canRestartServer = { true })
        assertEquals(AdbOutcome.Failed("No route to host"), wireless.connect("10.0.24.47:5555"))
        assertEquals(listOf("connect 10.0.24.47:5555"), client.calls)
    }

    @Test
    fun `connect asks for a restart while mirrors are open`() = runBlocking {
        val client = BlockedServerClient(ours = false)
        val wireless = WirelessAdb(client, mac = true, canRestartServer = { false })
        assertEquals(AdbOutcome.Failed("No route to host", restartCanFix = true), wireless.connect("10.0.24.47:5555"))
        assertEquals(listOf("connect 10.0.24.47:5555"), client.calls)
    }

    @Test
    fun `connect never restarts off macOS`() = runBlocking {
        val client = BlockedServerClient(ours = false)
        val wireless = WirelessAdb(client, mac = false, canRestartServer = { true })
        assertEquals(AdbOutcome.Failed("No route to host"), wireless.connect("10.0.24.47:5555"))
    }

    @Test
    fun `pair restarts a blocked server on a status message fault`() = runBlocking {
        var restarted = false
        val client = object : AdbClient({ error("unused") as Path }) {
            override val startedServer: Boolean get() = restarted
            override suspend fun restartServer() { restarted = true }
            override suspend fun exec(vararg args: String): ProcessResult =
                if (restarted) ProcessResult(0, "Successfully paired to ${args[1]} [guid=adb-X-Y]\n", "")
                else ProcessResult(1, "", "error: protocol fault (couldn't read status message): Undefined error: 0\n")
        }
        val wireless = WirelessAdb(client, mac = true, canRestartServer = { true })
        assertEquals(AdbOutcome.Ok, wireless.pair("10.0.24.47:41877", "482913"))
        assertEquals(true, restarted)
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
