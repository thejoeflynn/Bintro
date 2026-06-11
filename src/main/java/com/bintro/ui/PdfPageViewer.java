package com.bintro.ui;

import com.bintro.matching.MatchType;
import com.bintro.parser.Scene;
import com.bintro.parser.ScenePositionIndex;
import com.bintro.parser.ScenePositionIndex.LinePosition;
import com.bintro.parser.ScenePositionIndex.ScenePosition;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Script-tab viewer for PDF screenplays. Renders PDF pages as raster images
 * in a vertical scroll, with a parallel gutter column of clip-bar indicators
 * aligned to each scene's approximate position on its page.
 *
 * <h2>Lazy rendering</h2>
 * Loading a long PDF used to render every page upfront, which was slow. The
 * viewer now does a fast first pass that adds a light-gray placeholder for
 * each page (sized from the PDF's MediaBox so the scroll-extent is correct),
 * then renders actual page images on demand as they enter the visible
 * viewport. Renders run on a single-threaded {@code ExecutorService} so
 * scroll-driven submissions queue in order rather than thrashing.
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>{@link #loadPdf(File)} may be called from a background thread; it
 *       opens the {@link PDDocument} synchronously then marshals all UI
 *       updates back to the FX thread.</li>
 *   <li>{@link PDFRenderer#renderImage} calls run on the {@link #renderPool}
 *       background thread.</li>
 *   <li>All other public methods must be called on the FX application
 *       thread.</li>
 * </ul>
 *
 * <p>Call {@link #dispose()} when the host stage closes to shut down the
 * render pool and release the open {@code PDDocument}.
 */
public class PdfPageViewer extends ScrollPane {

    /** Render scale relative to the PDF's native 72 DPI. 1.0 ≈ on-screen 1:1. */
    private static final float RENDER_SCALE = 1.0f;
    private static final int PAGE_GAP = 8;
    private static final int BAR_WIDTH = 10;
    private static final int BAR_COL_GAP = 2;
    private static final double BAR_OPACITY = 0.8;
    /** Pages above/below the visible range to pre-render. */
    private static final int VIEWPORT_BUFFER_PAGES = 2;

    private static final Color PLACEHOLDER_BG = Color.web("#f0f0f0");
    private static final Color PLACEHOLDER_LABEL = Color.web("#888888");

    /** Bar palette mirrored from theme.css's `-clip-N` custom properties. */
    private static final String[] CLIP_COLORS = {
        "#4a90d9", "#e07b54", "#5aaa6a", "#b07dd4", "#c8a830", "#4abcbc"
    };
    private static final String VISUAL_COLOR = "#c8a830";

    private final VBox gutterColumn = new VBox();
    private final VBox pagesColumn = new VBox();

    /** Page placeholders in page order; ImageView gets appended to the
     *  StackPane on render so the placeholder background shows through any
     *  layout gaps. */
    private final List<StackPane> pagePlaceholders = new ArrayList<>();
    /** Rendered heights in page order (placeholder size; ImageView matches). */
    private final List<Double> pageHeights = new ArrayList<>();
    /** PDF native page heights in points — needed to convert
     *  {@link ScenePositionIndex} Y coordinates into pixel positions. */
    private final List<Double> pageHeightsPoints = new ArrayList<>();
    /** Per-page Pane in the gutter column; index matches page index. */
    private final List<Pane> gutterPagePanes = new ArrayList<>();
    /** Scene number → page index, populated by {@link #updateGutterBars}. */
    private final Map<Integer, Integer> sceneToPage = new HashMap<>();
    /** Pages whose actual image has been attached to the placeholder. */
    private final Set<Integer> renderedPages = new HashSet<>();
    /** Pages submitted to {@link #renderPool} (rendered or in-flight). */
    private final Set<Integer> submittedPages = new HashSet<>();

    private Consumer<String> onClipSelected = filename -> { };

    /** Pixel-accurate scene/line positions; null until indexing completes
     *  (or if indexing failed — bars fall back to the heuristic). */
    private volatile ScenePositionIndex positionIndex;

    /** Held open across renders so {@link PDFRenderer#renderImage} works on demand. */
    private volatile PDDocument openDoc;
    private volatile PDFRenderer renderer;
    private ExecutorService renderPool = newRenderPool();

    public PdfPageViewer() {
        gutterColumn.setSpacing(PAGE_GAP);
        gutterColumn.setMinWidth(BAR_WIDTH + BAR_COL_GAP);
        pagesColumn.setSpacing(0);

        HBox content = new HBox(8, gutterColumn, pagesColumn);
        content.setPadding(new Insets(8));
        HBox.setHgrow(pagesColumn, Priority.ALWAYS);

        setContent(content);
        setFitToWidth(false);
        setPannable(true);
        getStyleClass().add("pdf-page-viewer");

        // Lazy-render triggers: any scroll or viewport-size change checks for
        // newly-visible placeholders that need rendering.
        vvalueProperty().addListener((obs, oldV, newV) -> checkVisibleAndQueue());
        viewportBoundsProperty().addListener((obs, oldB, newB) -> checkVisibleAndQueue());
    }

    private static ExecutorService newRenderPool() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pdf-render");
            t.setDaemon(true);
            return t;
        });
    }

    /** Click-callback receives the bar's clip filename. Defaults to no-op. */
    public void setOnClipSelected(Consumer<String> callback) {
        this.onClipSelected = callback == null ? f -> { } : callback;
    }

    /**
     * Opens the PDF and adds page placeholders. Actual page rendering happens
     * lazily as pages scroll into view. May be called from a background
     * thread.
     */
    public synchronized void loadPdf(File pdfFile) throws IOException {
        // Cancel any in-flight renders from a previous PDF and re-create the
        // pool so future submissions aren't rejected.
        renderPool.shutdownNow();
        renderPool = newRenderPool();

        // Close the previous document (if any).
        closeOpenDoc();

        // Reset UI state on the FX thread (synchronously w.r.t. ordering —
        // subsequent runLater calls observe the cleared state).
        Platform.runLater(() -> {
            clearAllUi();
            setVvalue(0);
        });

        // Open the new document and capture per-page dimensions on this thread.
        PDDocument doc = Loader.loadPDF(pdfFile);
        this.openDoc = doc;
        this.renderer = new PDFRenderer(doc);

        int pageCount = doc.getNumberOfPages();
        // dims columns: [widthPx, heightPx, heightPt].
        double[][] dims = new double[pageCount][3];
        for (int i = 0; i < pageCount; i++) {
            var mb = doc.getPage(i).getMediaBox();
            dims[i][0] = mb.getWidth() * RENDER_SCALE;
            dims[i][1] = mb.getHeight() * RENDER_SCALE;
            dims[i][2] = mb.getHeight();
        }

        // Build placeholders on the FX thread, then trigger the initial
        // viewport check so first-visible pages render right away.
        Platform.runLater(() -> {
            for (int i = 0; i < dims.length; i++) {
                addPlaceholder(i, dims[i][0], dims[i][1], dims[i][2]);
            }
            // Force a layout pass, then defer the first viewport check one
            // more cycle so it sees the post-layout viewport bounds. Without
            // this, hosting inside a SplitPane delivers the initial
            // viewportBounds change before the document is loaded and the
            // first visible pages never get queued.
            requestLayout();
            Platform.runLater(this::checkVisibleAndQueue);
        });
    }

    /**
     * Backwards-compatible overload. The {@code scale} argument is ignored —
     * the viewer now uses {@link #RENDER_SCALE} uniformly.
     */
    public void loadPdf(File pdfFile, float ignored) throws IOException {
        loadPdf(pdfFile);
    }

    /** Should only be called from FX thread. */
    private void clearAllUi() {
        pagesColumn.getChildren().clear();
        gutterColumn.getChildren().clear();
        pagePlaceholders.clear();
        pageHeights.clear();
        pageHeightsPoints.clear();
        gutterPagePanes.clear();
        sceneToPage.clear();
        renderedPages.clear();
        submittedPages.clear();
        positionIndex = null;
    }

    /** Adds a single page's placeholder (and matching gutter pane) on the FX thread. */
    private void addPlaceholder(int idx, double w, double h, double heightPt) {
        StackPane placeholder = new StackPane();
        placeholder.setBackground(new Background(
            new BackgroundFill(PLACEHOLDER_BG, CornerRadii.EMPTY, Insets.EMPTY)));
        placeholder.setPrefSize(w, h);
        placeholder.setMinSize(w, h);
        placeholder.setMaxSize(w, h);

        Label label = new Label("Page " + (idx + 1));
        label.setTextFill(PLACEHOLDER_LABEL);
        placeholder.getChildren().add(label);

        VBox.setMargin(placeholder, new Insets(0, 0, PAGE_GAP, 0));
        pagesColumn.getChildren().add(placeholder);
        pagePlaceholders.add(placeholder);
        pageHeights.add(h);
        pageHeightsPoints.add(heightPt);

        // Parallel gutter Pane sized to match the page so absolute bar
        // positions translate directly into vertical offsets.
        Pane gutterPane = new Pane();
        gutterPane.setMinHeight(h);
        gutterPane.setPrefHeight(h);
        gutterPane.setMaxHeight(h);
        gutterPane.setMinWidth(BAR_WIDTH);
        gutterColumn.getChildren().add(gutterPane);
        gutterPagePanes.add(gutterPane);
    }

    /**
     * Walks placeholders, finds which are intersecting the viewport (plus
     * {@link #VIEWPORT_BUFFER_PAGES} on each side), and submits any that
     * haven't been rendered or submitted yet.
     */
    private void checkVisibleAndQueue() {
        // openDoc can be nulled by dispose() racing a queued check.
        if (openDoc == null || renderer == null || pagePlaceholders.isEmpty()) {
            return;
        }
        Set<Integer> visible = visiblePageIndices();
        Set<Integer> toRender = new HashSet<>();
        for (int v : visible) {
            for (int k = -VIEWPORT_BUFFER_PAGES; k <= VIEWPORT_BUFFER_PAGES; k++) {
                int idx = v + k;
                if (idx >= 0 && idx < pagePlaceholders.size()) {
                    toRender.add(idx);
                }
            }
        }
        for (int idx : toRender) {
            if (renderedPages.contains(idx) || submittedPages.contains(idx)) {
                continue;
            }
            submittedPages.add(idx);
            submitRender(idx);
        }
    }

    private Set<Integer> visiblePageIndices() {
        double vpHeight = getViewportBounds() == null ? 0 : getViewportBounds().getHeight();
        double contentHeight = pagesColumn.getHeight();
        double scrollable = Math.max(0.0, contentHeight - vpHeight);
        double vpTop = getVvalue() * scrollable;
        double vpBottom = vpTop + vpHeight;
        Set<Integer> visible = new HashSet<>();
        double y = 0;
        for (int i = 0; i < pagePlaceholders.size(); i++) {
            double h = pageHeights.get(i);
            if (y + h >= vpTop && y <= vpBottom) {
                visible.add(i);
            }
            y += h + PAGE_GAP;
        }
        return visible;
    }

    private void submitRender(int pageIdx) {
        ExecutorService pool = this.renderPool;
        PDFRenderer r = this.renderer;
        if (pool == null || r == null || pool.isShutdown()) {
            submittedPages.remove(pageIdx);
            return;
        }
        try {
            pool.submit(() -> renderPage(pageIdx, r));
        } catch (RejectedExecutionException e) {
            // Pool was shut down between check and submit; let the page be
            // re-queued on the next scroll event.
            submittedPages.remove(pageIdx);
        }
    }

    /** Runs on {@link #renderPool}. */
    private void renderPage(int pageIdx, PDFRenderer r) {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            BufferedImage img = r.renderImage(pageIdx, RENDER_SCALE);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            Image fxImage = SwingFXUtils.toFXImage(img, null);
            Platform.runLater(() -> attachPageImage(pageIdx, fxImage));
        } catch (Exception e) {
            // Most likely interrupted because a new PDF was loaded, or the
            // document was closed under us. Allow a re-queue on next scroll.
            Platform.runLater(() -> submittedPages.remove(pageIdx));
            if (!(e instanceof InterruptedException)) {
                System.err.println("PdfPageViewer: render failed for page "
                    + pageIdx + ": " + e);
            }
        }
    }

    /** FX-thread continuation of {@link #renderPage}. */
    private void attachPageImage(int pageIdx, Image image) {
        if (pageIdx < 0 || pageIdx >= pagePlaceholders.size()) {
            return;
        }
        if (renderedPages.contains(pageIdx)) {
            return;
        }
        StackPane placeholder = pagePlaceholders.get(pageIdx);
        ImageView iv = new ImageView(image);
        iv.setPreserveRatio(true);
        iv.setFitWidth(placeholder.getPrefWidth());
        placeholder.getChildren().add(iv);
        renderedPages.add(pageIdx);
    }

    /**
     * Hands the viewer a fresh {@link ScenePositionIndex} produced off the FX
     * thread; pass {@code null} to revert to heuristic bar placement.
     */
    public void setPositionIndex(ScenePositionIndex index) {
        this.positionIndex = index;
    }

    /**
     * Rebuilds the gutter bars from the current clip-match state.
     *
     * <p>When a {@link ScenePositionIndex} has been supplied via
     * {@link #setPositionIndex}, bars use pixel-accurate page+Y ranges from
     * the index — dialogue bars are sized to exactly the lines the clip's
     * transcript covers, and visual clips span the heading-to-last-line
     * extent of the scene. Bars that span multiple pages are decomposed into
     * one Rectangle segment per intersected page so the existing per-page
     * gutter Pane layout still works.
     *
     * <p>When no index is available, falls back to the original uniform
     * {@code pageNum = (sceneIndex * totalPages) / totalScenes} heuristic.
     */
    public void updateGutterBars(List<Scene> scenes,
                                 Map<Integer, List<ClipMatchViewModel>> byScene) {
        if (scenes == null) {
            scenes = List.of();
        }
        if (byScene == null) {
            byScene = Map.of();
        }
        sceneToPage.clear();
        for (Pane p : gutterPagePanes) {
            p.getChildren().clear();
        }
        if (pagePlaceholders.isEmpty() || scenes.isEmpty()) {
            return;
        }

        // Pre-compute scene → page (used by scrollToScene). Indexed scenes get
        // the real heading page; un-indexed scenes use the uniform heuristic.
        int totalPages = pagePlaceholders.size();
        int totalScenes = scenes.size();
        for (int si = 0; si < scenes.size(); si++) {
            Scene scene = scenes.get(si);
            int pageNum = uniformPageFor(si, totalScenes, totalPages);
            if (positionIndex != null) {
                ScenePosition pos = positionIndex.get(scene.sceneNumber());
                if (pos != null && pos.headingPage() >= 0 && pos.headingPage() < totalPages) {
                    pageNum = pos.headingPage();
                }
            }
            sceneToPage.put(scene.sceneNumber(), pageNum);
        }

        for (Scene scene : scenes) {
            List<ClipMatchViewModel> sceneClips = byScene.getOrDefault(
                scene.sceneNumber(), List.of());
            if (sceneClips.isEmpty()) {
                continue;
            }
            for (int clipIdx = 0; clipIdx < sceneClips.size(); clipIdx++) {
                ClipMatchViewModel vm = sceneClips.get(clipIdx);
                BarRange range = resolveBarRange(scene, vm, scenes);
                if (range == null) {
                    continue;
                }
                double colX = clipIdx * (BAR_WIDTH + BAR_COL_GAP);
                renderBarSegments(range, colX, vm, clipIdx);
            }
        }
    }

    private int uniformPageFor(int sceneIdx, int totalScenes, int totalPages) {
        if (totalScenes <= 0 || totalPages <= 0) {
            return 0;
        }
        return Math.min(totalPages - 1, (sceneIdx * totalPages) / totalScenes);
    }

    /**
     * Resolves a clip's bar coverage as a page+Y rectangle expressed in
     * <em>pixel</em> coordinates. Tries the position index first (preferred),
     * then falls back to the scene's full extent if span detection failed,
     * and finally to a uniform per-page slot if no index is available.
     */
    private BarRange resolveBarRange(Scene scene, ClipMatchViewModel vm, List<Scene> scenes) {
        if (positionIndex != null) {
            ScenePosition pos = positionIndex.get(scene.sceneNumber());
            if (pos != null && !pos.lines().isEmpty()) {
                if (vm.matchType() != MatchType.VISUAL) {
                    float[] span = positionIndex.findTranscriptSpan(
                        scene.sceneNumber(), vm.transcript());
                    if (span != null) {
                        return barRangeFromPoints((int) span[0], span[1],
                            (int) span[2], span[3]);
                    }
                }
                // Visual clip OR transcript span was rejected → use heading
                // through last-line extent as the full-scene fallback.
                LinePosition last = pos.lines().get(pos.lines().size() - 1);
                return barRangeFromPoints(pos.headingPage(), pos.headingY(),
                    last.page(), last.y());
            }
            // Scene not indexed; fall through to heuristic.
        }
        return heuristicBarRange(scene, scenes);
    }

    /** Converts a PDF-points (page, Y) range into pixel coordinates and
     *  clamps it to known page bounds. */
    private BarRange barRangeFromPoints(int startPage, float startYPt,
                                         int endPage, float endYPt) {
        int sp = clampPage(startPage);
        int ep = clampPage(endPage);
        if (sp > ep) {
            int swap = sp;
            sp = ep;
            ep = swap;
        }
        double startYPx = startYPt * RENDER_SCALE;
        double endYPx = endYPt * RENDER_SCALE;
        // Guarantee a visible minimum height even when transcript span is a
        // single line.
        return new BarRange(sp, startYPx, ep, endYPx);
    }

    private int clampPage(int p) {
        if (p < 0) {
            return 0;
        }
        if (p >= pagePlaceholders.size()) {
            return pagePlaceholders.size() - 1;
        }
        return p;
    }

    /**
     * Heuristic fallback: one bar slot per scene per page, equal slot heights.
     * Used when the position index isn't available or doesn't contain the
     * scene.
     */
    private BarRange heuristicBarRange(Scene scene, List<Scene> scenes) {
        int totalScenes = scenes.size();
        int totalPages = pagePlaceholders.size();
        Integer sceneIdxBoxed = null;
        for (int i = 0; i < scenes.size(); i++) {
            if (scenes.get(i).sceneNumber() == scene.sceneNumber()) {
                sceneIdxBoxed = i;
                break;
            }
        }
        if (sceneIdxBoxed == null) {
            return null;
        }
        int sceneIdx = sceneIdxBoxed;
        int pageIdx = uniformPageFor(sceneIdx, totalScenes, totalPages);

        // Count scenes mapped to the same page to figure out slot count.
        int scenesOnPage = 0;
        int slotIdx = 0;
        for (int i = 0; i < scenes.size(); i++) {
            int p = uniformPageFor(i, totalScenes, totalPages);
            if (p == pageIdx) {
                if (i == sceneIdx) {
                    slotIdx = scenesOnPage;
                }
                scenesOnPage++;
            }
        }
        scenesOnPage = Math.max(1, scenesOnPage);
        double pageHeightPx = pageHeights.get(pageIdx);
        double slotHeight = pageHeightPx / scenesOnPage;
        double yTop = slotIdx * slotHeight;
        return new BarRange(pageIdx, yTop, pageIdx, yTop + slotHeight);
    }

    /**
     * Splits a BarRange into per-page Rectangle segments and attaches them to
     * the corresponding gutter Panes.
     */
    private void renderBarSegments(BarRange range, double colX,
                                   ClipMatchViewModel vm, int clipIdx) {
        for (int pageIdx = range.startPage(); pageIdx <= range.endPage(); pageIdx++) {
            if (pageIdx < 0 || pageIdx >= gutterPagePanes.size()) {
                continue;
            }
            double pageHeightPx = pageHeights.get(pageIdx);
            double segStart = pageIdx == range.startPage() ? range.startYPx() : 0.0;
            double segEnd = pageIdx == range.endPage() ? range.endYPx() : pageHeightPx;
            // Clamp to page bounds; ensure visibility.
            segStart = Math.max(0.0, Math.min(pageHeightPx, segStart));
            segEnd = Math.max(0.0, Math.min(pageHeightPx, segEnd));
            double segHeight = Math.max(8.0, segEnd - segStart);

            Rectangle bar = buildBar(vm, clipIdx, segHeight);
            bar.setLayoutX(colX);
            bar.setLayoutY(segStart);
            gutterPagePanes.get(pageIdx).getChildren().add(bar);
        }
    }

    /** Inclusive page range with pixel Y bounds. */
    private record BarRange(int startPage, double startYPx, int endPage, double endYPx) {
    }

    private Rectangle buildBar(ClipMatchViewModel vm, int clipIdx, double height) {
        Rectangle bar = new Rectangle(BAR_WIDTH, Math.max(1.0, height));
        if (vm.matchType() == MatchType.VISUAL) {
            bar.setFill(Color.TRANSPARENT);
            bar.setStroke(Color.web(VISUAL_COLOR));
            bar.setStrokeWidth(2);
            bar.getStrokeDashArray().setAll(4.0, 3.0);
        } else {
            bar.setFill(Color.web(CLIP_COLORS[clipIdx % CLIP_COLORS.length]));
        }
        bar.setArcWidth(4);
        bar.setArcHeight(4);
        bar.setOpacity(BAR_OPACITY);
        bar.setCursor(Cursor.HAND);
        bar.setOnMouseEntered(e -> bar.setOpacity(1.0));
        bar.setOnMouseExited(e -> bar.setOpacity(BAR_OPACITY));

        String filename = vm.getFilename();
        Tooltip.install(bar, new Tooltip(filename == null ? "" : filename));
        bar.setOnMouseClicked(e -> {
            if (filename != null) {
                onClipSelected.accept(filename);
            }
        });
        return bar;
    }

    /**
     * Scrolls so the top of the target scene's page is at (or near) the top
     * of the visible viewport.
     */
    public void scrollToScene(int sceneNumber) {
        if (pagePlaceholders.isEmpty()) {
            return;
        }
        Integer pageBoxed = sceneToPage.get(sceneNumber);
        int page = pageBoxed == null ? 0 : pageBoxed;
        if (page < 0 || page >= pageHeights.size()) {
            return;
        }

        double targetY = 0;
        for (int i = 0; i < page; i++) {
            targetY += pageHeights.get(i) + PAGE_GAP;
        }
        double contentHeight = pagesColumn.getBoundsInLocal().getHeight();
        double vpHeight = getViewportBounds() == null ? 0 : getViewportBounds().getHeight();
        if (contentHeight <= 0) {
            Platform.runLater(() -> scrollToScene(sceneNumber));
            return;
        }
        double scrollable = Math.max(1.0, contentHeight - vpHeight);
        setVvalue(Math.min(1.0, Math.max(0.0, targetY / scrollable)));
    }

    /**
     * Shuts down the render pool and closes the open document. Call from the
     * host stage's close-request handler.
     */
    public void dispose() {
        renderPool.shutdownNow();
        closeOpenDoc();
    }

    private void closeOpenDoc() {
        PDDocument doc = this.openDoc;
        this.openDoc = null;
        this.renderer = null;
        if (doc != null) {
            try {
                doc.close();
            } catch (IOException ignored) {
                // Best-effort during teardown.
            }
        }
    }
}
