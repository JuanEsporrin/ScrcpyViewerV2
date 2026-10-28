# Connect over Wi-Fi Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pair and connect Android devices over Wi-Fi (QR code, pairing code, IP address), switch a USB device to Wi-Fi from a right-click menu, and keep a device's options when it moves from USB to Wi-Fi.

**Architecture:**
- Pure parsers for adb's text output live in `adb/WirelessParsers.kt`.
- `adb/WirelessAdb.kt` is an `open` service over `AdbClient.exec` / `AdbClient.shell`. Tests subclass it as a fake.
- `WifiPairingViewModel` drives the dialog's state machine, and `DevicesViewModel` gains the row actions.
- The UI is Compose Material 3, following `DevicesScreen.kt`.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.12.2 (Material 3), kotlinx-coroutines 1.11.0, `io.github.alexzhirkevich:qrose:1.3.1`, `kotlin("test")`.

**Spec:** `docs/superpowers/specs/2026-10-28-wifi-connect-design.md`. Mockups: https://claude.ai/artifact/169Rf8rgq6ewxrV7nQsmnv

## Global Constraints

- Package root: `com.programmersbox.scrcpyviewer`. Follow existing style: KDoc on public declarations, `kotlin.test` assertions, test names in backticks.
- All adb calls go through `AdbClient.exec(...)` (binary) or `AdbClient.shell(serial, cmd)`. `adb connect` exits 0 on failure, so decide success from stdout, never from the exit code.
- mDNS poll interval: 1000 ms. `adb pair` timeout: 15 000 ms (use `execWithTimeout`). After pairing, the wait for the connect service is at most 10 000 ms. USB-to-Wi-Fi uses port `5555` and makes at most 5 connect attempts, 1000 ms apart.
- QR payload: `WIFI:T:ADB;S:<name>;P:<password>;;`. `<name>` = `scrcpy-viewer-` + 6 random `[A-Za-z0-9]`. `<password>` = 10 random `[A-Za-z0-9]`.
- User-visible copy is exactly as written in the spec (button, tab, menu, snackbar and error strings).
- Tests must not touch a real adb or device. Screenshot tests stay behind `-Pscreenshots` (name ends in `ScreenshotTest`).
- Run tests with `./gradlew :composeApp:jvmTest` from the worktree root.

## Review Focus

1. **Dialog closed while polling or pairing.** Cancelling the dialog cancels mDNS polling and any running `adb pair`, and leaves no coroutine running. Test in Task 4 (`close cancels polling`).
2. **mDNS output with no services, a trailing-dot type, or extra whitespace.** The parser returns the right services and ignores the header and blank lines. Test in Task 1.
3. **Phone has no Wi-Fi IP, e.g. `wlan0` missing or down.** No `tcpip` is run, and the user gets the "isn't on Wi-Fi" snackbar. Test in Task 3.
4. **Connect over Wi-Fi clicked twice quickly.** The second click is ignored while the first is running, and the menu item shows "Connecting over Wi-Fi…". Test in Task 3.
5. **`ro.serialno` not yet fetched for a new Wi-Fi device.** The device uses its adb serial as the options key until info arrives, then switches to the shared key without crashing or losing edits made under the USB key. Test in Task 2.

---

## File Structure

```
gradle/libs.versions.toml                                  # + qrose
composeApp/build.gradle.kts                                # + implementation(libs.qrose)
composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/
  adb/Device.kt                 # DeviceInfo.serialNo, Device.optionsKey()
  adb/DeviceTracker.kt          # INFO_COMMAND reads ro.serialno
  adb/WirelessParsers.kt        # NEW: pure parsers + QR payload
  adb/WirelessAdb.kt            # NEW: open service over AdbClient
  ui/devices/DevicesViewModel.kt   # options key mapping, Wi-Fi actions, notices
  ui/devices/DevicesScreen.kt      # tags, bottom button, context menu, snackbar
  ui/devices/WifiPairingViewModel.kt  # NEW
  ui/devices/WifiPairingDialog.kt     # NEW
  Main.kt                       # wire WirelessAdb, options key into MirrorController + PresetsViewModel
composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/
  adb/WirelessParsersTest.kt  adb/WirelessAdbTest.kt  adb/FakeWirelessAdb.kt
  ui/devices/DevicesViewModelTest.kt  ui/devices/WifiPairingViewModelTest.kt
  ui/DevicesScreenshotTest.kt   # + context menu and dialog shots
```

