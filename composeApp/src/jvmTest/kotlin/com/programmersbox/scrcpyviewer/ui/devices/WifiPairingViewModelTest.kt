package com.programmersbox.scrcpyviewer.ui.devices

import com.programmersbox.scrcpyviewer.adb.AdbOutcome
import com.programmersbox.scrcpyviewer.adb.FakeWirelessAdb
import com.programmersbox.scrcpyviewer.adb.MdnsService
import com.programmersbox.scrcpyviewer.adb.MdnsType
import com.programmersbox.scrcpyviewer.adb.WirelessParsers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WifiPairingViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val done = CompletableDeferred<WifiNotice>()

    private val polls = AtomicInteger()
    private val wireless = object : FakeWirelessAdb() {
        override suspend fun mdnsServices(): List<MdnsService> {
            polls.incrementAndGet()
            return super.mdnsServices()
        }
    }

    private fun vm() = WifiPairingViewModel(
        wireless, scope, onDone = { done.complete(it) }, random = Random(1), pollMs = 10, connectWaitMs = 100,
    )

    @AfterTest
    fun tearDown() = scope.cancel()

    private suspend fun awaitState(vm: WifiPairingViewModel, condition: (WifiPairingState) -> Boolean) =
        withTimeout(3000) { while (!condition(vm.state.value)) delay(5) }

    @Test
    fun `qr payload uses generated name and password`() {
        val s = vm().state.value
        assertTrue(s.qrName.startsWith("scrcpy-viewer-"))
        assertEquals(20, s.qrName.length)
        assertEquals(10, s.qrPassword.length)
        assertEquals(WirelessParsers.qrPayload(s.qrName, s.qrPassword), s.qrPayload)
        assertEquals("Pair", s.primaryLabel)
        assertFalse(s.primaryEnabled)
    }

    @Test
    fun `qr auto pairs and connects`() = runBlocking {
        val vm = vm()
        val s = vm.state.value
        wireless.services = listOf(
            MdnsService(s.qrName, MdnsType.Pairing, "192.168.1.31:41877"),
            MdnsService("adb-ABC-x", MdnsType.Connect, "192.168.1.31:37215"),
        )
        val notice = withTimeout(3000) { done.await() }
        assertEquals("ABC connected over Wi-Fi", notice.text)
        assertEquals("adb-ABC-x", notice.mdnsName)
        assertTrue("pair 192.168.1.31:41877 ${s.qrPassword}" in wireless.calls)
        assertTrue("connect 192.168.1.31:37215" in wireless.calls)
    }

    @Test
    fun `paired without connect service`() = runBlocking {
        val vm = vm()
        wireless.services = listOf(MdnsService("adb-ABC-y", MdnsType.Pairing, "192.168.1.31:41877"))
        vm.selectTab(PairingTab.Code)
        awaitState(vm) { it.pairingServices.isNotEmpty() }
        vm.choose("192.168.1.31:41877")
        vm.setCode("482913")
        vm.submit()
        assertEquals("Paired with ABC", withTimeout(3000) { done.await() }.text)
        assertEquals(listOf("pair 192.168.1.31:41877 482913"), wireless.calls)
    }

    @Test
    fun `wrong code shows error and stays open`() = runBlocking {
        val vm = vm()
        wireless.pairResult = AdbOutcome.WrongCode
        wireless.services = listOf(MdnsService("adb-ABC-y", MdnsType.Pairing, "192.168.1.31:41877"))
        vm.selectTab(PairingTab.Code)
        awaitState(vm) { it.pairingServices.isNotEmpty() }
        vm.choose("192.168.1.31:41877")
        vm.setCode("111111")
        vm.submit()
        awaitState(vm) { it.error != null && !it.busy }
        assertEquals("Wrong pairing code. Check the code on the phone and try again.", vm.state.value.error)
        assertFalse(done.isCompleted)
    }

    @Test
    fun `code tab enables pair only with choice and six digits`() {
        val vm = vm()
        vm.selectTab(PairingTab.Code)
        vm.setCode("12a34567")
        assertEquals("123456", vm.state.value.code)
        assertFalse(vm.state.value.primaryEnabled)
        vm.choose("192.168.1.31:41877")
        assertTrue(vm.state.value.primaryEnabled)
        vm.setCode("12345")
        assertFalse(vm.state.value.primaryEnabled)
    }

    @Test
    fun `ip tab validates`() {
        val vm = vm()
        vm.selectTab(PairingTab.Ip)
        assertEquals("Connect", vm.state.value.primaryLabel)
        vm.setIp("999.1.1.1")
        vm.setPort("0")
        vm.submit()
        assertEquals("Enter an IP address", vm.state.value.ipError)
        assertEquals("Enter a port from 1 to 65535", vm.state.value.portError)
        assertEquals(emptyList(), wireless.calls)
        vm.setIp("192.168.1.31")
        assertNull(vm.state.value.ipError)
    }

    @Test
    fun `ip tab pairs first when pairing fields filled`() = runBlocking {
        val vm = vm()
        vm.selectTab(PairingTab.Ip)
        vm.setIp("192.168.1.31")
        vm.setPairPort("41877")
        vm.setPairCode("482913")
        vm.submit()
        assertEquals("192.168.1.31:5555 connected over Wi-Fi", withTimeout(3000) { done.await() }.text)
        assertEquals(listOf("pair 192.168.1.31:41877 482913", "connect 192.168.1.31:5555"), wireless.calls)
    }

    @Test
    fun `ip tab connect failure stays open`() = runBlocking {
        val vm = vm()
        wireless.connectResult = AdbOutcome.Failed("Connection refused")
        vm.selectTab(PairingTab.Ip)
        vm.setIp("192.168.1.31")
        vm.submit()
        awaitState(vm) { it.error != null && !it.busy }
        assertEquals("Couldn't connect: Connection refused", vm.state.value.error)
    }

    @Test
    fun `blocked server offers Restart adb in the dialog`() = runBlocking {
        val vm = vm()
        wireless.connectResult = AdbOutcome.Failed("No route to host", restartCanFix = true)
        vm.selectTab(PairingTab.Ip)
        vm.setIp("10.0.24.47")
        vm.submit()
        awaitState(vm) { it.error != null && !it.busy }
        assertTrue(vm.state.value.canRestartAdb)
        vm.restartAdb()
        awaitState(vm) { !it.busy && !it.canRestartAdb }
        assertTrue("restart" in wireless.calls)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `close cancels polling`() = runBlocking {
        val vm = vm()
        withTimeout(3000) { while (polls.get() < 2) delay(5) }
        vm.close()
        delay(30)
        val after = polls.get()
        delay(60)
        assertEquals(after, polls.get())
    }
}
