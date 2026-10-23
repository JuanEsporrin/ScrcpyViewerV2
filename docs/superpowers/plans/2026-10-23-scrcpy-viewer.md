# Scrcpy Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Compose Multiplatform desktop app that tracks adb devices live, builds scrcpy options from checkboxes and presets, and mirrors each device (video, audio, control, recording) in its own app window using a bundled scrcpy-server and adb.

**Architecture:**
- One Gradle module, `composeApp`, with a single `jvm()` target.
- Pure-Kotlin layers (adb wire protocol, options model, presets, scrcpy protocol) are unit-tested against golden data.
- Media goes through the FFmpeg JavaCPP presets: `avcodec` for decoding, `swscale` and `swresample` for conversion, `avformat` for muxing.
- Each mirror window owns one `ScrcpySession`, which reads three sockets on coroutines and exposes frames and state as `StateFlow`.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.12.2 (Material 3), kotlinx-coroutines 1.11.0, kotlinx-serialization-json 1.11.0, `org.bytedeco:ffmpeg:8.1.2-1.5.14` (+ `javacpp:1.5.14`), JUnit via `kotlin("test")`, Gradle 9.6.1 wrapper, JDK 21.

**Spec:** `docs/superpowers/specs/2026-10-23-scrcpy-viewer-design.md`. Mockups: https://claude.ai/artifact/ViMdskKQZUgNtjxnPXpe3V

## Global Constraints

- Package root: `com.programmersbox.scrcpyviewer`.
- Bundled scrcpy-server version: `5.0`. The version string sent to `com.genymobile.scrcpy.Server` must be exactly `5.0`.
- Protocol source of truth: scrcpy source at tag `v5.0`. Clone it read-only into the session scratchpad (`git clone --depth 1 --branch v5.0 https://github.com/Genymobile/scrcpy <scratchpad>/scrcpy-src`). Never build or run anything from that clone.
- Material 3 only. Teal seed. Light and dark color schemes use the exact hex values in the mockup's `:root` and dark blocks, for example `primary #006a6a` and dark `primary #4cdada`.
- Theme follows the setting: system, light or dark.
- Primary platform macOS arm64. Windows x64 and Linux x64 must build.
- FFmpeg natives: only the host platform's classifier is on the classpath (`macosx-arm64`, `windows-x86_64` or `linux-x86_64`), chosen by the build host.
- Preset file extension `.scrcpypresets.json`. Format id `"scrcpyviewer-presets"`, `version: 1`, `scrcpyVersion: "5.0"`. Option keys have no leading dashes. Booleans are `true`; valued options are strings.
- Device tracking uses the adb server's `host:track-devices-l` on `localhost:5037`. No polling timer.
- User-visible copy: use the mockup's labels ("Mirror", "Copy", "Copy JSON", "Import", "Export all", "+ New preset", "Apply", "Save", "Save as new preset", "Disconnected", "Reconnect", "<preset> (modified)").
- Hidden options: `--otg`, the `aoa` values of `--keyboard/--mouse/--gamepad`, `-s/--serial`, `-d`, `-e`, `--tcpip`, `--select-usb`, `--select-tcpip`, `--list-*`, `-h`, `-v`, `-V`, `--v4l2-*`, `--render-driver`, `--no-mipmaps`, `--hwdec`, `--shortcut-mod`, `--pause-on-exit`, `--no-window`, `--window-borderless`, `--disable-screensaver`, `--no-terminal-title`, `--kill-adb-on-close`.
- SDL-window options that become mirror-window behaviour: `--always-on-top`, `--fullscreen`, `--window-title`, `--window-x/-y/-width/-height`, `--print-fps` (FPS in the status line), `--no-window-aspect-ratio-lock`.

## Review Focus

1. **Device unplugged during a mirror session.** The session ends without an uncaught exception. The window shows "Disconnected" over the last frame and stays open. When the same serial returns to `device` state, Reconnect starts a new session. Tests are in Task 11 and Task 12.
2. **Two devices mirrored at once.** Each session gets a distinct `scid` and a distinct local port. Closing one leaves the other running. Test in Task 11.
3. **The adb server is not running or restarts.** The tracker starts the bundled adb and resubscribes with backoff. The device list empties while disconnected and refills afterwards. Test in Task 3.
4. **Malformed or foreign import.** Bad JSON, a wrong `format` or a higher `version` gives an error message in the import dialog. No preset is changed. Test in Task 5.
5. **Invalid option value**, for example `max-size = "abc"` or a crop of `"1:2"`. The field shows an error and Mirror is disabled. It is never sent to the server. Test in Task 4 and Task 7.

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew*
composeApp/build.gradle.kts
composeApp/bundled/                      # downloaded, git-ignored
  common/scrcpy-server
  macos-arm64/adb   windows-x64/adb.exe AdbWinApi.dll AdbWinUsbApi.dll   linux-x64/adb
composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/
  Main.kt
  AppDataDir.kt
  tools/BundledTools.kt
  adb/Device.kt  adb/AdbWire.kt  adb/DeviceListParser.kt  adb/AdbClient.kt  adb/DeviceTracker.kt
  options/OptionSpec.kt  options/OptionCatalog.kt  options/OptionValues.kt  options/ServerArgs.kt  options/CommandLine.kt
  presets/Preset.kt  presets/PresetCodec.kt  presets/PresetStore.kt  presets/ImportPlan.kt
  state/DeviceOptionsStore.kt  state/AppSettings.kt
  scrcpy/StreamProtocol.kt  scrcpy/ControlMessage.kt  scrcpy/DeviceMessage.kt  scrcpy/ServerLauncher.kt  scrcpy/ScrcpySession.kt
  media/VideoDecoder.kt  media/AudioPlayer.kt  media/Recorder.kt  media/Screenshot.kt
  ui/theme/Color.kt  ui/theme/Theme.kt
  ui/App.kt  ui/common/CopyButton.kt  ui/common/OptionRow.kt
  ui/devices/DevicesViewModel.kt  ui/devices/DevicesScreen.kt
  ui/presets/PresetsViewModel.kt  ui/presets/PresetsScreen.kt  ui/presets/ImportDialog.kt
  ui/settings/SettingsScreen.kt
  ui/mirror/InputMapper.kt  ui/mirror/MirrorWindow.kt  ui/mirror/MirrorController.kt
composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/...   # mirrors main layout
.github/workflows/build.yml
```

State holders are plain classes with a `CoroutineScope` and `StateFlow`s (no DI framework). One `AppGraph` object in `Main.kt` constructs the singletons.

---

### Task 1: Scaffold, theme, empty rail window

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `composeApp/build.gradle.kts`, `.gitignore`
- Create: `Main.kt`, `AppDataDir.kt`, `ui/theme/Color.kt`, `ui/theme/Theme.kt`, `ui/App.kt`
- Test: `AppDataDirTest.kt`

**Interfaces:**
- Produces: `object AppDataDir { fun root(): java.nio.file.Path }`. This is `~/Library/Application Support/ScrcpyViewer` on macOS, `%APPDATA%\ScrcpyViewer` on Windows, and `$XDG_DATA_HOME/ScrcpyViewer` or `~/.local/share/ScrcpyViewer` on Linux. The system property `scrcpyviewer.dataDir` overrides it, for tests.
- Produces: `@Composable fun ScrcpyViewerTheme(themeMode: ThemeMode, content: @Composable () -> Unit)`, `enum class ThemeMode { System, Light, Dark }`.
- Produces: `@Composable fun App(graph: AppGraph)` with a `NavigationRail` for Devices, Presets and Settings. Each destination is a placeholder `Text` for now.

- [ ] **Step 1:** Run `git init` and `gradle wrapper --gradle-version 9.6.1`. Write `.gitignore` covering `build/`, `.gradle/`, `.idea/`, `.kotlin/` and `composeApp/bundled/`.
- [ ] **Step 2:** Write the version catalog with the Tech Stack versions. Add the `org.jetbrains.compose`, `org.jetbrains.kotlin.plugin.compose`, `kotlin("multiplatform")` and `kotlin("plugin.serialization")` plugins.
  - `jvmMain` deps: `compose.desktop.currentOs`, `compose.material3`, `compose.materialIconsExtended`, coroutines-swing, serialization-json, `org.bytedeco:ffmpeg:8.1.2-1.5.14:<hostClassifier>` and `org.bytedeco:javacpp:1.5.14:<hostClassifier>`, plus the non-classifier jars of both.
  - `hostClassifier` comes from `os.name`/`os.arch`: `macosx-arm64`, `macosx-x86_64`, `windows-x86_64` or `linux-x86_64`.
  - `compose.desktop.application`: `mainClass = "com.programmersbox.scrcpyviewer.MainKt"`, `packageName = "ScrcpyViewer"`, `packageVersion = "1.0.0"`, `targetFormats(Dmg, Msi, Deb)`, `appResourcesRootDir = layout.projectDirectory.dir("bundled")`.
  - If `compose.material3` does not resolve for 1.12.2, use the `org.jetbrains.compose.material3:material3` version listed in the Compose Multiplatform 1.12.2 release notes.
- [ ] **Step 3:** Write `AppDataDirTest.root honors override property`. Set `scrcpyviewer.dataDir` to a temp dir and assert that `root()` equals it and that the directory exists.
- [ ] **Step 4:** Run `./gradlew :composeApp:jvmTest`. Expected: FAIL (unresolved `AppDataDir`).
- [ ] **Step 5:** Implement `AppDataDir`, `Color.kt` (light and dark `ColorScheme` built from the mockup tokens), `Theme.kt`, `App.kt` and `Main.kt` (`application { Window(title = "Scrcpy Viewer") { App(graph) } }`).
- [ ] **Step 6:** Run `./gradlew :composeApp:jvmTest`. Expected: PASS. Then run `./gradlew :composeApp:run` and check that the window shows the rail with three destinations in the system theme. Close the window.
- [ ] **Step 7:** Commit with the message `chore: scaffold compose desktop app with M3 theme`.

### Task 2: Bundled scrcpy-server and adb

**Files:**
- Modify: `composeApp/build.gradle.kts`, to add the task `downloadBundledTools`. `prepareAppResources` and `run` both depend on it.
- Create: `tools/BundledTools.kt`
- Test: `tools/BundledToolsTest.kt`

**Interfaces:**
- Produces: `class BundledTools(resourcesDir: Path?, dataDir: Path) { val serverJar: Path; fun adbExecutable(): Path; val serverVersion: String /* "5.0" */ }`.
  - `resourcesDir` defaults to `System.getProperty("compose.application.resources.dir")`.
  - In dev runs it falls back to `composeApp/bundled/<common|hostDir>` through the system property `scrcpyviewer.bundledDir`, which the `run` task sets.
  - `adbExecutable()` copies adb (and the Windows DLLs) into `dataDir/tools/adb-<sha256 prefix>/` on first call. It marks the file executable on macOS and Linux, and returns the copied path. A matching copy is reused.
  - `Settings.adbPathOverride` (Task 6) is applied by the caller, not here.

- [ ] **Step 1:** Write the Gradle task. It downloads:
  - `https://github.com/Genymobile/scrcpy/releases/download/v5.0/scrcpy-server-v5.0` to `bundled/common/scrcpy-server`. Verify its SHA-256 against the value in the v5.0 release notes and hard-code that value in the build script.
  - `https://dl.google.com/android/repository/platform-tools-latest-{darwin,windows,linux}.zip`, unzipped so that only the adb binary, plus `AdbWinApi.dll` and `AdbWinUsbApi.dll` on Windows, lands in `bundled/macos-arm64`, `bundled/windows-x64` and `bundled/linux-x64`. Also copy `NOTICE.txt` from each zip next to its binary.
  - The task is up-to-date when the files exist.
- [ ] **Step 2:** Write the tests:
  - `adbExecutable copies to data dir and is executable`: use a temp fake resources dir containing a dummy `adb` file. Assert the copied file exists under `dataDir/tools` and that `Files.isExecutable` is true. Skip the executable check on Windows.
  - `adbExecutable reuses existing copy`: two calls return the same path, and the file's mtime is unchanged.
  - `serverJar resolves from common dir`.
