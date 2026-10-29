# Moving users to Nucleus-based releases (1.6.0+)

From 1.6.0 the app updates itself through Nucleus. How people get to 1.6.0 depends on what they run today.

**Where things stand (2026-10-29):** the latest GitHub release is **v1.4.0**. v1.5.0 (PR #14, the hand-built
updater) has not been tagged. Two cases follow.

- **Case A:** PR #14 ships as v1.5.0 before this branch. Then some users have the old updater.
- **Case B:** this branch ships as the next release after v1.4.0. Nobody has an updater yet.

## What 1.6.0+ releases contain

The macOS names were checked on a local build. The Windows and Linux names come from the same `artifactName`
template, but were not built locally. Check them on the first CI release.

| Platform | Assets |
|---|---|
| macOS | `ScrcpyViewer-<v>-mac-arm64.dmg`, `ScrcpyViewer-<v>-mac-arm64.zip`, a `.blockmap` for each, `latest-mac.yml` |
| Windows | `ScrcpyViewer-<v>-win-x64-nsis.exe` (expected name), `.blockmap`, `latest.yml` |
| Linux | `ScrcpyViewer-<v>-linux-*.deb` (expected name), `latest-linux.yml` |

There is no `.msi` any more.

## Case B: users on 1.4.x or older (no updater)

These versions cannot update themselves, so every user installs 1.6.0 by hand once. After that, updates are
automatic.

- **macOS:**
  1. Open the `.dmg`.
  2. Drag Scrcpy Viewer to Applications.
  3. Choose **Replace**.

  The bundle id is unchanged (`com.programmersbox.scrcpyviewer`), so the Local Network permission should carry
  over.
- **Linux:**
  1. Install the `.deb` with `sudo apt install ./ScrcpyViewer-<v>-linux-*.deb`, or with `sudo dpkg -i`.
  2. 1.4.x was a jpackage `.deb` whose package name is `scrcpyviewer`. 1.6.0 uses `linux.packageName =
     "scrcpyviewer"` again, so dpkg should upgrade it in place.

  Confirm the package name on the first release with `dpkg-deb -f <file> Package`. The 1.6.0 deb was not built
  locally.
- **Windows:** run the new `.exe`. It installs a second copy beside the old MSI install; see
  [Windows: the old MSI install](#windows-the-old-msi-install).

## Case A: users on 1.5.0 (only if v1.5.0 is released first)

1.5.0 finds updates as follows:

1. It reads `https://api.github.com/repos/jakepurple13/ScrcpyViewerV2/releases/latest`.
2. It takes the first asset ending in `.dmg` (macOS), `.msi` (Windows) or `.deb` (Linux).
3. It checks that asset against GitHub's SHA-256 `digest`.

It ignores the new `.zip`, `.blockmap` and `latest-*.yml` files. Per platform:

- **macOS: updates itself.** It downloads the `.dmg` and swaps the `.app` as before.
- **Linux: probably updates itself, not verified.**
  - 1.5.0 installs the `.deb` with `pkexec dpkg -i`, then relaunches its own launcher path. On a jpackage install
    that path is `/opt/scrcpyviewer/bin/ScrcpyViewer`.
  - The path after the update was derived from Nucleus 2.5.18 source, not from a built package:
    - electron-builder installs deb payloads under `/opt/${productName}`. On Linux `productName` is
      `linux.packageName` = `scrcpyviewer` (`ElectronBuilderConfigGenerator.kt`).
    - The jpackage launcher is `bin/<packageName>` = `bin/ScrcpyViewer` (`AbstractJPackageTask` `--name`, and the
      launcher lookup in `AbstractElectronBuilderPackageTask`).
    - So the path should stay `/opt/scrcpyviewer/bin/ScrcpyViewer`.
  - If it does not, the update still installs and only the automatic relaunch fails. Check
    `dpkg -L scrcpyviewer | grep bin/` on the first release.
- **Windows: cannot update itself.** There is no `.msi`, so the popup offers only "Download from GitHub". After
  that it is the same as Case B on Windows.

From 1.6.0 on, every platform updates through Nucleus. Releases keep the `.dmg` and `.deb`. As long as they do,
macOS and Linux users still on 1.5.0 can jump straight to any later version.

## Windows: the old MSI install

The 1.4.x and 1.5.0 MSI installed per machine under `C:\Program Files\ScrcpyViewer`. The NSIS installer installs
per user under `%LOCALAPPDATA%\Programs`. They are different installers, so the new one does not remove the old one.
Both show up in Start and in **Installed apps** until the old one is uninstalled. Settings, presets and device
options are shared: both copies read `%APPDATA%\ScrcpyViewer`. Uninstalling the MSI copy does not remove them.

Text for the v1.6.0 release notes:

> **Windows: remove the old version after installing 1.6.0.** Starting with 1.6.0, Scrcpy Viewer installs per
> user and updates itself. The earlier installer (`.msi`) put a separate copy in Program Files, which stays until
> you remove it:
>
> 1. Install 1.6.0 with `ScrcpyViewer-1.6.0-win-x64-nsis.exe`.
> 2. Open **Settings > Apps > Installed apps** (Windows 10: **Settings > Apps > Apps & features**).
> 3. Search for **ScrcpyViewer**. Two entries are listed.
> 4. Select **⋯** next to the entry with version **1.4.x** or **1.5.0**, then choose **Uninstall**, and confirm.
>    Windows asks for administrator approval because the old copy was installed for all users.
> 5. Keep the **1.6.0** entry.
>
> Your settings and presets are kept. If you pinned the old app to Start or the taskbar, unpin it and pin the new
> one.

## Leftovers and paths

- **1.5.0's downloads.** 1.5.0 kept downloads and helper scripts in `<data dir>/updates`, where the data dir is
  `~/Library/Application Support/ScrcpyViewer`, `%APPDATA%\ScrcpyViewer` or `~/.local/share/ScrcpyViewer`. 1.6.0
  deletes that folder at start.
- **Nucleus' download cache.** Nucleus keeps the last downloaded installer in `<data dir>/update-cache`, about 150 MB
  on macOS. It uses it to download only the changed blocks next time, and replaces it on each update.
- **Nucleus' "just updated" marker.** It lives in `<platform data dir>/<nucleus.app.id>/nucleus-update-event`, and
  the app deletes it on the next start. The app id, from `JvmApplicationContext.resolvedAppIdProvider`, which feeds
  `-Dnucleus.app.id`, depends on the OS:
  - macOS: `bundleID` = `com.programmersbox.scrcpyviewer`. Checked in the built launcher config and in the
    hands-on update.
  - Windows: `windows.packageName ?: packageName` = `ScrcpyViewer`, so the marker sits in `%APPDATA%\ScrcpyViewer`.
    This is read from source only.
  - Linux: `linux.packageName` = `scrcpyviewer`, so the marker sits in `~/.local/share/scrcpyviewer`. This is read
    from source only.
- **Two different ids on Windows.** The electron-builder `appId`, which NSIS uses to recognise its own install and
  as the AUMID, is a separate value: `com.app.ScrcpyViewer`. Both ids come from `packageName`, which is why it must
  never change.
