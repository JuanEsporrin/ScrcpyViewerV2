# Nucleus migration: packaging, release CI and updater

Date: 2026-10-29. Branch `worktree-agent-ac1c141e552ccf3ec`, stacked on PR #14 (`worktree-auto-update` @ `e1337f7`).

## Result

Packaging, release CI and the in-app updater now all run on Nucleus 2.5.18.

- **Packaging:** the `dev.nucleusframework` Gradle plugin replaces `compose.desktop.application`.
- **Release CI:** Nucleus' composite actions `setup-nucleus`, `generate-update-yml` and `publish-release` build and
  publish releases.
- **Updater:** `NucleusUpdater` replaces our own release source, downloader, install helper scripts and
  install-target detection.

The UI is the same as on PR #14, with two small additions. The version goes to 1.6.0.

Tests: `./gradlew :composeApp:jvmTest` passes 477 tests, 0 failures. `-Pscreenshots` passes 71 screenshot tests,
0 failures. On macOS, a full update ran by hand: a packaged 1.6.0 checked, downloaded and installed 1.6.1, and
came back up as 1.6.1.

## Versions

| | Before | After |
|---|---|---|
| Nucleus plugin (`dev.nucleusframework`) | none | 2.5.18 (latest stable; `3.0.0-dev-*` snapshots exist) |
| `nucleus.updater-runtime` (+ `core-runtime`) | none | 2.5.18 |
| Nucleus CI actions | none | `NucleusFramework/Nucleus/.github/actions/*@v2.5.18` |
| electron-builder | none | 26.15.5, pinned by the plugin and installed with `npm ci` into the build dir |
| Node.js | not needed | 24 in CI (via `setup-nucleus`). Packaging locally needs Node; tested with 26.8.2 |
| Kotlin compiler | 2.3.21 | 2.3.21, **unchanged**. The plugin did not need Kotlin 2.4 |
| kotlin-stdlib at runtime | 2.4.0 | 2.4.10, pulled in by the Nucleus runtime. Compiles and runs fine on 2.3.21 |
| Compose Multiplatform / JBR | 1.12.2 / JBR 25 | unchanged |
| App version | 1.5.0 | 1.6.0 |

The single version catalog key `nucleus` drives both the plugin and the runtime. The CI action tags must match it
(see the comment in `build.yml`).

## What changed

### Build (`composeApp/build.gradle.kts`, `gradle/libs.versions.toml`, root `build.gradle.kts`)

- `nucleus.application { }` replaces `compose.desktop.application { }`.
  - The Compose plugin stays applied: it provides the compiler, `compose.desktop.currentOs` and hot reload.
  - Nucleus refuses to run when both `application` blocks exist.
- Unchanged settings:
  - Main class.
  - JBR 25 `javaHome`, resolved only for run and packaging tasks; the task names still match `needsAppRuntime`.
  - Both `jvmArgs`.
  - Modules.
  - `appResourcesRootDir` (bundled adb and scrcpy-server per OS).
  - macOS `infoPlist.extraKeysRawXml` (Local Network keys).
  - Icons.
  - `packageName` `ScrcpyViewer`, and Linux `scrcpyviewer`.
  - `generateAppVersion` / `APP_VERSION`.
  - `downloadBundledTools` wiring.
- Formats, picked per host because `TargetFormat.Zip` builds on every OS:
  - macOS: DMG + ZIP.
  - Windows: NSIS. `oneClick`, `perMachine = false`: a per-user install with no UAC prompt.
  - Linux: DEB.
- `macOS.bundleID = "com.programmersbox.scrcpyviewer"`. This is what jpackage derived before; I checked the installed
  1.5.x bundle. Keeping it keeps macOS privacy grants (Local Network) and is also Nucleus' `appId` on macOS.
- `artifactName = "ScrcpyViewer-${version}-${os}-${arch}.${ext}"`. On macOS this gives
  `ScrcpyViewer-1.6.0-mac-arm64.{dmg,zip}`. Without it, the plugin uses the lowercase npm name.
- `publish { github { owner = "jakepurple13"; repo = "ScrcpyViewerV2" } }`. It makes the plugin write
  `latest-mac.yml` next to each format's output. `publishMode` stays `Never`: nothing uploads from Gradle.
- Removed: MSI target and `upgradeUuid`.
- Test-only properties: `-PappVersion`, `-PupdateFeed`, `-PdataDir`. They bake
  `-Dscrcpyviewer.updateFeed` / `-Dscrcpyviewer.dataDir` into the launcher for local update tests.
