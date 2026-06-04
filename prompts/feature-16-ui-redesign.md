# Feature 16 — UI Redesign: Welcome Screen, Styling & Bug Fixes

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

The app currently uses default JavaFX styling throughout. This feature applies
proper visual design to the welcome screen and main view, wires up the logo,
and fixes two existing bugs.

---

## Bug fixes (do these first)

### Bug 1 — Scene list number duplication
In `MainController.onSelectScript()`, scenes are displayed like:
`"EXT. A CITY STREET - DAY1 1"` instead of `"1. EXT. A CITY STREET - DAY"`.

Find where the scene list items are built and fix the format string so the
scene number appears once, cleanly:
```java
s.sceneNumber() + ". " + s.heading()
```
Make sure `s.heading()` does not already contain the scene number embedded in it.
If it does (from the PDF parser appending the number to the heading text), strip
trailing digits and whitespace from `heading` before display.

### Bug 2 — Logo not loading on welcome screen
The welcome screen shows plain "BINTRO" text instead of the logo image.
The logo is now at `src/main/resources/images/bintro-logo.png`.
Fix the `WelcomeController` or `WelcomeView.fxml` to load it correctly:
```java
Image logo = new Image(getClass().getResourceAsStream("/images/bintro-logo.png"));
ImageView iv = new ImageView(logo);
iv.setFitWidth(280);
iv.setPreserveRatio(true);
```

---

## Welcome screen redesign

### Visual style
- Background: `#111111` (near-black)
- Remove the plain "BINTRO" text label entirely — the logo image replaces it
- Below the logo, add a subtitle label: `"Footage Organizer"`
  - Font size: 10px, color: `#555555`, letter-spacing: 0.25em, ALL CAPS

### Buttons
Replace default JavaFX buttons with styled versions using inline CSS:

**New Project (primary):**
```
-fx-background-color: #ffffff;
-fx-text-fill: #111111;
-fx-font-size: 12px;
-fx-padding: 8 22 8 22;
-fx-background-radius: 4;
-fx-border-color: transparent;
-fx-cursor: hand;
```

**Open Project (secondary/ghost):**
```
-fx-background-color: transparent;
-fx-text-fill: #aaaaaa;
-fx-font-size: 12px;
-fx-padding: 8 22 8 22;
-fx-background-radius: 4;
-fx-border-color: #444444;
-fx-border-width: 1;
-fx-border-radius: 4;
-fx-cursor: hand;
```

### Version label
- Color: `#444444`
- Font size: 10px

### Layout
- Root: `StackPane` with `style="-fx-background-color: #111111;"`
- Center `VBox` with spacing 24, alignment CENTER
- Order: `ImageView` (logo) → subtitle label → button `HBox` (spacing 10) → version label
- Window size stays 480 × 400

---

## Main view redesign

### Toolbar (`MainView.fxml`)

Replace the existing toolbar with a styled `HBox`:
```
-fx-background-color: #ffffff;
-fx-border-color: #dddddd;
-fx-border-width: 0 0 1 0;
-fx-padding: 7 12 7 12;
-fx-spacing: 8;
```

**Select Footage / Select Script buttons:**
```
-fx-background-color: #111111;
-fx-text-fill: #ffffff;
-fx-font-size: 11px;
-fx-padding: 5 14 5 14;
-fx-background-radius: 4;
-fx-border-color: transparent;
-fx-cursor: hand;
```

**Spacer:** `Region` with `HBox.hgrow="ALWAYS"`

**Clip/scene count badge** — add a `Label` between the spacer and Run button:
- Updated dynamically in `MainController` after footage/script load
- Text format: `"N clips · N scenes"` (or `""` if nothing loaded)
- Style: `-fx-font-size: 10px; -fx-text-fill: #888888; -fx-background-color: #f0efe9; -fx-padding: 4 10 4 10; -fx-background-radius: 3; -fx-border-color: #dddddd; -fx-border-width: 1; -fx-border-radius: 3;`

**Run button (primary — same style as Select buttons above)**

**Export button (disabled state):**
```
-fx-background-color: transparent;
-fx-text-fill: #aaaaaa;
-fx-font-size: 11px;
-fx-padding: 5 14 5 14;
-fx-background-radius: 4;
-fx-border-color: #cccccc;
-fx-border-width: 1;
-fx-border-radius: 4;
```

### Footage panel header

Replace the plain `"Footage"` label with a styled header bar:
```
-fx-background-color: #ffffff;
-fx-border-color: #eeeeee;
-fx-border-width: 0 0 1 0;
-fx-padding: 8 12 8 12;
```
- Left: `Label` "FOOTAGE" — font size 10px, color `#333`, letter-spacing via
  `-fx-font-family` or just uppercase text, font-weight bold
- Right: `Label` showing clip count (e.g. "2 clips") — font size 10px, color `#aaa`

### Table styling

In `MainController`, after the `TableView` is created/referenced, apply:
```java
clipTable.setStyle(
    "-fx-background-color: transparent;" +
    "-fx-border-color: transparent;"
);
clipTable.lookup(".column-header-background").setStyle(
    "-fx-background-color: #fafafa;" +
    "-fx-border-color: #eeeeee;" +
    "-fx-border-width: 0 0 1 0;"
);
```

Column header labels: font size 10px, uppercase, color `#888`, letter-spacing.

### Scenes tab list

Style the scene `ListView` rows:
- Font size: 10px
- Color: `#333333`
- Row padding: 5px 12px
- Alternating row background: `#ffffff` / `#fafafa`
- Selected row: background `#111111`, text `#ffffff`

### Status bar

```
-fx-background-color: #f5f4f0;
-fx-border-color: #dddddd;
-fx-border-width: 1 0 0 0;
-fx-padding: 5 12 5 12;
```

Add a right-aligned `Label` showing the current project path
(`footageFolder.getName() + " / " + scriptFile.getName()`)
updated whenever footage or script is loaded. Color: `#bbbbbb`, font size 10px.

---

## MainController updates

- Add a `countBadge` field (`@FXML Label`) wired to the new badge in the toolbar
- Update it in `onSelectFootage()` and `onSelectScript()`:
  ```java
  countBadge.setText(clips.size() + " clips · " + scenes.size() + " scenes");
  ```
- Add a `pathLabel` field for the right status bar label, update it similarly

---

## Constraints

- Java 21, JavaFX 21 — no new dependencies
- Apply all styling via FXML `style=""` attributes or inline Java `.setStyle()` —
  do NOT rely on `theme.css` loading correctly (it may not be wired up yet)
- Do not modify any parser, matching, transcription, or export classes
- After implementing: `./gradlew compileJava` passes, `./gradlew run` shows
  the dark welcome screen with the logo image

## Done when

- Welcome screen is dark (`#111`) with the PNG logo displayed, serif-style subtitle,
  white primary button, ghost secondary button
- Scene list shows "1. EXT. A CITY STREET - DAY" with no duplicate numbers
- Main toolbar has solid dark buttons, a count badge, and a ghost Export button
- Status bar shows project path on the right
- `./gradlew compileJava` passes with no errors