- [ ] **Step 3:** Run the tests. Expected: FAIL.
- [ ] **Step 4:** Implement `BundledTools`.
- [ ] **Step 5:** Run `./gradlew :composeApp:downloadBundledTools :composeApp:jvmTest`. Expected: PASS, and `composeApp/bundled/common/scrcpy-server` is about 720 KB.
- [ ] **Step 6:** Commit with the message `feat: bundle scrcpy-server 5.0 and adb`.

### Task 3: adb client and live device tracker

**Files:**
- Create: `adb/Device.kt`, `adb/AdbWire.kt`, `adb/DeviceListParser.kt`, `adb/AdbClient.kt`, `adb/DeviceTracker.kt`
- Test: `adb/DeviceListParserTest.kt`, `adb/AdbWireTest.kt`, `adb/DeviceTrackerTest.kt` (with a fake adb server on a `ServerSocket`)

**Interfaces:**
- Produces: `enum class DeviceState { Device, Unauthorized, Offline, Other }` and `data class Device(val serial: String, val state: DeviceState, val rawState: String, val model: String?, val product: String?, val transportId: Long?, val isTcp: Boolean, val info: DeviceInfo? = null)`.
- Produces: `data class DeviceInfo(val model: String, val androidVersion: String, val sdk: Int)`.
- Produces: `object AdbWire { fun encodeRequest(cmd: String): ByteArray /* 4-hex-length + cmd */; fun readStatus(input: InputStream) /* throws AdbException("FAIL: msg") */; fun readHexLengthPayload(input: InputStream): String? /* null on EOF */ }`.
- Produces: `object DeviceListParser { fun parse(payload: String): List<Device> }`. Parses the `-l` format `serial<ws>state key:value...`. `isTcp` is true when the serial matches `host:port` or starts with `adb-` and contains `._adb-tls-connect.`.
- Produces: `class AdbClient(adbPath: () -> Path, host: String = "127.0.0.1", port: Int = 5037)` with:
  - `suspend fun startServer()`, which runs `adb start-server`.
  - `suspend fun shell(serial: String, cmd: String): String`, over the socket using `host:transport:<serial>` then `shell:<cmd>`.
  - `suspend fun exec(vararg args: String): ProcessResult`, which runs the adb binary for push, reverse and forward.
- Produces: `class DeviceTracker(client: AdbClient, scope: CoroutineScope) { val devices: StateFlow<List<Device>>; val connected: StateFlow<Boolean> }`.
  - On each push it emits the parsed list immediately.
  - For each serial newly in `Device` state it runs one `getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk` and re-emits that device with `info`. The result is cached per serial and transportId.
  - On a connection error it sets `connected=false`, emits an empty list, calls `startServer()`, and retries with backoff of 250 ms doubling to a maximum of 5 s.

- [ ] **Step 1:** Write `DeviceListParserTest`. Use this golden input:
  ```
  39121FDJH004AB         device usb:1-1 product:husky model:Pixel_8_Pro device:husky transport_id:3
  192.168.1.42:5555      device product:gts9wifi model:SM_X710 device:gts9wifi transport_id:5
  emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1
  ZY22H8KLMN             unauthorized usb:1-2 transport_id:6
  R5CT30XYZ12            offline transport_id:7
  ```
  Assert 5 devices, the states in order, `model == "Pixel_8_Pro"`, `isTcp` only for the second, `transportId == 3L` for the first, and `rawState == "unauthorized"`. Also assert that an empty payload gives an empty list.
- [ ] **Step 2:** Write `AdbWireTest`:
  - `encodeRequest("host:track-devices-l")` equals the bytes of `"0014host:track-devices-l"`.
  - `readStatus` on `"FAIL0005nope!"` throws with the message `nope!`.
  - `readHexLengthPayload` on `"0000"` returns `""`, and on empty input returns `null`.
- [ ] **Step 3:** Write `DeviceTrackerTest`:
  - `emits each push`: the fake server replies `OKAY`, then two payloads. Collect `devices` and assert both lists in order.
  - `reconnects after server closes`: the fake closes after the first payload, then accepts again and sends a second payload. Assert `connected` goes true, false, true and the second list arrives. Use a fake `startServer` that records calls and assert it was called at least once. (Review Focus 3.)
  - `fetches info once per device`: assert the fake shell handler is called once for a serial that appears in two consecutive pushes.
- [ ] **Step 4:** Run the tests. Expected: FAIL.
- [ ] **Step 5:** Implement the files. All socket IO runs on `Dispatchers.IO`.
- [ ] **Step 6:** Run `./gradlew :composeApp:jvmTest`. Expected: PASS. With a real device connected, run a scratch `main` or the debugger and check that plugging and unplugging updates the list within about 1 s.
- [ ] **Step 7:** Commit with the message `feat: live adb device tracking`.

### Task 4: Options model, server args, command line

**Files:**
- Create: `options/OptionSpec.kt`, `options/OptionCatalog.kt`, `options/OptionValues.kt`, `options/ServerArgs.kt`, `options/CommandLine.kt`
- Test: `options/OptionCatalogTest.kt`, `options/ServerArgsTest.kt`, `options/CommandLineTest.kt`, `options/OptionValuesTest.kt`

**Interfaces:**
- Produces: `enum class OptionGroup { Video, Audio, Control, Device, Window, Recording, Camera, VirtualDisplay, Connection }`. Display labels come from the mockup chips.
- Produces: `sealed interface OptionType { Flag; IntValue(min: Int?, max: Int?); Text; Choice(values: List<String>); BitRate /* e.g. 8M, 128K */; Size /* WxH */; Crop /* W:H:X:Y */; OptionalValue(inner: OptionType) /* --new-display[=...] */ }`.
- Produces: `enum class Target { Server, Client, Window }`.
- Produces: `data class OptionSpec(val flag: String /* no dashes */, val label: String, val group: OptionGroup, val type: OptionType, val help: String, val target: Target, val toServer: (String?) -> List<Pair<String, String>>)`.
- Produces: `object OptionCatalog { val all: List<OptionSpec>; fun byFlag(flag: String): OptionSpec? }`. The list holds every non-hidden scrcpy 5.0 option. Get the list from `scrcpy --help` and `app/src/cli.c` in the v5.0 source, minus the hidden list in Global Constraints. Get the server key mapping for each option from `app/src/server.c` `execute_server()`, for example `max-size → max_size`, `no-audio → audio=false`, `no-control → control=false`, `video-source=camera → video_source=camera`. Give `--turn-screen-off`, `--show-touches` semantics and similar options the target the source shows. `--turn-screen-off` is `Client`, because it sends `SET_DISPLAY_POWER` after connect.
- Produces: `data class OptionValues(val entries: Map<String, String?> /* flag -> value, null for Flag */)` with `fun validate(): Map<String, String> /* flag -> error message */`, `fun with(flag: String, value: String?)` and `fun without(flag: String)`.
- Produces: `object ServerArgs { fun build(values: OptionValues, scid: Int, tunnelForward: Boolean): List<String> }`. The result always starts with `scid=%08x`, `log_level=info` and `tunnel_forward=<bool>`. It adds `send_frame_meta=true`, `send_device_meta=true` and `send_codec_meta=true` unless overridden, and ends with the mapped option args sorted by catalog order.
- Produces: `object CommandLine { fun build(serial: String?, values: OptionValues): String }`. Output format: `scrcpy -s <serial> --flag --key=value ...` in catalog order. Values containing spaces are single-quoted.