- Workaround for a plugin bug: `package.json` is deleted before each electron-builder package task.
  - Nucleus 2.5.18 writes that file only when it is missing, and electron-builder takes the version from it. After an
    `appVersion` change, an incremental build therefore named the files and the yml after the **old** version. I hit
    this hands-on.
  - Fixed upstream after 2.5.18 (Nucleus `85204103`). Remove the workaround on upgrade.
  - Clean CI builds are not affected.
- ProGuard: there is no ProGuard configuration on this branch; it lives only in open PR #3, and the old CI used
  `packageDmg`/`Msi`/`Deb`, not `packageRelease*`. Nucleus' `buildTypes.release.proguard` DSL is the place for it if
  PR #3 lands.

### Updater (`composeApp/src/jvmMain/.../update/`, `ui/update/`, `di/AppModule.kt`, `Main.kt`)

- **`UpdateBackend`** (`@Provided`, declared at Koin start) is the seam over Nucleus. It exists because
  `NucleusUpdater` is final.
  - `NucleusUpdateBackend` uses `GitHubProvider("jakepurple13", "ScrcpyViewerV2")`. The `scrcpyviewer.updateFeed`
    system property switches it to a `GenericProvider`, for loopback tests.
  - Nucleus' differential-download cache goes to `<data dir>/update-cache`.
  - Nucleus answers `NotAvailable` whenever it cannot install, for example in dev runs (`nucleus.executable.type=dev`).
    In that case a second updater checks as the OS's published type (`zip`, `nsis` or `deb`). The app still learns
    about new releases and offers "Download from GitHub", as PR #14 did for dev builds. It never installs from that
    updater.
- **`UpdateManager`** maps Nucleus results onto the unchanged `UpdateState`:

  | Nucleus | `UpdateState` / effect |
  |---|---|
  | `UpdateResult.Available` | `Available` |
  | `UpdateResult.NotAvailable` | `UpToDate`, and clears the known release |
  | `UpdateResult.Error` or a thrown exception | `Failed` on a manual check, the previous state on the launch check, so the launch check stays silent |
  | `DownloadProgress` | `Downloading(read, total)` |
  | final progress with a `file` | `Installing` |

  - `canSelfUpdate` = `backend.canInstall && !macNotInApplications`.
  - The launch from `/Volumes/` or App Translocation check is kept as one small function. Nucleus would try to
    replace a read-only or temporary copy there.
  - `installPending()` runs after the window loop. It stops the bundled adb server when that server is the one in
    use, then calls `installAndRestart`.
  - `UpdateLevel` is not used. Every newer version gets the same popup.
- **Update marker:** `consumeUpdateEvent()` is read once at start.
  - If the marker's new version equals the running version, the update worked. Settings shows "Updated from X."
    (new UI line).
  - Otherwise, `previousFailure` = "Updating to X did not complete." It shows in the popup and Settings as before.
- **`Main.kt`:** `application(exitProcessOnExit = false)`, then `updateManager.installPending()`, then
  `exitProcess(0)`. Sessions close and recordings finish before Nucleus takes over, as in the spike.
- **`Release`** is now a plain UI model: version, tag page URL, release date.
  - The yml has no release notes. The popup gets a "Release notes" link when "Update now" is shown (new UI element).
    Otherwise the main button already opens the release page.
  - Settings keeps "View release notes" and the always-visible "Download from GitHub" (`LocalUriHandler` +
    `openSafely`).
- **Deleted:** `ReleaseSource.kt`, `UpdateDownloader.kt`, `UpdateInstaller.kt` (helper scripts, WMI launch,
  osascript fallback), `InstallTarget.kt`, the GitHub release JSON model and `UpdatePlatform`.
- **Legacy cleanup:** 1.5.0's `<data dir>/updates` folder (downloads, helper scripts, `last-error.txt`) is deleted at
  start.

### CI (`.github/workflows/build.yml`)

- **`test`** is unchanged: jvmTest on macos-14, windows-latest and ubuntu-latest, with the test-report upload on
  failure.
- **`build`** runs on the same 3 runners.
  1. `setup-nucleus@v2.5.18` with JDK 21 for Gradle and Node 24. It also installs fakeroot and the other Linux
     packaging tools.
  2. `./gradlew :composeApp:downloadBundledTools :composeApp:packageDistributionForCurrentOS`.
  3. It uploads `release-assets-{macOS-arm64,Windows-amd64,Linux-amd64}`. Each holds only the installers and their
     `.blockmap`s, one directory level down. It excludes `__uninstaller*` as a precaution.
