# Auto-update — design

Date: 2026-10-29
Status: approved; implementation superseded in 1.6.0

> 1.6.0 replaced the hand-built release source, downloader and helper scripts below with the Nucleus updater and
> packaging (see `docs/nucleus-migration-report.md` and `docs/nucleus-update-transition.md`). The UI, the popup
> rules and the shutdown-then-install order described here still apply.

## Intent

Scrcpy Viewer ships through GitHub releases (`v*` tags build `.dmg`, `.msi`, `.deb` in CI and attach them to the
release). Today users only learn about a new version by visiting GitHub, and updating means downloading and running
an installer by hand. The goal: when a new version ships, the app tells the user the next time it opens, and one
click replaces the running app with the new version and relaunches it, with no installer to drive.

Success: a user on 1.5.0 (the first version with the updater) opens the app the day 1.5.1 ships, sees a popup, clicks "Update now", maybe types their
password, and a moment later is running 1.5.0.

## Goals

- Check GitHub for a newer release on every launch, and on demand from Settings.
- When one exists:
  - Show a popup on every launch until the user updates. "Later" dismisses it for this launch only.
  - Show a badge on the Settings rail icon. It stays while an update is available.
  - Show an "Updates" section in Settings with its own "Update now".
- "Update now" downloads the installer for this OS and verifies it. The app then quits, swaps itself for the new
  version and relaunches. No installer UI.
- OS password / admin prompts during the swap are acceptable on all three platforms (user decision).

## Rollout

1.4.x has no updater, so it can't update itself. 1.5.0 ships the updater and the pinned MSI upgrade code. Users on
1.4.x install 1.5.0 by hand once. The 1.5.0 release notes must tell Windows users to uninstall 1.4.x first if the
pinned upgrade code differs from what 1.4.0 used (see Windows below). Auto-update works from 1.5.0 onward.

## Non-goals

- Code signing or notarization.
- Delta / patch updates, update channels (beta), background downloading before the user clicks.
- Periodic re-checks while the app stays open.
- "Skip this version".
- Changing how Windows or Linux are packaged (per-user MSI, AppImage) to avoid prompts.

## Anti-goals (would make it a failure even if it "works")

- Leaving the user with no working app after a failed update. The old version must stay usable if the download,
  verification or install fails.
- Installing a file that doesn't match the release's published checksum.
- Nagging when offline: launch checks that fail are silent.
- Auto-updating a dev build (`./gradlew run`, IDE run) and overwriting something that isn't an installed app.

## UI (approved via mockups)

Mockup source: `.superpowers/brainstorm/29604-1793289686/content/update-flow.html`.

1. **Popup on launch** (Material 3 `AlertDialog`): title "Update available", text "Version X is ready. You have Y.",
   the release notes (release body from GitHub, scrollable, max ~110dp tall), buttons "Later" and "Update now".
2. **After "Update now"**: the same dialog switches to "Updating to X". It shows download progress (bytes of total,
   linear progress bar) and a note: "Scrcpy Viewer will close and reopen. Your computer may ask for your password.
   Open mirror windows will close." A "Cancel" button stops the download. Once the download is verified, the app
   starts the swap and quits (through `AppShutdown`, so recordings get finalized).
3. **Settings → "Updates" section**, first section under the "Settings" heading:
   - "Current version {APP_VERSION}".
   - When an update is available, a card: "Version X available", release date, a "View release notes" link to the
     release page, and an "Update now" button. Same flow and progress as the popup; the builder chooses whether
     progress shows inline in the card or by opening the dialog.
   - "Check for updates" button, plus "Last checked …" text. While checking: button disabled / spinner. On error:
     short message ("Couldn't check for updates: …"). When up to date: "You're on the latest version."
4. **Badge**: Material 3 `BadgedBox` dot on the Settings rail icon while an update is available.

Ordering: on first run the onboarding dialog shows first. The update popup waits until onboarding is closed (my call).

