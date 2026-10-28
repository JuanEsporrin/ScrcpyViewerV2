package com.programmersbox.scrcpyviewer.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WirelessParsersTest {
    @Test
    fun `mdns services parses pairing and connect lines`() {
        val out = "List of discovered mdns services\n" +
            "adb-3A111FDJG0029S-AbCdEf\t_adb-tls-connect._tcp\t192.168.1.31:37215\n" +
            "scrcpy-viewer-7QK2ab  _adb-tls-pairing._tcp.   192.168.1.31:41877\n\n"
        val services = WirelessParsers.mdnsServices(out)
        assertEquals(
            listOf(
                MdnsService("adb-3A111FDJG0029S-AbCdEf", MdnsType.Connect, "192.168.1.31:37215"),
                MdnsService("scrcpy-viewer-7QK2ab", MdnsType.Pairing, "192.168.1.31:41877"),
            ),
            services,
        )
        assertEquals("192.168.1.31", services[0].host)
    }

    @Test
    fun `mdns services empty and unknown types`() {
        assertEquals(emptyList(), WirelessParsers.mdnsServices("List of discovered mdns services\n\n"))
        assertEquals(emptyList(), WirelessParsers.mdnsServices("x\t_adb._tcp\t1.2.3.4:5\n"))
    }

    @Test
    fun `pair outcome`() {
        assertEquals(
            AdbOutcome.Ok,
            WirelessParsers.pairOutcome(ProcessResult(0, "Successfully paired to 192.168.1.31:41877 [guid=adb-X-Y]\n", "")),
        )
        assertEquals(
            AdbOutcome.WrongCode,
            WirelessParsers.pairOutcome(ProcessResult(1, "Failed: Wrong password or connection was dropped.\n", "")),
        )
        assertEquals(
            AdbOutcome.Failed("protocol fault (couldn't read status message): Undefined error: 0"),
            WirelessParsers.pairOutcome(ProcessResult(1, "", "error: protocol fault (couldn't read status message): Undefined error: 0\n")),
        )
    }

    @Test
    fun `connect outcome ignores exit code`() {
        assertEquals(AdbOutcome.Ok, WirelessParsers.connectOutcome("connected to 192.168.1.31:5555\n"))
        assertEquals(AdbOutcome.Ok, WirelessParsers.connectOutcome("already connected to 192.168.1.31:5555\n"))
        assertEquals(
            AdbOutcome.Failed("Connection refused"),
            WirelessParsers.connectOutcome("failed to connect to '127.0.0.1:1': Connection refused\n"),
        )
        assertEquals(
            AdbOutcome.Failed("No route to host"),
            WirelessParsers.connectOutcome("cannot connect to 10.0.0.9:5555: No route to host\n"),
        )
    }

    @Test
    fun `no route to host on mac points to local network permission`() {
        assertEquals(
            "No route to host. Allow Local Network access for this app in System Settings › Privacy & Security › Local Network.",
            WirelessParsers.withLocalNetworkHint("No route to host", mac = true),
        )
        assertEquals("No route to host", WirelessParsers.withLocalNetworkHint("No route to host", mac = false))
        assertEquals("Connection refused", WirelessParsers.withLocalNetworkHint("Connection refused", mac = true))
    }

    @Test
    fun `wlan address`() {
        val out = "30: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500\n" +
            "    inet 192.168.1.31/24 brd 192.168.1.255 scope global wlan0\n"
        assertEquals("192.168.1.31", WirelessParsers.wlanAddress(out))
        assertNull(WirelessParsers.wlanAddress("Device \"wlan0\" does not exist.\n"))
        assertNull(WirelessParsers.wlanAddress(""))
    }

    @Test
    fun `serial from service name`() {
        assertEquals("3A111FDJG0029S", WirelessParsers.serialFromServiceName("adb-3A111FDJG0029S-AbCdEf"))
        assertEquals("scrcpy-viewer-7QK2ab", WirelessParsers.serialFromServiceName("scrcpy-viewer-7QK2ab"))
    }

    @Test
    fun `qr payload and random`() {
        assertEquals(
            "WIFI:T:ADB;S:scrcpy-viewer-abc123;P:9Xc4mT28aa;;",
            WirelessParsers.qrPayload("scrcpy-viewer-abc123", "9Xc4mT28aa"),
        )
        val r = WirelessParsers.randomAlnum(10)
        assertEquals(10, r.length)
        assertTrue(r.all { it.isLetterOrDigit() && it.code < 128 })
    }
}
