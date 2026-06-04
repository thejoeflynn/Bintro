package com.bintro.ui;

import com.bintro.matching.MatchType;
import com.bintro.parser.Scene;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Script-tab viewer for PDF screenplays. Renders the PDF pages as raster
 * images in a vertical scroll, with a parallel gutter column of clip-bar
 * indicators that align (approximately) with each scene's location on its
 * page.
 *
 * <p>Used as an alternative to the HTML {@link ScriptRenderer} path, which
 * remains the canonical viewer for FDX. PDF rendering loses none of the
 * print formatting because we display the actual page images.
 *
 * <p>Threading: {@link #loadPdf(File, float)} performs the slow
 * {@code PDFRenderer.renderImage} calls on the calling thread (intended to
 * be a background thread) and marshals all JavaFX UI updates back to the
 * application thread via {@link Platform#runLater}. All other public methods
 * must be called on the FX application thread.
 */
public class PdfPageViewer extends ScrollPane {

    private static final int PAGE_GAP = 8;
    private static final int BAR_WIDTH = 10;
    private static final int BAR_COL_GAP = 2;
    private static final double BAR_OPACITY = 0.8;

    /** Bar palette mirrored from theme.css's `-clip-N` custom properties. */
    private static final String[] CLIP_COLORS = {
        "#4a90d9", "#e07b54", "#5aaa6a", "#b07dd4", "#c8a830", "#4abcbc"
    };
    /** Visual-clip outline colour (matches the dashed border in theme/css). */
    private static final String VISUAL_COLOR = "#c8a830";

    private final VBox gutterColumn = new VBox();
    private final VBox pagesColumn = new VBox();

    /** ImageViews in page order. Used by {@link #scrollToScene}. */
    private final List<ImageView> pageImages = new ArrayList<>();
    /** Rendered heights in page order — kept separately because we use
     *  {@code preserveRatio} on the ImageView, so {@code getFitHeight}
     *  returns 0 until layout. */
    private final List<Double> pageHeights = new ArrayList<>();
    /** Per-page Pane in the gutter column; the index matches page index. */
    private final List<Pane> gutterPagePanes = new ArrayList<>();
    /** Scene number → page index, populated by {@link #updateGutterBars}. */
    private final Map<Integer, Integer> sceneToPage = new HashMap<>();

    private Consumer<String> onClipSelected = filename -> { };

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
    }

    /** Click-callback receives the bar's clip filename. Defaults to no-op. */
    public void setOnClipSelected(Consumer<String> callback) {
        this.onClipSelected = callback == null ? f -> { } : callback;
    }

    /**
     * Renders the PDF file at the given scale (1.5f ≈ 108 DPI is a good
     * default). The slow {@code PDFRenderer.renderImage} pass runs on the
     * calling thread; all FX scene-graph mutations are posted to the FX
     * application thread via {@link Platform#runLater}.
     */
    public void loadPdf(File pdfFile, float scale) throws IOException {
        // Clear UI state on the FX thread; do it synchronously to avoid
        // racing with new page additions below.
        Platform.runLater(this::clearPages);

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                BufferedImage img = renderer.renderImage(i, scale);
                // FX nodes must be built on the application thread.
                Platform.runLater(() -> addPage(img));
            }
        }
    }

    private void clearPages() {
        pagesColumn.getChildren().clear();
        gutterColumn.getChildren().clear();
        pageImages.clear();
        pageHeights.clear();
        gutterPagePanes.clear();
        sceneToPage.clear();
    }

    /** FX-thread helper: convert a rendered page to an {@code ImageView}
     *  and append it to the pages column along with a matching gutter Pane. */
    private void addPage(BufferedImage img) {
        Image fxImage = SwingFXUtils.toFXImage(img, null);
        ImageView iv = new ImageView(fxImage);
        iv.setPreserveRatio(true);
        iv.setFitWidth(img.getWidth());
        VBox.setMargin(iv, new Insets(0, 0, PAGE_GAP, 0));
        pagesColumn.getChildren().add(iv);
        pageImages.add(iv);

        double height = img.getHeight();
        pageHeights.add(height);

        // Parallel gutter Pane sized to match the page so absolute bar
        // positions translate directly into vertical offsets.
        Pane gutterPane = new Pane();
        gutterPane.setMinHeight(height);
        gutterPane.setPrefHeight(height);
        gutterPane.setMaxHeight(height);
        gutterPane.setMinWidth(BAR_WIDTH);
        gutterColumn.getChildren().add(gutterPane);
        gutterPagePanes.add(gutterPane);
    }

    /**
     * Rebuilds the gutter bars from the current clip-match state. Uses the
     * spec's uniform-distribution heuristic to assign each scene to a page,
     * then stacks bars within each page based on each scene's per-page index.
     */
    public void updateGutterBars(List<Scene> scenes,
                                 Map<Integer, List<ClipMatchViewModel>> byScene) {
        if (scenes == null) {
            scenes = List.of();
        }
        if (byScene == null) {
            byScene = Map.of();
        }
        // Clear any existing bars from previous runs.
        sceneToPage.clear();
        for (Pane p : gutterPagePanes) {
            p.getChildren().clear();
        }
        if (pageImages.isEmpty() || scenes.isEmpty()) {
            return;
        }

        int totalPages = pageImages.size();
        int totalScenes = scenes.size();

        // Group scenes by their heuristic-mapped page, preserving order.
        Map<Integer, List<Scene>> scenesByPage = new LinkedHashMap<>();
        for (int si = 0; si < scenes.size(); si++) {
            Scene scene = scenes.get(si);
            int pageNum = totalScenes == 0
                ? 0
                : Math.min(totalPages - 1, (si * totalPages) / totalScenes);
            sceneToPage.put(scene.sceneNumber(), pageNum);
            scenesByPage.computeIfAbsent(pageNum, k -> new ArrayList<>()).add(scene);
        }

        for (Map.Entry<Integer, List<Scene>> entry : scenesByPage.entrySet()) {
            int pageIdx = entry.getKey();
            List<Scene> pageScenes = entry.getValue();
            Pane gutterPane = gutterPagePanes.get(pageIdx);
            double pageHeight = pageHeights.get(pageIdx);
            int scenesOnPage = pageScenes.size();
            double slotHeight = pageHeight / Math.max(1, scenesOnPage);

            for (int sceneIdxOnPage = 0; sceneIdxOnPage < scenesOnPage; sceneIdxOnPage++) {
                Scene scene = pageScenes.get(sceneIdxOnPage);
                List<ClipMatchViewModel> sceneClips = byScene.getOrDefault(
                    scene.sceneNumber(), List.of());
                if (sceneClips.isEmpty()) {
                    continue;
                }
                double yTop = sceneIdxOnPage * slotHeight;
                for (int clipIdx = 0; clipIdx < sceneClips.size(); clipIdx++) {
                    ClipMatchViewModel vm = sceneClips.get(clipIdx);
                    Rectangle bar = buildBar(vm, clipIdx, slotHeight);
                    bar.setLayoutX(clipIdx * (BAR_WIDTH + BAR_COL_GAP));
                    bar.setLayoutY(yTop);
                    gutterPane.getChildren().add(bar);
                }
            }
        }
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
        if (pageImages.isEmpty()) {
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
        if (contentHeight <= 0) {
            // Layout hasn't run yet; defer.
            Platform.runLater(() -> scrollToScene(sceneNumber));
            return;
        }
        double scrollable = Math.max(1.0,
            contentHeight - getViewportBounds().getHeight());
        setVvalue(Math.min(1.0, Math.max(0.0, targetY / scrollable)));
    }
}
