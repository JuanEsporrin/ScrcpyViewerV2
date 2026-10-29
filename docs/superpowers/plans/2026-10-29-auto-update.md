# Auto-update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Check GitHub for a newer release on launch and on demand, show a popup, a Settings badge and a Settings
section, and on "Update now" download, verify and swap in the new installer, then relaunch.

**Architecture:** A pure `update` package handles release parsing, version compare, asset pick, install-target
detection and helper-script generation. Thin I/O classes sit on top of it: GitHub fetch, download plus SHA-256, and
detached script launch. `UpdateManager` exposes a `StateFlow` that drives a Compose dialog, the rail badge and a
Settings section. The app quits through `AppShutdown`, and a detached per-OS script waits for the PID, installs, and
relaunches the new or the old app.

**Tech Stack:** Kotlin 2.3 / Compose Multiplatform 1.12 desktop, kotlinx.serialization, kotlinx.coroutines,
`java.net.http.HttpClient`, `com.sun.net.httpserver` (tests only), sh / PowerShell helper scripts.

**Spec:** `docs/superpowers/specs/2026-10-29-auto-update-design.md`

All paths below are relative to `composeApp/` unless they start with `docs/` or `.github/`. Main package root
`src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/` is written `main/`; test root
`src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/` is written `test/`.

## Global Constraints

- Release source: `https://api.github.com/repos/jakepurple13/ScrcpyViewerV2/releases/latest`, header
  `Accept: application/vnd.github+json`, unauthenticated. Repo overridable with system property `scrcpyviewer.updateRepo`.
- No new runtime dependencies. Use `java.net.http.HttpClient` and kotlinx.serialization (already present).
- Add `"java.net.http"` to `nativeDistributions.modules(...)` in `build.gradle.kts` (the jlinked runtime lacks it otherwise).
- Assets: `.dmg` on macOS, `.msi` on Windows, `.deb` on Linux, matched by extension.
- Verify against the asset's `digest` (`sha256:<hex>`). A missing digest or a mismatch means delete the file, don't install, and show an error.
- Downloads go to `AppDataDir.root()/updates/<version>/<asset name>`. Older version folders are deleted when a new download starts. Partial files are deleted on cancel or failure.
- Helper: waits for the app PID with a ~60 s timeout, kills the bundled adb server only if the bundled adb is in use, then installs. Success relaunches the new app. Failure relaunches the old app and writes `updates/last-error.txt`. Logs go to `updates/update.log`.
- "Update now" is offered only if:
  - `jpackage.app-path` is set.
  - The platform is macOS `aarch64`, Windows `amd64`, or Linux `amd64`/`x86_64`.
  - On macOS, the app is not under `/Volumes/` or an `AppTranslocation` path.
  - On Linux, `dpkg -S <app-path>` reports package `scrcpyviewer`.
  - Otherwise show "Download from GitHub", which opens the release `html_url`.
- The helper never uninstalls before installing.
- Launch-check failures are silent (logged only). Manual-check failures show "Couldn't check for updates: <message>".
- The popup shows on every launch while an update is available. "Later" hides it for this launch only. It waits until onboarding is closed (`settings.onboardingSeen`).
- Copy (exact):
  - Popup: "Update available"; "Version {new} is ready. You have {current}."; "Later"; "Update now".
  - Download: "Updating to {new}"; "Scrcpy Viewer will close and reopen. Your computer may ask for your password. Open mirror windows will close."; "Cancel".
  - Settings: "Updates"; "Current version {current}"; "Version {new} available"; "View release notes"; "Check for updates"; "You're on the latest version."
  - macOS hint: "Move Scrcpy Viewer to Applications to enable updates."
- `lastChecked` lives in memory only.
- Tests run with `./gradlew :composeApp:jvmTest`. Screenshot tests run with `./gradlew :composeApp:jvmTest -Pscreenshots`.

## Review Focus

