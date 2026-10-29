package com.programmersbox.scrcpyviewer.update

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Everything the update helper needs. [pid] is the running app, which the helper waits on.
 * [bundledAdb] is set only when the app's own adb server is running, so the helper can stop it.
 */
data class InstallRequest(
    val target: InstallTarget.Updatable,
    val installer: Path,
    val updatesDir: Path,
    val pid: Long,
    val bundledAdb: Path?,
)

/** Writes the per-OS helper script into [InstallRequest.updatesDir] and starts it detached from this JVM. */
class UpdateInstaller(
    private val start: (List<String>) -> Unit = ::startDetached,
    private val hasSetsid: Boolean = File("/usr/bin/setsid").canExecute(),
) {
    fun launch(r: InstallRequest) {
        val (name, text) = when (r.target.platform) {
            UpdatePlatform.MacArm64 -> "update.sh" to HelperScripts.mac(r)
            UpdatePlatform.LinuxX64 -> "update.sh" to HelperScripts.linux(r)
            UpdatePlatform.WindowsX64 -> "update.ps1" to HelperScripts.windows(r)
            UpdatePlatform.Unsupported -> throw IllegalArgumentException("Updates are not supported on this platform")
        }
        Files.createDirectories(r.updatesDir)
        val script = r.updatesDir.resolve(name)
        if (r.target.platform == UpdatePlatform.WindowsX64) {
            // BOM so Windows PowerShell 5.1 reads non-ASCII paths as UTF-8 instead of the ANSI code page.
            Files.write(script, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray())
        } else {
            Files.writeString(script, text)
            script.toFile().setExecutable(true)
        }
        Files.deleteIfExists(r.updatesDir.resolve(LAST_ERROR))
        val cmd = when (r.target.platform) {
            UpdatePlatform.WindowsX64 -> listOf(
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", script.toString(),
            )
            else -> {
                // Run through /bin/sh so a lost exec bit or a noexec mount can't stop the helper.
                val sh = listOf("/bin/sh", "-c", "nohup /bin/sh ${shQuote(script.toString())} >/dev/null 2>&1 &")
                if (r.target.platform == UpdatePlatform.LinuxX64 && hasSetsid) listOf("/usr/bin/setsid") + sh else sh
            }
        }
        start(cmd)
    }
}

/** Starts [cmd] without waiting and without holding its output, so it outlives this JVM. */
internal fun startDetached(cmd: List<String>) {
    ProcessBuilder(cmd)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}

internal const val LAST_ERROR = "last-error.txt"
private const val UPDATE_LOG = "update.log"

/**
 * Helper scripts that wait for the app to quit, install, and relaunch. Values are baked into the script text,
 * quoted for the target shell. The old install is never removed before the new one is fully in place, and
 * every failure path writes [LAST_ERROR] and relaunches whatever app is at the original path.
 */
