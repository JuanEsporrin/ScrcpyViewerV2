# Settings screen redesign

## Intent

The Settings screen today is one scrolling column of unrelated sections (theme, window, adb, default preset, captures folder, welcome guide, and — on this branch — updates). The user's complaint: "it just looks like a bunch of settings thrown into a screen. There's no structure or organization." Scrcpy Viewer is a desktop app, so Settings should feel like a desktop settings UI, not a mobile settings list.

Chosen direction (from mockups): a category list pane plus a detail pane, IntelliJ / VS Code style, inside the existing Settings tab. The user picked it because the category list can grow, so future settings can be added cleanly, and because search will help a lot.

## Goals

- Settings grouped into named categories, one category shown at a time.
- Search over settings that narrows the category list.
- Adding a new setting or category is a small, obvious change in one place.
- Every existing setting keeps working exactly as it does today.

## Non-goals

- No new settings, and no changes to what existing settings do or how they persist.
- No separate Settings window and no Cmd+, / Ctrl+, shortcut. Settings stays a tab in the main window's `NavigationRail` (user decision).
- No narrow-window / collapsed layout for the list pane (desktop-only app; builder's call if a minimum width is needed).

## Anti-goals

- The category list must not read as a second navigation rail next to the app's rail. It is a plain list pane: text items, no icons, a thin divider separating it from the detail pane.
- Search must not make settings unusable — controls in filtered results work in place.

## Base

Built on PR #14 (`worktree-auto-update`, unmerged) — user decision — so Updates is a category from the start. This branch (`worktree-better-settings`) was reset onto `origin/worktree-auto-update` at `e1337f7`.

## Layout

```
| app rail | list pane (~200dp)   | detail pane                         |
|          | [🔍 Search settings] | Appearance                          |
|    ⚙     | ▌Appearance          | ┌──────────────────────────────────┐|
|          |  Mirroring           | │ Theme          [Light|Dark|Sys]  │|
|          |  adb                 | │ desc                             │|
|          |  Updates  •          | ├──────────────────────────────────┤|
|          |  Help                | │ Use system title bar      [ ◯ ]  │|
|          |                      | └──────────────────────────────────┘|
```

- **List pane:** fixed width (~200dp), search field at top, then category names as plain text. Selected category: filled background + accent bar on the leading edge. Thin vertical divider between list and detail.
- **Detail pane:** category title (headline), then that category's settings as rows grouped in rounded cards (`surfaceContainer`-ish). Row: title + optional description on the left, control on the right. Content width capped (keep the existing ~720dp max) so rows don't stretch across wide windows.
- Settings that don't fit a single row (adb picker, Updates block) render as a full-width block inside the card instead of title-left / control-right.
- The persistence error banner (`SettingsUiState.persistenceError`) shows at the top of the detail pane regardless of category.

## Categories

| Category | Settings |
|---|---|
| Appearance | Theme (segmented Light/Dark/System); Use system title bar (switch, with the existing "applies to new windows", "not available on this runtime", and Linux resize hints) |
| Mirroring | Default preset (dropdown); Screenshots and recordings folder (path + Choose…) |
| adb | The full bundled / custom picker, inline as today (radio, path field, Browse…, Use, version / error text) |
| Updates | The existing `UpdatesContent` block from PR #14 |
| Help | Show welcome guide |

- **Updates badge:** when an update is available (`UpdateUiState.available != null`), the Updates list item shows a small badge, matching the rail badge in `App.kt`. `SettingsContent` today only gets updates through an opaque `updates` slot, so it needs the availability passed in explicitly (e.g. an `updateAvailable: Boolean` or the `UpdateUiState`). The Updates category is always shown so "Download from GitHub" is always reachable (user decision). Make the update state a required input rather than an optional `UpdateViewModel?`; tests and previews pass an `UpdateUiState`.
- **Updates block styling:** `UpdatesContent` currently wraps itself in `Section("Updates")` and an inner `surfaceContainer` card. In the new layout, drop its own section header (the category title already says "Updates") and retone the inner "version available" card so it doesn't blend into the outer card. Visual only; behavior unchanged. *Claude's call.*
- **Default selection:** Appearance on first open. While the app runs, Settings reopens on the last category viewed, including across rail tab switches — keep the selection in `SettingsViewModel` (hoisted in `App`), not persisted to disk. *Claude's call.*

## Settings registry

Categories and their settings are declared in one place (a registry). Each setting entry has: a stable id, title, optional description, optional keywords (search-only, not displayed), and its composable content. Each category has: id, title, and its ordered entries. The list pane, the detail pane, and search all read from this registry. Adding a setting = adding an entry; adding a category = adding a category with entries.

The exact Kotlin shape (data classes vs. DSL, where state/callbacks are threaded in) is the builder's call. It must keep `SettingsContent` stateless and previewable/screenshot-testable as today.

Keywords should be filled in for existing settings so obvious searches hit, e.g. Theme: "dark mode, light, color"; captures folder: "screenshot, recording, save, directory, path"; adb: "android debug bridge, platform-tools, binary"; Updates: "version, upgrade, release, download".

## Search

- Matches setting title, description, and keywords (user decision). Category titles also match. Case-insensitive substring match. *Claude's call on matching rules.*
- **Category list filters** to categories with at least one match (user decision). Each remaining category shows its match count.
- If the selected category is filtered out, the first remaining category is selected automatically.
- **Inside the selected category, non-matching rows are hidden** (user decision). If the category title itself matched, show all its rows.
- Matched text in visible row titles/descriptions is highlighted. *Claude's call.*
- No matches: list pane shows "No settings match"; detail pane is empty. *Claude's call.*
- Clearing the search restores the full list and returns to the category selected before searching — unless the user clicked a category while searching; then that category stays selected. *Claude's call.*
- Search query is UI state (not persisted).

## Testing

- Unit tests for search filtering: title / description / keyword hits, category-title hits, case-insensitivity, selection fallback when the selected category is filtered out, and restore-on-clear.
- Update `SettingsScreenshotTest` (and the Updates screenshots added in PR #14) for the new layout: at least one screenshot per category in light and dark, one search-active screenshot, and one no-results screenshot. Screenshots run with `./gradlew :composeApp:jvmTest -Pscreenshots`.
- Existing `SettingsViewModelTest` and `AppNavigationTest` keep passing.

Existing screenshot files (`settings-light.png`, `settings-updates-*.png`, etc.) may be renamed or replaced by per-category shots; nothing outside the test reads them.

## Left to the builder

- Registry shape and file layout under `ui/settings/`.
- Exact dimensions, colors, and typography within the Material 3 theme.
- Whether the search query lives in `SettingsViewModel` or remembered UI state.
