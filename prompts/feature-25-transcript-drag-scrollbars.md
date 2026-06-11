# Feature 25 — Transcript Log Fix, PDF Lazy-Render Fix, Scrollbar Styling & Drag-and-Drop

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

Feature 24 restructured the main layout into a vertical `SplitPane`. That change
introduced three regressions and left one aesthetic issue unaddressed:

1. The **Transcript Log** `TitledPane` no longer expands visibly — it is trapped
   inside the "topHalf" `VBox` where the horizontal `SplitPane` consumes all
   available space with `VBox.vgrow=ALWAYS`.
2. The **PDF script viewer** has become slow again — the lazy-render trigger
   (`checkVisibleAndQueue`) misfires in the new layout context.
3. **Scrollbars** throughout the app still use the default JavaFX chrome.

Plus one new feature:
4. Users should be able to **drag and drop** a footage folder or script file onto
   the main window instead of clicking the toolbar buttons.

Do not touch any parser, matching, transcription, export, or media classes.
`mvn compile` must pass cleanly after every change.

---

## Part 1 — Fix the Transcript Log

### Root cause

After Feature 24, `MainView.fxml` has this structure:

```
mainVerticalSplit (SplitPane, vertical)
  ├── topHalf (VBox)
  │     ├── horizontal SplitPane   ← VBox.vgrow=ALWAYS (takes all space)
  │     └── TitledPane "Transcript Log"   ← gets zero height; can't expand
  └── videoSlot (StackPane)
```

The horizontal `SplitPane` grabs every pixel of `topHalf`, so the `TitledPane`
collapses to zero height and its `TextArea` is unreachable.

### Fix

Move the `TitledPane` **out of `topHalf`** and make it a third item in the
vertical `SplitPane`, sandwiched between the top half and the video slot:

```
mainVerticalSplit (SplitPane, vertical, dividerPositions="0.62 0.82")
  ├── topHalf (VBox)
  │     └── horizontal SplitPane   ← VBox.vgrow=ALWAYS
  ├── transcriptPane (TitledPane)  ← second pane; fixed modest height
  └── videoSlot (StackPane)        ← third pane; hidden until clip selected
```

In `MainView.fxml`:

- Remove the `TitledPane` from inside the `topHalf` `VBox`.
- Add it as the second item in `mainVerticalSplit` with
  `SplitPane.resizableWithParent="false"` so it holds its size when the
  top or bottom dividers are dragged.
- Set initial `dividerPositions="0.65 0.85"` on `mainVerticalSplit` (top
  split ≈ 65 %, transcript ≈ 20 %, video ≈ 15 % when visible).

In `MainController.java`:

- When no clip is selected, collapse the **third** divider (index 1) to 1.0
  and hide `videoSlot`. The transcript pane stays at index 1 and remains
  accessible at all times.
- When a clip is selected, set divider index 1 to `VIDEO_DIVIDER_OPEN`
  (e.g. `0.80`) and show `videoSlot`.

Update `appendTranscriptLog` to also auto-expand the `TitledPane` the first
time a transcript entry arrives (so the user sees it without manually clicking):

```java
@FXML private TitledPane transcriptTitledPane;   // wire this up

private void appendTranscriptLog(String entry) {
    Platform.runLater(() -> {
        transcriptLog.appendText(entry);
        transcriptLog.setScrollTop(Double.MAX_VALUE);
        if (!transcriptTitledPane.isExpanded()) {
            transcriptTitledPane.setExpanded(true);
        }
    });
}
```

Add `fx:id="transcriptTitledPane"` to the `TitledPane` element in the FXML
and the corresponding `@FXML` field in `MainController`.

---

## Part 2 — Fix PDF lazy rendering

### Root cause

`PdfPageViewer` triggers `checkVisibleAndQueue()` from two listeners:

```java
vvalueProperty().addListener(...)
viewportBoundsProperty().addListener(...)
```