object HelperScripts {
    fun mac(r: InstallRequest): String {
        val bundle = requireNotNull(r.target.appBundle) { "macOS install has no .app bundle" }
        val parent = requireNotNull(bundle.parent) { "macOS install has no .app bundle" }
        val staged = r.updatesDir.resolve("ScrcpyViewer.app.staged").toString()
        val adminSwap = adminShellScript(macSwap(shQuote(staged), bundle, parent))
        return """
            |#!/bin/sh
            |set -u
            |APP=${shQuote(bundle.toString())}
            |PARENT=${shQuote(parent.toString())}
            |DMG=${shQuote(r.installer.toString())}
            |STAGED=${shQuote(staged)}
            |ERR=${shQuote(r.updatesDir.resolve(LAST_ERROR).toString())}
            |exec >>${shQuote(r.updatesDir.resolve(UPDATE_LOG).toString())} 2>&1
            |echo "Update started $(date)"
            |
            |${shFunctions()}
            |
            |${shWaitForPid(r.pid)}
            |${shKillAdb(r.bundledAdb)}
            |rc=1
            |canceled=0
            |if MNT=$(mktemp -d); then
            |  if hdiutil attach -nobrowse -readonly -mountpoint "${'$'}MNT" "${'$'}DMG"; then
            |    if [ -w "${'$'}PARENT" ]; then
            |      ( ${macSwap("\"\$MNT/ScrcpyViewer.app\"", bundle, parent)} )
            |      rc=$?
            |    else
            |      # Copy out of the image as this user, then copy and swap as admin with fixed paths only.
            |      rm -rf "${'$'}STAGED"
            |      if ditto "${'$'}MNT/ScrcpyViewer.app" "${'$'}STAGED"; then
            |        out=$(osascript -e ${shQuote(adminSwap)} 2>&1)
            |        rc=$?
            |        echo "${'$'}out"
            |        case "${'$'}out" in *"(-128)"*) canceled=1 ;; esac
            |      fi
            |      rm -rf "${'$'}STAGED"
            |    fi
            |    hdiutil detach "${'$'}MNT" || hdiutil detach -force "${'$'}MNT"
            |  fi
            |  rmdir "${'$'}MNT"
            |fi
            |
            |if [ "${'$'}rc" -eq 0 ]; then
            |  echo "Update installed"
            |elif [ "${'$'}canceled" -eq 1 ]; then
            |  fail "Install was canceled."
            |else
            |  fail "Install failed (exit ${'$'}rc). See update.log."
            |fi
            |open "${'$'}APP"
            |
        """.trimMargin()
    }

    fun linux(r: InstallRequest): String = """
        |#!/bin/sh
        |set -u
        |DEB=${shQuote(r.installer.toString())}
        |LAUNCHER=${shQuote(r.target.launcher.toString())}
        |ERR=${shQuote(r.updatesDir.resolve(LAST_ERROR).toString())}
        |exec >>${shQuote(r.updatesDir.resolve(UPDATE_LOG).toString())} 2>&1
        |echo "Update started $(date)"
        |
        |${shFunctions()}
        |
        |${shWaitForPid(r.pid)}
        |${shKillAdb(r.bundledAdb)}
        |if ! command -v pkexec >/dev/null 2>&1; then
        |  echo "pkexec not found; handing the package to the system installer with xdg-open"
        |  xdg-open "${'$'}DEB" >/dev/null 2>&1 &
        |  exit 0
        |fi
        |
        |pkexec dpkg -i "${'$'}DEB"
        |rc=$?
        |case "${'$'}rc" in
        |  0) echo "Update installed" ;;
        |  126) fail "Install was canceled." ;;
        |  *) fail "Install failed (exit ${'$'}rc). See update.log." ;;
        |esac
        |"${'$'}LAUNCHER" >/dev/null 2>&1 &
        |
    """.trimMargin()

