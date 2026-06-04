# Feature 14 — PDF Lazy Rendering & Performance

> Paste this into a Claude Code session opened at the Bintro project root.
> Patches `PdfPageViewer.java` introduced in feature 13.

---

## Context

The PDF script viewer renders all pages upfront at load time, which is slow for
long screenplays. Two fixes:

1. **Lower the default render scale** from `1.5f` to `1.0f`
2. **Lazy rendering** — show placeholder boxes for all pages immediately, then
   render each page's actual image only when it scrolls into the visible viewport

---

## Task

### 1. Rewrite `PdfPageViewer.loadPdf()` for lazy rendering

**Step 1 — Fast first pass (on background thread):**
Open the PDF, get the page count and each page's dimensions (without rendering pixels).
Use `PDDocument.getPage(i).getMediaBox()` to get width/height in points.

For each page, immediately add a placeholder `StackPane` to `pagesColumn`:
- Background: `#f0f0f0` (light gray)
- Size: `pageWidth * scale` × `pageHeight * scale`
- Contains a centered `Label` with text `"Page N"` in muted gray
- 8px bottom margin between pages

This runs fast (no pixel rendering) — the full page list appears instantly.

**Step 2 — Viewport-driven rendering:**
Add a `ChangeListener` to the `ScrollPane`'s `vvalueProperty()` that fires
whenever the user scrolls. On each scroll event:
- Calculate which page placeholders are currently visible in the viewport
- For any visible page that hasn't been rendered yet, submit a render job
  to a background `ExecutorService` (single-threaded, so renders queue up
  in scroll order rather than all firing at once)
- When a page finishes rendering, swap its placeholder content with the
  real `ImageView` on the JavaFX thread via `Platform.runLater()`

Also trigger an initial viewport check immediately after the placeholder
pass completes, so the first visible pages render right away without
requiring a scroll event.

**Visible page detection:**
```java
private Set<Integer> visiblePageIndices() {
    double vpTop = getVvalue() * (pagesColumn.getHeight() - getViewportBounds().getHeight());
    double vpBottom = vpTop + getViewportBounds().getHeight();

    Set<Integer> visible = new HashSet<>();
    double y = 0;
    for (int i = 0; i < pagePlaceholders.size(); i++) {
        double h = pagePlaceholders.get(i).getPrefHeight();
        if (y + h >= vpTop && y <= vpBottom) {
            visible.add(i);
        }
        y += h + 8; // 8px gap
    }
    return visible;
}
```

Pre-render 1 page above and below the visible range (buffer) to reduce
visible pop-in when scrolling slowly.

### 2. Render scale

Change the default scale from `1.5f` to `1.0f` everywhere it appears.
Add a constant at the top of `PdfPageViewer`:
```java
private static final float RENDER_SCALE = 1.0f;
```

Use `RENDER_SCALE` everywhere instead of a hardcoded float, so it's easy
to adjust later.

### 3. Background thread management

Replace any ad-hoc thread usage with a proper `ExecutorService`:
```java
private final ExecutorService renderPool =
    Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-render");
        t.setDaemon(true);
        return t;
    });
```

Cancel in-flight renders when a new PDF is loaded:
```java
public void loadPdf(File pdfFile) throws IOException {
    renderPool.shutdownNow(); // cancel any in-flight renders
    // re-create the pool
    ...
}
```

Track which pages have been rendered in a `Set<Integer> renderedPages`
so scroll events don't re-submit already-rendered pages.

### 4. Keep the PDDocument open during lazy rendering

Currently the PDF is opened, all pages rendered, then closed. For lazy rendering
the document needs to stay open. Change `loadPdf()` to:
- Close any previously open `PDDocument` first
- Open the new one and store it as a field: `private PDDocument openDoc`
- Render pages from `openDoc` on demand
- Close `openDoc` when a new PDF is loaded or the component is torn down

Add a `public void dispose()` method that closes `openDoc` and shuts down
`renderPool`. Call `dispose()` from `MainController` when the app closes
(use `stage.setOnCloseRequest()`).

---

## Constraints

- Java 21, JavaFX 21 — no new dependencies.
- Modify only: `ui/PdfPageViewer.java` and `ui/MainController.java`
  (for `dispose()` call on close).
- All rendering must happen off the JavaFX thread. Only UI updates
  (`Platform.runLater`) touch the JavaFX thread.
- After implementing: `./gradlew compileJava` passes, `./gradlew run` launches.
- The PDF viewer must feel instant on load — placeholders appear immediately,
  first visible pages render within 1–2 seconds.

## Done when

- Loading a long PDF (100+ pages) shows all page placeholders instantly.
- The first visible pages render within ~1 second of loading.
- Scrolling renders new pages smoothly as they come into view.
- Previously rendered pages are not re-rendered on scroll.
- Gutter bars continue to work as before.
- `./gradlew compileJava` passes with no errors.