1. **GitHub returns 403 (rate limit), 404, or non-JSON** on launch. Expected: state stays quiet, no popup, no badge, no crash. From the button, a readable error. (Task 2, Task 6 tests.)
2. **Release asset without `digest`** (older or odd releases). Expected: download completes, then an error. Nothing installs and the file is deleted. (Task 3 test.)
3. **Paths containing spaces or quotes**, e.g. `C:\Program Files\…`, a home folder like `/Users/Jane Doe/`. Expected: the generated scripts quote every path. (Task 5 tests.)
4. **"Check for updates" or a second "Update now" while a download runs.** Expected: ignored, the download isn't restarted or duplicated. (Task 6 test.)
5. **Cancel mid-download**. Expected: the partial file is deleted, state returns to `Available`, and "Update now" works again. (Task 3 and Task 6 tests.)

---

### Task 1: Release model, version compare, asset pick

**Files:**
- Create: `main/update/Release.kt`
- Test: `test/update/ReleaseTest.kt`
- Create fixture: `src/jvmTest/resources/update/release-v1.4.0.json`. Fetch the real response with
  `gh api repos/jakepurple13/ScrcpyViewerV2/releases/latest > …` and trim `author`/`uploader` objects if large.

**Interfaces:**
- Produces:
  - `@Serializable data class ReleaseAsset(val name: String, val size: Long, @SerialName("browser_download_url") val downloadUrl: String, val digest: String? = null)`
  - `@Serializable data class Release(@SerialName("tag_name") val tagName: String, val name: String? = null, val body: String? = null, @SerialName("html_url") val htmlUrl: String, @SerialName("published_at") val publishedAt: String? = null, val assets: List<ReleaseAsset> = emptyList())` with `val version: Version?` (parsed from `tagName`).
  - `data class Version(val parts: List<Int>) : Comparable<Version>` with `companion fun parse(text: String): Version?` (strip leading `v`/`V`, split on `.`, all parts must be ints, else `null`; compare part by part, missing parts = 0) and `toString()` = parts joined by `.`.
  - `enum class UpdatePlatform(val extension: String?) { MacArm64("dmg"), WindowsX64("msi"), LinuxX64("deb"), Unsupported(null) }` with `companion fun detect(osName: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): UpdatePlatform`.
  - `fun Release.assetFor(platform: UpdatePlatform): ReleaseAsset?`
  - `fun parseRelease(json: String): Release` (a `Json { ignoreUnknownKeys = true }` instance private to the file).

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun `parses real release`() {
    val r = parseRelease(resource("update/release-v1.4.0.json"))
    assertEquals("v1.4.0", r.tagName); assertEquals(Version(listOf(1, 4, 0)), r.version)
    assertEquals("sha256:75fef6be39739c67321ad7f8dddfa168be646f13554dbfbafccc539ead7a3888", r.assetFor(UpdatePlatform.MacArm64)!!.digest)
    assertEquals("scrcpyviewer_1.4.0_amd64.deb", r.assetFor(UpdatePlatform.LinuxX64)!!.name)
    assertEquals("ScrcpyViewer-1.4.0.msi", r.assetFor(UpdatePlatform.WindowsX64)!!.name)
    assertNull(r.assetFor(UpdatePlatform.Unsupported))
}
@Test fun `version compare`() {
    assertTrue(Version.parse("v1.10.0")!! > Version.parse("1.9.3")!!)
    assertEquals(0, Version.parse("1.4")!!.compareTo(Version.parse("1.4.0")!!))
    assertNull(Version.parse("v1.5.0-beta")); assertNull(Version.parse("nightly"))
}
@Test fun `platform detect`() {
    assertEquals(UpdatePlatform.MacArm64, UpdatePlatform.detect("Mac OS X", "aarch64"))
    assertEquals(UpdatePlatform.Unsupported, UpdatePlatform.detect("Mac OS X", "x86_64"))
    assertEquals(UpdatePlatform.WindowsX64, UpdatePlatform.detect("Windows 11", "amd64"))
    assertEquals(UpdatePlatform.LinuxX64, UpdatePlatform.detect("Linux", "amd64"))
    assertEquals(UpdatePlatform.LinuxX64, UpdatePlatform.detect("Linux", "x86_64"))
    assertEquals(UpdatePlatform.Unsupported, UpdatePlatform.detect("Linux", "aarch64"))
}
```

- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest --tests '*ReleaseTest'`. Expected: compile failure.
- [ ] **Step 3:** Implement `Release.kt` per Interfaces.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5:** Commit `feat(update): release model, version compare, asset pick`.

### Task 2: GitHub release source