## How it works

### Detecting an updatable install

jpackage launchers set the system property `jpackage.app-path`. "Update now" is offered only when all of these hold.
Otherwise the check and UI still run, but "Update now" is replaced by "Download from GitHub", which opens the
release page:
- `jpackage.app-path` is set (not a dev run).
- The architecture matches what CI ships (my call): macOS `aarch64` only, Linux `amd64` / `x86_64` only, Windows
  `amd64`. Intel Macs and arm64 Linux get "Download from GitHub".
- **macOS**: the `.app` isn't running from a read-only or temporary location: not under `/Volumes/` and not under an
  `AppTranslocation` path (my call). In that case Settings also shows "Move Scrcpy Viewer to Applications to enable
  updates."
- **Linux**: `dpkg -S <app-path>` reports the file as owned by the `scrcpyviewer` package (my call). An extracted app
  image or an uninstalled build gets "Download from GitHub".

### Checking

- `GET https://api.github.com/repos/jakepurple13/ScrcpyViewerV2/releases/latest` with
  `Accept: application/vnd.github+json`. Unauthenticated; 60 requests/hour/IP is plenty.
- Use `java.net.http.HttpClient` (no new dependency). Parse with kotlinx.serialization (already present): `tag_name`,
  `name`, `body`, `html_url`, `published_at`, and `assets[]` (`name`, `size`, `browser_download_url`, `digest`).
- Version compare: strip leading `v`, compare dotted numeric parts (`1.10.0 > 1.9.3`). Treat any tag that doesn't
  parse as not newer. `/releases/latest` already skips drafts and pre-releases.
- Asset pick by OS: `.dmg` on macOS, `.msi` on Windows, `.deb` on Linux (match by extension; one of each per release).
  No matching asset means no "Update now"; show "Download from GitHub" instead.
- Launch check runs once in the background after the window opens. Failures are logged and otherwise silent.
- Store `lastChecked` in memory only (my call); it needs no persistence.

### Verifying

GitHub's API already exposes `"digest": "sha256:…"` on every release asset (confirmed on v1.4.0), so **no CI change is
needed**. After download, compute SHA-256 and compare. Missing digest or mismatch: delete the file, show an error, and
don't install.

### Downloading

- To `AppDataDir.root()/updates/<version>/<asset name>`. Delete older version folders when a new download starts.
- Stream with progress; cancellable. Delete partial files on cancel or failure.

### Swapping (per OS)

The app writes a small helper script into the updates folder and starts it fully detached. The script outlives the
JVM. Then the app quits through `AppShutdown.request()`. The script:
1. Waits for the app's PID to exit (passed as an argument), with a timeout (~60 s).
2. Installs.
3. Relaunches the app.
4. On install failure, relaunches the **old** app (still in place) and writes a marker file, e.g.
   `updates/last-error.txt`. On next launch the app shows the error in Settings and the popup, then deletes the file.

The script also writes a log (`updates/update.log`) for debugging.

Before installing, the helper stops the adb server, but only when this app started it from the bundled adb. The app
passes the bundled adb path to the helper only when the bundled adb is in use, not a custom adb path from Settings.
The helper then runs `<bundled adb> kill-server`. Reason: the daemon runs from inside the install folder and locks
`adb.exe` on Windows, which breaks the MSI. Other adb tools (Android Studio, CLI) restart the server on their next
command.

Installed app location comes from `jpackage.app-path`. On macOS it is `…/ScrcpyViewer.app/Contents/MacOS/ScrcpyViewer`,
so walk up to the `.app` bundle.