- [ ] **Step 1:** Write the tests:
  - `catalog has no hidden flags` (iterate the Global Constraints list).
  - `catalog flags are unique`.
  - `every Server option maps to at least one key`.
  - `ServerArgs maps QA default`: values `max-size=1920, max-fps=60, no-audio, show-touches, stay-awake` with scid `0x1234abcd` produce a list starting `["scid=1234abcd", "log_level=info", "tunnel_forward=false"]` and containing `max_size=1920`, `max_fps=60`, `audio=false`, `show_touches=true`, `stay_awake=true`.
  - `ServerArgs omits client-only and window options` (`turn-screen-off`, `always-on-top`).
  - `CommandLine matches mockup`: equals `scrcpy -s 39121FDJH004AB --max-size=1920 --video-bit-rate=8M --max-fps=60 --no-audio --stay-awake --turn-screen-off --show-touches`. Assert the order matches `OptionCatalog` order and adjust the expected string to that order.
  - `validate rejects bad values` (Review Focus 5): `max-size=abc` gives an error key `max-size`, `crop=1:2` gives an error key `crop`, `video-bit-rate=8M` is valid, `video-bit-rate=8Q` is invalid.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement, with the catalog transcribed from the v5.0 source.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit with the message `feat: scrcpy 5.0 options catalog and arg builders`.

### Task 5: Presets codec, store, import plan

**Files:**
- Create: `presets/Preset.kt`, `presets/PresetCodec.kt`, `presets/PresetStore.kt`, `presets/ImportPlan.kt`
- Test: `presets/PresetCodecTest.kt`, `presets/ImportPlanTest.kt`, `presets/PresetStoreTest.kt`

**Interfaces:**
- Produces: `@Serializable data class Preset(val id: String /* UUID */, val name: String, val description: String = "", val options: OptionValues)`. The `options` field is serialized as a JSON object whose values are `true` or a string.
- Produces: `object PresetCodec { fun encode(presets: List<Preset>): String /* pretty JSON, spec format */; fun decode(text: String): Result<DecodedFile> }`.
  - `data class DecodedFile(val presets: List<Preset>, val unknownOptions: Map<String /*preset name*/, List<String>>)`.
  - `decode` fails with `PresetImportException`. The message is user-facing, for example "This file isn't a Scrcpy Viewer preset file." or "This file was made by a newer version of Scrcpy Viewer."
  - The exported JSON has no `id`; ids are regenerated on import.
- Produces: `enum class ConflictChoice { Replace, KeepBoth, Skip }` and `data class ImportItem(val preset: Preset, val conflictsWith: Preset?, val choice: ConflictChoice, val include: Boolean)`.
- Produces: `object ImportPlan { fun plan(existing: List<Preset>, incoming: List<Preset>): List<ImportItem>; fun apply(existing: List<Preset>, items: List<ImportItem>): List<Preset> }`. "Keep both" renames the incoming preset to `"<name> (2)"`, `"(3)"` and so on.
- Produces: `class PresetStore(file: Path, scope: CoroutineScope) { val presets: StateFlow<List<Preset>>; val defaultPresetId: StateFlow<String?>; fun upsert(p: Preset); fun delete(id: String); fun duplicate(id: String): Preset; fun setDefault(id: String?) }`. The file is `AppDataDir/presets.json` and stores both the presets and `defaultPresetId`. Writes go to a temp file and are then moved into place atomically.

- [ ] **Step 1:** Write the tests:
  - `round trip`: encoding then decoding gives the same names, descriptions and options.
  - `encode matches spec shape`: the parsed JSON has `format == "scrcpyviewer-presets"`, `version == 1`, `scrcpyVersion == "5.0"`, and `options["no-audio"] == true`.
  - `decode rejects wrong format`, `decode rejects version 2`, and `decode rejects malformed JSON`. Each returns a failure and never throws. (Review Focus 4.)
  - `decode reports unknown options`: `{"legacy-flag": true}` appears in `unknownOptions` and is dropped from options.
  - `plan flags name conflicts`, `apply Replace keeps id of existing`, `apply KeepBoth renames to "(2)"`, `apply Skip leaves existing untouched`.
  - `store persists across instances`.
  - `delete of default clears default`.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit with the message `feat: presets with import and export`.

### Task 6: Per-device working sets and app settings

**Files:**
- Create: `state/DeviceOptionsStore.kt`, `state/AppSettings.kt`
- Test: `state/DeviceOptionsStoreTest.kt`, `state/AppSettingsTest.kt`

**Interfaces:**
- Produces: `data class WorkingSet(val values: OptionValues, val basePresetId: String?)` and `class DeviceOptionsStore(file: Path, presets: PresetStore, scope: CoroutineScope)` with:
  - `fun workingSet(serial: String): StateFlow<WorkingSet>`. The first time a serial is seen, its set is seeded from the default preset, or empty.
  - `fun edit(serial: String, values: OptionValues)`.
  - `fun applyPreset(serial: String, presetId: String)`.
  - `fun isModified(serial: String): Boolean`, which is true when `values != basePreset.options`.
  - `fun saveToBasePreset(serial: String)`.
  - `fun saveAsNewPreset(serial: String, name: String): Preset`, which also rebases the working set onto the new preset.
  - The file is `AppDataDir/devices.json`.
