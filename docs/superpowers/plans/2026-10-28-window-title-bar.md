# Window Title Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Paint the main and mirror windows' title bars in the app's Material 3 `surfaceContainer` colour with native drag / snap / resize, via JetBrains Runtime + Jewel `DecoratedWindow`, with a "Use system title bar" escape hatch (default on for Linux).

**Architecture:** A pure `windowChrome(...)` decision picks `Custom` or `System` per window from the setting, the OS and whether the runtime can load Jewel (JBR 25+). One `AppWindow` composable opens either a Jewel `DecoratedWindow` + `TitleBar` (colours fed from the M3 scheme) or a plain Compose `Window` with the title-bar slot drawn at the top of the content. The mirror window passes its existing top strip as that slot. Run and packaging use a JBR 25 launcher (Jewel 0.41 is Java 25 bytecode) resolved through Gradle toolchains (foojay auto-provisioning), only when a run or packaging task is requested, so CI and local builds need no manual JDK setup.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.12.2 (desktop), Material 3, Jewel `org.jetbrains.jewel:jewel-int-ui-decorated-window:0.41.0-262.10968.63`, JetBrains Runtime 25 (runtime only; compiled with toolchain 21), kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-28-window-title-bar-design.md`

## Global Constraints

- Jewel version exactly `0.41.0-262.10968.63`; no other new runtime dependency.
- Title bar background `MaterialTheme.colorScheme.surfaceContainer`, content colour `onSurface`; follows ThemeMode changes live.
- "Use system title bar" default: off on macOS and Windows, on on Linux. Applies to windows opened after the change.
- Linux setting row hint, verbatim: "Window can't be resized from its edges." General hint, verbatim: "Applies to windows opened after the change. Restart to apply it to this window."
- `FinishingRecordingWindow` keeps the plain `Window`.
- Runtime can't load Jewel (tests, stock JDK, JBR 21) ⇒ always system title bar; never call `DecoratedWindow` (it throws without JBR).
- No changes to nav rail contents, mirror toolbar contents, or theme colours.
- Run / packaging JDK: JBR 25, resolved only for run and packaging tasks. Compilation and tests keep the existing `jvmToolchain(21)`.

## Review Focus

1. Mirror input with the strip in the title bar: clicks on zoom / overflow buttons must work and not start a window drag; stage pointer mapping (`onStageBounds` via `boundsInWindow`) must stay correct now that content starts at window y=0. → Task 4 screenshot + manual check.
2. Mirror auto-fit sizing (`MirrorWindow.kt:588-608`, chrome measured from window minus stage) must converge with the new bar height, not grow every frame. → Task 4 manual step.
3. Theme switch while windows are open: bar recolours immediately, including inactive-window colour. → Task 3 test on `titleBarColors`.
4. Settings file written by an older version (no `useSystemTitleBar` key) must load with the OS default, and a corrupt value must fall back to defaults. → Task 2 test.
5. Running on a runtime that can't load Jewel (stock JDK, JBR 21, or tests) must open windows with the system bar instead of crashing. → Task 2 test on `windowChrome(supported = false)` and `jewelUsable`.

---

### Task 1: JBR packaging runtime + Jewel dependency

**Files:**
- Modify: `settings.gradle.kts` (add foojay toolchain resolver plugin)
- Modify: `gradle/libs.versions.toml` (version `jewel = "0.41.0-262.10968.63"`, library `jewel-decorated-window`)
- Modify: `composeApp/build.gradle.kts` (dependency; `compose.desktop.application.javaHome`; `modules(...)` if needed)

**Interfaces:**
- Produces: `libs.jewel.decorated.window` on the `jvmMain` classpath; packaged app and `run` task launched on JBR 25.

- [ ] **Step 1: Add the foojay resolver** — `plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }` in `settings.gradle.kts`.
- [ ] **Step 2: Add Jewel** to the catalog and `jvmMain.dependencies { implementation(libs.jewel.decorated.window) }`.
- [ ] **Step 3: Point packaging at JBR** — in `compose.desktop { application { ... } }` set `javaHome` to `javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)); vendor.set(JvmVendorSpec.JETBRAINS) }.get().metadata.installationPath.asFile.absolutePath`, only when a requested task is a run or packaging task, with a comment saying why (custom title bar needs JBR; Jewel 0.41 is Java 25 bytecode; tests and IDE sync shouldn't download it). This doubles as the local-build documentation (the repo has no README).
- [ ] **Step 4: Check runtime modules** — run `./gradlew :composeApp:suggestRuntimeModules`; add any modules it lists that `nativeDistributions` lacks via `modules(...)`.
- [ ] **Step 5: Verify**
  Run: `./gradlew :composeApp:createDistributable && grep JAVA_VERSION composeApp/build/compose/binaries/main/app/ScrcpyViewer.app/Contents/runtime/Contents/Home/release`
  Expected: build succeeds; `JAVA_VERSION="25.…"` (the jlinked image has no IMPLEMENTOR line).
  Run: `./gradlew :composeApp:jvmTest` → PASS (unchanged).
- [ ] **Step 6: Commit** — `build: package on JetBrains Runtime and add Jewel decorated window`

CI (`.github/workflows/build.yml`) needs no change: the toolchain is auto-provisioned on each runner. Confirm on the PR's CI run.

---

### Task 2: Setting + chrome decision

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/state/AppSettings.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/window/WindowChrome.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/state/AppSettingsTest.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/window/WindowChromeTest.kt`