---

### Task 1: Wireless output parsers

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/adb/WirelessParsers.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/adb/WirelessParsersTest.kt`

**Interfaces:**
- Produces:
  - `enum class MdnsType { Pairing, Connect }`
  - `data class MdnsService(val name: String, val type: MdnsType, val address: String)`. Also `val host: String` (the address before the last `:`).
  - `object WirelessParsers` with:
    - `fun mdnsServices(stdout: String): List<MdnsService>`
    - `fun pairOutcome(result: ProcessResult): AdbOutcome`
    - `fun connectOutcome(stdout: String): AdbOutcome`
    - `fun wlanAddress(ipAddrOutput: String): String?`
    - `fun serialFromServiceName(name: String): String`
    - `fun qrPayload(name: String, password: String): String`
    - `fun randomAlnum(length: Int, random: kotlin.random.Random = kotlin.random.Random.Default): String`
  - `sealed interface AdbOutcome { data object Ok; data object WrongCode; data class Failed(val message: String) }`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `mdns services parses pairing and connect lines`() {
    val out = "List of discovered mdns services\n" +
        "adb-3A111FDJG0029S-AbCdEf\t_adb-tls-connect._tcp\t192.168.1.31:37215\n" +
        "scrcpy-viewer-7QK2ab  _adb-tls-pairing._tcp.   192.168.1.31:41877\n\n"
    assertEquals(listOf(
        MdnsService("adb-3A111FDJG0029S-AbCdEf", MdnsType.Connect, "192.168.1.31:37215"),
        MdnsService("scrcpy-viewer-7QK2ab", MdnsType.Pairing, "192.168.1.31:41877"),
    ), WirelessParsers.mdnsServices(out))
    assertEquals("192.168.1.31", WirelessParsers.mdnsServices(out)[0].host)
}
@Test fun `mdns services empty and unknown types`() {
    assertEquals(emptyList(), WirelessParsers.mdnsServices("List of discovered mdns services\n\n"))
    assertEquals(emptyList(), WirelessParsers.mdnsServices("x\t_adb._tcp\t1.2.3.4:5\n"))
}
@Test fun `pair outcome`() {
    assertEquals(AdbOutcome.Ok, WirelessParsers.pairOutcome(ProcessResult(0, "Successfully paired to 192.168.1.31:41877 [guid=adb-X-Y]\n", "")))
    assertEquals(AdbOutcome.WrongCode, WirelessParsers.pairOutcome(ProcessResult(1, "Failed: Wrong password or connection was dropped.\n", "")))
    assertEquals(AdbOutcome.Failed("protocol fault (couldn't read status message): Undefined error: 0"),
        WirelessParsers.pairOutcome(ProcessResult(1, "", "error: protocol fault (couldn't read status message): Undefined error: 0\n")))
}
@Test fun `connect outcome ignores exit code`() {
    assertEquals(AdbOutcome.Ok, WirelessParsers.connectOutcome("connected to 192.168.1.31:5555\n"))
    assertEquals(AdbOutcome.Ok, WirelessParsers.connectOutcome("already connected to 192.168.1.31:5555\n"))
    assertEquals(AdbOutcome.Failed("Connection refused"), WirelessParsers.connectOutcome("failed to connect to '127.0.0.1:1': Connection refused\n"))
    assertEquals(AdbOutcome.Failed("No route to host"), WirelessParsers.connectOutcome("cannot connect to 10.0.0.9:5555: No route to host\n"))
}
@Test fun `wlan address`() {
    val out = "30: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500\n    inet 192.168.1.31/24 brd 192.168.1.255 scope global wlan0\n"
    assertEquals("192.168.1.31", WirelessParsers.wlanAddress(out))
    assertNull(WirelessParsers.wlanAddress("Device \"wlan0\" does not exist.\n"))
    assertNull(WirelessParsers.wlanAddress(""))
}
@Test fun `serial from service name`() {
    assertEquals("3A111FDJG0029S", WirelessParsers.serialFromServiceName("adb-3A111FDJG0029S-AbCdEf"))
    assertEquals("scrcpy-viewer-7QK2ab", WirelessParsers.serialFromServiceName("scrcpy-viewer-7QK2ab"))
}
@Test fun `qr payload and random`() {
    assertEquals("WIFI:T:ADB;S:scrcpy-viewer-abc123;P:9Xc4mT28aa;;", WirelessParsers.qrPayload("scrcpy-viewer-abc123", "9Xc4mT28aa"))
    val r = WirelessParsers.randomAlnum(10)
    assertEquals(10, r.length); assertTrue(r.all { it.isLetterOrDigit() && it.code < 128 })
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:jvmTest --tests '*WirelessParsersTest'`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement `WirelessParsers.kt`**