- Produces: `@Serializable data class Settings(val themeMode: ThemeMode = ThemeMode.System, val adbPathOverride: String? = null, val captureDir: String = "<user home>/Movies/ScrcpyViewer" /* resolved at load */)` and `class AppSettings(file: Path) { val settings: StateFlow<Settings>; fun update(transform: (Settings) -> Settings) }`.
  - The default preset lives only in `PresetStore` (stored in `presets.json`), not in `Settings`.

- [ ] **Step 1:** Write the tests:
  - `new serial seeded from default preset`.
  - `edit marks modified`.
  - `saveToBasePreset updates preset and clears modified`.
  - `saveAsNewPreset rebases`.
  - `applyPreset replaces values`.
  - `working sets persist per serial`.
  - `settings persist and default when file missing or corrupt`.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit with the message `feat: per-device option sets and settings`.

### Task 7: Devices screen

**Files:**
- Create: `ui/devices/DevicesViewModel.kt`, `ui/devices/DevicesScreen.kt`, `ui/common/OptionRow.kt`, `ui/common/CopyButton.kt`
- Modify: `ui/App.kt`, `Main.kt` (`AppGraph` wiring)
- Test: `ui/devices/DevicesViewModelTest.kt`

**Interfaces:**
- Consumes: `DeviceTracker.devices`, `DeviceOptionsStore`, `PresetStore`, `OptionCatalog`, `CommandLine.build`, `OptionValues.validate`.
- Produces: `class DevicesViewModel(...)` with:
  - `val state: StateFlow<DevicesUiState>`, where `DevicesUiState(devices, selectedSerial, query, groupFilter: OptionGroup?, visibleOptions: List<OptionSpec>, values, errors, presetLabel: String /* "QA default" or "QA default (modified)" or "No preset" */, command: String, canMirror: Boolean)`.
  - `fun select(serial: String)`, `fun setQuery(q: String)`, `fun setGroup(g: OptionGroup?)`, `fun toggle(flag: String, checked: Boolean)`, `fun setValue(flag: String, value: String)`, `fun save()`, `fun saveAsNew(name: String)`.
  - `canMirror` is true only when a device is selected, its state is `Device`, and `errors` is empty.
- Produces: `@Composable fun CopyButton(text: () -> String, label: String = "Copy")`. It uses `LocalClipboardManager`, or the 1.12 `LocalClipboard` equivalent, and shows a "Copied" snackbar.
- Produces: `MirrorRequests`, a `SharedFlow<String /*serial*/>` on `AppGraph`. A double-click or Mirror emits to it, and Task 12 consumes it.
- The layout follows mockup Concept C:
  - Device column: a header reading "N ready · M need attention", and rows with a state dot, model (or serial), and the serial in monospace.
  - A two-column option grid with group headers, a search field ("Search N options") and group filter chips.
  - A footer with the preset chip and "(modified)" Save / Save as new preset, the command text with `CopyButton`, and the Mirror button.
  - An empty state when there are no devices: "No devices. Connect a device with USB debugging on."
  - When the adb tracker is disconnected: "Starting adb…".

- [ ] **Step 1:** Write the ViewModel tests with fake flows:
  - `first ready device auto-selected`.
  - `selecting unauthorized device disables mirror`.
  - `search filters by label and flag` ("touch" matches `show-touches`).
  - `group filter`.
  - `toggle updates command`.
  - `invalid value disables mirror and shows error` (Review Focus 5).
  - `preset label shows modified`.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement the ViewModel, then the composables. A value field is enabled only when its checkbox is checked. A `Choice` option uses `ExposedDropdownMenuBox`.
- [ ] **Step 4:** Run the tests. Expected: PASS. Then run `./gradlew :composeApp:run` with a device connected and check the list, search, chips, the command footer and that Copy puts the command on the clipboard (`pbpaste`).
- [ ] **Step 5:** Commit with the message `feat: devices screen with options and command preview`.

### Task 8: Presets screen and import dialog

**Files:**
- Create: `ui/presets/PresetsViewModel.kt`, `ui/presets/PresetsScreen.kt`, `ui/presets/ImportDialog.kt`
- Test: `ui/presets/PresetsViewModelTest.kt`

**Interfaces:**
- Consumes: `PresetStore`, `PresetCodec`, `ImportPlan`, `DeviceOptionsStore.applyPreset`, the selected serial from `DevicesViewModel` (shared through `AppGraph.selectedSerial: StateFlow<String?>`), and `CopyButton`.
- Produces: `class PresetsViewModel` with:
  - `selected`, `fun new()`, `fun edit(id, name, description, values)`, `fun duplicate(id)`, `fun delete(id)`, `fun setDefault(id)`, `fun applyToSelectedDevice(id)`.
  - `fun exportJson(ids: List<String>): String`.
  - `fun beginImport(text: String)`, which sets `importState: StateFlow<ImportUiState?>` holding the items, `unknownOptions` and `error: String?`.
  - `fun setChoice(index, ConflictChoice)`, `fun setInclude(index, Boolean)`, `fun confirmImport()` and `fun cancelImport()`.
- File dialogs use `java.awt.FileDialog`, the native file dialog on macOS:
  - Export suggests `<name>.scrcpypresets.json`, or `presets.scrcpypresets.json` for Export all.
  - Import filters on `.json`.
  - "Paste JSON" in the import menu opens a text field dialog whose content goes to `beginImport`.
- Layout follows the Presets mockup: list, detail with option rows (label and flag), the JSON panel with "Copy JSON", and the import dialog with Replace / Keep both / Skip segmented buttons.
- Edit opens the same option grid component as the Devices screen (`OptionRow`), bound to the preset's values.

- [ ] **Step 1:** Write the tests:
  - `export selected produces spec JSON`.
  - `import malformed shows error and changes nothing` (Review Focus 4).
  - `import conflict defaults to Replace`.
  - `confirm applies plan`.
  - `apply to selected device updates working set`.
  - `apply disabled with no selected device`.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS. Then do this manual round trip: export all, delete a preset, import the file and choose Keep both, and check the names.
- [ ] **Step 5:** Commit with the message `feat: presets screen with import and export`.

### Task 9: Settings screen