**Interfaces:**
- Produces:
  - `Settings.useSystemTitleBar: Boolean? = null` (null = OS default)
  - `enum class WindowChrome { Custom, System }`
  - `fun defaultUseSystemTitleBar(isLinux: Boolean = Os.isLinux): Boolean`
  - `fun windowChrome(useSystemTitleBar: Boolean?, isLinux: Boolean = Os.isLinux, supported: Boolean = customTitleBarSupported): WindowChrome`
  - `val customTitleBarSupported: Boolean` (lazy: `com.jetbrains.JBR.isAvailable()`, Java feature >= 25 via `internal fun jewelUsable(jbrAvailable, javaFeature)`, and Jewel's window class loads; false on any `Throwable`)

- [ ] **Step 1: Write the failing tests**

```kotlin
// WindowChromeTest
@Test fun `macOS and Windows default to custom`() = assertEquals(WindowChrome.Custom, windowChrome(null, isLinux = false, supported = true))
@Test fun `Linux defaults to system`() = assertEquals(WindowChrome.System, windowChrome(null, isLinux = true, supported = true))
@Test fun `explicit setting wins over OS default`() {
    assertEquals(WindowChrome.Custom, windowChrome(false, isLinux = true, supported = true))
    assertEquals(WindowChrome.System, windowChrome(true, isLinux = false, supported = true))
}
@Test fun `unsupported runtime always system`() = assertEquals(WindowChrome.System, windowChrome(false, isLinux = false, supported = false))

// AppSettingsTest
@Test fun `useSystemTitleBar absent from older file loads as null`() {
    Files.writeString(file, """{"themeMode":"Light"}""")
    assertNull(AppSettings(file).settings.value.useSystemTitleBar)
}
@Test fun `useSystemTitleBar persists`() {
    AppSettings(file).update { it.copy(useSystemTitleBar = true) }
    assertEquals(true, AppSettings(file).settings.value.useSystemTitleBar)
}
```
Also update the existing positional `Settings(ThemeMode.Dark, "/opt/adb", "/tmp/cap")` assertion if the new field breaks it (add the field last so it does not).

- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*WindowChromeTest' --tests '*AppSettingsTest'` → FAIL (unresolved references).
- [ ] **Step 3: Implement** the field (last in `Settings`) and the three functions in `WindowChrome.kt`.
- [ ] **Step 4: Run** same command → PASS.
- [ ] **Step 5: Commit** — `feat(window): title bar setting and chrome decision`

---

### Task 3: `AppWindow` + main window

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/window/AppWindow.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/window/TitleBarColors.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/theme/Theme.kt` (extract `isDark`)
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/Main.kt:155-163`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/App.kt` (rail top padding only if the mockup's flush rail needs it)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/window/TitleBarColorsTest.kt`

**Interfaces:**
- Consumes: `WindowChrome`, `windowChrome(...)`, `Settings.useSystemTitleBar` (Task 2).
- Produces:
  - `@Composable fun ThemeMode.isDark(): Boolean` (used by `ScrcpyViewerTheme` too)
  - `data class BarColors(val background: Color, val inactiveBackground: Color, val content: Color, val border: Color)`
  - `fun barColors(scheme: ColorScheme): BarColors` — background `surfaceContainer`, inactive `surfaceContainerLow`, content `onSurface`, border `outlineVariant`
  - `interface AppTitleBarScope { fun Modifier.interactive(key: String): Modifier }` — Jewel `clientRegion(key)` in Custom, identity in System
  - ```kotlin
    @Composable fun AppWindow(
        onCloseRequest: () -> Unit,
        title: String,
        themeMode: ThemeMode,
        chrome: WindowChrome,
        state: WindowState = rememberWindowState(),
        visible: Boolean = true,
        alwaysOnTop: Boolean = false,
        icon: Painter? = AppIcon.painter,
        titleBar: @Composable AppTitleBarScope.() -> Unit,
        content: @Composable FrameWindowScope.() -> Unit,
    )
    ```
    Custom: Jewel `IntUiTheme` (light/dark definition from `themeMode.isDark()`) with decorated-window styling whose `TitleBarStyle` / `DecoratedWindowStyle` colours come from `barColors(...)`; `DecoratedWindow { TitleBar { titleBar() } ; content() }` inside `ScrcpyViewerTheme`. On Linux, round GNOME-style pane buttons via `TitleBarStyle` icons / `paneButtonStyle`. System: plain `Window`, content is `Column { titleBar(); content() }` — callers that do not want a bar in System mode render nothing for it (main window: nothing; mirror: its strip). Check exact Jewel styling factory names against the sources in the Jewel jar.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `bar colours come from the M3 scheme`() {
    assertEquals(BarColors(LightColors.surfaceContainer, LightColors.surfaceContainerLow, LightColors.onSurface, LightColors.outlineVariant), barColors(LightColors))
    assertEquals(DarkColors.surfaceContainer, barColors(DarkColors).background)
}
```
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*TitleBarColorsTest'` → FAIL.
- [ ] **Step 3: Implement** `isDark`, `barColors`, `AppTitleBarScope`, `AppWindow`.
- [ ] **Step 4: Switch the main window** in `Main.kt` to `AppWindow(chrome = windowChrome(settings.useSystemTitleBar), ...)`, computing `chrome` once with `remember` so a setting change does not rebuild the open window. Title bar slot in Custom mode: "Scrcpy Viewer" text (`titleSmall`), start-aligned after the traffic lights on macOS, after the app icon on Windows, centred on Linux; nothing in System mode.
- [ ] **Step 5: Run** `./gradlew :composeApp:jvmTest` → PASS.
- [ ] **Step 6: Manual check on macOS** — `./gradlew :composeApp:run`: bar matches rail colour in Light and Dark; switching theme in Settings recolours the bar at once; drag, double-click zoom, green-button fullscreen, resize from all edges work.
- [ ] **Step 7: Commit** — `feat(window): app-coloured title bar on the main window`

---

### Task 4: Mirror window strip as title bar

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/mirror/MirrorWindow.kt` (`MirrorWindow` ~276-520, `MirrorContent` 674, `TopStrip` 735, `OverflowMenu` 815, `ZoomControls` 794)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/MirrorScreenshotTest.kt`

**Interfaces:**
- Consumes: `AppWindow`, `AppTitleBarScope.interactive`, `windowChrome` (Tasks 2-3). `MirrorWindow` gains param `useSystemTitleBar: Boolean?` passed from `Main.kt`.
- Produces: `MirrorContent(..., showTopStrip: Boolean = true)`; `TopStrip` becomes `internal fun AppTitleBarScope.TopStrip(state, onZoom)`.

- [ ] **Step 1: Add a screenshot test** `mirror without top strip` rendering `MirrorContent(showTopStrip = false)` light + dark, next to the existing ones (screenshots run with `-Pscreenshots`).
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest -Pscreenshots --tests '*MirrorScreenshotTest'` → FAIL (no parameter).
- [ ] **Step 3: Implement** — `MirrorWindow` uses `AppWindow(chrome = remember { windowChrome(useSystemTitleBar) }, ...)`; titleBar slot = `TopStrip` in both modes (in System mode `AppWindow` puts it at the top of content, matching today); `MirrorContent(showTopStrip = false)` always from the window, so the strip is drawn once. Every clickable in the strip (zoom −/+/fit, copy, overflow) gets `Modifier.interactive("<unique key>")`. Strip background transparent in Custom mode (the bar paints `surfaceContainer`); the divider below it stays.
- [ ] **Step 4: Run** same screenshot command → PASS; then `./gradlew :composeApp:jvmTest` → PASS.
- [ ] **Step 5: Manual check on macOS with a phone** (Review Focus 1-2): strip buttons click without dragging; dragging empty strip area moves the window; taps on the phone image land where clicked; auto-fit resizes once and settles; traffic lights don't overlap the status dot.
- [ ] **Step 6: Commit** — `feat(mirror): top strip becomes the window title bar`

---

### Task 5: Settings toggle

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsViewModel.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsScreen.kt`
- Modify: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/SettingsScreenshotTest.kt` (new `SettingsContent` lambda param)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `Settings.useSystemTitleBar`, `defaultUseSystemTitleBar` (Task 2).
- Produces: `SettingsViewModel.setUseSystemTitleBar(value: Boolean)`; `SettingsContent(..., onUseSystemTitleBar: (Boolean) -> Unit, ...)`.

- [ ] **Step 1: Write the failing test** — `setUseSystemTitleBar(true)` then `settings.value.useSystemTitleBar == true`; `setUseSystemTitleBar(false)` then `== false`.
- [ ] **Step 2: Run** `./gradlew :composeApp:jvmTest --tests '*SettingsViewModelTest'` → FAIL.
- [ ] **Step 3: Implement** the VM method and a Section "Window" after "Theme": a `Switch` row labelled "Use system title bar", checked = `settings.useSystemTitleBar ?: defaultUseSystemTitleBar()`, hint "Applies to windows opened after the change. Restart to apply it to this window." plus, on Linux, "Window can't be resized from its edges." shown when the switch is off.
- [ ] **Step 4: Run** `./gradlew :composeApp:jvmTest` (and `-Pscreenshots --tests '*SettingsScreenshotTest'`, re-recording the settings goldens) → PASS.
- [ ] **Step 5: Commit** — `feat(settings): Use system title bar toggle`

---

### Task 6: Packaged-app verification + PR

- [ ] **Step 1:** `./gradlew :composeApp:createDistributable`, open the `.app`, repeat Task 3 Step 6 and Task 4 Step 5 on the packaged build; toggle the setting and open a new mirror window to confirm it gets the system bar.
- [ ] **Step 2:** Push, open PR, confirm CI `build` jobs (macOS, Windows, Linux) succeed with the auto-provisioned JBR.
- [ ] **Step 3:** Hand Windows / Linux manual checks to the user (spec Verification list): Aero snap, Snap Layouts on hover over maximize, edge resize on Windows; Linux opens with the system bar, and with the toggle off drags and tiles via the window manager.
