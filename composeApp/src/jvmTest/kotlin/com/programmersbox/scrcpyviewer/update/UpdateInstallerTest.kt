package com.programmersbox.scrcpyviewer.update

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateInstallerTest {
    private val dir: Path = Files.createTempDirectory("installer")
    private val hasSh = File("/bin/sh").canExecute()

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private val macRequest = InstallRequest(
        target = InstallTarget.Updatable(
            UpdatePlatform.MacArm64,
            Paths.get("/Users/Jane O'Doe/Apps/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer"),
            Paths.get("/Users/Jane O'Doe/Apps/ScrcpyViewer.app"),
        ),
        installer = Paths.get(
            "/Users/Jane O'Doe/Library/Application Support/ScrcpyViewer/updates/1.5.0/ScrcpyViewer-1.5.0.dmg",
        ),
        updatesDir = Paths.get("/Users/Jane O'Doe/Library/Application Support/ScrcpyViewer/updates"),
        pid = 4242,
        bundledAdb = null,
    )

    private val windowsRequest = InstallRequest(
        target = InstallTarget.Updatable(
            UpdatePlatform.WindowsX64,
            Paths.get("C:\\Program Files\\ScrcpyViewer\\ScrcpyViewer.exe"),
            null,
        ),
        installer = Paths.get("C:\\Users\\Jane O'Doe\\AppData\\Roaming\\ScrcpyViewer\\updates\\1.5.0\\ScrcpyViewer-1.5.0.msi"),
        updatesDir = Paths.get("C:\\Users\\Jane O'Doe\\AppData\\Roaming\\ScrcpyViewer\\updates"),
        pid = 4242,
        bundledAdb = null,
    )

    private val linuxRequest = InstallRequest(
        target = InstallTarget.Updatable(
            UpdatePlatform.LinuxX64,
            Paths.get("/opt/scrcpyviewer/bin/scrcpyviewer"),
            null,
        ),
        installer = Paths.get("/home/jane o'doe/.local/share/ScrcpyViewer/updates/1.5.0/scrcpyviewer_1.5.0_amd64.deb"),
        updatesDir = Paths.get("/home/jane o'doe/.local/share/ScrcpyViewer/updates"),
        pid = 4242,
        bundledAdb = null,
    )

    @Test
    fun macScriptQuotesPaths() {
        val script = HelperScripts.mac(macRequest)
        assertTrue("'/Users/Jane O'\\''Doe/Apps/ScrcpyViewer.app'" in script, script)
        assertFalse(Regex("[^']Jane O'Doe").containsMatchIn(script), script)
    }

    @Test
    fun macScriptWaitsForPidThenInstalls() {
        val script = HelperScripts.mac(macRequest)
        val wait = script.indexOf("kill -0 4242")
        val attach = script.indexOf("hdiutil attach")
        val open = script.indexOf("open ")
        assertTrue(wait in 0 until attach, script)
        assertTrue(attach < open, script)
    }

    @Test
    fun macScriptUsesAdminPromptWhenNotWritable() {
        val script = HelperScripts.mac(macRequest)
        assertTrue("with administrator privileges" in script, script)
        assertTrue("[ -w " in script, script)
    }

    @Test
    fun macScriptRequiresAppBundle() {
        val request = macRequest.copy(target = macRequest.target.copy(appBundle = null))
        val e = assertFailsWith<IllegalArgumentException> { HelperScripts.mac(request) }
        assertEquals("macOS install has no .app bundle", e.message)
    }

    @Test
    fun adbKillOnlyWhenBundled() {
        assertFalse("kill-server" in HelperScripts.mac(macRequest))
        assertFalse("kill-server" in HelperScripts.linux(linuxRequest))
        assertFalse("kill-server" in HelperScripts.windows(windowsRequest))

        val macAdb = "/Users/Jane O'Doe/Apps/ScrcpyViewer.app/Contents/app/resources/adb"
        val mac = HelperScripts.mac(macRequest.copy(bundledAdb = Paths.get(macAdb)))
        assertTrue("${shQuote(macAdb)} kill-server" in mac, mac)

        val winAdb = "C:\\Program Files\\ScrcpyViewer\\app\\resources\\adb.exe"
        val win = HelperScripts.windows(windowsRequest.copy(bundledAdb = Paths.get(winAdb)))
        assertTrue("'$winAdb' kill-server" in win, win)
    }

    @Test
    fun windowsScript() {
        val script = HelperScripts.windows(windowsRequest)
        assertTrue("'C:\\Program Files\\ScrcpyViewer\\ScrcpyViewer.exe'" in script, script)
        assertTrue("'C:\\Users\\Jane O''Doe\\AppData" in script, script)
        for (part in listOf("/passive", "/norestart", "-Verb RunAs", "3010", "Wait-Process -Id 4242")) {
            assertTrue(part in script, "missing $part in\n$script")
        }
    }

    @Test
    fun linuxScript() {
        val script = HelperScripts.linux(linuxRequest)
        assertTrue("pkexec dpkg -i" in script, script)
        assertTrue("xdg-open" in script, script)
        assertTrue("last-error.txt" in script, script)
        assertTrue("kill -0 4242" in script, script)
        assertFalse(Regex("[^']jane o'doe").containsMatchIn(script), script)
    }

    @Test
    fun shScriptsStartWithShebangAndSetU() {
        for (script in listOf(HelperScripts.mac(macRequest), HelperScripts.linux(linuxRequest))) {
            assertTrue(script.startsWith("#!/bin/sh\n"), script)
            assertTrue("\nset -u\n" in script, script)
            assertFalse("set -e" in script, script)
        }
    }

    @Test
    fun shScriptsPassSyntaxCheck() {
        if (!hasSh) return
        val requests = listOf(
            "mac" to HelperScripts.mac(macRequest),
            "mac-adb" to HelperScripts.mac(macRequest.copy(bundledAdb = Paths.get("/a b/adb"))),
            "linux" to HelperScripts.linux(linuxRequest),
            "linux-adb" to HelperScripts.linux(linuxRequest.copy(bundledAdb = Paths.get("/a b/adb"))),
        )
        for ((name, script) in requests) {
            val file = dir.resolve("$name.sh").also { it.writeText(script) }
            val process = ProcessBuilder("/bin/sh", "-n", file.toString()).redirectErrorStream(true).start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), "$name: $output")
        }
    }

    @Test
    fun shQuoteRoundTripsThroughSh() {
        if (!hasSh) return
        val value = "/a b/Jane O'Doe/\"q\"/\$HOME/`x`/back\\slash"
        val process = ProcessBuilder("/bin/sh", "-c", "printf %s ${shQuote(value)}").start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(value, process.inputStream.bufferedReader().readText())
    }

    @Test
    fun psQuoteDoublesSingleQuotes() {
        assertEquals("'C:\\Jane O''Doe\\x'", psQuote("C:\\Jane O'Doe\\x"))
    }

    @Test
    fun appleScriptEscapesBackslashAndDoubleQuote() {
        val cmd = "printf %s ${shQuote("/a b/Jane O'Doe/\"q\"/back\\slash")}"
        assertEquals(
            "do shell script \"printf %s '/a b/Jane O'\\\\''Doe/\\\"q\\\"/back\\\\slash'\" with administrator privileges",
            adminShellScript(cmd),
        )
    }

    @Test
    fun appleScriptStringRoundTripsThroughOsascript() {
        if (!File("/usr/bin/osascript").canExecute()) return
        val value = "/a b/Jane O'Doe/\"q\"/back\\slash"
        val cmd = "printf %s ${shQuote(value)}"
        // Same escaping as the admin path, minus the privilege prompt.
        val source = adminShellScript(cmd).removeSuffix(" with administrator privileges")
        val process = ProcessBuilder("/usr/bin/osascript", "-e", source).redirectErrorStream(true).start()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        assertEquals(value, process.inputStream.bufferedReader().readText().trimEnd('\n'))
    }

    @Test
    fun macSwapReplacesBundle() {
        if (!File("/usr/bin/ditto").canExecute()) return
        val parent = dir.resolve("Apps").createDirectories()
        val app = parent.resolve("ScrcpyViewer.app").createDirectories()
        app.resolve("version").writeText("old")
        val src = dir.resolve("mnt/ScrcpyViewer.app").createDirectories()
        src.resolve("version").writeText("new")

        assertEquals(0, runSh(HelperScripts.macSwap(shQuote(src.toString()), app, parent)))
        assertEquals("new", app.resolve("version").readText())
        assertFalse(parent.resolve(".ScrcpyViewer.app.new").exists())
        assertFalse(parent.resolve(".ScrcpyViewer.app.old").exists())
    }

    @Test
    fun macSwapFailureLeavesOldBundle() {
        if (!File("/usr/bin/ditto").canExecute()) return
        val parent = dir.resolve("Apps").createDirectories()
        val app = parent.resolve("ScrcpyViewer.app").createDirectories()
        app.resolve("version").writeText("old")
        val missing = dir.resolve("mnt/ScrcpyViewer.app")

        assertEquals(1, runSh(HelperScripts.macSwap(shQuote(missing.toString()), app, parent)))
        assertEquals("old", app.resolve("version").readText())
        assertFalse(parent.resolve(".ScrcpyViewer.app.old").exists())
    }

    /** Lays out a writable install with an old bundle, a stub `open`, and a stub adb; null when hdiutil is unavailable. */
    private fun macFixture(): Triple<InstallRequest, Path, Path>? {
        if (!File("/usr/bin/hdiutil").canExecute()) return null
        val parent = dir.resolve("Apps x").createDirectories()
        val app = parent.resolve("ScrcpyViewer.app/Contents").createDirectories().parent
        app.resolve("Contents/version").writeText("old")
        val updates = dir.resolve("up d'q").createDirectories()
        val stubs = dir.resolve("stubs").createDirectories()
        stubs.resolve("open").writeText("#!/bin/sh\necho \"stub open \$*\" >>'${stubs.resolve("calls")}'\n")
        stubs.resolve("adb").writeText("#!/bin/sh\necho \"stub adb \$*\" >>'${stubs.resolve("calls")}'\n")
        stubs.toFile().listFiles()!!.forEach { it.setExecutable(true) }
        val request = InstallRequest(
            InstallTarget.Updatable(UpdatePlatform.MacArm64, app.resolve("Contents/MacOS/ScrcpyViewer"), app),
            installer = updates.resolve("ScrcpyViewer-1.5.0.dmg"),
            updatesDir = updates,
            pid = ProcessHandle.current().pid() + 1_000_000,
            bundledAdb = stubs.resolve("adb"),
        )
        return Triple(request, app, stubs)
    }

    private fun runMacScript(request: InstallRequest, stubs: Path): Int {
        val script = request.updatesDir.resolve("update.sh").also { it.writeText(HelperScripts.mac(request)) }
        val pb = ProcessBuilder("/bin/sh", script.toString()).redirectErrorStream(true)
        pb.environment()["PATH"] = "$stubs:/usr/bin:/bin:/usr/sbin:/sbin"
        val process = pb.start()
        process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS))
        return process.exitValue()
    }

    @Test
    fun macScriptInstallsFromDiskImage() {
        val (request, app, stubs) = macFixture() ?: return
        val src = dir.resolve("src/ScrcpyViewer.app/Contents").createDirectories()
        src.resolve("version").writeText("new")
        val create = ProcessBuilder(
            "/usr/bin/hdiutil", "create", "-quiet", "-srcfolder", src.parent.parent.toString(),
            "-volname", "SV", "-format", "UDRO", "-fs", "HFS+", request.installer.toString(),
        ).redirectErrorStream(true).start()
        create.inputStream.bufferedReader().readText()
        if (!create.waitFor(60, TimeUnit.SECONDS) || create.exitValue() != 0) return

        assertEquals(0, runMacScript(request, stubs))

        val log = request.updatesDir.resolve("update.log").readText()
        assertEquals("new", app.resolve("Contents/version").readText(), log)
        assertFalse(app.parent.resolve(".ScrcpyViewer.app.new").exists(), log)
        assertFalse(app.parent.resolve(".ScrcpyViewer.app.old").exists(), log)
        assertFalse(request.updatesDir.resolve("last-error.txt").exists(), log)
        assertTrue("Update installed" in log, log)
        assertEquals("stub adb kill-server\nstub open $app\n", stubs.resolve("calls").readText())
    }

    @Test
    fun macScriptKeepsOldAppWhenDiskImageIsBad() {
        val (request, app, stubs) = macFixture() ?: return
        request.installer.writeText("not a disk image")

        assertEquals(0, runMacScript(request, stubs))

        val log = request.updatesDir.resolve("update.log").readText()
        assertEquals("old", app.resolve("Contents/version").readText(), log)
        assertEquals(
            "Install failed (exit 1). See update.log.\n",
            request.updatesDir.resolve("last-error.txt").readText(),
        )
        assertEquals("stub adb kill-server\nstub open $app\n", stubs.resolve("calls").readText())
    }

    @Test
    fun launchWritesScriptAndStartsDetached() {
        var captured: List<String>? = null
        val updates = dir.resolve("updates").createDirectories()
        updates.resolve("last-error.txt").writeText("stale")
        val request = macRequest.copy(updatesDir = updates)

        UpdateInstaller(start = { captured = it }).launch(request)

        val script = updates.resolve("update.sh")
        assertTrue(script.exists())
        assertEquals(HelperScripts.mac(request), script.readText())
        if (!System.getProperty("os.name").startsWith("Windows")) assertTrue(script.isExecutable())
        assertFalse(updates.resolve("last-error.txt").exists())
        val cmd = captured!!
        assertEquals(listOf("/bin/sh", "-c"), cmd.take(2))
        assertTrue(script.toString() in cmd.last(), cmd.last())
        assertTrue(cmd.last().startsWith("nohup "), cmd.last())
        assertTrue(cmd.last().endsWith(" &"), cmd.last())
    }

    @Test
    fun launchOnWindowsWritesPowerShellScript() {
        var captured: List<String>? = null
        val updates = dir.resolve("updates").createDirectories()
        val request = windowsRequest.copy(updatesDir = updates)

        UpdateInstaller(start = { captured = it }).launch(request)

        val script = updates.resolve("update.ps1")
        assertTrue(script.exists())
        assertEquals(
            listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString()),
            captured,
        )
    }

    @Test
    fun launchOnLinuxUsesSetsidWhenPresent() {
        var captured: List<String>? = null
        val updates = dir.resolve("updates").createDirectories()
        val request = linuxRequest.copy(updatesDir = updates)

        UpdateInstaller(start = { captured = it }, hasSetsid = true).launch(request)

        val cmd = captured!!
        assertEquals(listOf("/usr/bin/setsid", "/bin/sh", "-c"), cmd.take(3))
        assertTrue(updates.resolve("update.sh").toString() in cmd.last(), cmd.last())
    }

    @Test
    fun launchRejectsUnsupportedPlatform() {
        val request = linuxRequest.copy(
            target = linuxRequest.target.copy(platform = UpdatePlatform.Unsupported),
            updatesDir = dir,
        )
        assertFailsWith<IllegalArgumentException> { UpdateInstaller(start = {}).launch(request) }
    }

    private fun runSh(script: String): Int {
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue()
    }
}