- macOS signing is ad hoc, as in the plugin default; I checked `Signature=adhoc`. No secrets are needed.
- **`release`** runs on `v*` tags.
  1. It downloads the `release-assets-*` folders.
  2. It **checks that every platform folder has a file named with the tag's version**. A tag that does not match
     `appVersion` would otherwise publish a yml that offers the same update forever.
  3. `generate-update-yml@v2.5.18` merges the per-platform `latest-*.yml`; on macOS it lists both the DMG and the ZIP.
  4. `publish-release@v2.5.18` publishes. It creates the release with `--generate-notes` and uploads the binaries
     and yml files. `GH_REPO` is set because the job does no checkout.
- `softprops/action-gh-release` is gone.

### Docs

- `docs/nucleus-update-transition.md`: what happens to 1.5.0 users.
- `README.md`: download table, packaging command, Node requirement, tech stack.
- A "superseded" note at the top of the PR #14 spec.

## Lines (from `e1337f7`)

| | Added | Removed |
|---|---|---|
| Production Kotlin (`jvmMain`) | 256 | 760 |
| Tests (`jvmTest`) | 300 | 1150 |
| Gradle (both build files and the catalog) | 100 | 48 |
| CI workflow | 64 | 27 |

Production code shrinks by about 500 lines. The test suite now covers the mapping instead of our download, install
and script code.

## Verified hands-on (macOS arm64, this machine)

1. **Tests.**
   - `./gradlew :composeApp:jvmTest`: 477 tests, 0 failures. Run with `--rerun-tasks` on the final code.
   - `-Pscreenshots`: 71 tests, 0 failures. I looked at `update-available.png` (new "Release notes" link) and
     `settings-updates-just-updated.png`.
   - No test touches the network: `withFakeAppKoin` uses a `FakeUpdateBackend` whose check never returns.
2. **Packaging with `packageDistributionForCurrentOS`.**
   - It produced `dmg/ScrcpyViewer-1.6.0-mac-arm64.dmg` and `zip/ScrcpyViewer-1.6.0-mac-arm64.zip`, each with a
     `.blockmap` and a per-format `latest-mac.yml`.
   - In the bundle:
     - `Contents/app/resources/{adb, scrcpy-server, NOTICE.txt}` are present.
     - `Info.plist` has `CFBundleIdentifier` `com.programmersbox.scrcpyviewer`, version 1.6.0,
       `NSLocalNetworkUsageDescription` and `NSBonjourServices`.
     - `ScrcpyViewer.icns` is present.
     - The runtime is JDK 25.0.3 and `libjvm` is JetBrains-built.
     - The launcher cfg has `-Dnucleus.executable.type=zip` (ZIP) or `=dmg` (DMG, mounted and checked) and
       `-Dnucleus.app.id=com.programmersbox.scrcpyviewer`.
     - It is ad hoc signed.
   - Duplicate jars (for example `runtime-desktop` 1.12.1 and 1.12.2) are in the bundle. They were already in the
     installed 1.5.x bundle, so this is not a regression.
3. **End-to-end update over a loopback `GenericProvider`.**
   - **Setup.**
     - The 1.6.0 ZIP was installed as `~/Applications/ScrcpyViewerNucleus.app`.
     - It used an isolated data dir with `adbPathOverride=/usr/bin/false`, so it never ran `adb kill-server` on
       the shared adb server.
     - The feed was `python3 -m http.server` on 127.0.0.1:8765. It served the 1.6.1 DMG and ZIP, their blockmaps,
       and a `latest-mac.yml` from a Python port of Nucleus' `generate-update-yml` script. The port was needed
       because macOS bash 3.2 lacks `declare -A`. It produced the same shape CI will publish: DMG first, both files
       listed, `blockMapSize`.
   - **Sequence.**
     1. On launch the app fetched the yml.
     2. It picked the **ZIP** over the DMG.
     3. It downloaded the ZIP and fetched the `.blockmap` for next time. The `.asc` returned 404, which is fine.
     4. It verified SHA-512.
     5. It shut down through `AppShutdown`.
     6. Nucleus' script logged `Archive contains: ScrcpyViewer.app` / `Installing to: …/ScrcpyViewerNucleus.app`.
     7. It swapped the bundle in place.
     8. It relaunched. `CFBundleShortVersionString` became `1.6.1`.
     9. The relaunched app checked again (nothing newer) and consumed the update marker.
   - The download cache landed in `<data dir>/update-cache`, not `~/.cache/nucleus`.
   - Jewel's JNA loader logged on start, so the decorated-window path ran.
   - **Limit:** this terminal has no Accessibility or Screen Recording permission. I could not click "Update now" or
     take a screenshot of the live window.
     - The click was replaced by a **temporary, uncommitted** 8-line env-gated auto-start in `Main.kt`, built only
       into the 1.6.0 test package and reverted right after. `git status` was clean.
     - Everything after the click is the committed code path.
   - **Cleanup.**
     - Removed: the test app, the feed server, the scratch data dir (with the cache), Nucleus' two
       `$TMPDIR/nucleus-update-*` dirs and the empty marker dir `~/Library/Application Support/com.programmersbox.scrcpyviewer`
       that the test created.
     - Not touched: `~/Applications/ScrcpyViewer.app` and `/Applications/ScrcpyViewer.app`. Neither was running
       during the test.
