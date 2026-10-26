package com.programmersbox.scrcpyviewer

import com.programmersbox.scrcpyviewer.adb.AdbClient
import com.programmersbox.scrcpyviewer.adb.ProcessResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import java.nio.file.Path

/** The devices the screenshot tests show: a USB Pixel, a wireless tablet and an unauthorized phone. */
const val FAKE_DEVICES_PAYLOAD =
    "39121FDJH004AB device product:husky model:Pixel_8_Pro device:husky transport_id:1\n" +
        "adb-R5CT90ABCDE-xK2fPq._adb-tls-connect._tcp device product:gts9wifi model:SM_X710 device:gts9wifi transport_id:2\n" +
        "ZY22H8KLMN unauthorized transport_id:3"

/** An adb client that never starts a process or opens a socket. */
class FakeAdbClient(private val payload: String = FAKE_DEVICES_PAYLOAD) : AdbClient(adbPath = { error("no real adb in tests") }) {
    override fun trackDevices(): Flow<String> = flow {
        emit(payload)
        awaitCancellation()
    }

    override suspend fun startServer() {}

    override suspend fun shell(serial: String, cmd: String): String =
        if (serial == "39121FDJH004AB") "Pixel 8 Pro\n15\n35\n" else ""

    override suspend fun exec(vararg args: String): ProcessResult =
        ProcessResult(0, "Android Debug Bridge version 1.0.41\nVersion 35.0.2-12147458\n", "")

    override fun startProcess(vararg args: String): Process = error("no real adb in tests")
}

/** An [AppGraph] on a fresh temp data dir with [FakeAdbClient]; cancel its scope when done. */
fun fakeAppGraph(dataDir: Path = Files.createTempDirectory("scrcpyviewer-test")): AppGraph =
    AppGraph(dataDir = dataDir, adbClientFactory = { FakeAdbClient() })
