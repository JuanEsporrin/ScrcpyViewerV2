package com.programmersbox.scrcpyviewer.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Output samples are real `dns-sd` output captured on macOS 27. */
class DnsSdDiscoveryTest {
    private val browse = """
        Browsing for _adb-tls-pairing._tcp.local.
        DATE: ---Wed 28 Oct 2026---
         9:17:29.099  ...STARTING...
        Timestamp     A/R    Flags  if Domain               Service Type         Instance Name
         9:17:29.099  Add        3   1 local.               _adb-tls-pairing._tcp. adb-TESTSERIAL-abc123
         9:17:29.099  Add        3  23 local.               _adb-tls-pairing._tcp. adb-TESTSERIAL-abc123
         9:17:29.099  Add        2  15 local.               _adb-tls-pairing._tcp. adb-TESTSERIAL-abc123
         9:17:29.291  Add        2  15 local.               _adb-tls-pairing._tcp. adb-R5CX12GDWPZ-tKl7wT
         9:17:30.001  Rmv        0  15 local.               _adb-tls-pairing._tcp. adb-TESTSERIAL-abc123
    """.trimIndent().lines()

    private val lookup = """
        Lookup adb-R5CX12GDWPZ-tKl7wT._adb-tls-pairing._tcp.local.
        DATE: ---Wed 28 Oct 2026---
         9:17:31.113  ...STARTING...
         9:17:31.113  adb-R5CX12GDWPZ-tKl7wT._adb-tls-pairing._tcp.local. can be reached at Android-2.local.:41877 (interface 15) Flags: 1
    """.trimIndent().lines()

    private val address = """
        DATE: ---Wed 28 Oct 2026---
         9:17:33.140  ...STARTING...
        Timestamp     A/R  Flags         IF  Hostname                               Address                                      TTL
         9:17:33.141  Add  40000003       1  Android-2.local.                       127.0.0.1                                    4500
         9:17:33.141  Add  40000003      23  Android-2.local.                       10.0.4.99                                    4500
         9:17:33.141  Add  40000002      15  Android-2.local.                       10.0.28.169                                  4500
    """.trimIndent().lines()

    @Test
    fun `browse keeps added names minus removed ones`() {
        assertEquals(listOf("adb-R5CX12GDWPZ-tKl7wT"), DnsSdParsers.browseNames(browse))
    }

    @Test
    fun `lookup gives host port and interface`() {
        assertEquals(DnsSdParsers.Target("Android-2.local.", 41877, 15), DnsSdParsers.target(lookup))
        assertNull(DnsSdParsers.target(lookup.take(3)))
    }

    @Test
    fun `address prefers the lookup interface and skips loopback`() {
        assertEquals("10.0.28.169", DnsSdParsers.address(address, interfaceIndex = 15))
        assertEquals("10.0.4.99", DnsSdParsers.address(address, interfaceIndex = 99))
        assertNull(DnsSdParsers.address(address.take(3), interfaceIndex = 15))
    }

    @Test
    fun `services browses resolves and maps types`() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val discovery = DnsSdDiscovery { args, _, _ ->
            calls += args
            when {
                args[0] == "-B" && args[1] == "_adb-tls-pairing._tcp" -> browse
                args[0] == "-B" -> emptyList()
                args[0] == "-L" -> lookup
                else -> address
            }
        }
        assertEquals(listOf(MdnsService("adb-R5CX12GDWPZ-tKl7wT", MdnsType.Pairing, "10.0.28.169:41877")), discovery.services())
    }

    @Test
    fun `cancelling a run kills the process`() = runBlocking {
        com.programmersbox.scrcpyviewer.assumePosix()
        // A process that prints nothing and never exits, like dns-sd waiting for answers.
        val job = launch(Dispatchers.Default) { runLines(listOf("sleep", "30"), timeoutMs = 60_000) { false } }
        delay(300)
        withTimeout(2_000) { job.cancelAndJoin() }
    }

    @Test
    fun `a run stops at its timeout`() = runBlocking {
        com.programmersbox.scrcpyviewer.assumePosix()
        // `; true` keeps sh from exec-ing sleep, so sleep is a child process holding the output pipe open. On Linux
        // the read only ends once that child is gone, so the timeout has to kill the whole process tree.
        val lines = withTimeout(3_000) { runLines(listOf("sh", "-c", "echo first; sleep 37; true"), timeoutMs = 300) { false } }
        assertEquals(listOf("first"), lines)
        delay(200)
        val leftover = ProcessHandle.allProcesses().filter { p ->
            p.info().command().orElse("").endsWith("sleep") && p.info().arguments().orElse(emptyArray()).contentEquals(arrayOf("37"))
        }.toList()
        leftover.forEach { it.destroy() }
        assertEquals(emptyList(), leftover.map { it.pid() }, "the child sleep should be killed with its shell")
    }

    @Test
    fun `each poll resolves again because the pairing port changes under the same name`() = runBlocking {
        var port = 41877
        val discovery = DnsSdDiscovery { args, _, _ ->
            when {
                args[0] == "-B" && args[1] == "_adb-tls-pairing._tcp" -> browse
                args[0] == "-B" -> emptyList()
                args[0] == "-L" -> lookup.map { it.replace(":41877", ":$port") }
                else -> address
            }
        }
        assertEquals("10.0.28.169:41877", discovery.services().single().address)
        port = 39001 // the phone reopened its pairing popup: same service name, new port
        assertEquals("10.0.28.169:39001", discovery.services().single().address)
    }
}
