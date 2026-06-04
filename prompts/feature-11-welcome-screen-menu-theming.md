# Feature 11 — Welcome Screen, Menu Bar & Theme Foundation

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

Bintro is a JavaFX 21 desktop app for video editors. Currently the app opens
directly into the main project view. We need:
1. A welcome screen on launch (New Project / Open Project)
2. A proper menu bar
3. The FragmentCore font embedded and used for headings/logo text
4. A CSS theming foundation (variables-based) so themes can be swapped later
   without rewriting UI code — default theme is Light

The app aesthetic is clean and minimal, inspired by professional film tools —
not a tech startup. Think Final Draft or Silverstack, not a SaaS dashboard.

---

## Assets to add first (do this before writing any code)

1. **Logo:** Copy `bintro-logo.png` (provided by user, black background, white
   FragmentCore text) into `src/main/resources/images/bintro-logo.png`

2. **Font:** Copy the FragmentCore font file into
   `src/main/resources/fonts/FragmentCore.ttf` (or .otf — use whichever the user
   provides). If both weights exist, add them all.

---

## Task

### 1. CSS theming foundation

Create `src/main/resources/css/theme.css` — the single stylesheet imported by all
FXML files. Use CSS custom properties (variables) for every color so themes can
be swapped by replacing just this file:

```css
/* Load FragmentCore */
@font-face {
    src: url('/fonts/FragmentCore.ttf');
    -fx-font-family: 'FragmentCore';
}

/* ── Light theme (default) ─────────────────────────────── */
.root {
    -bintro-bg:           #f5f4f0;      /* off-white background */
    -bintro-bg-raised:    #ffffff;      /* cards, panels */
    -bintro-bg-sunken:    #e8e7e3;      /* input fields, list backgrounds */
    -bintro-border:       #d0cfc9;
    -bintro-text:         #1a1a1a;
    -bintro-text-muted:   #6b6b6b;
    -bintro-accent:       #1a1a1a;      /* buttons, highlights — near-black */
    -bintro-accent-text:  #ffffff;

    /* Gutter bar palette (same across themes) */
    -clip-0: #4a90d9;
    -clip-1: #e07b54;
    -clip-2: #5aaa6a;
    -clip-3: #b07dd4;
    -clip-4: #c8a830;
    -clip-5: #4abcbc;

    -fx-background-color: -bintro-bg;
    -fx-font-family: 'Courier New';     /* body text — readable monospace */
    -fx-font-size: 13px;
}

/* ── Dark theme (future) ───────────────────────────────── */
/* .root.theme-dark { ... } */

/* ── Shared component styles ───────────────────────────── */
.bintro-heading {
    -fx-font-family: 'FragmentCore';
    -fx-text-fill: -bintro-text;
}

.toolbar-button {
    -fx-background-color: -bintro-accent;
    -fx-text-fill: -bintro-accent-text;
    -fx-font-size: 12px;
    -fx-padding: 6 14 6 14;
    -fx-background-radius: 3;
    -fx-cursor: hand;
    -fx-border-color: transparent;
}
.toolbar-button:hover {
    -fx-opacity: 0.85;
}
.toolbar-button.secondary {
    -fx-background-color: transparent;
    -fx-text-fill: -bintro-text;
    -fx-border-color: -bintro-border;
    -fx-border-radius: 3;
    -fx-border-width: 1;
}
.toolbar-button.secondary:hover {
    -fx-background-color: -bintro-bg-sunken;
}

.status-bar {
    -fx-background-color: -bintro-bg-sunken;
    -fx-border-color: -bintro-border;
    -fx-border-width: 1 0 0 0;
    -fx-padding: 4 8 4 8;
}
```

Apply `theme.css` to both scenes (welcome + main) via their FXML or in `App.java`.

---

### 2. Welcome screen

Create `src/main/resources/fxml/WelcomeView.fxml` and
`src/main/java/com/bintro/ui/WelcomeController.java`.

**Layout** (centered vertically and horizontally on a `StackPane` root):

