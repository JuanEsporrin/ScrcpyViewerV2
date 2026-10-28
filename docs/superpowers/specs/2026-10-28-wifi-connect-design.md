# Connect over Wi-Fi: Design

Mockups (approved 2026-10-28): https://claude.ai/artifact/169Rf8rgq6ewxrV7nQsmnv

## Goal

Connect Android devices to the app over Wi-Fi, the way Android Studio's "Pair devices using Wi-Fi" does, plus a one-click switch for a device that is already on USB.

## Feasibility

The bundled adb (platform-tools 37.0.1) supports everything needed: `adb pair`, `adb mdns services`, `adb connect`, `adb disconnect`, `adb tcpip`. No new native tools. The existing `DeviceTracker` already lists network devices once adb reports them.

## User-facing behaviour

### Entry point

- A tonal button **"Pair over Wi-Fi"** (Wi-Fi icon) is pinned to the bottom of the device column, always visible, including while the list is empty.
- Clicking it opens the pairing dialog on the **QR code** tab.
- Empty-list text becomes: "No devices. Connect a device with USB debugging on, or pair one over Wi-Fi."
- Each device row shows a small tag after the name: **USB** (from `Device.isTcp == false`) or **Wi-Fi** (`isTcp == true`, primary-container colors).

### Pairing dialog: "Pair device over Wi-Fi"

Three tabs: **QR code**, **Pairing code**, **IP address**. Actions: **Cancel** and a primary button (**Pair** on the first two tabs, **Connect** on IP address).

**QR code tab (Android 11+)**
- Shows a QR code (rendered with qrose) encoding `WIFI:T:ADB;S:<name>;P:<password>;;`, where `<name>` is `scrcpy-viewer-` plus 6 random alphanumeric characters and `<password>` is 10 random alphanumeric characters. A new pair is generated each time the dialog opens.
- Steps text: "On the phone, open Developer options › Wireless debugging." / "Tap Pair device with QR code." / "Scan this code. The phone and this computer must be on the same Wi-Fi network."
- Status line with a spinner: "Waiting for a phone to scan…" and the service name on the right.
- The app polls `adb mdns services` every second for a `_adb-tls-pairing._tcp` service whose name equals `<name>`, then runs `adb pair <address> <password>` automatically. The Pair button stays disabled on this tab.

**Pairing code tab (Android 11+)**
- Steps text: "On the phone, open Wireless debugging › Pair device with pairing code." / "Pick the phone below, then type the 6-digit code it shows."
- Lists every `_adb-tls-pairing._tcp` service found by polling `adb mdns services` every second. Each row shows the device serial taken from the service name (`adb-<serial>-<suffix>`; the whole name if it does not match) and "`<address>` · found on network". Empty list text: "Looking for phones on this network…".
- A 6-digit code field (digits only, max 6). **Pair** is enabled when a phone is picked and the code has 6 digits.

**IP address tab (any Android)**
- Text: "For phones already paired, or older phones where you ran `adb tcpip 5555`. To pair a new phone manually, also fill in the pairing port and code."
- Fields: **IP address**, **Port** (default `5555`), and an expandable **"Pair first (Android 11+)"** section with **Pairing port** and **Code**.
- **Connect**: if pairing port and code are both filled, run `adb pair <ip>:<pairing port> <code>` first; then `adb connect <ip>:<port>`.

**After a successful pair (QR or code tab)**: poll `adb mdns services` for up to 10 s for a `_adb-tls-connect._tcp` service with the same IP as the pairing service, then `adb connect` to it (adb may already have auto-connected; "already connected" counts as success). If none appears, the pair still counts as success.

**Success**: the dialog closes and a snackbar says "`<name>` connected over Wi-Fi" (name = serial derived from the service name, or the address), or "Paired with `<name>`" when no connect service appeared.

**Errors stay in the dialog**, under the content, in the error color:
- Wrong code: "Wrong pairing code. Check the code on the phone and try again."
- `adb pair` / `adb connect` other failure: "Couldn't pair: `<adb message>`" / "Couldn't connect: `<adb message>`".
- Invalid IP or port: field-level error "Enter an IP address" / "Enter a port from 1 to 65535".

### Right-click menu on device rows

Right-click (secondary press) on a device row selects it and opens a Material 3 dropdown menu at the pointer.

| Row | Items |
|---|---|
| USB device | **Mirror** (trailing hint "double-click"), **Connect over Wi-Fi** |
| USB device already on Wi-Fi | **Mirror**, **Connected over Wi-Fi** (disabled) |
| USB device while switching | **Mirror**, **Connecting over Wi-Fi…** (disabled) |
| Wi-Fi device | **Mirror**, **Disconnect** |

- **Mirror** does what double-click does. Disabled unless the device state is `device` and its options are valid.
- **Connect over Wi-Fi** is disabled unless the device state is `device`.
- "Already on Wi-Fi" means some `isTcp` device has the same options key (below) as this USB device.

**Connect over Wi-Fi** steps:
1. `adb -s <serial> shell ip -f inet addr show wlan0`, parse the first `inet a.b.c.d/nn`. None: snackbar "`<name>` isn't on Wi-Fi. Connect it to the same network as this computer."
2. `adb -s <serial> tcpip 5555`. Non-zero exit: snackbar "Couldn't switch `<name>` to Wi-Fi: `<adb message>`".
3. `adb connect <ip>:5555`, retried up to 5 times, 1 s apart. Success: snackbar "`<name>` connected at `<ip>:5555`" with action **Mirror** (mirrors `<ip>:5555`). Failure: "Couldn't connect to `<ip>:5555`: `<adb message>`".

**Disconnect** runs `adb disconnect <serial>`; failure gives snackbar "Couldn't disconnect `<name>`: `<adb message>`".

### Options carry over between USB and Wi-Fi

Device options are stored per key. The key is the adb serial, except for a Wi-Fi device whose `ro.serialno` is known: then it is that `ro.serialno`. So a phone switched to Wi-Fi uses its USB options. `DeviceTracker` reads `ro.serialno` along with the model and Android version.

## Out of scope

Reconnecting Wi-Fi devices on launch; Copy serial in the context menu; IPv6 addresses.

## Dependency

`io.github.alexzhirkevich:qrose:1.3.1` (Compose Multiplatform QR painter, `rememberQrCodePainter`).