- **macOS (`.dmg`)**, shell script:
  - `hdiutil attach -nobrowse -readonly -mountpoint <tmp> <dmg>`.
  - Copy `<tmp>/ScrcpyViewer.app` to a staging path next to the target with `ditto`, then swap in the staged copy
    (rename old aside, rename new in, delete old).
  - If the target's parent isn't writable by the user (e.g. `/Applications` without admin rights), run that step via
    `osascript -e 'do shell script "…" with administrator privileges'` (password prompt).
  - `hdiutil detach`. Strip `com.apple.quarantine` from the new bundle (`xattr -dr`) in case something set it.
  - Relaunch with `open <app>`.
- **Windows (`.msi`)**, PowerShell or `.cmd` script:
  - `msiexec /i <msi> /passive /norestart` (shows the UAC prompt and a progress bar, nothing to click).
  - Relaunch the installed `ScrcpyViewer.exe` from the same path.
  - **Upgrade code**: the MSI must replace the existing install in place, not install side by side. That requires a
    stable upgrade UUID across versions. `build.gradle.kts` doesn't set `windows { upgradeUuid }` today. The builder
    reads the UpgradeCode from the shipped 1.4.0 MSI (`Property` table, e.g. `msiinfo export` or Orca) and pins that
    value. If it can't be read or looks random per build, pin a freshly generated fixed UUID instead and add the
    "uninstall 1.4.x first" note to the 1.5.0 release (my call; see Rollout). Never uninstall-then-install from the
    helper. A failed install would leave no app.
- **Linux (`.deb`)**, shell script:
  - `pkexec dpkg -i <deb>` (password prompt via polkit). If `pkexec` is missing, fall back to opening the `.deb` with
    `xdg-open` (system installer) and skip relaunch.
  - Relaunch via the installed launcher (`/opt/scrcpyviewer/bin/scrcpyviewer`, or derived from `jpackage.app-path`).

Process launch must be detached so the helper survives the JVM exit: `nohup … &` via `/bin/sh -c` on macOS/Linux,
`cmd /c start "" /b …` or `powershell -WindowStyle Hidden` on Windows.

## Code shape (suggested, builder's call)

- `update/ReleaseInfo.kt`: data classes plus version parsing/compare and asset selection (pure, unit-tested).
- `update/UpdateChecker.kt`: GitHub fetch plus JSON parse.
- `update/UpdateDownloader.kt`: download with progress, SHA-256 verify.
- `update/UpdateInstaller.kt`: writes and launches the per-OS helper script (script templates as resources or strings).
- `update/UpdateManager.kt`: `StateFlow<UpdateState>` (`Idle`, `Checking`, `UpToDate`, `Available(release)`,
  `Downloading(progress)`, `ReadyToInstall`, `Error(msg)`). An app-lifetime Koin `@Singleton` (`UpdateModule` in `di/AppModule.kt`).
  A `@KoinViewModel UpdateViewModel` in `ui/update/` wraps it, and the dialog, the badge and the Settings section read from that.
- `ui/update/UpdateDialog.kt`, plus edits to `ui/App.kt` (dialog + badge), `ui/settings/SettingsScreen.kt` (section).

Follow existing patterns: `AppShutdown` for quitting, `AppDataDir` for paths, `Os` for platform, Compose screenshot
tests like `OnboardingScreenshotTest` for the dialog and Settings section.

## Testing

- Unit: version compare, asset pick per OS, release JSON parsing (fixture from the real v1.4.0 response), digest
  verify (match, mismatch, missing), state transitions in `UpdateManager` with a fake checker/downloader.
- Screenshot: popup (available, downloading), Settings section (up to date, available, error), rail badge.
- Manual, per OS: install 1.4.x, publish a test release (or point the checker at a fork / local override via a system
  property such as `scrcpyviewer.updateRepo`), and run the full update path. Include the "no admin rights" path on
  macOS and the failure-rollback path (e.g. cancel the password prompt). The manual run is required. Unit tests
  can't cover the swap.

## Left to the builder

- Exact copy text beyond what's above, icons, spacing.
- Whether download progress in Settings is inline or uses the dialog.
- Script language on Windows (PowerShell vs cmd).
- How to expose a test override for the release source.