**Files:**
- Create: `main/update/ReleaseSource.kt`
- Modify: `build.gradle.kts` (add `"java.net.http"` to `modules(...)`)
- Test: `test/update/GitHubReleaseSourceTest.kt`

**Interfaces:**
- Consumes: `Release`, `parseRelease` (Task 1).
- Produces:
  - `fun interface ReleaseSource { suspend fun latest(): Release }`
  - `class GitHubReleaseSource(private val apiBase: String = "https://api.github.com", private val repo: String = System.getProperty("scrcpyviewer.updateRepo") ?: "jakepurple13/ScrcpyViewerV2", private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()) : ReleaseSource`
  - Throws `UpdateException(message: String)` (`class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)`, also in this file) on non-2xx (`"GitHub returned HTTP 403"`) or a parse failure (`"Unexpected response from GitHub"`). IO errors propagate as `IOException`.
  - Runs on `Dispatchers.IO` (use `http.send` inside `withContext(Dispatchers.IO)`), sends `Accept: application/vnd.github+json` and a `User-Agent: ScrcpyViewer/$APP_VERSION` header.

- [ ] **Step 1: Write failing tests.** Start a `com.sun.net.httpserver.HttpServer` on port 0 serving
  `/repos/o/r/releases/latest`, and pass `apiBase = "http://127.0.0.1:$port"`, `repo = "o/r"`.
  - `returns parsed release`: 200 with the fixture. Assert `latest().tagName == "v1.4.0"` and the request's `Accept` header equals `application/vnd.github+json`.
  - `rate limited throws UpdateException`: 403. `assertFailsWith<UpdateException> { … }.message == "GitHub returned HTTP 403"`.
  - `html body throws UpdateException`: 200 with `<html>`. The message is `"Unexpected response from GitHub"`.
- [ ] **Step 2:** Run `--tests '*GitHubReleaseSourceTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement, and add the module to `build.gradle.kts`.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit `feat(update): GitHub latest-release source`.

### Task 3: Downloader with SHA-256 verify

**Files:**
- Create: `main/update/UpdateDownloader.kt`
- Test: `test/update/UpdateDownloaderTest.kt`

**Interfaces:**
- Consumes: `ReleaseAsset`, `Version`, `UpdateException`.
- Produces:
  - `class UpdateDownloader(private val updatesDir: Path, private val http: HttpClient = <same builder as Task 2>)`
  - `suspend fun download(asset: ReleaseAsset, version: Version, onProgress: (read: Long, total: Long) -> Unit): Path`:
    1. Delete every subfolder of `updatesDir` except `version.toString()`.
    2. Stream to `updatesDir/<version>/<asset.name>.part` in 64 KiB chunks, honoring coroutine cancellation (`ensureActive()` per chunk). Total = `asset.size`.
    3. Verify the hash and move the file to its final name.
    4. On any exception, including `CancellationException`, delete the `.part` and the final file, then rethrow.
  - `fun verifySha256(file: Path, digest: String?)` throws `UpdateException("Release file has no checksum")` when `digest` is null or doesn't start with `sha256:`. It throws `UpdateException("Downloaded file is corrupted (checksum mismatch)")` when the hex differs (case-insensitive).

- [ ] **Step 1: Write failing tests** (local `HttpServer` serving bytes `ByteArray(200_000) { it.toByte() }`):
  - `downloads and verifies`: correct digest. The returned path exists with the same bytes, no `.part` remains, and the last `onProgress` call is `(200_000, 200_000)`.
  - `checksum mismatch deletes file`: wrong digest. Assert `UpdateException` and that `updatesDir/1.5.0` contains no files.
  - `missing digest rejected`: `digest = null`. The message is `"Release file has no checksum"`, and no files remain.
  - `cancel deletes partial`: server writes 100 KB then sleeps 5 s. Launch `download` and cancel after the first progress callback. Assert no files remain in `updatesDir/1.5.0`.
  - `removes older version folders`: pre-create `updatesDir/1.4.9/x.dmg`. After the download, `1.4.9` is gone.
- [ ] **Step 2:** Run `--tests '*UpdateDownloaderTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit `feat(update): download with SHA-256 verification`.

### Task 4: Install-target detection

**Files:**
- Create: `main/update/InstallTarget.kt`
- Test: `test/update/InstallTargetTest.kt`

