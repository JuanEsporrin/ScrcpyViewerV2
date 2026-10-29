# Settings Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-column Settings screen with a searchable category list pane plus a detail pane, inside the existing Settings rail tab.

**Architecture:** A settings registry (categories → entries with title, description, keywords, composable control) is the single source for the list pane, detail pane and search. Search and category-selection rules are pure Kotlin, unit-tested; selection/query state lives in `SettingsViewModel` so it survives rail tab switches. `SettingsContent` stays stateless and screenshot-testable.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop (Material 3), Koin 4.2.2, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-29-settings-redesign-design.md`

## Global Constraints

- Base: branch `worktree-better-settings` sits on PR #14 (`origin/worktree-auto-update`, `e1337f7`). Do not rebase onto main.
- No new settings; no behavior or persistence changes to existing settings.
- Settings stays a tab in the main window `NavigationRail`; no separate window, no Cmd+,/Ctrl+, shortcut.
- List pane is plain text items, no icons, thin divider — must not read as a second rail.
- Categories in order: Appearance, Mirroring, adb, Updates, Help. Updates category is always shown.
- Search matches entry title, description, keywords, and category title; case-insensitive substring.
- Non-matching rows in the selected category are hidden during search.
- Detail content width capped at 720dp (as today).
- Unit tests: `./gradlew :composeApp:jvmTest`. Screenshot tests (only `*ScreenshotTest`): `./gradlew :composeApp:jvmTest -Pscreenshots`; output in `composeApp/build/screenshots/`.

## Review Focus

- Query with only whitespace, or leading/trailing spaces: treated as trimmed; whitespace-only = no search (full list). Test in Task 1.
- Query containing regex metacharacters (`.`, `(`, `*`, `\`): matched literally, no crash. Test in Task 1.
- Repeated occurrences of the query in one string ("a" in "data"): every occurrence highlighted, ranges non-overlapping. Test in Task 1.
- Typing more characters while the selected category still matches: selection must not jump to the first category. Test in Task 2.
- Persistence error banner still visible while searching / with no results. Screenshot in Task 4.

---

### Task 1: Registry types and search

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsRegistry.kt` (types only in this task)
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsSearch.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsSearchTest.kt`

**Interfaces:**
- Produces (in `SettingsRegistry.kt`):
  ```kotlin
  enum class SettingsCategoryId { Appearance, Mirroring, Adb, Updates, Help }
  enum class EntryLayout { Inline, Block }   // Inline: control right of text; Block: control below text

  /** Everything an entry's control can read or call. */
  class SettingsBindings(
      val state: SettingsUiState,
      val updates: UpdateUiState,
      val actions: SettingsActions,
      val isLinux: Boolean,
      val customTitleBarSupported: Boolean,
  )

  /** All Settings callbacks; every one defaults to a no-op so tests and previews pass only what they need. */
  class SettingsActions(
      val onTheme: (ThemeMode) -> Unit = {},
      val onUseSystemTitleBar: (Boolean) -> Unit = {},
      val onDefaultPreset: (String?) -> Unit = {},
      val onUseBundledAdb: () -> Unit = {},
      val onUseCustomAdb: (String) -> Unit = {},
      val onBrowseAdb: () -> Unit = {},
      val onBrowseCaptureDir: () -> Unit = {},
      val onShowOnboarding: () -> Unit = {},
      val onCheckUpdates: () -> Unit = {},
      val onUpdate: () -> Unit = {},
      val onCancelUpdate: () -> Unit = {},
      val onSelectCategory: (SettingsCategoryId) -> Unit = {},
      val onQuery: (String) -> Unit = {},
  )

  class SettingEntry(
      val id: String,
      val title: String,
      val description: String? = null,
      val keywords: List<String> = emptyList(),
      val layout: EntryLayout = EntryLayout.Inline,
      val content: @Composable (SettingsBindings) -> Unit = {},
  )

  class SettingsCategory(val id: SettingsCategoryId, val title: String, val entries: List<SettingEntry>)
  ```
- Produces (in `SettingsSearch.kt`):
  ```kotlin
  data class CategoryMatch(val category: SettingsCategory, val entries: List<SettingEntry>)
  /** Blank (after trim) query → every category with all entries. */
  fun searchSettings(categories: List<SettingsCategory>, query: String): List<CategoryMatch>
  /** Case-insensitive, literal, non-overlapping occurrences of trimmed [query] in [text]; empty for blank query. */
  fun matchRanges(text: String, query: String): List<IntRange>
  ```

- [ ] **Step 1: Write the failing tests** in `SettingsSearchTest.kt`, using a small fake registry (two categories, entries built with `SettingEntry(id, title, description, keywords)`):
  - `blank query returns every category with all entries` — `searchSettings(cats, "")` and `searchSettings(cats, "   ")` equal all categories, full entry lists.
  - `matches title description and keywords case-insensitively` — query `"DARK"` hits an entry whose keyword is `"dark mode"`; `"saved"` hits a description; `"theme"` hits a title.
  - `category title match keeps all its entries` — query `"mirror"` against category titled "Mirroring" returns all its entries.
  - `categories without matches are dropped` — result ids exclude a category with no hits; `searchSettings(cats, "zzz")` is empty.
  - `query is trimmed` — `"  folder "` behaves like `"folder"`.
  - `regex metacharacters match literally` — `searchSettings(cats, "(")` and `"."` don't throw; `"1.5"` matches text `"v1.5"` but not `"v105"`.
  - `matchRanges finds every occurrence` — `matchRanges("Data path data", "data") == listOf(0..3, 10..13)`; `matchRanges("aaa", "aa") == listOf(0..1)`; `matchRanges("x", " ") == emptyList()`.

- [ ] **Step 2: Run to verify failure**
  Run: `./gradlew :composeApp:jvmTest --tests '*SettingsSearchTest'`
  Expected: compile FAIL (`searchSettings` unresolved).

- [ ] **Step 3: Implement** the types in `SettingsRegistry.kt` and both functions in `SettingsSearch.kt`. Use `String.indexOf(query, startIndex, ignoreCase = true)`, not `Regex`.

- [ ] **Step 4: Run to verify pass**
  Run: `./gradlew :composeApp:jvmTest --tests '*SettingsSearchTest'`
  Expected: PASS.

- [ ] **Step 5: Commit**
  ```bash
  git add composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsRegistry.kt composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsSearch.kt composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsSearchTest.kt
  git commit -m "feat(settings): settings registry types and search"
  ```

---

### Task 2: Category selection and query state

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsNav.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsViewModel.kt` (add nav state + two methods)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsNavTest.kt`, `SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `SettingsCategoryId` (Task 1).
- Produces:
  ```kotlin
  data class SettingsNav(
      val selected: SettingsCategoryId = SettingsCategoryId.Appearance,
      val query: String = "",
      /** Category to return to when the search is cleared; null when the user picked one mid-search. */
      val restoreTo: SettingsCategoryId? = null,
  ) {
      fun withQuery(query: String): SettingsNav
      fun select(id: SettingsCategoryId): SettingsNav
      /** [selected] if it is in [visible], else the first of [visible]; null when [visible] is empty. */
      fun shown(visible: List<SettingsCategoryId>): SettingsCategoryId?
  }
  ```
  - `SettingsViewModel.nav: StateFlow<SettingsNav>`, `fun selectCategory(id: SettingsCategoryId)`, `fun setQuery(query: String)`.

Rules (spec "Search"): blank→non-blank query records `restoreTo = selected`; non-blank→blank sets `selected = restoreTo ?: selected`, `restoreTo = null`; `select` while searching sets `restoreTo = null`; "blank" means blank after trim. Query text itself is stored untrimmed (it's what the field shows).

- [ ] **Step 1: Write the failing tests** in `SettingsNavTest.kt`:
  - `defaults to Appearance` — `SettingsNav().selected == Appearance`.
  - `clearing search restores the category selected before searching` — select Adb, `withQuery("fold")` (shown would be Mirroring), `withQuery("")` → `selected == Adb`.
  - `category picked during search stays after clearing` — select Adb, `withQuery("a")`, `select(Help)`, `withQuery("")` → `selected == Help`.
  - `shown falls back to first visible` — `SettingsNav(selected = Adb).shown(listOf(Mirroring, Help)) == Mirroring`; `shown(emptyList()) == null`.
  - `typing more keeps a still-visible selection` — select Mirroring, `withQuery("f")`, `withQuery("fo")`; `shown(listOf(Appearance, Mirroring)) == Mirroring`, `restoreTo == Mirroring`.
  - `whitespace query is not a search` — `withQuery("  ").restoreTo == null`.
  In `SettingsViewModelTest.kt`: `selection survives as view model state` — `vm().apply { selectCategory(Help); setQuery("x") }.nav.value` has `selected == Help`, `query == "x"`.

- [ ] **Step 2: Run to verify failure**
  Run: `./gradlew :composeApp:jvmTest --tests '*SettingsNavTest' --tests '*SettingsViewModelTest'`
  Expected: compile FAIL.

- [ ] **Step 3: Implement** `SettingsNav` and the ViewModel additions (a `MutableStateFlow<SettingsNav>` exposed read-only; not persisted).

- [ ] **Step 4: Run to verify pass** — same command, expected PASS.

- [ ] **Step 5: Commit**
  ```bash
  git add composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsNav.kt composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsViewModel.kt composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/settings/
  git commit -m "feat(settings): category selection and search query state"
  ```

---

### Task 3: Real registry entries and the detail pane

**Files:**
- Modify: `ui/settings/SettingsRegistry.kt` (add `val settingsCategories: List<SettingsCategory>`)
- Create: `ui/settings/SettingsDetailPane.kt`
- Modify: `ui/settings/SettingsScreen.kt` (move existing controls into entry `content` lambdas; the adb picker and preset dropdown composables stay, made `internal`)
- Modify: `ui/update/UpdatesSection.kt` (drop the `Section("Updates")` wrapper → plain `Column`; give the "Version X available" surface a tone distinct from the card, e.g. `secondaryContainer`)
- Test: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/settings/SettingsRegistryTest.kt`

(All `ui/...` paths are under `composeApp/src/jvmMain/kotlin/com/programmersbox/scrcpyviewer/`.)

**Interfaces:**
- Consumes: Task 1 types, `searchSettings`, `matchRanges`.
- Produces:
  - `val settingsCategories: List<SettingsCategory>` — exactly the table below.
  - `@Composable fun SettingsDetailPane(category: SettingsCategory, entries: List<SettingEntry>, query: String, bindings: SettingsBindings, modifier: Modifier = Modifier)` — category title (`headlineSmall`), then entries in one rounded card (`surfaceContainer`, dividers between rows), title/description highlighted via `matchRanges` (highlight = `primaryContainer` background span). Inline: text left, control right. Block: text, then control full-width below. Shows `bindings.state.persistenceError` banner (existing error-container style) above the title. Scrolls vertically; content `widthIn(max = 720.dp)`.

Registry (copy verbatim; descriptions are display text):

| Category (title) | Entry id | Title | Description | Keywords | Layout | Control |
|---|---|---|---|---|---|---|
| Appearance ("Appearance") | `theme` | Theme | Light, dark, or follow the system. | dark mode, light, color, colour | Inline | existing segmented row |
| | `title-bar` | Use system title bar | Applies to windows opened after the change. Restart to apply it to this window. | window, frame, decorations, chrome | Inline | existing switch; the "isn't available on this Java runtime" and Linux "can't be resized" hints render under the description under the same conditions as today |
| Mirroring ("Mirroring") | `default-preset` | Default preset | Applied to a device the first time it is selected. | preset, profile, defaults | Inline | existing dropdown |
| | `capture-dir` | Screenshots and recordings folder | Where screenshots and screen recordings are saved. | screenshot, recording, capture, save, directory, path | Block | existing path field + Choose… |
| Adb ("adb") | `adb-path` | adb executable | Use the bundled adb or point to your own. | android debug bridge, platform-tools, binary, path, custom | Block | existing radio / path / Browse… / Use picker |
| Updates ("Updates") | `updates` | App updates | Check for new versions and download them. | version, upgrade, release, download, github | Block | `UpdatesContent(bindings.updates, onCheckUpdates, onUpdate, onCancelUpdate)` |
| Help ("Help") | `welcome-guide` | Welcome guide | Setup steps for USB debugging, Wi-Fi and mirroring. | onboarding, tutorial, setup, usb, wifi | Inline | OutlinedButton "Show welcome guide" |

- [ ] **Step 1: Write the failing test** `SettingsRegistryTest.kt`:
  - `categories in spec order` — `settingsCategories.map { it.id } == listOf(Appearance, Mirroring, Adb, Updates, Help)`.
  - `entry ids are unique` — all ids distinct.
  - `common searches find the right setting` — `searchSettings(settingsCategories, q)` first match's entry ids: `"dark mode"` → `["theme"]`, `"folder"` → `["capture-dir"]`, `"platform-tools"` → `["adb-path"]`, `"github"` → `["updates"]`, `"wifi"` → `["welcome-guide"]`.

- [ ] **Step 2: Run to verify failure**
  Run: `./gradlew :composeApp:jvmTest --tests '*SettingsRegistryTest'`
  Expected: compile FAIL (`settingsCategories` unresolved).

- [ ] **Step 3: Implement** the registry, move controls into entry lambdas, write `SettingsDetailPane`, restyle `UpdatesContent`. `SettingsContent` may temporarily render the selected category's pane only; Task 4 builds the shell.

- [ ] **Step 4: Run all unit tests**
  Run: `./gradlew :composeApp:jvmTest`
  Expected: PASS (existing tests included).

- [ ] **Step 5: Commit**
  ```bash
  git add composeApp/src/
  git commit -m "feat(settings): settings registry entries and detail pane"
  ```

---

### Task 4: List pane, search field, wiring, and screenshots

**Files:**
- Create: `ui/settings/SettingsListPane.kt`
- Modify: `ui/settings/SettingsScreen.kt` (`SettingsScreen`, `SettingsContent` new signatures)
- Modify: `ui/App.kt:136-139` (pass `updateViewModel` as required)
- Modify: `composeApp/src/jvmTest/kotlin/com/programmersbox/scrcpyviewer/ui/SettingsScreenshotTest.kt`

**Interfaces:**
- Consumes: everything above; `SettingsViewModel.nav`, `selectCategory`, `setQuery`; `UpdateViewModel.state`, `check`, `update`, `cancel`.
- Produces:
  ```kotlin
  @Composable fun SettingsScreen(viewModel: SettingsViewModel, updateViewModel: UpdateViewModel, files: SettingsFileDialogs, modifier: Modifier = Modifier)

  @Composable fun SettingsContent(
      state: SettingsUiState,
      updates: UpdateUiState,
      nav: SettingsNav,
      actions: SettingsActions,
      modifier: Modifier = Modifier,
      categories: List<SettingsCategory> = settingsCategories,
      isLinux: Boolean = Os.isLinux,
      customTitleBarSupported: Boolean = com.programmersbox.scrcpyviewer.ui.window.customTitleBarSupported,
  )

  @Composable fun SettingsListPane(
      matches: List<CategoryMatch>, selected: SettingsCategoryId?, query: String, searching: Boolean,
      updateAvailable: Boolean, onQuery: (String) -> Unit, onSelect: (SettingsCategoryId) -> Unit, modifier: Modifier = Modifier,
  )
  ```
- List pane: width 200dp, `OutlinedTextField` (single line, leading search icon, placeholder "Search settings", trailing clear icon when non-empty), then one plain-text item per match. Selected item: `secondaryContainer` fill + 3dp `primary` leading bar. While `searching`, each item shows its match count (`entries.size`) trailing, `onSurfaceVariant`. Updates item shows a small `Badge()` when `updateAvailable`. No results: "No settings match" in `onSurfaceVariant`; detail pane empty. 1dp `outlineVariant` vertical divider between panes.
- `SettingsContent` computes `matches = searchSettings(categories, nav.query)`, `shown = nav.shown(matches.map { it.category.id })`, and renders `SettingsDetailPane` for the shown match (its filtered entries). With no matches, still show the persistence error banner if present.

- [ ] **Step 1: Rewrite screenshot tests** in `SettingsScreenshotTest.kt` against the new `SettingsContent` (keep the existing `state`, `release`, `now`, `updatesUi` fixtures; keep `app light with rail`). Each asserts file size > 10_000:
  - `settings <category> light` and `settings <category> dark` for all five categories → `settings-<category>-light.png` / `-dark.png`.
  - `settings search folder` — `nav = SettingsNav(query = "folder")` → only Mirroring listed with count 1, only the captures row visible.
  - `settings search no results` — `query = "zzz"`, with `persistenceError = "disk full"` → "No settings match" and the banner visible.
  - `settings update badge` — `updates.available = release`.
  - Keep the existing updates states (available, downloading, up to date, idle, error, mac hint) and adb error / no-custom-title-bar cases, now rendered with `nav = SettingsNav(selected = Updates)` / `Adb` / `Appearance`.

- [ ] **Step 2: Run to verify failure**
  Run: `./gradlew :composeApp:jvmTest -Pscreenshots --tests '*SettingsScreenshotTest'`
  Expected: compile FAIL (new `SettingsContent` signature).

- [ ] **Step 3: Implement** `SettingsListPane`, new `SettingsContent`/`SettingsScreen`, and the `App.kt` call change.

- [ ] **Step 4: Run tests**
  Run: `./gradlew :composeApp:jvmTest -Pscreenshots --tests '*SettingsScreenshotTest'` then `./gradlew :composeApp:jvmTest`
  Expected: both PASS. Open `composeApp/build/screenshots/settings-*.png` and check: list pane reads as a list (no icons), selected bar visible, search highlights visible, Updates block has no duplicate header, badge on Updates item.

- [ ] **Step 5: Run the app** — `./gradlew :composeApp:run`; open Settings, click each category, search "folder" then clear (returns to previous category), switch to Devices and back (same category), change theme (applies live).

- [ ] **Step 6: Commit**
  ```bash
  git add composeApp/src/
  git commit -m "feat(settings): category list pane with search"
  ```
