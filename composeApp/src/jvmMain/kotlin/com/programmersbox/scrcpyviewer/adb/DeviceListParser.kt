package com.programmersbox.scrcpyviewer.adb

/** Parses the `host:track-devices-l` / `adb devices -l` payload: `serial<ws>state key:value...` per line. */
object DeviceListParser {
    private val tcpSerial = Regex("^.+:\\d+$")

    fun parse(payload: String): List<Device> =
        payload.lineSequence().mapNotNull(::parseLine).toList()

    private fun parseLine(line: String): Device? {
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.size < 2) return null
        val serial = tokens[0]
        val rawState = tokens[1]
        val props = tokens.drop(2).mapNotNull {
            val i = it.indexOf(':')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val state = when (rawState) {
            "device" -> DeviceState.Device
            "unauthorized" -> DeviceState.Unauthorized
            "offline" -> DeviceState.Offline
            else -> DeviceState.Other
        }
        val isTcp = tcpSerial.matches(serial) || (serial.startsWith("adb-") && "._adb-tls-connect." in serial)
        return Device(
            serial = serial,
            state = state,
            rawState = rawState,
            model = props["model"],
            product = props["product"],
            transportId = props["transport_id"]?.toLongOrNull(),
            isTcp = isTcp,
        )
    }
}