**Interfaces:**
- Consumes: `UpdatePlatform`.
- Produces:
  - `sealed interface InstallTarget`:
    - `data class Updatable(val platform: UpdatePlatform, val launcher: Path, val appBundle: Path?) : InstallTarget`. `appBundle` is set on macOS only: the `.app` folder, found by walking up from the launcher to the first ancestor whose name ends with `.app`.
    - `data class NotUpdatable(val reason: Reason) : InstallTarget`, with `enum class Reason { DevBuild, UnsupportedPlatform, MacNotInApplications, LinuxNotFromPackage }`.
  - `fun detectInstallTarget(appPath: String? = System.getProperty("jpackage.app-path"), platform: UpdatePlatform = UpdatePlatform.detect(), dpkgOwns: (Path) -> Boolean = ::dpkgOwnsPath): InstallTarget`
  - `internal fun dpkgOwnsPath(path: Path): Boolean`: runs `dpkg -S <path>`, true when the exit code is 0 and stdout starts with `scrcpyviewer:`. Any exception means false. It has a 5 s timeout.
  - Order of checks:
    1. `appPath` is null or blank: `DevBuild`.
    2. The platform is `Unsupported`: `UnsupportedPlatform`.
    3. macOS with the bundle under `/Volumes/` or a path containing `/AppTranslocation/`: `MacNotInApplications`.
    4. Linux where `!dpkgOwns`: `LinuxNotFromPackage`.

- [ ] **Step 1: Write failing tests**, one per branch, plus the bundle walk-up:
  - `/Applications/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer` gives `Updatable(MacArm64, …, appBundle = /Applications/ScrcpyViewer.app)`.
  - `/Volumes/ScrcpyViewer/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer` gives `MacNotInApplications`.
  - `/private/var/folders/x/AppTranslocation/ABC/d/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer` gives `MacNotInApplications`.
  - Linux `/opt/scrcpyviewer/bin/scrcpyviewer` with `dpkgOwns = { true }` gives `Updatable`; with `{ false }` gives `LinuxNotFromPackage`.
  - `C:\Program Files\ScrcpyViewer\ScrcpyViewer.exe` on `WindowsX64` gives `Updatable(appBundle = null)`.
  - `appPath = null` gives `DevBuild`.
- [ ] **Step 2:** Run `--tests '*InstallTargetTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit `feat(update): detect whether this install can self-update`.

### Task 5: Helper scripts and installer launch

**Files:**
- Create: `main/update/UpdateInstaller.kt`
- Test: `test/update/UpdateInstallerTest.kt`

**Interfaces:**
- Consumes: `InstallTarget.Updatable`, `UpdatePlatform`.
- Produces:
  - `data class InstallRequest(val target: InstallTarget.Updatable, val installer: Path, val updatesDir: Path, val pid: Long, val bundledAdb: Path?)`
  - `object HelperScripts { fun mac(r: InstallRequest): String; fun windows(r: InstallRequest): String; fun linux(r: InstallRequest): String }`. These are pure string builders.
  - `class UpdateInstaller(private val start: (List<String>) -> Unit = ::startDetached)` with `fun launch(r: InstallRequest)`. It writes the script to `updatesDir/update.sh` (macOS/Linux, `chmod +x`) or `updatesDir/update.ps1` (Windows), deletes a stale `last-error.txt`, then calls `start` with:
    - macOS/Linux: `listOf("/bin/sh", "-c", "nohup \"$script\" >/dev/null 2>&1 &")`. On Linux, prefix with `setsid` when `/usr/bin/setsid` exists.
    - Windows: `listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString())`.
  - `internal fun startDetached(cmd: List<String>)`: `ProcessBuilder(cmd)`, redirect output and error to `DISCARD`, `start()`, don't wait.
- Quoting: every path is single-quoted in sh (an embedded `'` becomes `'\''`). In PowerShell, every path is single-quoted (an embedded `'` becomes `''`). Write one helper per shell: `shQuote(String)`, `psQuote(String)`.
- Every script appends its output to `updates/update.log` and does these steps in order:
  1. Wait for the PID: `while kill -0 $PID 2>/dev/null` with a 60 × 1 s cap (sh), or `Wait-Process -Id $pid -Timeout 60 -ErrorAction SilentlyContinue` (ps).
  2. If `bundledAdb != null`, run `<adb> kill-server`, ignoring failure.
  3. Install:
     - **macOS:**
       1. `hdiutil attach -nobrowse -readonly -mountpoint "$MNT" "$DMG"`, where `MNT=$(mktemp -d)`.
       2. Let `$PARENT` be the bundle's parent folder. Run `ditto "$MNT/ScrcpyViewer.app" "$PARENT/.ScrcpyViewer.app.new"`.
       3. Run `mv "$APP" "$PARENT/.ScrcpyViewer.app.old"`, then `mv "$PARENT/.ScrcpyViewer.app.new" "$APP"`, then `rm -rf "$PARENT/.ScrcpyViewer.app.old"`. Then `xattr -dr com.apple.quarantine "$APP"` and `hdiutil detach "$MNT"`.
       4. If `[ -w "$PARENT" ]` fails, run the copy and swap commands through `osascript -e 'do shell script "<cmd>" with administrator privileges'`.
       5. If a `mv` fails, move `.old` back.
     - **Windows:**
       1. `$p = Start-Process msiexec -ArgumentList '/i', ('"' + $msi + '"'), '/passive', '/norestart' -Verb RunAs -Wait -PassThru`.
       2. Exit codes 0 and 3010 mean success. A canceled UAC prompt throws, which counts as failure.
     - **Linux:** `pkexec dpkg -i "$DEB"`. If `pkexec` is missing, run `xdg-open "$DEB"`, log it, and exit without relaunching.
  4. On failure, write a one-line reason to `last-error.txt`, e.g. `Install failed (exit 1). See update.log.` or `Install was canceled.`
  5. Relaunch:
     - macOS: `open "$APP"`.
     - Windows: `Start-Process -FilePath $exe`.
     - Linux: `"$LAUNCHER" >/dev/null 2>&1 &`.
     - The same path serves both outcomes. After success it points at the new build; after failure the old app is still there.