**Files:**
- Create: `ui/settings/SettingsScreen.kt`
- Modify: `Main.kt`, so the theme reads `AppSettings` and the adb path resolves as `adbPathOverride ?: bundledTools.adbExecutable()`

**Interfaces:**
- Consumes: `AppSettings`, `PresetStore`, `BundledTools`.
- Layout:
  - Theme as a segmented button with System, Light and Dark.
  - adb as a radio choice between "Bundled (version x)" and "Custom path" with a file picker. Show the version from `adb version`.
  - Default preset as a dropdown.
  - Screenshots and recordings folder with a folder picker.
  - Restart the tracker when the adb path changes.

- [ ] **Step 1:** Implement. The settings layer is already tested in Task 6.
- [ ] **Step 2:** Run `./gradlew :composeApp:run`. Switching the theme applies immediately and survives a restart.
- [ ] **Step 3:** Commit with the message `feat: settings screen`.

### Task 10: scrcpy wire protocol

**Files:**
- Create: `scrcpy/StreamProtocol.kt`, `scrcpy/ControlMessage.kt`, `scrcpy/DeviceMessage.kt`
- Test: `scrcpy/ControlMessageTest.kt`, `scrcpy/StreamProtocolTest.kt`, `scrcpy/DeviceMessageTest.kt`

**Interfaces:**
- Produces: `data class DeviceMeta(val name: String)` with `fun readDeviceMeta(input: DataInputStream): DeviceMeta`, which reads the 64-byte NUL-padded name.
- Produces: `data class VideoCodecMeta(val codecId: Int, val width: Int, val height: Int)` and `fun readVideoCodecMeta(input): VideoCodecMeta`.
- Produces: `fun readAudioCodecId(input): Int`. It returns 0 when the device disabled audio and 1 on error, as in scrcpy.
- Produces: `data class MediaPacket(val ptsUs: Long, val isConfig: Boolean, val isKeyFrame: Boolean, val data: ByteArray)` and `fun readPacket(input: DataInputStream): MediaPacket?`, which returns null on EOF. The 12-byte header is an 8-byte pts and flags field (bit 63 config, bit 62 key frame) followed by a 4-byte length.
- Produces: `sealed interface ControlMessage { fun serialize(): ByteArray }` with these subclasses: `InjectKeycode`, `InjectText`, `InjectTouch`, `InjectScroll`, `BackOrScreenOn`, `ExpandNotificationPanel`, `ExpandSettingsPanel`, `CollapsePanels`, `GetClipboard`, `SetClipboard`, `SetDisplayPower`, `RotateDevice`, `UhidCreate`, `UhidInput`, `UhidDestroy`, `OpenHardKeyboardSettings`, `StartApp` and `ResetVideo`.
  - Type ids, field order and fixed-point encodings (pressure as u16 fixed-point, scroll as i16 fixed-point) come exactly from v5.0 `app/src/control_msg.c`.
- Produces: `sealed interface DeviceMessage { Clipboard(text); AckClipboard(sequence: Long); UhidOutput(id: Int, data: ByteArray) }` and `fun readDeviceMessage(input): DeviceMessage?`.

- [ ] **Step 1:** Port every case in v5.0 `app/tests/test_control_msg_serialize.c` into `ControlMessageTest`, with the same inputs and the same expected byte arrays. Port `app/tests/test_device_msg_deserialize.c` into `DeviceMessageTest` the same way.
- [ ] **Step 2:** Write the `StreamProtocolTest` cases:
  - `readPacket decodes config flag`: header `0x80000000_00000000` with length 3 gives `isConfig=true` and `ptsUs` ignored.
  - `readPacket decodes key frame and pts`: `0x40000000_000F4240` gives `isKeyFrame=true` and `ptsUs=1_000_000`.
  - `readPacket returns null on EOF`.
  - `readVideoCodecMeta reads h264 1344x2992`: the codec id is the bytes `"h264"` (`0x68323634`).
- [ ] **Step 3:** Run the tests. Expected: FAIL.
- [ ] **Step 4:** Implement with big-endian `DataInputStream`/`DataOutputStream`.
- [ ] **Step 5:** Run the tests. Expected: PASS.
- [ ] **Step 6:** Commit with the message `feat: scrcpy 5.0 wire protocol`.

### Task 11: Server launcher and session

**Files:**
- Create: `scrcpy/ServerLauncher.kt`, `scrcpy/ScrcpySession.kt`
- Test: `scrcpy/ServerLauncherTest.kt`, `scrcpy/ScrcpySessionTest.kt`

**Interfaces:**
- Consumes: `AdbClient.exec/shell`, `BundledTools.serverJar/serverVersion`, `ServerArgs.build`, and Task 10 readers.
- Produces: `object ScidAllocator { fun next(): Int }`, which returns a random 31-bit value unique within the process. Also `object PortAllocator { fun next(): Int }`, which tries ports from 27183 upward, binds a `ServerSocket` to test each, and skips ports already taken by this process.
- Produces: `class ServerLauncher(adb: AdbClient, tools: BundledTools)` with:
  - `suspend fun start(serial: String, values: OptionValues): ServerConnection`, where `ServerConnection(video: Socket?, audio: Socket?, control: Socket?, deviceMeta: DeviceMeta, process: Process, cleanup: suspend () -> Unit)`.
  - The steps follow v5.0 `app/src/server.c`:
    1. Push the jar to `/data/local/tmp/scrcpy-server.jar`.
    2. Try `reverse localabstract:scrcpy_%08x tcp:<port>` and listen. On failure, or if `force-adb-forward` is set, use `forward tcp:<port> localabstract:scrcpy_%08x` and connect with retry (100 attempts, 100 ms apart).
    3. Run `adb -s <serial> shell CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 5.0 <args>` as a `Process`.
    4. Accept or connect the sockets in the order video, audio, control, skipping any disabled by `video=false`, `audio=false` or `control=false`.
    5. In forward mode, read the dummy byte on the first socket.
    6. Read `DeviceMeta` from the first socket.
  - `cleanup` closes the sockets, destroys the process, and removes the reverse or forward tunnel.