4. **CI:** `actionlint` 1.7.12 (installed with brew) reports no errors on `build.yml`.

## Not verified

- **Visual check of the packaged app's Jewel title bar.** No screen-capture permission; see the limit above. The
  runtime is JBR and Jewel initialised. Look at it once by hand.
- **The CI workflow itself.** It cannot run from here. The first push runs the `build` job; the first `v1.6.0` tag
  runs `release`. Watch for:
  - the Windows NSIS and Linux DEB file names, and that only installers are uploaded;
  - fakeroot and electron-builder on ubuntu-latest;
  - `gh` working without a checkout (via `GH_REPO`).
- **Windows and Linux packaging and installs.** Not built locally: electron-builder NSIS and DEB only build on their
  own OS. Their update install paths were read from Nucleus' source only (`PlatformInstaller.kt`,
  `WindowsUpdateScript.kt`).
- **Updating a DMG-installed copy.** Its type is `dmg`. Nucleus still prefers the ZIP on macOS, which is the same
  path as above, but I did not run it.
- **The 1.5.0 → 1.6.0 transition.** Reasoned from PR #14's code (`assetFor` picks the first `.dmg`, `.msi` or `.deb`
  and checks the GitHub `digest`), not run.

## Accepted gaps (user decision: do not work around)

1. **The Windows install helper may be killed by the jpackage launcher's job object.**
   - Nucleus starts `powershell -File nucleus-update.ps1` with a plain `ProcessBuilder`, as a child of the JVM. That
     script waits for the app to exit, runs `<setup>.exe /S --updated` and relaunches.
   - PR #14 used a WMI launch because jpackage's launcher job kills children when the app exits. If that applies to
     the NSIS-installed launcher too, the update downloads but never installs, and the next launch says "Updating to
     X did not complete." **Test this on Windows before relying on it.**
   - "Download from GitHub" is always available as the fallback.
2. **macOS has no administrator-password fallback.** If the `.app` cannot be replaced, Nucleus' script restores the
   backup, exits 1 and does **not** relaunch. That happens with a root-owned bundle in `/Applications` or a standard
   account. The user reopens the app by hand and sees "Updating to X did not complete."
3. **Smaller differences from PR #14.**
   - No inline release notes; the app links to the release page instead.
   - No failure reason: Nucleus' log stays in a temp dir, so only "did not complete" is shown.
   - Linux relaunches even when `dpkg` failed.
   - The integrity check is SHA-512 from the yml (same origin as the file) instead of GitHub's asset digest.

## Transition for 1.5.0 users (see `docs/nucleus-update-transition.md`)

- **macOS and Linux 1.5.0 users update as before.** Releases still carry a `.dmg` and a `.deb`, and 1.5.0's
  updater ignores the new ZIP, blockmap and yml assets. From 1.6.0 on they update through Nucleus.
- **Windows 1.5.0 users get no `.msi`.** Their popup offers only "Download from GitHub". The NSIS install is per
  user and sits beside the old per-machine MSI install, which has to be uninstalled by hand. Settings are shared
  (`%APPDATA%\ScrcpyViewer`). The MSI is not coming back.

## The PR description must say

- It is stacked on PR #14 and replaces that PR's updater internals; the UI is unchanged apart from the "Release
  notes" link and the "Updated from X." line.
- Windows switches from MSI to an NSIS `.exe`. Windows 1.5.0 users must download it by hand, and the old MSI
  install stays until it is uninstalled.
- Accepted gaps: the Windows job-object risk (untested) and no macOS admin fallback.
- Packaging needs Node.js locally (`setup-nucleus` handles CI).
- The `package.json` workaround and its removal condition (Nucleus > 2.5.18).
- Before tagging `v1.6.0`:
  1. Check the first CI run's artifacts.
  2. Test a Windows update by hand from a 1.6.0 NSIS install to a 1.6.1 test release (job-object risk).
  3. Look at the macOS title bar once.
- The tag must equal `appVersion`. The release job now enforces this.
