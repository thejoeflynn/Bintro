# Feature 23 — UI Overhaul: Film Tool Aesthetic

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Goal

Rework Bintro's visual design to feel like a professional film tool — dense, functional,
and refined — in the spirit of Silverstack, DaVinci Resolve's panels, or Final Draft.
The layout structure (split pane, toolbar, status bar) stays the same. Everything
*around* it — typography, colors, spacing, component styling, and the broken theme
system — gets a full pass.

---

## Bug fix first: themes are broken

Themes stopped working after features 19 and 20 were merged. The theme-switching mechanism
in `App.java` and `MainController.java` needs to be restored so that selecting Light /
Dark / Navy / Forest from the View menu visibly changes the UI.

**Diagnosis steps:**

1. Read `App.java` — find `setActiveTheme()`, `applyActiveTheme()`, and
   `attachThemeStylesheet()`.
2. Read `MainController.java` — find the `@FXML` radio menu items (`themeLight`,
   `themeDark`, `themeNavy`, `themeForest`) and their event handling.
3. Check that `theme.css` is actually attached to the scene in `App.java` or
   `MainController.initialize()`.
4. The theme class (e.g. `theme-dark`) must be applied to the **scene root node** of
   the main window. If it's being applied to a child node instead, move it to the root.
5. After applying the class, `scene.getRoot().applyCss()` and
   `scene.getRoot().layout()` must be called.

Fix whatever is broken. After the fix, switching themes via the menu must visibly update
toolbar colors, table row colors, scene list colors, and the status bar across all four
themes. Verify with `mvn compile` before moving on.

---

## Typography

Replace `'Courier New'` as the body font. Choose the best option from:
- `system-ui` (maps to SF Pro on macOS, Segoe UI on Windows — clean, native feel)
- `'Inter'` (if loadable via Google Fonts; skip if it requires a network dependency)
- `'JetBrains Mono'` (keeps the monospace feel but is much more readable)

**Recommendation:** Use `system-ui, -apple-system, sans-serif`. It is zero-cost,
renders natively on every OS, and is the standard choice for professional desktop tools.

In `theme.css`, change the root font declaration:
```css
.root {
    -fx-font-family: 'system-ui, -apple-system, sans-serif';
    -fx-font-size: 12px;
}
```