- [ ] **Step 1: Write failing tests** on the generated strings. Use a request with spaces and a quote:
  launcher `/Users/Jane O'Doe/Apps/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer` and installer
  `/Users/Jane O'Doe/Library/Application Support/ScrcpyViewer/updates/1.5.0/ScrcpyViewer-1.5.0.dmg`.
  - `mac script quotes paths`: contains `'/Users/Jane O'\''Doe/Apps/ScrcpyViewer.app'` and doesn't contain an unquoted `Jane O'Doe/Apps` outside quotes. Check with a regex for `[^']Jane O'Doe`.
  - `mac script waits for pid then installs`: `indexOf("kill -0 4242") < indexOf("hdiutil attach") < indexOf("open ")`.
  - `mac script uses admin prompt when not writable`: contains `with administrator privileges` and `[ -w `.
  - `adb kill only when bundled`: `bundledAdb = null` gives no `kill-server`; a path gives the quoted path followed by ` kill-server`.
  - `windows script`: launcher `C:\Program Files\ScrcpyViewer\ScrcpyViewer.exe` appears as `'C:\Program Files\ScrcpyViewer\ScrcpyViewer.exe'`; contains `/passive`, `/norestart`, `-Verb RunAs`, `3010`, `Wait-Process -Id 4242`.
  - `linux script`: contains `pkexec dpkg -i`, the `xdg-open` fallback, and `last-error.txt`.
  - `launch writes script and starts detached`: with a fake `start` capturing the cmd and a temp `updatesDir`, the script file exists, is executable (non-Windows), and the captured cmd's last element references it.
- [ ] **Step 2:** Run `--tests '*UpdateInstallerTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS. Also run `sh -n` on the generated mac and linux scripts in a test (`ProcessBuilder("sh", "-n", file)` exit 0) to catch syntax errors.
- [ ] **Step 5:** Commit `feat(update): per-OS helper scripts that swap and relaunch`.

### Task 6: UpdateManager and app wiring

**Files:**
- Create: `main/update/UpdateManager.kt`
- Modify: `main/Main.kt`:
  - In `AppGraph`, add a constructor param `releaseSource: ReleaseSource = GitHubReleaseSource()` and the property `updateManager`.
  - In `main()`, launch the check and watch for `Installing`.
- Modify: `test/FakeAppGraph.kt` so `fakeAppGraph` passes a source that never returns (`ReleaseSource { awaitCancellation() }`).
- Test: `test/update/UpdateManagerTest.kt`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces:
  - `sealed interface UpdateState`:
    - `data object Idle`
    - `data object Checking`
    - `data object UpToDate`
    - `data class Available(val release: Release)`
    - `data class Downloading(val release: Release, val read: Long, val total: Long)`
    - `data class Installing(val release: Release)`
    - `data class Failed(val message: String, val release: Release?)`
  - `class UpdateManager(source: ReleaseSource, downloader: UpdateDownloader, installer: UpdateInstaller, val target: InstallTarget, updatesDir: Path, scope: CoroutineScope, currentVersion: String = APP_VERSION, platform: UpdatePlatform = UpdatePlatform.detect(), bundledAdbInUse: () -> Path?, now: () -> Instant = Instant::now)`
    - `val state: StateFlow<UpdateState>`
    - `val available: StateFlow<Release?>`: the newest known release newer than current. It survives `Checking`/`Failed` and drives the badge.
    - `val lastChecked: StateFlow<Instant?>`
    - `val popupVisible: StateFlow<Boolean>`: true when `available != null`, `dismissPopup()` wasn't called this launch, and state isn't `UpToDate`.
    - `val previousFailure: String?`: read and deleted from `updatesDir/last-error.txt` in `init`.
    - `fun canSelfUpdate(release: Release): Boolean` = `target is InstallTarget.Updatable && release.assetFor(platform) != null`.
    - `fun check(userInitiated: Boolean)`
      - Ignored while `Checking`, `Downloading` or `Installing`.
      - Success: `Available` or `UpToDate`, and `lastChecked = now()`.
      - Failure: when `userInitiated`, `Failed("Couldn't check for updates: ${e.message}", available.value)`. Otherwise, back to the previous state (or `Idle`), logged via `System.getLogger("scrcpyviewer.Update")`.
    - `fun startUpdate()`
      - Only from `Available` or `Failed` with a release, and only when `canSelfUpdate`.
      - Downloads (emitting `Downloading`), calls `installer.launch(InstallRequest(…, pid = ProcessHandle.current().pid(), bundledAdb = bundledAdbInUse()))`, then sets `Installing`.
      - Errors give `Failed(e.message ?: "Update failed", release)`.
    - `fun cancel()` cancels the download job and returns to `Available(release)`.
    - `fun dismissPopup()`
- Wiring:
  - `AppGraph.updateManager`: use `UpdateDownloader(dataDir.resolve("updates"))` and `detectInstallTarget()`.
  - Pass `bundledAdbInUse = { if (settings.settings.value.adbPathOverride == null) bundledTools.adbExecutable() else null }`.
  - In `main()`:
    - Add `LaunchedEffect(Unit) { graph.updateManager.check(userInitiated = false) }`.
    - Add `LaunchedEffect(Unit) { graph.updateManager.state.first { it is UpdateState.Installing }; shutdown.request() }`.

- [ ] **Step 1: Write failing tests** with fake source/downloader/installer. Make `UpdateDownloader` open (or add a `download` lambda seam) so tests can fake it. `UpdateInstaller` takes the fake `start`. Use `runBlocking` and a `CoroutineScope(Job())`.
  - `newer release becomes Available and popup visible`: current `1.4.0`, source `v1.5.0`.
  - `same version is UpToDate and no popup`.
  - `launch check failure is silent`: source throws `UpdateException("GitHub returned HTTP 403")`, `check(false)` gives `Idle`, `available == null`.
  - `manual check failure shows message`: `check(true)` gives `Failed("Couldn't check for updates: GitHub returned HTTP 403", null)`.
  - `check ignored while downloading`: the downloader suspends; call `check(true)` and a second `startUpdate()`; source call count stays 1 and the downloader call count stays 1.
  - `cancel returns to Available`.
  - `successful download launches installer and goes Installing`: the captured `InstallRequest.pid == ProcessHandle.current().pid()`.
  - `checksum failure becomes Failed with release kept`: `available` is still non-null.
  - `startUpdate refused when NotUpdatable`: target `NotUpdatable(DevBuild)` keeps the state `Available`.
  - `dismissPopup hides popup but keeps available`.
  - `previous failure read and deleted`: pre-write `last-error.txt`, then `previousFailure == content` and the file is gone.
- [ ] **Step 2:** Run `--tests '*UpdateManagerTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement and wire.
- [ ] **Step 4:** Run the full `./gradlew :composeApp:jvmTest`. Expected: all PASS (existing ~443 plus new). This confirms `fakeAppGraph` makes no network call.
- [ ] **Step 5:** Commit `feat(update): update manager and launch check`.

### Task 7: Update dialog and rail badge

**Files:**
- Create: `main/ui/update/UpdateDialog.kt`
- Modify: `main/ui/App.kt`: wrap the Settings rail icon in `BadgedBox(badge = { Badge() })` when `available != null`. Show `UpdateDialog` when `popupVisible && settings.onboardingSeen` or state is `Downloading`/`Installing`.
- Test: `test/ui/UpdateScreenshotTest.kt`

**Interfaces:**
- Consumes: `UpdateManager`, `UpdateState`, `Release`.
- Produces: `@Composable fun UpdateDialog(state: UpdateState, release: Release, currentVersion: String, canSelfUpdate: Boolean, onLater: () -> Unit, onUpdate: () -> Unit, onOpenReleasePage: () -> Unit, onCancel: () -> Unit)`
  - `Available`/`Failed` show the popup copy and the release `body` as plain text in a scrollable box (`heightIn(max = 110.dp)`, `surfaceContainerLowest` background, `shapes.medium`). `Failed` adds its message in `colorScheme.error`. The confirm button is "Update now", or "Download from GitHub" when `!canSelfUpdate`, which calls `onOpenReleasePage`.
  - `Downloading` shows "Updating to {new}", "Downloading… {read MB} of {total MB} MB", a `LinearProgressIndicator`, the close/reopen note, and only "Cancel".
  - `Installing` shows "Updating to {new}" with an indeterminate progress bar and no buttons.
  - `onOpenReleasePage` uses `Desktop.getDesktop().browse(URI(release.htmlUrl))` from `App.kt`.
  - The dialog isn't dismissable by clicking outside while `Downloading`/`Installing`.

- [ ] **Step 1: Write screenshot tests** in `OnboardingScreenshotTest` style, rendering `App(graph)` with `UpdateDialog` on top, light theme:
  - `update-available.png`
  - `update-downloading.png` (read 31 MB, total 50 MB)
  - `update-failed.png`
  - `update-no-self-update.png` (`canSelfUpdate = false`)
  - `rail-badge.png`: an `App(graph)` whose fake source returns `v9.9.9`. Trigger `graph.updateManager.check(false)`, wait for `Available`, and render without the dialog: call `dismissPopup()` first.
  - Each asserts file size > 10_000.
- [ ] **Step 2:** Run `./gradlew :composeApp:jvmTest -Pscreenshots --tests '*UpdateScreenshotTest'`. Expected: FAIL (compile).
- [ ] **Step 3:** Implement the dialog and the App changes.
- [ ] **Step 4:** Run the tests. Expected: PASS. Open the PNGs in `composeApp/build/screenshots/` and compare them to the approved mockup (`.superpowers/brainstorm/29604-1793289686/content/update-flow.html`, panels 1–2).
- [ ] **Step 5:** Commit `feat(update): update popup and Settings badge`.

### Task 8: Settings "Updates" section

**Files:**
- Create: `main/ui/update/UpdatesSection.kt`
- Modify: `main/ui/settings/SettingsScreen.kt`:
  - Add the param `updates: @Composable () -> Unit = {}` to `SettingsContent` and render it first under the "Settings" title.
  - Make `Section` `internal`.
  - `SettingsScreen` gets a new `updateManager: UpdateManager?` param (default `null`) and passes `{ UpdatesSection(updateManager) }` when non-null.
- Modify: `main/ui/App.kt` to pass `graph.updateManager` to `SettingsScreen`.
- Test: extend `test/ui/SettingsScreenshotTest.kt`.

**Interfaces:**
- Consumes: `UpdateManager`, `Section`.
- Produces:
  - `@Composable fun UpdatesSection(manager: UpdateManager)`: collects state and delegates.
  - `@Composable fun UpdatesContent(state: UpdateState, available: Release?, currentVersion: String, lastChecked: Instant?, previousFailure: String?, target: InstallTarget, canSelfUpdate: Boolean, onCheck: () -> Unit, onUpdate: () -> Unit, onOpenReleasePage: () -> Unit, onCancel: () -> Unit)` renders:
    - The `Section("Updates")` title and "Current version {current}".
    - If `available != null`, a `Surface(surfaceContainer, shapes.medium)` card:
      - "Version {new} available", the date from `publishedAt` (`MMM d, yyyy`), and a "View release notes" `TextButton`.
      - "Update now" (`Button`), or "Download from GitHub" when `!canSelfUpdate`.
      - While `Downloading`, the card shows the progress bar and "Cancel" inline (builder choice made: inline).
    - If `target` is `NotUpdatable(MacNotInApplications)`, the macOS hint in `bodySmall` `onSurfaceVariant`.
    - An `OutlinedButton("Check for updates")`, disabled while `Checking`/`Downloading`/`Installing`, with a small `CircularProgressIndicator` while `Checking`. Next to it, "Last checked {relative}". Relative means "just now" under 1 min, "{n} min ago", or "{n} h ago".
    - `UpToDate` shows "You're on the latest version."
    - `Failed` or `previousFailure` shows the message in `colorScheme.error`.

- [ ] **Step 1: Write screenshot tests** rendering `SettingsContent(…, updates = { UpdatesContent(…) })`:
  - `settings-updates-available.png`
  - `settings-updates-uptodate.png`
  - `settings-updates-error.png` (`Failed("Couldn't check for updates: GitHub returned HTTP 403", null)`)
  - `settings-updates-mac-hint.png`
- [ ] **Step 2:** Run with `-Pscreenshots --tests '*SettingsScreenshotTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS, and existing settings screenshots are still produced. Compare to mockup panel 3.
- [ ] **Step 5:** Commit `feat(update): Updates section in Settings`.

### Task 9: Pin the MSI upgrade code and bump to 1.5.0

**Files:**
- Modify: `build.gradle.kts` (`windows { upgradeUuid = "<value>" }`, `appVersion = "1.5.0"`)

- [ ] **Step 1:** Download `ScrcpyViewer-1.4.0.msi` from the v1.4.0 release into the scratchpad. Read its UpgradeCode: `msiinfo export ScrcpyViewer-1.4.0.msi Property | grep UpgradeCode`. `msiinfo` comes from `brew install msitools`. **Ask the user before installing.**
- [ ] **Step 2:** If found, pin that exact value. If it can't be read, pin a freshly generated UUID (`uuidgen`), and record in the PR description that the 1.5.0 release notes must say "Windows: uninstall 1.4.x before installing 1.5.0."
- [ ] **Step 3:** Add a comment above `upgradeUuid`: "Never change: MSI upgrades replace the install in place only while this matches."
- [ ] **Step 4:** Run `./gradlew :composeApp:jvmTest`. Expected: PASS.
- [ ] **Step 5:** Commit `chore: pin MSI upgrade code, bump version to 1.5.0`.

### Task 10: End-to-end verification (manual, macOS here; Windows/Linux by the user)

- [ ] **Step 1:** Run `./gradlew :composeApp:downloadBundledTools :composeApp:packageDmg` and install the resulting 1.5.0 `.app` to `~/Applications` (user-writable, no prompt).
- [ ] **Step 2:** Create a test release source: a fork, or a draft-free tag on a test repo that holds a `v1.5.1` release built from this branch with `appVersion = "1.5.1"` (don't commit that bump). Launch with `-Dscrcpyviewer.updateRepo=<owner>/<repo>`. On a packaged app, add it to `jvmArgs` for the test build only.
- [ ] **Step 3:** Verify these:
  - The popup shows, and the badge shows.
  - "Later" hides the popup and keeps the badge.
  - Settings "Update now" downloads, the app quits and reopens, and the title shows 1.5.1.
  - `updates/update.log` shows each step.
- [ ] **Step 4:** Repeat with the app in `/Applications` to confirm the admin password prompt. Cancel the prompt once and confirm the old app relaunches and Settings shows the failure.
- [ ] **Step 5:** Hand the Windows and Linux runs to the user with the same steps. Report results faithfully in the PR. Unverified platforms are listed as unverified.
