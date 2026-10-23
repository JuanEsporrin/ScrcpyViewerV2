package com.programmersbox.scrcpyviewer.adb

enum class DeviceState { Device, Unauthorized, Offline, Other }

data class DeviceInfo(val model: String, val androidVersion: String, val sdk: Int)

data class Device(
    val serial: String,
    val state: DeviceState,
    val rawState: String,
    val model: String?,
    val product: String?,
    val transportId: Long?,
    val isTcp: Boolean,
    val info: DeviceInfo? = null,
)