After Feature 24 placed it inside a vertical `SplitPane`, the initial
`viewportBoundsProperty` change fires before the `PDDocument` is loaded,
so the first batch of pages never gets submitted to the render pool.

### Fix in `PdfPageViewer.java`

1. Add an explicit call to `checkVisibleAndQueue()` at the end of
   `loadPdf()`, after the placeholder `VBox` is fully populated and the
   layout pass is requested. Wrap it in `Platform.runLater` so it runs
   after the FX layout pass completes:

   ```java
   // At the end of loadPdf(), after addAll(pagePlaceholders):
   Platform.runLater(() -> {
       requestLayout();
       Platform.runLater(this::checkVisibleAndQueue);
   });
   ```

   The double `runLater` ensures the layout pass has updated the viewport
   bounds before the first render check runs.

2. Increase `VIEWPORT_BUFFER_PAGES` from `1` to `2` so pages just outside
   the visible area are pre-rendered and scrolling feels instantaneous:

   ```java
   private static final int VIEWPORT_BUFFER_PAGES = 2;
   ```

3. In `checkVisibleAndQueue()`, guard against calling `renderImage` when
   `openDoc` is null (can happen if `dispose()` races with a queued check):

   ```java
   if (openDoc == null || renderer == null) return;
   ```
   Add this as the first line of the method body.

---

## Part 3 — Scrollbar styling

No scroll bar rules exist in `theme.css`. Add them now. The goal is a minimal,
thin scrollbar that stays out of the way — matching the film-tool aesthetic.

```css
/* ── Scrollbars ──────────────────────────────────────────── */
.scroll-bar {
    -fx-background-color: transparent;
    -fx-padding: 0;
}
.scroll-bar .thumb {
    -fx-background-color: #c8c8c8;
    -fx-background-radius: 4px;
    -fx-background-insets: 2px;
}
.scroll-bar .thumb:hover {
    -fx-background-color: #aaaaaa;
}
.scroll-bar .thumb:pressed {
    -fx-background-color: #888888;
}
.scroll-bar .track {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
}
.scroll-bar .track-background {
    -fx-background-color: transparent;
}
/* Arrow buttons — hide them for a minimal look */
.scroll-bar .increment-button,
.scroll-bar .decrement-button {
    -fx-background-color: transparent;
    -fx-padding: 0;
    -fx-pref-width: 0;
    -fx-pref-height: 0;
    -fx-min-width: 0;
    -fx-min-height: 0;
}
.scroll-bar .increment-arrow,
.scroll-bar .decrement-arrow {
    -fx-shape: "";
    -fx-pref-width: 0;
    -fx-pref-height: 0;
}
/* Make horizontal bars thinner */
.scroll-bar:horizontal { -fx-pref-height: 8px; }
.scroll-bar:vertical   { -fx-pref-width:  8px; }
```

Dark theme override:
```css
.theme-dark .scroll-bar .thumb         { -fx-background-color: #484848; }
.theme-dark .scroll-bar .thumb:hover   { -fx-background-color: #606060; }
.theme-dark .scroll-bar .thumb:pressed { -fx-background-color: #808080; }
```

Navy theme override:
```css
.theme-navy .scroll-bar .thumb         { -fx-background-color: #2a4a6a; }
.theme-navy .scroll-bar .thumb:hover   { -fx-background-color: #3a6090; }
.theme-navy .scroll-bar .thumb:pressed { -fx-background-color: #4a80b4; }
```

Forest theme override:
```css
.theme-forest .scroll-bar .thumb         { -fx-background-color: #2a4a30; }
.theme-forest .scroll-bar .thumb:hover   { -fx-background-color: #3a6040; }
.theme-forest .scroll-bar .thumb:pressed { -fx-background-color: #4a8050; }
```

---

## Part 4 — Drag-and-drop for footage folder and script file