```
[ bintro-logo.png — 320px wide, preserved aspect ratio ]

          New Project          Open Project

     [ version label: "0.1.0 — Early Preview" ]
```

- Background: `-bintro-bg` (off-white)
- Logo: `ImageView` with `fitWidth="320"` and `preserveRatio="true"`
- "New Project" → primary `toolbar-button` style
- "Open Project" → secondary `toolbar-button` style
- Buttons sit in an `HBox` with 16px spacing, centered
- Version label uses `-bintro-text-muted`, small font, `FragmentCore` typeface
- Window size: 480 × 400, not resizable
- No window chrome decorations beyond the OS title bar (no custom title bar needed)

**WelcomeController behaviour:**
- `onNewProject()` → opens the main project window (load `MainView.fxml` into a
  new `Stage`), then closes the welcome window
- `onOpenProject()` → opens a `FileChooser` filtered to `*.bintro` files
  (stub — just show the chooser for now and print the selected path;
  actual project serialization is a future feature)

**Update `App.java`** to load `WelcomeView.fxml` as the initial scene instead of
`MainView.fxml`.

---

### 3. Menu bar

Add a `MenuBar` to `MainView.fxml` above the `ToolBar` (inside a `VBox` at the top).

**File menu:**
- New Project — calls `onNewProject()` (returns to welcome screen or opens fresh window)
- Open Project — stub (same as welcome screen open)
- Save Project — stub (disabled for now, labeled "Save Project")
- `SeparatorMenuItem`
- Export → submenu:
  - Export to Folder (calls existing export logic)
  - Final Draft (.fdx) — disabled, label "(Coming soon)"
  - Adobe Premiere Pro — disabled, label "(Coming soon)"
  - DaVinci Resolve — disabled, label "(Coming soon)"
- `SeparatorMenuItem`
- Quit — `Platform.exit()`

**View menu:**
- Theme → submenu:
  - Light ✓ (checked, default)
  - Dark (unchecked, disabled with label "(Coming soon)")
  - Navy — disabled "(Coming soon)"
  - Forest — disabled "(Coming soon)"

**Help menu:**
- About Bintro → `Alert` dialog showing logo + version string

On macOS, set `System.setProperty("apple.laf.useScreenMenuBar", "true")` in
`App.main()` so the menu bar appears in the system menu bar instead of inside
the window.

---

### 4. Update `MainView.fxml` toolbar

Replace the existing plain `Button` elements with styled buttons using the
`toolbar-button` CSS class. The toolbar should have:
- "Select Footage" (primary style)
- "Select Script" (primary style)
- Spacer (`Region` with `HBox.hgrow="ALWAYS"`)
- "Run" (primary style, disabled until ready)
- "Export" (secondary style, disabled until Phase 1 complete)

---

### 5. Window title

Set the main window title dynamically in `MainController`:
- Default: `"Bintro"`
- After footage loaded: `"Bintro — <folder name>"`
- After script loaded: `"Bintro — <folder name> / <script filename>"`

---

## Constraints

- Java 21, JavaFX 21 only — no new Gradle dependencies.
- Do NOT use `StageStyle.UNDECORATED` or custom window chrome.
- The `theme.css` file must use JavaFX CSS custom properties (`-bintro-*`),
  not hardcoded colors anywhere in FXML or other CSS files.
- All existing functionality (scan, transcribe, match, export) must continue
  to work unchanged.
- After implementing: `./gradlew compileJava` passes, `./gradlew run` launches
  the welcome screen.

## Done when

- App launches to the welcome screen with the Bintro logo and two buttons.
- "New Project" opens the main window; welcome window closes.
- Main window has a working menu bar (File, View, Help).
- Export submenu shows future items as disabled with "(Coming soon)" labels.
- Theme submenu shows Light as checked; other themes disabled.
- Main toolbar buttons are styled using `theme.css` classes.
- Window title updates as footage/script are loaded.
- `./gradlew compileJava` passes with no errors.