- Produces: `enum class SessionStatus { Connecting, Running, Disconnected, Failed }` and `class ScrcpySession(serial, values, launcher, decoderFactory, audioFactory, scope)` with:
  - `val status: StateFlow<SessionStatus>`, `val error: StateFlow<String?>`, `val frameSize: StateFlow<IntSize?>`, `val fps: StateFlow<Double>`.
  - `val frames: StateFlow<ImageBitmap?>`, set by Task 12.
  - `fun send(msg: ControlMessage)`, which goes through a `Channel` drained by a writer coroutine.
  - `fun startRecording(file: Path)` and `fun stopRecording()`, filled in by Task 14.
  - `suspend fun close()`.
  - When any reader hits EOF or an IOException it sets `Disconnected` and runs cleanup once. No exception escapes `scope` (use a `SupervisorJob` and a `CoroutineExceptionHandler`).
  - After `Running`, if `turn-screen-off` is set, send `SetDisplayPower(false)`.

- [ ] **Step 1:** Write `ServerLauncherTest` with a fake `AdbClient` that records exec calls:
  - `start pushes then reverses then runs server with version 5.0`: assert the call order and that the shell command contains `com.genymobile.scrcpy.Server 5.0 scid=`.
  - `falls back to forward when reverse fails`.
  - `force-adb-forward skips reverse`.
  - `cleanup removes reverse`.
- [ ] **Step 2:** Write `ScrcpySessionTest` using a fake launcher that returns local socket pairs fed by the test:
  - `EOF on video sets Disconnected without throwing` (Review Focus 1).
  - `two sessions get distinct scid and port`. Use the real allocators and assert they differ. (Review Focus 2.)
  - `closing one session leaves another Running`.
  - `send writes serialized bytes to control socket`.
  - `turn-screen-off sends SetDisplayPower(false) after Running`.
- [ ] **Step 3:** Run the tests. Expected: FAIL.
- [ ] **Step 4:** Implement. The decoder and audio factories are interfaces here (`VideoSink`, `AudioSink`), and the tests pass no-op fakes.
- [ ] **Step 5:** Run the tests. Expected: PASS.
- [ ] **Step 6:** Commit with the message `feat: scrcpy server launcher and session lifecycle`.

### Task 12: Video decode and mirror window

**Files:**
- Create: `media/VideoDecoder.kt`, `ui/mirror/InputMapper.kt`, `ui/mirror/MirrorWindow.kt`, `ui/mirror/MirrorController.kt`
- Modify: `Main.kt` (render one `MirrorWindow` per entry in `MirrorController.open`)
- Test: `ui/mirror/InputMapperTest.kt`, `media/VideoDecoderTest.kt`

**Interfaces:**
- Produces: `class VideoDecoder(codecId: Int) : VideoSink`.
  - `fun onPacket(p: MediaPacket)` feeds `avcodec_send_packet` and `avcodec_receive_frame`. A config packet is held and prepended to the next packet, as scrcpy's `packet_merger` does.
  - Frames are converted with `sws_scale` to BGRA and written into a reused `IntArray`, then into a Skia `Bitmap` exposed as `ImageBitmap`.
  - When the frame size changes, for example on rotation, it reallocates and updates `frameSize`.
  - It counts decoded frames per second for `fps`.
- Produces: `class InputMapper(frameSize: () -> IntSize?, viewRect: () -> Rect)`.
  - `fun pointer(event: PointerEvent): List<ControlMessage>` maps mouse down, move and up to `InjectTouch` (pointer id `-1` for the mouse, as scrcpy uses `POINTER_ID_MOUSE`), the right button to `BackOrScreenOn`, and the wheel to `InjectScroll`.
  - `fun key(event: KeyEvent): List<ControlMessage>` maps AWT key codes to Android keycodes through a table ported from v5.0 `app/src/keyboard_sdk.c` / `android/keycodes.h`. Printable text goes through `InjectText` (sdk keyboard mode).
  - Points outside the letterboxed frame are dropped.
- Produces: `class MirrorController(graph)` with `val open: StateFlow<Map<String, ScrcpySession>>`, `fun open(serial)` (focus the window if it is already open), `fun close(serial)` and `fun reconnect(serial)`. It watches `DeviceTracker.devices` and enables Reconnect when a Disconnected session's serial is back in `Device` state.
- `MirrorWindow` follows the mockup:
  - The title is `<model> · <serial>`.
  - The top strip has the state dot, model, Android version and connection type, a "Fit to window" toggle, and Copy command.
  - The `Canvas` draws the frame aspect-fit. With fit off it shows 1:1 inside a scroll container.
  - The side toolbar sends the keycodes Back (4), Home (3), Recents/AppSwitch (187), Volume up (24), Volume down (25) and Power (26), plus Rotate (`RotateDevice`), Screenshot (Task 14) and Record (Task 14).
  - The status line shows `<w> × <h>`, the codec and bit rate, and FPS.
  - On Disconnected, a scrim over the last frame says "Disconnected" with a Reconnect button that is enabled when the device returns (Review Focus 1).
  - Window options are applied: `always-on-top` sets `alwaysOnTop`, `fullscreen` sets the window placement, `window-title`, `window-x/-y/-width/-height`, and `no-window-aspect-ratio-lock`.
  - Clipboard: a `DeviceMessage.Clipboard` sets the system clipboard unless `no-clipboard-autosync` is set. A Cmd/Ctrl+V sends `SetClipboard(paste=true)` with the system text.

- [ ] **Step 1:** Write the `InputMapperTest` cases:
  - `maps view point to device point with letterbox`: a 1344x2992 frame in a 500x500 view, a click at the view centre gives `InjectTouch` at x=672, y=1496, and the screen size field equals 1344x2992.
  - `drops points in letterbox bars`.
  - `right click is BackOrScreenOn`.
  - `wheel maps to scroll sign` (the sign convention follows v5.0 `app/src/input_manager.c`).
  - `rotated frame size updates mapping`: change `frameSize` to 2992x1344 and assert the new mapping.