Users should be able to drop a **folder** onto the main window to set the
footage source, and drop a **`.pdf` or `.fdx` file** to set the script —
exactly the same effect as clicking the toolbar buttons.

### Implementation in `MainController.java`

Add a `setupDragAndDrop()` helper method and call it from `initialize()`.
Wire the drag handlers to the `BorderPane` root (`mainRoot`):

1. Add `fx:id="mainRoot"` to the `<BorderPane>` in `MainView.fxml` and
   `@FXML private BorderPane mainRoot;` in `MainController`.

2. Implement `setupDragAndDrop()`:

```java
private void setupDragAndDrop() {
    mainRoot.setOnDragOver(event -> {
        Dragboard db = event.getDragboard();
        if (db.hasFiles()) {
            // Accept if any dragged item is a directory (footage)
            // or a .pdf/.fdx file (script).
            boolean acceptable = db.getFiles().stream().anyMatch(f ->
                f.isDirectory()
                || f.getName().toLowerCase().endsWith(".pdf")
                || f.getName().toLowerCase().endsWith(".fdx"));
            if (acceptable) {
                event.acceptTransferModes(TransferMode.COPY);
            }
        }
        event.consume();
    });

    mainRoot.setOnDragEntered(event -> {
        if (event.getDragboard().hasFiles()) {
            mainRoot.setStyle("-fx-border-color: #4a8fd4; -fx-border-width: 2;");
        }
        event.consume();
    });

    mainRoot.setOnDragExited(event -> {
        mainRoot.setStyle("");
        event.consume();
    });

    mainRoot.setOnDragDropped(event -> {
        mainRoot.setStyle("");
        Dragboard db = event.getDragboard();
        boolean success = false;
        if (db.hasFiles()) {
            for (File f : db.getFiles()) {
                String name = f.getName().toLowerCase();
                if (f.isDirectory()) {
                    loadFootageFolder(f);
                    success = true;
                    break;
                } else if (name.endsWith(".pdf") || name.endsWith(".fdx")) {
                    loadScriptFile(f);
                    success = true;
                    break;
                }
            }
        }
        event.setDropCompleted(success);
        event.consume();
    });
}
```

3. Extract the file-loading logic from `onSelectFootage()` and
   `onSelectScript()` into private helpers `loadFootageFolder(File folder)`
   and `loadScriptFile(File file)` respectively. The existing button handlers
   call `showDirectoryChooser()`/`showFileChooser()` then delegate to these
   helpers. This avoids duplicating the scan and parse logic.

   `loadFootageFolder(File folder)` should contain everything currently after
   `File chosen = chooser.showDialog(...)` in `onSelectFootage()`.

   `loadScriptFile(File file)` should contain everything currently after
   `File chosen = chooser.showOpenDialog(...)` in `onSelectScript()`.

### Visual feedback during drag

The `setOnDragEntered` / `setOnDragExited` handlers apply a temporary blue
border (`#4a8fd4`) to signal that the window will accept the drop. The
`mainRoot.setStyle("")` in `setOnDragExited` restores the normal appearance.
Do not add a permanent style attribute to `mainRoot` in the FXML — only apply
it transiently from Java.

---

## Constraints

- Java 17, JavaFX 17.0.11, Maven — no new dependencies
- Do not touch parser, matching, transcription, export, or media classes
- `mvn compile` must pass cleanly

## Done when

- Expanding the "Transcript Log" pane reveals the `TextArea` and it fills
  with transcript entries during a Run; it auto-expands on first entry
- Scrolling through a PDF script is smooth from first load, with no blank
  page flicker at the top
- All scrollbars app-wide are thin (8px) with a minimal thumb and no arrows
- Dropping a folder onto the main window loads it as the footage source
- Dropping a `.pdf` or `.fdx` file onto the main window loads it as the script
- A blue border appears on the window during an active drag, disappears on drop
  or exit
- `mvn compile` passes with no errors
