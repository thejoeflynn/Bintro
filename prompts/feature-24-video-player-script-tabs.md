# Feature 24 — Video Player Resizability & Script Tab Styling

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

Two UI areas still feel unfinished after the Feature 23 overhaul:

1. The video player is pinned to a fixed 220px height and cannot be resized.
   Editors often want a larger preview — the current implementation adds
   `VideoPlayerPanel` as a fixed-height child of `centerBox` (a `VBox`),
   with `MEDIA_HEIGHT = 220` hardcoded in three places.

2. The `TabPane` ("Scenes" / "Script") and `TitledPane` ("Transcript Log")
   use default JavaFX chrome — system-native tab buttons and a disclosure
   triangle that look inconsistent with the rest of the redesigned UI.

Do not touch any parser, matching, transcription, or export classes.
`mvn compile` must pass cleanly after each change.

---

## Part 1 — Video player: make it resizable

### Current structure (in `MainController.initialize()`)

```
centerBox (VBox)
  └── SplitPane (horizontal, divider 0.55)        ← VBox.vgrow=ALWAYS
        ├── left: footage header + clip table
        └── right: TabPane (Scenes / Script)
  └── TitledPane "Transcript Log"
  └── VideoPlayerPanel                             ← fixed 220px, visible=false
```

### Target structure

Replace the outer `VBox + fixed VideoPlayerPanel` with a **vertical `SplitPane`**
so the user can drag the divider to make the video preview taller or shorter.

```
centerBox (VBox, vgrow=ALWAYS)
  └── SplitPane vertical  (divider at 0.70 initially)
        ├── top: SplitPane horizontal (0.55) + TitledPane in a VBox
        └── bottom: VideoPlayerPanel
```

**Steps:**

1. In `MainView.fxml`, wrap the existing horizontal `SplitPane` and the
   `TitledPane` together in a `VBox` (call it `topHalf`). Then wrap
   `topHalf` and a placeholder `StackPane` (fx:id `videoSlot`) in a new
   vertical `SplitPane` (fx:id `mainVerticalSplit`), dividerPositions `0.70`.
   Set `VBox.vgrow="ALWAYS"` on the vertical `SplitPane`.

2. In `MainController.java`, remove the code that appends `videoPlayer`
   to `centerBox`. Instead, inject `videoSlot` via `@FXML` and on
   `initialize()`, add `videoPlayer` as the sole child of `videoSlot`.

3. The vertical `SplitPane` bottom pane should stay **collapsed** (height ~0)
   when no clip is selected, and **expand** when a row is selected.
   Implement this by saving and restoring the divider position:

   ```java
   private static final double VIDEO_DIVIDER_OPEN = 0.65;

   // When a clip is selected:
   mainVerticalSplit.setDividerPosition(0, VIDEO_DIVIDER_OPEN);
   videoPlayer.setVisible(true);
   videoPlayer.setManaged(true);

   // When selection cleared:
   mainVerticalSplit.setDividerPosition(0, 1.0);
   videoPlayer.setVisible(false);
   videoPlayer.setManaged(false);
   ```

   After calling `setDividerPosition`, call
   `mainVerticalSplit.layout()` to force immediate recalculation.

4. Remove the three hardcoded height constraints from `VideoPlayerPanel`:
   - Delete `private static final double MEDIA_HEIGHT = 220;`
   - Replace `mediaContainer.setPrefHeight(MEDIA_HEIGHT)`,
     `mediaContainer.setMaxHeight(MEDIA_HEIGHT)`,
     `mediaContainer.setMinHeight(MEDIA_HEIGHT)` with:
     ```java
     mediaContainer.setMinHeight(120);
     VBox.setVgrow(mediaContainer, Priority.ALWAYS);
     ```
   - Replace `mediaView.setFitHeight(MEDIA_HEIGHT)` with
     `mediaView.fitHeightProperty().bind(mediaContainer.heightProperty())`.

   This makes `mediaContainer` fill the available space so the video
   scales with the panel as the user drags the divider.

---

## Part 2 — VideoPlayerPanel: modernize controls