Keep `FragmentCore` for the logo/heading only (it's already scoped to `.bintro-heading`).

---

## Color palette refresh

The current light theme uses `#f5f4f0` (warm off-white) throughout. The dark themes
are functional but flat. Apply a more deliberate palette:

### Light theme (base `.root`)

```css
.root {
    -fx-background-color: #f2f2f2;
    -fx-font-family: 'system-ui, -apple-system, sans-serif';
    -fx-font-size: 12px;
}
```

| Token | Value | Usage |
|---|---|---|
| Toolbar BG | `#ffffff` | Top bar |
| Toolbar border | `#e0e0e0` | 1px bottom border |
| Panel BG | `#f8f8f8` | Table, scene list |
| Row alt | `#f2f2f2` | Odd rows |
| Selection BG | `#1a1a1a` | Selected row |
| Selection text | `#ffffff` | Selected row text |
| Primary button | `#1a1a1a` BG, `#ffffff` text | Select Footage, Run |
| Ghost button | transparent BG, `#1a1a1a` text, `#c8c8c8` border | Export |
| Status bar BG | `#ebebeb` | Bottom bar |
| Status text | `#999999` | Path label |
| Muted text | `#888888` | Count badge, column headers |
| Border | `#e0e0e0` | Dividers |

### Dark theme (`.theme-dark`)

Deep charcoal, not pure black:

| Element | Value |
|---|---|
| Root BG | `#1c1c1e` |
| Toolbar BG | `#2a2a2c` |
| Panel BG | `#1c1c1e` |
| Row alt | `#222224` |
| Selection BG | `#f0f0f0` |
| Selection text | `#111111` |
| Primary button | `#e8e8e8` BG, `#111111` text |
| Ghost button | transparent, `#aaaaaa` text, `#444444` border |
| Status bar BG | `#141416` |
| Border | `#3a3a3c` |
| Body text | `#e0e0e0` |
| Muted text | `#888888` |

### Navy theme (`.theme-navy`)

Slate-blue professional:

| Element | Value |
|---|---|
| Root BG | `#0f1c30` |
| Toolbar BG | `#162540` |
| Primary button | `#4a8fd4` BG, `#ffffff` text |
| Selection BG | `#4a8fd4` |
| Border | `#253a5a` |
| Body text | `#d8e4f4` |
| Muted text | `#7a90b0` |

### Forest theme (`.theme-forest`)

Deep green, desaturated:

| Element | Value |
|---|---|
| Root BG | `#1a2820` |
| Toolbar BG | `#223328` |
| Primary button | `#4ea860` BG, `#ffffff` text |
| Selection BG | `#4ea860` |
| Border | `#304838` |
| Body text | `#d4e8d8` |
| Muted text | `#7a9a80` |

---

## Toolbar buttons

Current buttons feel flat. Apply these improvements:

```css
.bintro-toolbar-button {
    -fx-background-color: #1a1a1a;
    -fx-text-fill: #ffffff;
    -fx-font-size: 11px;
    -fx-font-weight: bold;
    -fx-letter-spacing: 0.03em;
    -fx-padding: 6 16 6 16;
    -fx-background-radius: 5;
    -fx-border-color: transparent;
    -fx-cursor: hand;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.18), 4, 0, 0, 1);
}
.bintro-toolbar-button:hover {
    -fx-background-color: #333333;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.28), 6, 0, 0, 2);
}
.bintro-toolbar-button:pressed {
    -fx-background-color: #000000;
    -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.10), 2, 0, 0, 0);
}
.bintro-toolbar-button:disabled {
    -fx-opacity: 0.38;
    -fx-cursor: default;
    -fx-effect: none;
}

.bintro-toolbar-button-ghost {
    -fx-background-color: transparent;
    -fx-text-fill: #555555;
    -fx-font-size: 11px;
    -fx-padding: 5 15 5 15;
    -fx-background-radius: 5;
    -fx-border-color: #c8c8c8;
    -fx-border-width: 1;
    -fx-border-radius: 5;
    -fx-cursor: hand;
}
.bintro-toolbar-button-ghost:hover {
    -fx-background-color: #f0f0f0;
    -fx-border-color: #aaaaaa;
    -fx-text-fill: #1a1a1a;
}
.bintro-toolbar-button-ghost:disabled {
    -fx-opacity: 0.38;
    -fx-cursor: default;
}
```

Apply the matching dark/navy/forest overrides in their theme blocks. Dark example:
```css
.theme-dark .bintro-toolbar-button {
    -fx-background-color: #e8e8e8;
    -fx-text-fill: #111111;
}
.theme-dark .bintro-toolbar-button:hover {
    -fx-background-color: #ffffff;
}
.theme-dark .bintro-toolbar-button-ghost {
    -fx-text-fill: #c0c0c0;
    -fx-border-color: #505050;
}
.theme-dark .bintro-toolbar-button-ghost:hover {
    -fx-background-color: #333333;
    -fx-border-color: #888888;
}
```

---

## Clip table

The table needs tighter, more intentional styling:

```css
.bintro-clip-table {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-table-cell-border-color: transparent;
}
.bintro-clip-table .column-header-background {
    -fx-background-color: #f8f8f8;
    -fx-border-color: #e0e0e0;
    -fx-border-width: 0 0 1 0;
}
.bintro-clip-table .column-header .label {
    -fx-font-size: 10px;
    -fx-font-weight: bold;
    -fx-text-fill: #999999;
    -fx-text-transform: uppercase;
    -fx-alignment: center-left;
    -fx-padding: 0 8 0 8;
}
.bintro-clip-table .table-row-cell {
    -fx-background-color: #ffffff;
    -fx-border-color: transparent transparent #f0f0f0 transparent;
    -fx-border-width: 0 0 1 0;
    -fx-cell-size: 32px;
}
.bintro-clip-table .table-row-cell:odd {
    -fx-background-color: #f8f8f8;
}
.bintro-clip-table .table-row-cell:selected {
    -fx-background-color: #1a1a1a;
}
.bintro-clip-table .table-cell {
    -fx-font-size: 11px;
    -fx-text-fill: #333333;
    -fx-padding: 0 8 0 8;
}
.bintro-clip-table .table-row-cell:selected .table-cell {
    -fx-text-fill: #ffffff;
}
/* Remove the focus ring on the table itself */
.bintro-clip-table:focused {
    -fx-background-color: transparent;
}
```

Row height `-fx-cell-size: 32px` is a good default for dense professional tools. Adjust
if content feels cramped.

Apply dark/navy/forest overrides. Dark example:
```css
.theme-dark .bintro-clip-table .column-header-background {
    -fx-background-color: #2a2a2c;
    -fx-border-color: #3a3a3c;
}
.theme-dark .bintro-clip-table .column-header .label { -fx-text-fill: #888888; }
.theme-dark .bintro-clip-table .table-row-cell {
    -fx-background-color: #1c1c1e;
    -fx-border-color: transparent transparent #2a2a2c transparent;
}
.theme-dark .bintro-clip-table .table-row-cell:odd { -fx-background-color: #222224; }
.theme-dark .bintro-clip-table .table-row-cell:selected { -fx-background-color: #e8e8e8; }
.theme-dark .bintro-clip-table .table-cell { -fx-text-fill: #d8d8d8; }
.theme-dark .bintro-clip-table .table-row-cell:selected .table-cell { -fx-text-fill: #111111; }
```

---

## Scene list

```css
.bintro-scene-list {
    -fx-background-color: #f8f8f8;
    -fx-border-color: transparent;
}
.bintro-scene-list .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #444444;
    -fx-font-size: 11px;
    -fx-padding: 6 12 6 12;
    -fx-border-color: transparent transparent #f0f0f0 transparent;
    -fx-border-width: 0 0 1 0;
}
.bintro-scene-list .list-cell:odd { -fx-background-color: #f2f2f2; }
.bintro-scene-list .list-cell:selected,
.bintro-scene-list .list-cell:selected:odd {
    -fx-background-color: #1a1a1a;
    -fx-text-fill: #ffffff;
}
.bintro-scene-list .list-cell:hover:!selected { -fx-background-color: #ebebeb; }
```

---

## Status bar

```css
.bintro-status-bar {
    -fx-background-color: #ebebeb;
    -fx-border-color: #e0e0e0;
    -fx-border-width: 1 0 0 0;
    -fx-padding: 4 12 4 12;
    -fx-spacing: 10;
}
.bintro-status-bar .label {
    -fx-font-size: 11px;
    -fx-text-fill: #555555;
}
.bintro-path-label {
    -fx-text-fill: #aaaaaa;
    -fx-font-size: 10px;
}
```

---

## Count badge

```css
.bintro-count-badge {
    -fx-font-size: 10px;
    -fx-text-fill: #777777;
    -fx-background-color: #f0f0f0;
    -fx-padding: 3 10 3 10;
    -fx-background-radius: 4;
    -fx-border-color: #e0e0e0;
    -fx-border-width: 1;
    -fx-border-radius: 4;
}
```

---

## Welcome screen

Polish the welcome screen to match the new direction:
- Background: `#141414` (slightly darker than before)
- Logo: keep as-is
- Subtitle ("Footage Organizer"): `#606060`, 10px, system-ui, uppercase, letter-spacing
- "New Project" button: `#f0f0f0` BG, `#111111` text, 5px radius, slight shadow
- "Open Project" button: transparent BG, `#888888` text, `#555555` border
- Version label: `#404040`, 10px

No changes to `WelcomeController.java` logic — styling only.

---

## Constraints

- Java 17, JavaFX 17.0.11, Maven — no new dependencies
- All changes go in `theme.css`, `WelcomeView.fxml`, and wherever is needed to fix
  the theme bug (`App.java`, `MainController.java`)
- Do NOT change any parser, matching, transcription, export, or media classes
- FragmentCore font stays for headings only; body font switches to system-ui
- After implementing: `mvn compile` passes cleanly

---

## Done when

- `mvn compile` passes with no errors
- All four themes visibly switch when selected from View → Theme menu
- Body text renders in system-ui (not Courier New) across all themes
- Toolbar buttons have hover and pressed states
- Table rows are 32px tall with subtle row separators, no harsh cell borders
- Scene list has hover state and matching theme colors
- Welcome screen background is `#141414` with the updated button styles
