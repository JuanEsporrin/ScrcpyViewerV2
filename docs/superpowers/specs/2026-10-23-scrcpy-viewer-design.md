# Scrcpy Viewer — Design

Date: 2026-10-23
Package: `com.programmersbox.scrcpyviewer`
Mockups: https://claude.ai/artifact/ViMdskKQZUgNtjxnPXpe3V (Concept C, Presets page, Mirror window)

## Intent

A desktop app that shows the Android devices adb can see, lets the user pick scrcpy options with checkboxes, and mirrors a device in a window that belongs to the app. Today this means running `scrcpy` from a terminal with flags remembered by hand. The app replaces that with a visible list of devices, a searchable list of every option, and saved presets that can be shared.

## Goals

- Live device list from adb. Changes appear as they happen, with no manual refresh.
- One checkbox per scrcpy option. Options that take a value have a checkbox plus an input (text field or dropdown). The input is disabled until the box is checked.
- Click a device (or press Mirror) to open a new app window that shows that device's screen and accepts mouse and keyboard input.
- Several mirror windows can be open at once, one per device.
- Presets: save, edit, duplicate, delete, apply, set as default for new devices.
- Presets export to and import from a `.scrcpypresets.json` file.
- Copy-to-clipboard buttons for the preset JSON and for the generated scrcpy command.
- Import also accepts pasted JSON.
- scrcpy-server and adb are bundled. The user installs nothing else.
- Material 3 theming throughout, light and dark.

## Non-goals

- Running the native `scrcpy` program or hosting its SDL window.
- Android, iOS or web targets. The project is Compose Multiplatform but only the desktop (JVM) target is built.
- Device management features beyond mirroring (file browser, app installer, logcat viewer).

## Anti-goals

- The mirror opening in a window the app does not own.
- Requiring the user to install scrcpy, adb or FFmpeg.
- A device list that goes stale until the user presses refresh.

## Decided by the user

- **Main window: Concept C.** A navigation rail with three destinations: Devices, Presets, Settings.
  - Devices: device list on the left, all options on the right in two columns. Search box and filter chips by group (Video, Audio, Control, Device, Window, Recording, Camera, Virtual display, Connection). Footer shows the active preset, the generated command with a Copy button, and a Mirror button.
  - Presets: preset list on the left with New, Import, Export all. The right pane shows the selected preset's options with Apply, Edit, Duplicate, Export, Delete, and a "Default" marker. Shows the exported JSON with a Copy JSON button.
  - Import dialog lists each preset in the file. For a name that already exists, the user picks Replace, Keep both or Skip. Unknown options are listed and ignored.
- **Built-in client.** The app talks to scrcpy-server directly, decodes the video itself and draws it in Compose. Options that only affect the native scrcpy SDL window are dropped or turned into app settings.
- **Bundle scrcpy** (scrcpy-server) and **bundle adb**.
- **Real-time device list.**
- **Copy buttons** on the JSON and on scrcpy commands.

## How it works

### Device tracking

- Connect to the adb server on `localhost:5037` and send `host:track-devices-l`. The server pushes the full device list on every change. Parse each push into `Device(serial, state, model, product, transportId, usb|tcp)`.
- On a device entering `device` state, read `ro.product.model`, `ro.build.version.release` and `ro.build.version.sdk` once with `adb -s <serial> shell getprop`.
- If no adb server is running, start it with the bundled adb (`adb start-server`) and reconnect. If the socket drops, reconnect with backoff.
- States shown: `device` (ready), `unauthorized` (tell the user to accept the prompt on the phone), `offline`, others shown as their raw name.
- If a device disconnects while its mirror window is open, the window shows "Disconnected" over the last frame and stays open. When the same serial returns, offer Reconnect.

### Mirroring (built-in client)

Follows the scrcpy client protocol for the bundled server version:

1. `adb -s <serial> push scrcpy-server /data/local/tmp/scrcpy-server.jar`
2. `adb reverse localabstract:scrcpy_<scid> tcp:<port>` (fall back to `adb forward` when reverse fails, or when "Force adb forward" is checked).
3. Start the server: `adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server <version> scid=<scid> <key=value options…>`.
4. Accept the video, audio and control sockets in the order the server opens them.
5. Video: read the codec header and frame packets, decode with FFmpeg through JavaCV, convert to an `ImageBitmap`, draw in a Compose `Canvas` scaled to fit and keeping aspect ratio.
6. Control: map pointer and key events from the Compose window to scrcpy control messages (inject touch, inject keycode, inject text, scroll, back-or-screen-on, set clipboard, rotate, expand panels). Map window coordinates to device coordinates using the current frame size.

Closing a mirror window closes its sockets, which stops the server on the device, and removes the reverse/forward tunnel.

### Options model