`VideoPlayerPanel` has several dated styling choices. Update them in-place
(no logic changes, only style):

### Font
Replace all occurrences of `'Courier New'` with `system-ui, -apple-system, sans-serif`.
This affects `clipNameLabel` and `timecodeLabel`.

### Clip name label
```java
clipNameLabel.setStyle(
    "-fx-font-size: 10px; -fx-text-fill: #888888;" +
    " -fx-padding: 5 12 4 12;" +
    " -fx-font-family: 'system-ui, -apple-system, sans-serif';");
```

### Play/pause button
Replace the Unicode glyph buttons with text labels that read "Play" and "Pause".
Update the constants:
```java
private static final String PLAY_GLYPH  = "Play";
private static final String PAUSE_GLYPH = "Pause";
```
Style the button:
```java
playPauseButton.setStyle(
    "-fx-background-color: #2a2a2a;" +
    " -fx-text-fill: #e8e8e8;" +
    " -fx-font-size: 10px;" +
    " -fx-font-weight: bold;" +
    " -fx-padding: 4 12 4 12;" +
    " -fx-background-radius: 4;" +
    " -fx-cursor: hand;" +
    " -fx-min-width: 52px;");
```

### Timecode label
```java
timecodeLabel.setStyle(
    "-fx-font-family: 'system-ui, -apple-system, sans-serif';" +
    " -fx-font-size: 11px;" +
    " -fx-text-fill: #aaaaaa;" +
    " -fx-min-width: 88px;");
```

### Volume icon
Replace the `🔊` emoji label with a plain text label:
```java
Label volumeIcon = new Label("Vol");
volumeIcon.setStyle("-fx-text-fill: #777777; -fx-font-size: 10px;");
```

### Controls bar
```java
controls.setStyle(
    "-fx-background-color: #141414;" +
    " -fx-padding: 6 12 6 12;" +
    " -fx-spacing: 10;");
```

### Scrub and volume sliders
```java
scrubBar.setStyle("-fx-accent: #cccccc;");
volumeSlider.setStyle("-fx-accent: #cccccc;");
```

---

## Part 3 — TabPane styling

The "Scenes" / "Script" `TabPane` in `MainView.fxml` uses default JavaFX
chrome. Add a `bintro-tab-pane` style class to it in the FXML:

```xml
<TabPane tabClosingPolicy="UNAVAILABLE" styleClass="bintro-tab-pane">
```

Then add these rules to `theme.css`:

```css
/* ── TabPane ─────────────────────────────────────────────── */
.bintro-tab-pane .tab-header-area {
    -fx-padding: 0;
}
.bintro-tab-pane .tab-header-background {
    -fx-background-color: #ffffff;
    -fx-border-color: #e0e0e0;
    -fx-border-width: 0 0 1 0;
}
.bintro-tab-pane .tab {
    -fx-background-color: transparent;
    -fx-background-radius: 0;
    -fx-border-color: transparent;
    -fx-padding: 6 16 6 16;
    -fx-focus-color: transparent;
    -fx-faint-focus-color: transparent;
}
.bintro-tab-pane .tab .tab-label {
    -fx-font-size: 10px;
    -fx-font-weight: bold;
    -fx-text-fill: #aaaaaa;
}
.bintro-tab-pane .tab:selected {
    -fx-background-color: #ffffff;
    -fx-border-color: transparent transparent #1a1a1a transparent;
    -fx-border-width: 0 0 2 0;
}
.bintro-tab-pane .tab:selected .tab-label {
    -fx-text-fill: #1a1a1a;
}
.bintro-tab-pane .tab:hover:!selected .tab-label {
    -fx-text-fill: #555555;
}
/* Hide the default close button area */
.bintro-tab-pane .tab .tab-close-button { -fx-managed: false; -fx-visible: false; }
```

Add dark/navy/forest overrides in their respective theme blocks:

```css
/* Dark */
.theme-dark .bintro-tab-pane .tab-header-background {
    -fx-background-color: #2a2a2c;
    -fx-border-color: #3a3a3c;
}
.theme-dark .bintro-tab-pane .tab:selected {
    -fx-background-color: #2a2a2c;
    -fx-border-color: transparent transparent #e8e8e8 transparent;
}
.theme-dark .bintro-tab-pane .tab .tab-label { -fx-text-fill: #666666; }
.theme-dark .bintro-tab-pane .tab:selected .tab-label { -fx-text-fill: #e8e8e8; }

/* Navy */
.theme-navy .bintro-tab-pane .tab-header-background {
    -fx-background-color: #162540;
    -fx-border-color: #253a5a;
}
.theme-navy .bintro-tab-pane .tab:selected {
    -fx-background-color: #162540;
    -fx-border-color: transparent transparent #4a8fd4 transparent;
}
.theme-navy .bintro-tab-pane .tab .tab-label { -fx-text-fill: #5a7090; }
.theme-navy .bintro-tab-pane .tab:selected .tab-label { -fx-text-fill: #d8e4f4; }

/* Forest */
.theme-forest .bintro-tab-pane .tab-header-background {
    -fx-background-color: #223328;
    -fx-border-color: #304838;
}
.theme-forest .bintro-tab-pane .tab:selected {
    -fx-background-color: #223328;
    -fx-border-color: transparent transparent #4ea860 transparent;
}
.theme-forest .bintro-tab-pane .tab .tab-label { -fx-text-fill: #5a7a60; }
.theme-forest .bintro-tab-pane .tab:selected .tab-label { -fx-text-fill: #d4e8d8; }
```

---

## Part 4 — TitledPane styling

The "Transcript Log" `TitledPane` uses a default disclosure arrow. Add a
`bintro-titled-pane` style class in the FXML:

```xml
<TitledPane text="Transcript Log" expanded="false" animated="true"
            styleClass="bintro-titled-pane">
```

Add to `theme.css`:

```css
/* ── TitledPane ──────────────────────────────────────────── */
.bintro-titled-pane > .title {
    -fx-background-color: #f0f0f0;
    -fx-border-color: #e0e0e0;
    -fx-border-width: 1 0 1 0;
    -fx-padding: 5 12 5 12;
    -fx-cursor: hand;
}
.bintro-titled-pane > .title > .text {
    -fx-font-size: 10px;
    -fx-font-weight: bold;
    -fx-text-fill: #888888;
}
.bintro-titled-pane > .title > .arrow-button {
    -fx-background-color: transparent;
}
.bintro-titled-pane > .title > .arrow-button > .arrow {
    -fx-background-color: #aaaaaa;
}
.bintro-titled-pane > .content {
    -fx-background-color: #fafafa;
    -fx-border-color: #e0e0e0;
    -fx-border-width: 0 0 1 0;
}
```

Dark override:
```css
.theme-dark .bintro-titled-pane > .title {
    -fx-background-color: #222224;
    -fx-border-color: #3a3a3c;
}
.theme-dark .bintro-titled-pane > .title > .text { -fx-text-fill: #666666; }
.theme-dark .bintro-titled-pane > .title > .arrow-button > .arrow {
    -fx-background-color: #666666;
}
.theme-dark .bintro-titled-pane > .content {
    -fx-background-color: #1c1c1e;
    -fx-border-color: #3a3a3c;
}
```

Apply matching overrides for navy and forest following the same pattern.

---

## Constraints

- Java 17, JavaFX 17.0.11, Maven — no new dependencies
- Do not change any parser, matching, transcription, export, or media classes
- `mvn compile` must pass cleanly

## Done when

- The video player panel is absent (bottom half collapsed) when no clip is
  selected, and expands when a row is clicked — user can drag the divider
  to resize it
- Video scales to fill the panel at any height
- Video controls use system-ui font, "Play"/"Pause" text buttons, "Vol" label
- The "Scenes" / "Script" tabs show as an underline-style tab bar (no default
  JavaFX rounded tab chrome)
- The "Transcript Log" TitledPane title bar matches the app's styling
- All four themes apply correctly to the new components
- `mvn compile` passes with no errors
