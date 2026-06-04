# Feature 15 — Pixel-Accurate Gutter Bar Positioning

> Paste this into a Claude Code session opened at the Bintro project root.
> Builds on features 13 and 14 (PdfPageViewer with lazy rendering).

---

## Context

Gutter bars currently use a rough estimate to position themselves alongside
PDF pages. This is fundamentally inaccurate because scenes vary wildly in
length. The fix is to use PDFBox's position-aware text extraction to find
the exact page and Y coordinate of every scene heading and every line of
dialogue — then use those real coordinates to draw bars that align precisely
with the footage coverage.

---

## Core concept

PDFBox's `PDFTextStripper` can be subclassed to intercept every text chunk
as it's extracted, along with its exact X/Y position on the page. We use
this during script loading to build a position index:

```
ScenePositionIndex:
  sceneNumber → {
    page:        int         (0-based page index)
    headingY:    float       (Y coordinate of scene heading on that page, 0=top)
    lines: [
      { text: String, page: int, y: float }
      ...
    ]
  }
```

After Phase 1 matching, for each clip we know:
- Which scene it matched
- Its transcript text

We use the subsequence span algorithm (already written) to find which lines
in `ScenePositionIndex` the transcript covers — giving us a precise
`startPage, startY, endPage, endY` for the gutter bar.

---

## Task

### 1. Create `PositionAwareTextStripper.java`

Create `src/main/java/com/bintro/parser/PositionAwareTextStripper.java`.

Extend `PDFTextStripper` and override `writeString(String text, List<TextPosition> positions)`.
For each string written, capture:
- The current page number (`getCurrentPageNo() - 1` for 0-based)
- The Y position of the first `TextPosition` in the list
  (`positions.get(0).getYDirAdj()` — this gives Y from the top of the page)
- The full text string

Store all captured chunks in a `List<TextChunk>`:
```java
public record TextChunk(int page, float y, float x, String text) {}
```

After extraction is complete, expose `List<TextChunk> getChunks()`.

Suppress the normal text output (override `writeString` without calling super,
or redirect output to a `NullWriter`) — we only want the position data, not
the extracted text string.

---

### 2. Create `ScenePositionIndex.java`

Create `src/main/java/com/bintro/parser/ScenePositionIndex.java`.

```java
public class ScenePositionIndex {

    public record LinePosition(String text, int page, float y) {}

    public record ScenePosition(
        int sceneNumber,
        int headingPage,
        float headingY,
        List<LinePosition> lines   // all text lines in this scene, in order
    ) {}

    private final Map<Integer, ScenePosition> index = new LinkedHashMap<>();

    /**
     * Builds the index from a PDF file and the already-parsed list of scenes.
     * Uses PositionAwareTextStripper to get real coordinates, then matches
     * text chunks to scene headings using the slugline pattern.
     */
    public static ScenePositionIndex build(File pdfFile, List<Scene> scenes)
            throws IOException { ... }

    public ScenePosition get(int sceneNumber) { ... }

    /**
     * Given a transcript string and a scene number, returns the Y range
     * (startPage, startY, endPage, endY) that the transcript covers within
     * that scene's lines. Uses subsequence word matching.
     * Returns null if fewer than 30% of transcript words match.
     */
    public float[] findTranscriptSpan(int sceneNumber, String transcript) { ... }
}
```

**`build()` implementation:**

1. Run `PositionAwareTextStripper` over the PDF → get `List<TextChunk>`
2. Group chunks into lines: chunks within 2pt of the same Y on the same page
   are the same line (concatenate their text with a space)
3. Walk through lines looking for slugline matches (reuse the existing
   `SLUGLINE` pattern from `ScriptParser`) to find scene heading positions
4. Assign all lines between scene N's heading and scene N+1's heading
   to scene N's `lines` list
5. Match detected scenes to the parsed `List<Scene>` by comparing heading
   text (case-insensitive, strip whitespace) to get the correct `sceneNumber`

**`findTranscriptSpan()` implementation:**

1. Tokenize transcript into words (lowercase, strip punctuation)
2. Walk through `ScenePosition.lines()` in order, tokenizing each line
3. Subsequence match: for each transcript word, find its next occurrence
   in the line sequence after the previous match
4. Track `firstMatchLine` and `lastMatchLine`
5. Return `float[] { firstMatchLine.page(), firstMatchLine.y(),
                      lastMatchLine.page(), lastMatchLine.y() }`

---

### 3. Update `PdfPageViewer.java`

Add a field `private ScenePositionIndex positionIndex`.

Add a method:
```java
public void setPositionIndex(ScenePositionIndex index) {
    this.positionIndex = index;
}
```

