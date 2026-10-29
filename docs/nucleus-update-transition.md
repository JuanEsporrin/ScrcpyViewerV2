# Updating from 1.5.0 to Nucleus-based releases (1.6.0+)

1.5.0 is the only release with the hand-built updater. From 1.6.0 on, the app updates through Nucleus. This note
says what happens to people still on 1.5.0.

## How 1.5.0 finds an update

It reads `https://api.github.com/repos/jakepurple13/ScrcpyViewerV2/releases/latest`. Then it picks the first asset
ending in `.dmg` (macOS), `.msi` (Windows) or `.deb` (Linux) and checks it against the SHA-256 `digest` GitHub
reports. It ignores every other asset, including the new `.zip`, `.blockmap` and `latest-*.yml` files.

## What 1.6.0+ releases contain

The macOS names were checked on a local build. The Windows and Linux names follow the same `artifactName`
template but were not built locally, so check them on the first CI release.

| Platform | Assets | 1.5.0 updater |
|---|---|---|
| macOS | `ScrcpyViewer-<v>-mac-arm64.dmg`, `.zip`, both `.blockmap`, `latest-mac.yml` | **Updates.** It finds the `.dmg` and swaps the `.app` as before. |
| Linux | `ScrcpyViewer-<v>-linux-*.deb`, `latest-linux.yml` | **Updates.** It finds the `.deb` and installs it with `pkexec dpkg -i` as before. The Debian package name should stay `scrcpyviewer` (`linux.packageName`), so dpkg upgrades it in place; confirm with `dpkg-deb -f <file> Package` on the first release. |
| Windows | `ScrcpyViewer-<v>-win-x64-nsis.exe`, `.blockmap`, `latest.yml` | **Cannot update.** No `.msi` is published, so the popup offers only "Download from GitHub". |

Once on 1.6.0 or later, every platform updates through Nucleus. For as long as releases keep the `.dmg` and `.deb`,
macOS and Linux users can jump straight from 1.5.0 to any later version.

## Windows users on 1.5.0

1. They see the update popup with "Download from GitHub". It opens the release page.
2. They download and run the NSIS `.exe`. It installs per user under `%LOCALAPPDATA%\Programs\ScrcpyViewer` and
   needs no administrator prompt.
3. The old MSI install (per machine, under `Program Files`) is **not removed**. Both copies appear in Start and in
   Apps & features. Uninstall the old "ScrcpyViewer" MSI entry by hand. Settings are shared: both read
   `%APPDATA%\ScrcpyViewer`.

The MSI is not coming back. This is a one-time manual step for the few Windows users on 1.5.0.

## Leftovers

- 1.5.0 kept downloads and helper scripts in `<data dir>/updates`. 1.6.0 deletes that folder at start.
- Nucleus keeps the last downloaded installer in `<data dir>/update-cache` (about 150 MB on macOS). It uses it to
  download only the changed blocks next time, and replaces it on each update.
- Nucleus writes its "just updated" marker to `~/Library/Application Support/com.programmersbox.scrcpyviewer`
  (macOS), `%APPDATA%\ScrcpyViewer` (Windows) or `~/.local/share/scrcpyviewer` (Linux). It deletes the marker on
  the next start.
