# Cohesive window title bar — design

Date: 2026-10-28
Approved mockup: https://claude.ai/artifact/FENkYo8nfGMDVMothbRcMV (main + mirror window, macOS / Windows / Linux, current vs proposed, light / dark)

## Intent

Every Scrcpy Viewer window today uses the operating system's title bar, which is a different
colour from the app and sits on top of it as a separate strip. The goal is a title bar painted
in the app's own Material 3 colours so the window frame and the content read as one surface,
on all three desktop OSes, while the window still behaves exactly like a normal native window.

## Goals

- Title bar background is `MaterialTheme.colorScheme.surfaceContainer` (the nav rail / mirror
  toolbar tone) and follows the app's light / dark theme, including theme changes at runtime.
- Custom bar on by default on macOS and Windows. On Linux it is available but **off by default**
  (user decision, see Linux below).
- Native window behaviour is kept (user requirement: "It needs to drag, snap, and resize"):
  - drag the bar to move;
  - Windows: Aero snap when dragging to screen edges, Snap Layouts flyout on hover over
    maximize, double-click to maximize/restore, resize from every edge and corner, native shadow;
  - Linux (only when the user turns the custom bar on): window-manager move (so GNOME/KDE edge
    tiling works), double-click maximize. No edge resize — see Linux below;
  - macOS: native traffic lights, green-button fullscreen, tiling, rounded corners, shadow,
    double-click per the system "double-click a window's title bar" setting.
- **Main window:** bar shows the title "Scrcpy Viewer"; on Windows also the app icon at the left.
  The nav rail and content start directly under the bar (see mockup).
- **Mirror window:** the existing top strip (`TopStrip` in `ui/mirror/MirrorWindow.kt`: status
  dot, model, details, zoom controls, overflow menu) *becomes* the title bar. No separate bar
  above it; the window gets ~30px shorter for the same content. Window controls sit at the
  strip's edge: traffic lights at the left on macOS, min/max/close at the right on Windows and
  Linux. Empty space in the strip drags the window; its buttons stay clickable.
- Button styling per OS: Windows — the native min / max / close drawn by JBR (Fluent-style,
  close hovers red); only their light/dark variant follows the app theme. Linux —
  GNOME/libadwaita-style round buttons on the right (custom Jewel `TitleBarStyle`). macOS —
  native traffic lights.
- A Settings toggle **"Use system title bar"**. Default: off on macOS and Windows, **on on Linux**.
  When on, windows use the plain OS title bar as today. Takes effect for windows opened after the
  change (an app restart applies it to the main window); the setting row says so.

### Linux

Jewel's `DecoratedWindow` on Linux is an undecorated frame: drag goes through
`JBR.getWindowMove()` (a real window-manager move, so tiling works), but there is no edge or
corner resize and no native shadow. The user chose not to build a custom resize. So on Linux the
system title bar is the default; a user who turns the custom bar on gets it without edge resize,
and the setting row on Linux says so ("Window can't be resized from its edges").

## Non-goals

- No changes to nav rail, mirror toolbar contents, or theme colours.
- The "Finishing recording…" window (`FinishingRecordingWindow` in `Main.kt`) keeps the
  system title bar; it is tiny and non-closable.
- No dialog windows (file choosers etc.) are restyled.
- No left-side button placement on Linux and no reading of the desktop's button-layout setting.

## Anti-goals (would be a failure even if it "works")

- A window that drags by moving itself from app code and so loses OS snapping / tiling.
- On macOS / Windows: resize that only works from some edges, or a dead zone where the bar does neither drag nor click.
- A bar colour that lags or mismatches the content after a theme switch.
- Mirror input (pointer capture, key forwarding in `MirrorWindow`) broken by the new bar.

## Constraints and decided "how"

- **Runtime: JetBrains Runtime (JBR) 21** replaces Temurin 21 for the packaged app. JBR provides
  the custom-title-bar hit-testing that gives real native drag / snap / resize. Decided by the user.
- **Library: Jewel** decorated window, `org.jetbrains.jewel:jewel-int-ui-decorated-window`
  version `0.41.0-262.10968.63` (built against Compose foundation 1.12.0; project uses Compose
  Multiplatform 1.12.2). Use its `DecoratedWindow` + `TitleBar` on all three OSes (one code path,
  macOS included).
- The app's UI stays Material 3. Jewel's theme is only used as far as `DecoratedWindow` /
  `TitleBar` require; title-bar colours are fed from the current `MaterialTheme.colorScheme`, so
  nothing else in the app changes look.
- Packaging: CI (`.github/workflows/build.yml`, currently `actions/setup-java` with
  `distribution: temurin`, `java-version: "21"`) switches the packaging JDK to JBR 21
  (`setup-java` supports `distribution: jetbrains`). Local `createDistributable` / `package*`
  must also run on JBR. Tests may keep running on any JDK 21.
- Graceful fallback: when not running on JBR (tests, `./gradlew run` on a stock JDK), windows
  must still open and work, using the system title bar.
- ProGuard: not used by the shipped installers today (CI runs `packageDmg` / `packageMsi` /
  `packageDeb`). ProGuard lives only in open PR #3. Not part of this work; if PR #3 merges, Jewel /
  JBR API (reflection) will need keep rules then. *(Builder's call.)*

## Left to the builder

- Exact Jewel API wiring (theme wrapper, `TitleBarStyle` / colour construction from the M3 scheme,
  how the mirror strip content is placed inside `TitleBar`).
- How the "Use system title bar" setting is stored (existing `AppSettings` / settings JSON) and
  how a window chooses `Window` vs `DecoratedWindow`.
- How JBR is selected for local builds (Gradle toolchain vendor, `javaHome` in
  `compose.desktop.application`, or documented env var) — pick the least surprising option and
  document it in the README.
- Bar heights: aim for the mockup (main ~38dp, mirror strip stays ~40dp).
- Tests: unit / screenshot tests where the project already has a pattern (e.g. screenshot tests
  like `DevicesScreenshotTest`); window-manager behaviour is verified manually.

## Verification

- `jvmTest` green.
- Manual, on the packaged app per OS (macOS here; Windows and Linux by the user or CI artifacts):
  drag, edge snap / tiling, Snap Layouts (Win 11), double-click maximize, resize from all edges
  and corners (macOS / Windows), light ↔ dark switch, mirror window input still works,
  "Use system title bar" toggle switches bars for new windows; Linux opens with the system bar
  by default.