    fun windows(r: InstallRequest): String {
        val adb = r.bundledAdb?.let {
            """
            |try {
            |    ${'$'}out = & ${psQuote(it.toString())} kill-server 2>&1 | Out-String
            |    Write-Log ('adb kill-server: ' + ${'$'}out)
            |} catch {
            |    Write-Log ('adb kill-server failed: ' + ${'$'}_)
            |}
            """.trimMargin()
        }.orEmpty()
        return """
            |${'$'}ErrorActionPreference = 'Continue'
            |${'$'}log = ${psQuote(r.updatesDir.resolve(UPDATE_LOG).toString())}
            |${'$'}errFile = ${psQuote(r.updatesDir.resolve(LAST_ERROR).toString())}
            |${'$'}msi = ${psQuote(r.installer.toString())}
            |${'$'}exe = ${psQuote(r.target.launcher.toString())}
            |
            |function Write-Log([string]${'$'}message) {
            |    Add-Content -LiteralPath ${'$'}log -Value ((Get-Date -Format 's') + ' ' + ${'$'}message)
            |}
            |
            |function Write-Failure([string]${'$'}message) {
            |    Write-Log ${'$'}message
            |    Set-Content -LiteralPath ${'$'}errFile -Value ${'$'}message
            |}
            |
            |Write-Log 'Update started'
            |Wait-Process -Id ${r.pid} -Timeout 60 -ErrorAction SilentlyContinue
            |if (Get-Process -Id ${r.pid} -ErrorAction SilentlyContinue) {
            |    Write-Failure 'Scrcpy Viewer did not quit in time. Update skipped.'
            |    exit 1
            |}
            |$adb
            |try {
            |    ${'$'}p = Start-Process msiexec -ArgumentList '/i', ('"' + ${'$'}msi + '"'), '/passive', '/norestart' -Verb RunAs -Wait -PassThru
            |    if (${'$'}p.ExitCode -eq 0 -or ${'$'}p.ExitCode -eq 3010) {
            |        Write-Log ('Update installed (exit ' + ${'$'}p.ExitCode + ')')
            |    } else {
            |        Write-Failure ('Install failed (exit ' + ${'$'}p.ExitCode + '). See update.log.')
            |    }
            |} catch {
            |    Write-Log ('msiexec did not run: ' + ${'$'}_)
            |    if ("${'$'}_" -match 'cancel') {
            |        Write-Failure 'Install was canceled.'
            |    } else {
            |        Write-Failure 'Install failed. See update.log.'
            |    }
            |}
            |
            |try {
            |    Start-Process -FilePath ${'$'}exe
            |} catch {
            |    Write-Log ('Relaunch failed: ' + ${'$'}_)
            |}
            |
        """.trimMargin()
    }

    /**
     * One sh command line that replaces [bundle] with a copy of [src] (an already quoted sh word).
     * It copies to a hidden sibling first and moves the old bundle aside only after the copy succeeds.
     * If moving the new bundle in fails, the old one is moved back. Exits 0 on success, 1 otherwise.
     * Runs either in a subshell or as root through [adminShellScript], so it uses only baked paths.
     */
    internal fun macSwap(src: String, bundle: Path, parent: Path): String {
        val app = shQuote(bundle.toString())
        val new = shQuote(parent.resolve(".ScrcpyViewer.app.new").toString())
        val old = shQuote(parent.resolve(".ScrcpyViewer.app.old").toString())
        return "test -d $app && rm -rf $new $old && ditto $src $new && mv $app $old && " +
            "{ mv $new $app || { mv $old $app; exit 1; }; } && " +
            "{ rm -rf $old; xattr -dr com.apple.quarantine $app; exit 0; }; " +
            "rm -rf $new; exit 1"
    }

    private fun shFunctions() = """
        |fail() {
        |  echo "${'$'}1"
        |  printf '%s\n' "${'$'}1" >"${'$'}ERR"
        |}
    """.trimMargin()

    private fun shWaitForPid(pid: Long) = """
        |i=0
        |while kill -0 $pid 2>/dev/null; do
        |  if [ "${'$'}i" -ge 60 ]; then
        |    fail "Scrcpy Viewer did not quit in time. Update skipped."
        |    exit 1
        |  fi
        |  i=$((i + 1))
        |  sleep 1
        |done
    """.trimMargin()

    private fun shKillAdb(adb: Path?) =
        adb?.let { "${shQuote(it.toString())} kill-server || echo \"adb kill-server failed; continuing\"" }.orEmpty()
}

/** Single-quotes [value] for sh. An embedded `'` becomes `'\''`. */
internal fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** Single-quotes [value] for PowerShell. An embedded `'` becomes `''`. */
internal fun psQuote(value: String): String = "'" + value.replace("'", "''") + "'"

/** AppleScript that runs the sh [command] as root after a password prompt. Escapes `\` and `"` for AppleScript. */
internal fun adminShellScript(command: String): String {
    val escaped = command.replace("\\", "\\\\").replace("\"", "\\\"")
    return "do shell script \"$escaped\" with administrator privileges"
}