- Each option is a data entry: flag name, label, group, type (`Boolean`, `Int`, `String`, `Enum(values)`, `Size`, `Crop`), help text, and how it maps to the server (`key=value` server argument, client-side behaviour, or app setting).
- The list is the scrcpy 5.0 `--help` output, grouped. Options that only exist for the native SDL client (`--render-driver`, `--window-borderless`, `--window-x/y/width/height`, `--always-on-top`, `--fullscreen`, `--disable-screensaver`, `--no-mipmaps`, `--v4l2-*`, `--otg`, `--print-fps`, `--no-window`, `--hwdec`, `--shortcut-mod`, `--pause-on-exit`) are either mapped to a Compose window equivalent (always on top, fullscreen, window size/position, FPS display) or hidden.
- The generated command shown in the footer and in the mirror window is the equivalent `scrcpy -s <serial> …` command line, so it can be copied and run with a standalone scrcpy if needed.

### Presets

- Stored in the app's data directory as JSON.
- File format:

```json
{
  "format": "scrcpyviewer-presets",
  "version": 1,
  "scrcpyVersion": "5.0",
  "presets": [
    { "name": "QA default", "description": "…", "options": { "max-size": "1920", "no-audio": true } }
  ]
}
```

- `options` keys are flag names without leading dashes. Booleans are `true`; valued options are strings.
- Import rejects files whose `format` does not match, and lists options the current options model does not know.

### Mirror window

A Compose `Window` per device, title `<model> · <serial>`.
- Top strip: state dot, model, Android version, connection type, "Fit to window" toggle, Copy command.
- Side toolbar: Back, Home, Recents, Volume up, Volume down, Rotate, Power, Screenshot, Record.
- Status line: device resolution and capped size, codec and bit rate, live FPS, recording indicator.

### Bundling

- `scrcpy-server` 5.0 (≈720 KB, Apache 2.0) as an app resource. Its version string must match what the client sends at startup.
- `adb` from Android platform-tools for macOS arm64, Windows x64 and Linux x64, shipped in the app resources and extracted to the app's data directory on first run (marked executable on macOS and Linux). Platform-tools license notice included.
- FFmpeg natives through JavaCV's platform artifacts, limited to the target OSes to control size.
- Packaged with the Compose Desktop Gradle plugin (`nativeDistributions`): DMG for macOS, MSI for Windows, DEB for Linux.

### Settings page

- Path to adb: bundled (default) or a custom path.
- Theme: system, light, dark.
- Default preset for new devices.
- Where screenshots and recordings are saved.

## Answered by the user after review

- **Audio and recording are in v1.**
  - Audio: read the audio socket. Decode Opus (the default), AAC, FLAC or raw with FFmpeg. Play it through `javax.sound.sampled` with a jitter buffer sized from `--audio-buffer`, which defaults to 50 ms.
  - Recording: mux the received packets without re-encoding to MP4 or MKV through FFmpeg (`--record`, `--record-format`, `--record-orientation`, `--time-limit`). The Record button in the mirror window starts and stops recording into the folder set in Settings.
- **Checkbox state:**
  - Each device serial has its own working option set. It is seeded from the default preset the first time that serial is seen, and saved between runs.
  - Editing a checkbox while a preset is active shows "<preset> (modified)" with **Save** (overwrites the preset) and **Save as new preset**. The preset changes only when the user clicks Save.
  - **Apply** on the Presets page copies the preset into the selected device's working set.
  - A mirror window starts with that device's working set.
- **Platforms:**
  - macOS on Apple Silicon is the primary target, built and tested.
  - Windows x64 and Linux x64 are set up and must build: their adb binaries, FFmpeg natives and installers.
  - GitHub Actions builds every platform, each on its own runner:
    - `macos-14` (arm64) builds the DMG.
    - `windows-latest` builds the MSI.
    - `ubuntu-latest` builds the DEB.
  - Builds run on every push and PR. When a tag is pushed, the installers are uploaded to a GitHub Release.

## Builder's calls (made by Claude, not the user)

- **Device row clicks.** A single click selects the device, so its options and command appear on the right. A double-click or the Mirror button opens the mirror window. Devices that are `unauthorized` or `offline` can be selected but cannot be mirrored. For those, the Mirror button is disabled and the row shows what to do.
- **Options that need more than video and control:**
  - `--keyboard`, `--mouse` and `--gamepad`: offer `sdk`, `uhid` and `disabled`, which work through the server. Hide `aoa` and `--otg`, because they need raw USB access.
  - Connection group: `-s`, `-d`, `-e`, `--tcpip`, `--select-*` and `--serial` are replaced by the device list and hidden. Port and tunnel options (`--port`, `--force-adb-forward`, `--tunnel-host`, `--tunnel-port`) stay.
  - A Wi‑Fi pairing screen is out of scope for v1.
  - Camera and virtual display options are passed to the server. They need no extra client work beyond what video already needs.

## Constraints

- Kotlin, Compose Multiplatform, desktop (JVM) target. JDK 21 is installed locally.
- Material 3 (`androidx.compose.material3` via Compose Multiplatform). Seed color teal, as in the mockups.
- The bundled scrcpy-server and the client protocol code must be the same scrcpy version.

## Left to the builder

- Module layout, DI, and state holder style.
- Exact decoder pipeline and frame conversion, as long as 60 fps at 1920 px max size is smooth on a recent Mac.
- Wording of help text for each option.
- Icons.