- mDNS: split each line on whitespace into exactly 3 tokens. Map the type with `trimEnd('.')`: `_adb-tls-pairing._tcp` gives Pairing, `_adb-tls-connect._tcp` gives Connect. Drop any other line.
- `pairOutcome`: stdout contains `Successfully paired` gives Ok. stdout+stderr contains `Wrong password` gives WrongCode. Otherwise Failed with the first non-blank line from stderr, then stdout, minus a leading `error: ` or `Failed: `.
- `connectOutcome`: a line starting with `connected to` or `already connected to` gives Ok. Otherwise Failed with the text after the last `: ` of the first non-blank line, or the whole line if there is none.
- `wlanAddress`: the first match of `inet (\d+\.\d+\.\d+\.\d+)/`.
- `serialFromServiceName`: the regex `^adb-(.+)-[^-]+$` gives group 1, otherwise the name.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:jvmTest --tests '*WirelessParsersTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/adb/WirelessParsers.kt composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/adb/WirelessParsersTest.kt
git commit -m "feat(adb): parsers for mdns, pair, connect and wlan output"
```

---

### Task 2: WirelessAdb service and the options key

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/adb/WirelessAdb.kt`
- Modify: `adb/Device.kt`, `adb/DeviceTracker.kt` (`INFO_COMMAND`, `parseInfo`)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/adb/WirelessAdbTest.kt`, `adb/DeviceTrackerTest.kt` (update expectations for the 4th info line)
- Create (test helper): `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/adb/FakeWirelessAdb.kt`

**Interfaces:**
- Consumes: Task 1's `WirelessParsers`, `MdnsService`, `AdbOutcome`.
- Produces:
  - `data class DeviceInfo(val model: String, val androidVersion: String, val sdk: Int, val serialNo: String? = null)`
  - `fun Device.optionsKey(): String`. For `isTcp` devices it returns `info?.serialNo` when that is non-blank. Otherwise it returns `serial`.
  - `open class WirelessAdb(private val client: AdbClient)` with these `open suspend` functions:
    - `mdnsServices(): List<MdnsService>`. Any failure returns an empty list.
    - `pair(address: String, code: String): AdbOutcome`. Uses `execWithTimeout(15_000, "pair", address, code)`; a timeout gives `Failed("timed out")`.
    - `connect(address: String): AdbOutcome`
    - `disconnect(serial: String): AdbOutcome`. Exit 0 gives Ok, otherwise Failed with the trimmed output.
    - `wifiAddress(serial: String): String?`. Runs `shell(serial, "ip -f inet addr show wlan0")`; any exception gives null.
    - `switchToWifi(serial: String): SwitchResult`
  - `sealed interface SwitchResult { data class Connected(val address: String); data object NotOnWifi; data class TcpipFailed(val message: String); data class ConnectFailed(val address: String, val message: String) }`
  - `FakeWirelessAdb`: a test helper subclass with mutable fields `services`, `pairResult`, `connectResult`, `switchResult`, and recorded `calls: MutableList<String>`. Its `AdbClient` throws if used.

- [ ] **Step 1: Write the failing tests**

Use a fake `AdbClient` subclass that overrides `exec` and `shell` and records args. `AdbClient` is `open`; pass `adbPath = { error("unused") }`.

```kotlin
@Test fun `switchToWifi runs tcpip then connects`()  // shell -> "inet 192.168.1.31/24", exec tcpip -> exit 0, connect -> "connected to 192.168.1.31:5555"
    // assert result == SwitchResult.Connected("192.168.1.31:5555")
    // assert execCalls == listOf(listOf("-s","S1","tcpip","5555"), listOf("connect","192.168.1.31:5555"))
