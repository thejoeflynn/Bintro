# Feature 13 — PDF Page Renderer in Script Viewer

> Paste this into a Claude Code session opened at the Bintro project root.
> Builds on top of features 10, 11, and 12.

---

## Context

The script viewer currently re-parses the screenplay text and renders it as HTML,
which loses formatting. The fix is to render the actual PDF as images using
PDFBox's `PDFRenderer`, displayed in a `ScrollPane` alongside the existing
gutter bars.

For FDX files, the HTML renderer (already built) stays as-is — it only applies
to PDFs.

---

## Task

### 1. Create `PdfPageViewer.java`

Create `src/main/java/com/bintro/ui/PdfPageViewer.java`.

This is a JavaFX `HBox` that displays:
- A **gutter column** on the left (a `VBox` containing gutter bars)
- A **pages column** on the right (a `VBox` of `ImageView`s, one per PDF page)

Both are wrapped in a `ScrollPane` that scrolls them together.

```java
public class PdfPageViewer extends ScrollPane {
    private final VBox gutterColumn;
    private final VBox pagesColumn;

    public PdfPageViewer() { ... }

    /** Renders the PDF file at the given scale (1.5f = 108 DPI, good default). */
    public void loadPdf(File pdfFile, float scale) throws IOException { ... }

    /** Updates gutter bars based on current clip matches. Call after Phase 1. */
    public void updateGutterBars(List<Scene> scenes,
                                 Map<Integer, List<ClipMatchViewModel>> byScene) { ... }

    /** Scrolls to the page that contains the given scene number. */
    public void scrollToScene(int sceneNumber) { ... }
}
```

**`loadPdf()` implementation:**
```java
public void loadPdf(File pdfFile, float scale) throws IOException {
    pagesColumn.getChildren().clear();
    gutterColumn.getChildren().clear();
    pageImages.clear();

    try (PDDocument doc = Loader.loadPDF(pdfFile)) {
        PDFRenderer renderer = new PDFRenderer(doc);
        int pageCount = doc.getNumberOfPages();
        for (int i = 0; i < pageCount; i++) {
            BufferedImage img = renderer.renderImage(i, scale);
            Image fxImage = SwingFXUtils.toFXImage(img, null);
            ImageView iv = new ImageView(fxImage);
            iv.setPreserveRatio(true);
            iv.setFitWidth(img.getWidth());
            // Add 8px gap between pages
            VBox.setMargin(iv, new Insets(0, 0, 8, 0));
            pagesColumn.getChildren().add(iv);
            pageImages.add(iv);
        }
    }
}
```

Store a `List<ImageView> pageImages` and a `Map<Integer, Integer> sceneToPage`
(populated in `updateGutterBars`) for scrolling.

**`updateGutterBars()` implementation:**

Build gutter bars that align vertically with the PDF page images.
Each bar's height and vertical offset are calculated based on which page the
scene appears on and approximately where on that page (estimated by scene index
within that page's scenes).

- Group scenes by page: estimate page number from scene index and total page count
  (`pageNum = (sceneIndex * totalPages) / totalScenes`)
- For each page, stack matched scene bars vertically inside a container that
  matches that page's `ImageView` height
- Each bar is a `Rectangle` (not an HTML div) — JavaFX shapes work better here
  than WebView for pixel-accurate positioning:
  ```java
  Rectangle bar = new Rectangle(10, barHeight);
  bar.setFill(Color.web(clipColors[colorIndex % clipColors.length]));
  bar.setArcWidth(4);
  bar.setArcHeight(4);
  bar.setOpacity(0.8);
  bar.setCursor(Cursor.HAND);
  // Tooltip
  Tooltip.install(bar, new Tooltip(clip.filename()));
  // Click to select
  bar.setOnMouseClicked(e -> onClipSelected.accept(clip.filename()));
  ```
- Store a `Consumer<String> onClipSelected` callback set by `MainController`
  to select the clip in the `TableView`

**`scrollToScene()` implementation:**
Use the `sceneToPage` map to find which `ImageView` to scroll to, then calculate
its vertical position within the `ScrollPane`:
```java
int page = sceneToPage.getOrDefault(sceneNumber, 0);
double targetY = pageImages.subList(0, page).stream()
    .mapToDouble(iv -> iv.getFitHeight() + 8)
    .sum();
double contentHeight = pagesColumn.getBoundsInLocal().getHeight();
setVvalue(targetY / contentHeight);
```

### 2. Update `MainController.java`

The script tab currently shows a `WebView` (from feature 10). Make it source-aware:

- Add a field `private boolean isPdfScript = false`
- Add a field `private PdfPageViewer pdfPageViewer`
- Add a field `private File loadedScriptFile`

In `onSelectScript()`:
- If the file ends with `.pdf`:
  - Set `isPdfScript = true`
  - Replace the "Script" tab content with a new `PdfPageViewer`
  - Call `pdfPageViewer.loadPdf(file, 1.5f)` on a background thread
    (rendering can take a second for long scripts)
  - Show a "Rendering PDF…" status message while loading
- If the file ends with `.fdx`:
  - Set `isPdfScript = false`
  - Use the existing `WebView` + `ScriptRenderer` path (no change)

After Phase 1 completes, in `refreshScriptView()`:
- If `isPdfScript`: call `pdfPageViewer.updateGutterBars(scenes, byScene)`
- If FDX: call the existing `ScriptRenderer.render(...)` path

When a `TableView` row is selected:
- If `isPdfScript`: call `pdfPageViewer.scrollToScene(sceneNumber)`
- If FDX: call the existing WebView `scrollIntoView` script

Set the click callback:
```java
pdfPageViewer.setOnClipSelected(filename -> {
    viewModels.stream()
        .filter(vm -> vm.getFilename().equals(filename))
        .findFirst()
        .ifPresent(vm -> {
            clipTable.getSelectionModel().select(vm);
            clipTable.scrollTo(vm);
        });
});
```

### 3. Add `javafx.swing` interop check

`SwingFXUtils.toFXImage()` requires the `javafx.swing` module.
Check that `build.gradle` already includes `javafx.swing` in the modules list
(it was added in feature 5). If not, add it.

---

## Constraints

- Java 21, JavaFX 21 — no new Gradle dependencies (PDFBox and javafx.swing
  are already on the classpath).
- Create: `ui/PdfPageViewer.java`
- Modify: `ui/MainController.java`
- Do not modify: `ScriptRenderer.java`, `ScriptParser.java`, `Scene.java`,
  or any matching/export classes.
- PDF rendering must happen on a background thread — never on the JavaFX
  application thread (it will freeze the UI on long scripts).
- After implementing: `./gradlew compileJava` passes, `./gradlew run` launches.

## Done when

- Loading a PDF screenplay renders the actual PDF pages as images in the
  script tab — layout, fonts, and formatting exactly as printed.
- Gutter bars appear to the left of the pages, aligned to the approximate
  vertical position of each matched scene.
- Hovering a gutter bar shows a tooltip with the clip filename.
- Clicking a gutter bar selects that clip in the TableView.
- Selecting a clip row in the table scrolls the PDF viewer to that scene's page.
- FDX files continue to use the existing HTML renderer unchanged.
- `./gradlew compileJava` passes with no errors.