- [ ] **Step 2:** Write `VideoDecoderTest.decodes sample h264`. Generate the sample with `ffmpeg -f lavfi -i testsrc=size=320x240:rate=10 -frames:v 5 -c:v libx264 -bsf:v h264_mp4toannexb -f h264 sample.h264`, commit it under `src/jvmTest/resources`, and feed it as a single config packet followed by frames, splitting on NAL units. Assert `frameSize == 320x240` and at least one non-null frame.
- [ ] **Step 3:** Run the tests. Expected: FAIL.
- [ ] **Step 4:** Implement the decoder, mapper, controller and window. Hook `DevicesViewModel` mirror requests to `MirrorController.open`.
- [ ] **Step 5:** Run the tests. Expected: PASS.
- [ ] **Step 6:** Manual device check on the Pixel. Pass means all of these hold:
  - The window opens in under 3 s.
  - The status line shows about 60 fps at `max-size=1920`.
  - Tapping, scrolling, typing and the Back, Home and Recents buttons work.
  - Rotating the device resizes the frame correctly.
  - Unplugging shows "Disconnected"; replugging enables Reconnect, which works.
  - Mirror two devices (the emulator plus the phone) at once, then close one; the other keeps running.
- [ ] **Step 7:** Commit with the message `feat: mirror window with video and input`.

### Task 13: Audio playback

**Files:**
- Create: `media/AudioPlayer.kt`
- Test: `media/AudioPlayerTest.kt`

**Interfaces:**
- Produces: `class AudioPlayer(codecId: Int, bufferMs: Int /* from audio-buffer, default 50 */, outputBufferMs: Int /* audio-output-buffer, default 5 */) : AudioSink`.
  - Decode `opus`, `aac`, `flac` or `raw` (codec ids from v5.0 `app/src/demuxer.c`) with avcodec.
  - Resample with swresample to S16LE, 48 kHz, stereo.
  - Write to a `javax.sound.sampled.SourceDataLine` opened with a buffer sized from `outputBufferMs`.
  - A jitter buffer of `bufferMs` holds samples before playback starts. On underrun it plays silence and re-buffers. On overrun it drops the oldest samples.
  - `readAudioCodecId` returning 0 means audio is disabled on the device. The window then shows a "No audio from device" note and does not fail. A return of 1 is logged and treated the same way.
- [ ] **Step 1:** Write the tests:
  - `jitter buffer drops oldest on overrun` and `jitter buffer plays silence on underrun`. Test the buffer as a pure class, `JitterBuffer`, in the same file.
  - `decodes opus sample`. Make a 1 s sample with ffmpeg (`-c:a libopus -f opus`), then extract the raw packets. Simplest is to use `avformat` in the test to read packets and feed them in. Assert that the PCM output length is about 48000 frames ± 5%.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement and wire it into `ScrcpySession` when audio is enabled.
- [ ] **Step 4:** Run the tests. Expected: PASS. Then on the Pixel play a video: sound plays with no audible stutter and A/V drift stays under about 100 ms by ear over 2 minutes.
- [ ] **Step 5:** Commit with the message `feat: audio playback`.

### Task 14: Recording and screenshots

**Files:**
- Create: `media/Recorder.kt`, `media/Screenshot.kt`
- Test: `media/RecorderTest.kt`

**Interfaces:**
- Produces: `class Recorder(file: Path, format: String /* mp4|mkv|m4a|mka|opus|aac|flac|wav, from record-format or extension */, video: VideoCodecMeta?, audioCodecId: Int?, orientation: Int)`.
  - `fun onVideo(p: MediaPacket)`, `fun onAudio(p: MediaPacket)`, `fun close()`.
  - Muxing is stream copy through avformat. Codec parameters come from the first config packet's extradata. pts is rescaled from microseconds to the stream time base. The `orientation` option sets the display matrix side data.
  - Packets wait in the queue until each enabled stream has had its config packet, as v5.0 `app/src/recorder.c` does.
- `ScrcpySession.startRecording(file)` attaches a Recorder to both sinks. `time-limit` stops recording after N seconds. `--record` in the options starts recording when the session starts.
- The Record button toggles recording into `Settings.captureDir/<model>-<yyyyMMdd-HHmmss>.mp4`. The status line shows `● REC mm:ss`.
- `Screenshot.save(bitmap: ImageBitmap, dir: Path, model: String): Path` writes a PNG named `<model>-<yyyyMMdd-HHmmss>.png`.
- [ ] **Step 1:** Write the tests:
  - `records sample h264 to mp4`. Feed Task 12's sample packets with synthetic pts at 100 ms steps, close, then open the file with avformat. Assert one video stream, 320x240, and a duration of about 0.5 s.
  - `waits for config before writing`.
  - `mkv chosen from extension`.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement and wire up.
- [ ] **Step 4:** Run the tests. Expected: PASS. Then on the Pixel record 10 s with audio, and check that the file plays in QuickTime with sound. Take a screenshot and check the PNG opens.
- [ ] **Step 5:** Commit with the message `feat: recording and screenshots`.

### Task 15: GitHub Actions builds

**Files:**
- Create: `.github/workflows/build.yml`

**Interfaces:**
- The workflow runs on `push`, `pull_request` and tags `v*`.
- It uses a matrix: `macos-14` → `packageDmg`, `windows-latest` → `packageMsi`, `ubuntu-latest` → `packageDeb`.
- Steps:
  1. `actions/checkout@v4`.
  2. `actions/setup-java@v4` with temurin 21.
  3. `gradle/actions/setup-gradle@v4`.
  4. `./gradlew :composeApp:downloadBundledTools :composeApp:jvmTest :composeApp:<packageTask>`.
  5. `actions/upload-artifact@v4` uploads `composeApp/build/compose/binaries/main/*/`.
- On a `v*` tag, a `release` job that needs the matrix downloads the artifacts and runs `softprops/action-gh-release@v2` with the files.
- The build script already picks the FFmpeg classifier from the host, so each runner packages only its own natives.
- [ ] **Step 1:** Write the workflow.
- [ ] **Step 2:** Run `./gradlew :composeApp:packageDmg` locally. Expected: a DMG under `composeApp/build/compose/binaries/main/dmg/`. It opens and mirrors a device without scrcpy or adb on `PATH`. Test this with `PATH=/usr/bin:/bin open <app>`, or by checking Settings shows "Bundled".
- [ ] **Step 3:** Validate the workflow syntax with `gh workflow view`, or with `actionlint` if it is installed. If no remote exists yet, note that the CI run is unverified until the repo is pushed.
- [ ] **Step 4:** Commit with the message `ci: build dmg, msi and deb on github actions`.