@Test fun `switchToWifi with no wlan ip runs nothing`()   // shell -> "" ; assert NotOnWifi and execCalls empty
@Test fun `switchToWifi retries connect up to 5 times`()   // connect always "failed to connect to '192.168.1.31:5555': Connection refused"
    // assert ConnectFailed("192.168.1.31:5555", "Connection refused") and 5 connect calls (use retryDelayMs = 0 constructor param)
@Test fun `switchToWifi tcpip failure`()                    // tcpip exit 1, stderr "error: closed" -> TcpipFailed("error: closed")
@Test fun `mdnsServices swallows failures`()               // exec throws IOException -> emptyList()
```

Give `WirelessAdb` a constructor parameter `retryDelayMs: Long = 1000` for the tests.

In `DeviceTrackerTest`: the stub shell output for info gains a 4th line (`3A111FDJG0029S`). Assert `DeviceInfo("Pixel 8 Pro", "15", 35, "3A111FDJG0029S")`. Also assert that 3-line output still parses with `serialNo == null`.

Add an options-key test in `WirelessAdbTest` (or a small `DeviceTest`):

```kotlin
@Test fun `options key prefers ro serialno for tcp devices only`() {
    val usb = Device("ABC", DeviceState.Device, "device", null, null, 1, false, DeviceInfo("P", "15", 35, "XYZ"))
    val tcp = Device("192.168.1.31:5555", DeviceState.Device, "device", null, null, 2, true, DeviceInfo("P", "15", 35, "ABC"))
    val tcpNoInfo = tcp.copy(info = null)
    assertEquals("ABC", usb.optionsKey()); assertEquals("ABC", tcp.optionsKey()); assertEquals("192.168.1.31:5555", tcpNoInfo.optionsKey())
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:jvmTest --tests '*WirelessAdbTest' --tests '*DeviceTrackerTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

- `INFO_COMMAND` appends `; getprop ro.serialno`.
- `parseInfo` reads an optional 4th line, blank meaning null.
- `WirelessAdb` uses only `client.exec` / `execWithTimeout` / `shell`.

- [ ] **Step 4: Run all tests**

Run: `./gradlew :composeApp:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/adb composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/adb
git commit -m "feat(adb): WirelessAdb service and ro.serialno options key"
```

---

### Task 3: DevicesViewModel: options key mapping and Wi-Fi row actions

**Files:**
- Modify: `ui/devices/DevicesViewModel.kt`, `Main.kt`
- Test: `ui/devices/DevicesViewModelTest.kt`

**Interfaces:**
- Consumes: `Device.optionsKey()`, `WirelessAdb`, `SwitchResult`, `AdbOutcome`, `FakeWirelessAdb`.
- Produces:
  - Constructor gains a last parameter: `private val wireless: WirelessAdb`.
  - `data class WifiNotice(val text: String, val mirrorSerial: String? = null)`
  - `val notices: SharedFlow<WifiNotice>` (a `MutableSharedFlow` with `extraBufferCapacity = 8`)
  - `val selectedOptionsKey: StateFlow<String?>`
  - `fun connectOverWifi(serial: String)`, `fun disconnect(serial: String)`, `fun notify(notice: WifiNotice)`. The dialog uses `notify` for its success message.
  - `DevicesUiState` gains `val switching: Set<String> = emptySet()` (serials currently switching).
  - Top-level functions `fun isOnWifi(device: Device, devices: List<Device>): Boolean` and `fun canMirrorDevice(state: DevicesUiState, serial: String): Boolean`. The second checks the device state and validates the options under that device's key.

- [ ] **Step 1: Write the failing tests** (add to `DevicesViewModelTest`; the `vm()` helper passes a `FakeWirelessAdb`)

```kotlin
@Test fun `wifi device shares options with its usb twin`()
    // devices = [usb "ABC"]; vm.toggle("no-audio", true); then devices = [usb, tcp "192.168.1.31:5555" with info.serialNo "ABC"]
    // vm.select("192.168.1.31:5555"); assert "no-audio" in vm.state.value.values.entries; assert vm.selectedOptionsKey.value == "ABC"
@Test fun `wifi device without info uses its own key then switches`()
    // tcp without info -> selectedOptionsKey == "192.168.1.31:5555"; set info.serialNo "ABC" -> key == "ABC", values from ABC
@Test fun `connectOverWifi success emits notice with mirror action`()
    // fake.switchResult = Connected("192.168.1.31:5555"); device R1 model "Pixel 8 Pro" via info
    // vm.connectOverWifi("R1"); assert notice == WifiNotice("Pixel 8 Pro connected at 192.168.1.31:5555", "192.168.1.31:5555")
@Test fun `connectOverWifi not on wifi`()
    // NotOnWifi -> "Pixel 8 Pro isn't on Wi-Fi. Connect it to the same network as this computer."
@Test fun `connectOverWifi ignores a second click while running`()
    // fake suspends switchToWifi on a CompletableDeferred; call twice; assert fake.calls.count{it.startsWith("switch")} == 1
    // and "R1" in vm.state.value.switching; complete -> switching empty
@Test fun `disconnect failure emits notice`()
    // fake.disconnectResult = Failed("no such device") -> "Couldn't disconnect Pixel 8 Pro: no such device"
@Test fun `isOnWifi`()  // usb ABC + tcp with serialNo ABC -> true; tcp row itself -> false
```

Other notice copy, exactly:
- TcpipFailed: "Couldn't switch `<name>` to Wi-Fi: `<message>`"
- ConnectFailed: "Couldn't connect to `<address>`: `<message>`"

`<name>` is `deviceTitle(device)`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:jvmTest --tests '*DevicesViewModelTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

- Derive `selectedOptionsKey` from `selection`: the selected device's `optionsKey()`.
- Make `workingValues`, `presetLabel`, `toggle`, `setValue`, `applyPreset`, `save`, `saveAsNew`, `openDevice`'s validation and `canMirror` use the key. Device lookups keep using the serial.
- Keep `switching` in a `MutableStateFlow<Set<String>>` combined into `state`. Run actions with `scope.launch`.
- In `Main.kt`:
  - Construct `WirelessAdb(adbClient)` and pass it to the view model.
  - Make `MirrorController.optionsFor` look up the key with `deviceTracker.devices.value.firstOrNull { it.serial == serial }?.optionsKey() ?: serial`.
  - Pass `devicesViewModel.selectedOptionsKey` to `PresetsViewModel`. Its `deviceLabel` finds the device whose `optionsKey() == key`.

- [ ] **Step 4: Run all tests**

Run: `./gradlew :composeApp:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat(devices): Wi-Fi row actions and shared options key"
```

---

### Task 4: WifiPairingViewModel

**Files:**
- Create: `ui/devices/WifiPairingViewModel.kt`
- Test: `ui/devices/WifiPairingViewModelTest.kt`

**Interfaces:**
- Consumes: `WirelessAdb`, `MdnsService`, `AdbOutcome`, `WirelessParsers`.
- Produces:
  - `enum class PairingTab { Qr, Code, Ip }`
  - `data class WifiPairingState`, with these fields:
    - `tab`
    - `qrName`, `qrPassword`, `qrPayload`
    - `pairingServices: List<MdnsService>`, `chosen: String?` (the service address), `code: String`
    - `ip: String`, `port: String = "5555"`, `pairPort: String`, `pairCode: String`, `ipError: String?`, `portError: String?`
    - `busy: Boolean`, `error: String?`
    - `primaryLabel: String` ("Pair" / "Connect"), `primaryEnabled: Boolean`
  - `class WifiPairingViewModel(wireless: WirelessAdb, scope: CoroutineScope, onDone: (WifiNotice) -> Unit, random: Random = Random.Default, pollMs: Long = 1000, connectWaitMs: Long = 10_000)` with:
    - `val state: StateFlow<WifiPairingState>`
    - `fun selectTab(tab)`, `fun choose(address)`, `fun setCode(s)` (digits only, max 6)
    - `fun setIp(s)`, `fun setPort(s)`, `fun setPairPort(s)`, `fun setPairCode(s)`
    - `fun submit()`, `fun close()` (cancels all jobs)
- Behaviour: polling starts in `init` and runs every `pollMs` while not closed. The QR tab auto-pairs when a Pairing service with `name == qrName` appears; each service name is tried at most once per error. The code list is refreshed from the same poll. After a successful pair, wait for a Connect service with the same `host`, then `connect` it, then call `onDone`. Copy is from the spec.

- [ ] **Step 1: Write the failing tests** (use `FakeWirelessAdb`, `pollMs = 10`, `connectWaitMs = 100`, `runBlocking` with a real scope cancelled at the end, and a seeded `Random(1)`)

```kotlin
@Test fun `qr payload uses generated name and password`()  // qrName starts "scrcpy-viewer-", length 20; qrPassword length 10; payload == WirelessParsers.qrPayload(...)
@Test fun `qr auto pairs and connects`()
    // fake.services = [Pairing(name = state.qrName, "192.168.1.31:41877"), Connect("adb-ABC-x", "192.168.1.31:37215")]
    // await onDone; assert fake.calls contains "pair 192.168.1.31:41877 <password>" and "connect 192.168.1.31:37215"
    // notice text == "ABC connected over Wi-Fi"
@Test fun `paired without connect service`()   // no Connect service -> notice "Paired with ABC"  (pairing service name "adb-ABC-y" on the Code tab)
@Test fun `wrong code shows error and stays open`()  // pairResult = WrongCode -> state.error == "Wrong pairing code. Check the code on the phone and try again."; onDone not called
@Test fun `code tab enables pair only with choice and six digits`()  // setCode("12a34567") -> code == "123456"
@Test fun `ip tab validates`()   // ip "999.1.1.1" -> ipError "Enter an IP address"; port "0" -> portError "Enter a port from 1 to 65535"; no adb calls
@Test fun `ip tab pairs first when pairing fields filled`()  // calls == ["pair 192.168.1.31:41877 482913", "connect 192.168.1.31:5555"]; notice "192.168.1.31:5555 connected over Wi-Fi"
@Test fun `close cancels polling`()  // close(); record calls size; delay 50; size unchanged
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:jvmTest --tests '*WifiPairingViewModelTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `WifiPairingViewModel`**

- Use a child `Job` of `scope`, so `close()` cancels everything.
- Validate the IP as 4 dot-separated integers in 0..255.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:jvmTest --tests '*WifiPairingViewModelTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/devices/WifiPairingViewModel.kt composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/devices/WifiPairingViewModelTest.kt
git commit -m "feat(devices): Wi-Fi pairing state machine"
```

---

### Task 5: Pairing dialog, device list UI, and right-click menu

**Files:**
- Modify: `gradle/libs.versions.toml` (`qrose = "1.3.1"`, library `qrose = { module = "io.github.alexzhirkevich:qrose", version.ref = "qrose" }`), `composeApp/build.gradle.kts` (`implementation(libs.qrose)` in `jvmMain`)
- Create: `ui/devices/WifiPairingDialog.kt`
- Modify: `ui/devices/DevicesScreen.kt`
- Test: `ui/DevicesScreenshotTest.kt`

**Interfaces:**
- Consumes: Task 3 (`notices`, `connectOverWifi`, `disconnect`, `notify`, `switching`, `isOnWifi`, `canMirrorDevice`) and Task 4 (`WifiPairingViewModel`, `WifiPairingState`).
- Produces:
  - `@Composable fun WifiPairingDialog(state: WifiPairingState, actions: WifiPairingActions, onDismiss: () -> Unit)`. It is stateless, for screenshots. `WifiPairingActions` is a data class of the VM's setter lambdas.
  - `DevicesContent` gains the parameters `onPairWifi: () -> Unit = {}`, `onConnectWifi: (String) -> Unit = {}`, `onDisconnect: (String) -> Unit = {}`, and `contextMenuSerial: String? = null`. The last one starts with that row's menu open, for screenshots.
  - `DevicesScreen` gains a `wireless: WirelessAdb` parameter, owns the dialog's open state, creates the VM with `remember { WifiPairingViewModel(wireless, scope, onDone = { viewModel.notify(it); open = false }) }`, and calls `close()` in a `DisposableEffect`. It collects `viewModel.notices` into `LocalSnackbarHostState.current`. When `mirrorSerial != null` it uses `actionLabel = "Mirror"`, and on `SnackbarResult.ActionPerformed` it calls `viewModel.openDevice(it)`.
  - `App.kt`: `DevicesScreen(graph.devicesViewModel, graph.wirelessAdb, panes = devicesPanes)`. `AppGraph` exposes `val wirelessAdb`.

- [ ] **Step 1: Add the qrose dependency and confirm it resolves**

Run: `./gradlew :composeApp:compileKotlinJvm`
Expected: BUILD SUCCESSFUL. Check the qrose 1.3.1 API from the downloaded sources or jar: the painter is `rememberQrCodePainter(data: String)` in package `io.github.alexzhirkevich.qrose`.

- [ ] **Step 2: Build the dialog**

- Use an M3 `AlertDialog` titled "Pair device over Wi-Fi", with a `PrimaryTabRow` holding three tabs.
- Show each tab's content from the spec.
- The QR code is an `Image(rememberQrCodePainter(state.qrPayload))`, 176 dp, drawn on a white rounded box so it scans in dark theme.
- Show `state.error` in the error color.
- While `busy`, show a 16 dp `CircularProgressIndicator` beside the primary button.

- [ ] **Step 3: Update the device column and rows in `DevicesScreen.kt`**

- Pin a `FilledTonalButton` with `Icons.Filled.Wifi` and the label "Pair over Wi-Fi" to the bottom of the column, outside the scroll.
- Change the empty-list text to the spec copy.
- Add the USB / Wi-Fi tag after the row title, using `Icons.Filled.Usb` / `Icons.Filled.Wifi` at 12 dp.
- Context menu:
  - In `DeviceRow`, add `Modifier.onPointerEvent(PointerEventType.Press)`. When `event.buttons.isSecondaryPressed`, select the row and open a `DropdownMenu` at the pointer position, using `DpOffset` from the pointer position converted to dp.
  - Items and their disabled states follow the spec table: Mirror with `Icons.Filled.PlayArrow` and trailing text "double-click"; Wi-Fi items with `Icons.Filled.Wifi`; Disconnect with `Icons.Filled.LinkOff`.

- [ ] **Step 4: Add screenshot tests**

In `DevicesScreenshotTest`, add:
- `devices with context menu`: `contextMenuSerial` set to the USB device.
- `wifi pairing dialog qr`, `wifi pairing dialog code`, `wifi pairing dialog ip`: render `WifiPairingDialog` with sample states.

Each must render in light and dark, and its PNG must exist under `build/screenshots`.

Run: `./gradlew :composeApp:jvmTest -Pscreenshots --tests '*DevicesScreenshotTest'`
Expected: PASS. Open the PNGs and compare them with the mockup's Proposals 1–3.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew :composeApp:jvmTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A composeApp gradle
git commit -m "feat(devices): Pair over Wi-Fi dialog, row tags and right-click menu"
```

---

### Task 6: Manual check on real devices

- [ ] Run the app: `./gradlew :composeApp:run`.
- [ ] Right-click a USB phone and choose **Connect over Wi-Fi**. Check that a Wi-Fi row appears, the snackbar's **Mirror** button works, and the options match the USB row.
- [ ] On an Android 11+ phone, pair using the QR tab, then the Pairing code tab, then the IP address tab.
- [ ] Right-click the Wi-Fi row and choose **Disconnect**. Check that the row disappears.
- [ ] Record results in the final summary. Mark any step that could not be run as not verified.