**Rewrite `updateGutterBars()`:**

For each clip match (`ClipMatchViewModel`):
1. Get the scene number and transcript from the view model
2. Call `positionIndex.findTranscriptSpan(sceneNumber, transcript)`
   → `float[] span = { startPage, startY, endPage, endY }`
3. If span is null, fall back to the full scene span:
   `ScenePosition pos = positionIndex.get(sceneNumber)`
   → use `{ pos.headingPage(), pos.headingY(), lastLineOfScene.page(), lastLineOfScene.y() }`

**Converting Y coordinates to pixel positions:**

Each page is rendered as an image at `RENDER_SCALE`. The PDF page dimensions
in points are known from `PDPage.getMediaBox()`. Convert Y coordinate to
pixel offset:

```java
float pageHeightPt = pageHeightPoints[pageIndex];
float pageHeightPx = pageHeightPt * RENDER_SCALE;
float yFraction = yCoordinate / pageHeightPt;
float yPixel = yFraction * pageHeightPx;
```

**Drawing the gutter bar:**

The gutter bar is a JavaFX `Rectangle` placed in the gutter column.
Its position and height are set using the page image layout:

```java
// Calculate absolute Y offset from top of all pages combined
double absoluteStartY = sumOfPageHeights(0, startPage) + startYPixel;
double absoluteEndY   = sumOfPageHeights(0, endPage)   + endYPixel;
double barHeight = Math.max(absoluteEndY - absoluteStartY, 8); // min 8px

Rectangle bar = new Rectangle(10, barHeight);
bar.setFill(Color.web(color));
bar.setArcWidth(4);
bar.setArcHeight(4);
bar.setOpacity(0.8);
bar.setCursor(Cursor.HAND);
Tooltip.install(bar, new Tooltip(clipFilename + "\n" + formatDuration(clip.duration())));
bar.setOnMouseClicked(e -> onClipSelected.accept(clipFilename));

// Position bar absolutely within the gutter VBox using a StackPane + margins
StackPane barWrapper = new StackPane(bar);
StackPane.setAlignment(bar, Pos.TOP_LEFT);
VBox.setMargin(barWrapper, new Insets(absoluteStartY, 0, 0, colorColumnOffset));
```

Place each clip's bar in its own column within the gutter (10px wide, 2px gap)
by tracking `colorColumnOffset` per scene.

---

### 4. Update `MainController.java`

After loading the script file (in `onSelectScript()`), if it's a PDF:
```java
Task<ScenePositionIndex> indexTask = new Task<>() {
    protected ScenePositionIndex call() throws Exception {
        return ScenePositionIndex.build(scriptFile, scenes);
    }
};
indexTask.setOnSucceeded(e -> {
    pdfPageViewer.setPositionIndex(indexTask.getValue());
    statusLabel.setText("Script indexed.");
});
indexTask.setOnFailed(e -> {
    System.err.println("Position indexing failed: " + indexTask.getException().getMessage());
    // App still works — gutter bars fall back to full-scene height
});
new Thread(indexTask, "pdf-indexer").start();
```

After Phase 1 completes, pass transcripts through to the view models so
`PdfPageViewer` can access them for span detection:
- Ensure `ClipMatchViewModel` stores the transcript string
- Pass `viewModels` to `pdfPageViewer.updateGutterBars(scenes, byScene)`

---

## Constraints

- Java 21, JavaFX 21 — no new Gradle dependencies (PDFBox already on classpath).
- Create: `parser/PositionAwareTextStripper.java`, `parser/ScenePositionIndex.java`
- Modify: `ui/PdfPageViewer.java`, `ui/MainController.java`,
  `ui/ClipMatchViewModel.java` (add transcript field if missing)
- Do not modify: `ScriptParser.java`, `Scene.java`, `ScriptRenderer.java`,
  or any matching/export classes.
- Position indexing runs on a background thread — never blocks the UI.
- If indexing fails for any reason, gutter bars fall back gracefully to
  full-scene height rather than crashing.
- After implementing: `./gradlew compileJava` passes, `./gradlew run` launches.

## Done when

- Gutter bars start at the exact line in the PDF where the clip's transcript
  begins and end at the exact line where it ends.
- Bars are visually flush with the rendered PDF page images — no offset or gap.
- Multiple clips on the same scene stack as side-by-side bar columns.
- Hover tooltip shows clip filename and duration.
- Clicking a bar selects that clip in the TableView.
- If no transcript span is found, the bar covers the full scene height (graceful fallback).
- `./gradlew compileJava` passes with no errors.
