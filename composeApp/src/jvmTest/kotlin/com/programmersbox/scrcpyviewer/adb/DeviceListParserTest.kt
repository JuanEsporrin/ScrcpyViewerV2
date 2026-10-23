package com.programmersbox.scrcpyviewer.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceListParserTest {
    private val golden = """
        39121FDJH004AB         device usb:1-1 product:husky model:Pixel_8_Pro device:husky transport_id:3
        192.168.1.42:5555      device product:gts9wifi model:SM_X710 device:gts9wifi transport_id:5
        emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1
        ZY22H8KLMN             unauthorized usb:1-2 transport_id:6
        R5CT30XYZ12            offline transport_id:7
    """.trimIndent()

    @Test
    fun `parses golden payload`() {
        val d = DeviceListParser.parse(golden)
        assertEquals(5, d.size)
        assertEquals(
            listOf(DeviceState.Device, DeviceState.Device, DeviceState.Device, DeviceState.Unauthorized, DeviceState.Offline),
            d.map { it.state },
        )
        assertEquals("Pixel_8_Pro", d[0].model)
        assertEquals("husky", d[0].product)
        assertEquals(3L, d[0].transportId)
        assertEquals(listOf(false, true, false, false, false), d.map { it.isTcp })
        assertEquals("unauthorized", d[3].rawState)
        assertEquals(null, d[3].model)
    }

    @Test
    fun `empty payload gives empty list`() {
        assertTrue(DeviceListParser.parse("").isEmpty())
    }

    @Test
    fun `unknown state maps to Other and mdns serial is tcp`() {
        val d = DeviceListParser.parse("adb-ABC123-xyz._adb-tls-connect._tcp recovery transport_id:9\nusb-serial device").single { it.serial.startsWith("adb-") }
        assertEquals(DeviceState.Other, d.state)
        assertEquals("recovery", d.rawState)
        assertTrue(d.isTcp)
        assertFalse(DeviceListParser.parse("usb-serial device").single().isTcp)
    }
}
