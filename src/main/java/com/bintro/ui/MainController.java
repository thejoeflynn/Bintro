package com.bintro.ui;

import com.bintro.App;
import com.bintro.export.ClipMatch;
import com.bintro.export.Exporter;
import com.bintro.matching.MatchResult;
import com.bintro.matching.MatchType;
import com.bintro.matching.SceneMatcher;
import com.bintro.matching.VisualMatcher;
import com.bintro.media.Clip;
import com.bintro.media.MediaScanner;
import com.bintro.parser.Scene;
import com.bintro.parser.ScenePositionIndex;
import com.bintro.parser.ScriptParser;
import com.bintro.transcription.Transcriber;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuBar;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.converter.IntegerStringConverter;
import netscape.javascript.JSObject;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Drives the Bintro UI in two distinct phases:
 *
 * <ol>
 *   <li><b>Phase 1 — Analysis (Run button):</b> transcribe each clip, route
 *       it to the dialogue or visual matcher, populate the review table.</li>
 *   <li><b>Phase 2 — Export (Export button):</b> read the (possibly user-corrected)
 *       scene numbers out of the table and copy footage into per-scene folders.</li>
 * </ol>
 *
 * <p>Export is disabled until Phase 1 succeeds, and is disabled again whenever
 * the user re-selects footage or a script (which invalidates Phase 1 results).
 */
public class MainController {

    private static final String MAIN_FXML = "/fxml/MainView.fxml";
    private static final String LOGO_RESOURCE = "/images/bintro-logo.png";
    private static final String VERSION_STRING = "0.1.0 — Early Preview";

    @FXML private MenuBar menuBar;
    @FXML private javafx.scene.control.RadioMenuItem themeLight;
    @FXML private javafx.scene.control.RadioMenuItem themeDark;
    @FXML private javafx.scene.control.RadioMenuItem themeNavy;
    @FXML private javafx.scene.control.RadioMenuItem themeForest;
    @FXML private Button selectFootageButton;
    @FXML private Button selectScriptButton;
    @FXML private Button runButton;
    @FXML private Button exportButton;

    @FXML private TableView<ClipMatchViewModel> clipTable;
    @FXML private TableColumn<ClipMatchViewModel, String> clipCol;
    @FXML private TableColumn<ClipMatchViewModel, String> exportNameCol;
    @FXML private TableColumn<ClipMatchViewModel, String> transcriptCol;
    @FXML private TableColumn<ClipMatchViewModel, MatchType> typeCol;
    @FXML private TableColumn<ClipMatchViewModel, Integer> sceneNumberCol;
    @FXML private TableColumn<ClipMatchViewModel, String> sceneHeadingCol;

    @FXML private ListView<String> sceneList;
    @FXML private Label statusLabel;
    @FXML private Label pathLabel;
    @FXML private Label countBadge;
    @FXML private Label footageCountLabel;
    @FXML private ProgressBar progressBar;
    @FXML private TextArea transcriptLog;
    @FXML private StackPane scriptStack;
    @FXML private WebView scriptWebView;
    @FXML private VBox centerBox;
    @FXML private SplitPane mainVerticalSplit;
    @FXML private StackPane videoSlot;

    /** Divider position when the video preview opens from collapsed. */
    private static final double VIDEO_DIVIDER_OPEN = 0.65;

    /** In-app video preview, hosted in the vertical SplitPane's bottom slot. */
    private VideoPlayerPanel videoPlayer;

    /** Created lazily when the user first opens a PDF script. */
    private PdfPageViewer pdfPageViewer;
    /** True while a PDF script is loaded — drives which viewer the controller talks to. */
    private boolean isPdfScript = false;

    private File footageFolder;
    private File scriptFile;
    private List<Clip> clips = new ArrayList<>();
    private List<Scene> scenes = new ArrayList<>();

    /** Strong reference required — JSObject.setMember holds the bridge weakly. */
    private ScriptViewBridge scriptBridge;

    @FXML
    private void initialize() {
        runButton.setDisable(true);
        exportButton.setDisable(true);
        statusLabel.setText("Ready");
        progressBar.setProgress(0);
        // On macOS this puts the menu bar into the system menu strip; on
        // other platforms JavaFX silently keeps it in-window.
        if (menuBar != null) {
            menuBar.setUseSystemMenuBar(true);
        }
        setupClipTable();
        wireThemeMenu();
        applyThemeToCurrentScene();

        // Scroll the script viewer to the selected row's scene.
        clipTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVm, newVm) -> {
            if (newVm == null || newVm.sceneNumber() <= 0) {
                return;
            }
            scrollScriptTo(newVm.sceneNumber());
        });

        // Video preview lives in the bottom half of the vertical SplitPane.
        // Collapsed (divider pushed to 1.0) until the user picks a row in
        // the clip table; while open the user can drag the divider to
        // resize the preview.
        videoPlayer = new VideoPlayerPanel();
        videoPlayer.setVisible(false);
        videoPlayer.setManaged(false);
        if (videoSlot != null) {
            videoSlot.getChildren().add(videoPlayer);
        }
        if (mainVerticalSplit != null) {
            mainVerticalSplit.setDividerPosition(0, 1.0);
        }
        clipTable.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldVm, newVm) -> {
                if (newVm == null) {
                    videoPlayer.stop();
                    videoPlayer.setVisible(false);
                    videoPlayer.setManaged(false);
                    mainVerticalSplit.setDividerPosition(0, 1.0);
                    mainVerticalSplit.layout();
                    return;
                }
                if (oldVm == null) {
                    // Opening from collapsed — restore the default split.
                    // Re-selecting while already open keeps whatever divider
                    // position the user dragged to.
                    mainVerticalSplit.setDividerPosition(0, VIDEO_DIVIDER_OPEN);
                }
                videoPlayer.setVisible(true);
                videoPlayer.setManaged(true);
                mainVerticalSplit.layout();
                videoPlayer.load(newVm.clip());
            });

        // Re-install the Java-side bridge as `window.bintro` after every page
        // load — loadContent() wipes the JS global, so we re-attach on each
        // SUCCEEDED transition.
        scriptBridge = new ScriptViewBridge(clipTable);
        scriptWebView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldS, newS) -> {
            if (newS == Worker.State.SUCCEEDED) {
                try {
                    JSObject window = (JSObject) scriptWebView.getEngine().executeScript("window");
                    window.setMember("bintro", scriptBridge);
                } catch (Exception e) {
                    System.err.println("MainController: failed to install JS bridge: " + e);
                }
            }
        });

        // When the stage appears, register a close-request hook so we can
        // tear down the lazy-render pool / open PDDocument cleanly.
        clipTable.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                return;
            }
            newScene.windowProperty().addListener((wObs, oldWin, newWin) -> {
                if (newWin instanceof Stage stage) {
                    stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST,
                        ev -> disposeOnClose());
                }
            });
        });
    }

    /** Releases the lazy-renderer's threads / open PDDocument / video player. */
    private void disposeOnClose() {
        if (pdfPageViewer != null) {
            pdfPageViewer.dispose();
        }
        if (videoPlayer != null) {
            videoPlayer.dispose();
        }
    }

    private void setupClipTable() {
        clipCol.setCellValueFactory(c -> c.getValue().filenameProperty());

        transcriptCol.setCellValueFactory(c -> c.getValue().transcriptProperty());
        transcriptCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.length() > 80 ? item.substring(0, 80) + "…" : item);
                }
            }
        });

        typeCol.setCellValueFactory(c -> c.getValue().matchTypeProperty());
        typeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(MatchType item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item == MatchType.VISUAL ? "Visual" : "Dialogue");
                }
            }
        });

        sceneNumberCol.setCellValueFactory(c -> c.getValue().sceneNumberProperty().asObject());
        sceneNumberCol.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        sceneNumberCol.setOnEditCommit(ev -> {
            Integer v = ev.getNewValue();
            ev.getRowValue().sceneNumberProperty().set(v == null ? 0 : v);
            refreshExportNames();
            refreshScriptView();
        });

        // Editable export name. The cell italicises the value until the user
        // edits it; committing an edit flips the customised flag on the VM
        // so subsequent refreshes don't clobber it.
        exportNameCol.setCellValueFactory(c -> c.getValue().exportNameProperty());
        exportNameCol.setCellFactory(col ->
            new TextFieldTableCell<ClipMatchViewModel, String>(
                new javafx.util.converter.DefaultStringConverter()) {
                @Override
                public void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || getTableRow() == null) {
                        setStyle("");
                        return;
                    }
                    ClipMatchViewModel vm = getTableRow().getItem();
                    setStyle(vm != null && vm.isExportNameCustomized()
                        ? ""
                        : "-fx-font-style: italic;");
                }
            });
        exportNameCol.setOnEditCommit(ev -> {
            String newName = ev.getNewValue();
            ev.getRowValue().setExportNameByUser(newName == null ? "" : newName);
        });

        sceneHeadingCol.setCellValueFactory(c -> c.getValue().sceneHeadingProperty());
        sceneHeadingCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    setStyle(ClipMatchViewModel.UNKNOWN.equals(item) ? "-fx-text-fill: red;" : "");
                }
            }
        });
    }

    @FXML
    private void onSelectFootage() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Footage Folder");
        File chosen = chooser.showDialog(selectFootageButton.getScene().getWindow());
        if (chosen == null) {
            return;
        }
        footageFolder = chosen;
        statusLabel.setText("Scanning footage…");
        try {
            clips = new MediaScanner().scanFolder(chosen);
            rebuildClipTableFromScan();
            statusLabel.setText("Loaded " + clips.size() + " clip(s) from " + chosen.getName() + ".");
        } catch (Exception e) {
            statusLabel.setText("Failed to scan footage: " + e.getMessage());
        }
        // Selecting new footage invalidates any previous analysis.
        exportButton.setDisable(true);
        updateRunEnabled();
        updateWindowTitle();
        updateCountsAndPath();
    }

    @FXML
    private void onSelectScript() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Screenplay");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Screenplay", "*.pdf", "*.fdx")
        );
        File chosen = chooser.showOpenDialog(selectScriptButton.getScene().getWindow());
        if (chosen == null) {
            return;
        }
        try {
            scenes = new ScriptParser().parse(chosen);
            scriptFile = chosen;
            sceneList.setItems(FXCollections.observableArrayList(
                scenes.stream().map(s -> s.sceneNumber() + ". " + cleanHeading(s)).toList()
            ));
            // Pick the right viewer for the file format and prime it with no
            // highlights — clips haven't been matched yet.
            boolean pdf = chosen.getName().toLowerCase().endsWith(".pdf");
            isPdfScript = pdf;
            if (pdf) {
                showPdfViewer();
                loadPdfInBackground(chosen);
                buildPositionIndexInBackground(chosen);
            } else {
                showWebView();
                scriptWebView.getEngine().loadContent(
                    ScriptRenderer.render(scenes, Map.of()), "text/html");
            }
            // Rebuild table so new VMs reference the freshly loaded scenes for heading lookup.
            rebuildClipTableFromScan();
            statusLabel.setText("Loaded " + scenes.size() + " scene(s) from " + chosen.getName() + ".");
        } catch (Exception e) {
            statusLabel.setText("Failed to parse script: " + e.getMessage());
        }
        // Selecting a new script invalidates any previous analysis.
        exportButton.setDisable(true);
        updateRunEnabled();
        updateWindowTitle();
        updateCountsAndPath();
    }

    /** Resets the clip table to placeholder VMs (no transcript, scene 0) using current state. */
    private void rebuildClipTableFromScan() {
        clipTable.setItems(FXCollections.observableArrayList(
            clips.stream().map(c -> new ClipMatchViewModel(c, "", 0, scenes)).toList()
        ));
    }

    private void updateRunEnabled() {
        runButton.setDisable(footageFolder == null || scriptFile == null
            || clips.isEmpty() || scenes.isEmpty());
    }

    /** Phase 1: transcribe + match every clip, populate the review table. */
    @FXML
    private void onRun() {
        runButton.setDisable(true);
        exportButton.setDisable(true);
        progressBar.setProgress(0);
        transcriptLog.clear();
        statusLabel.setText("Starting analysis…");

        final List<Clip> clipsSnapshot = List.copyOf(clips);
        final List<Scene> scenesSnapshot = List.copyOf(scenes);

        Task<List<ClipMatchViewModel>> task = new Task<>() {
            @Override
            protected List<ClipMatchViewModel> call() {
                Transcriber transcriber = new Transcriber();
                SceneMatcher dialogueMatcher = new SceneMatcher();
                VisualMatcher visualMatcher = new VisualMatcher();
                List<ClipMatchViewModel> rows = new ArrayList<>();
                int total = clipsSnapshot.size();

                for (int i = 0; i < total; i++) {
                    Clip clip = clipsSnapshot.get(i);
                    int idx = i + 1;
                    publish("Transcribing " + clip.filename() + " (" + idx + " of " + total + ")…",
                        (double) i / total);

                    // 1. Transcribe — failure isn't fatal; we'll route the
                    //    clip through VisualMatcher instead.
                    String transcript = "";
                    boolean transcriptionFailed = false;
                    try {
                        transcript = transcriber.transcribe(clip);
                        appendTranscriptLog("[" + clip.filename() + "]\n" + transcript + "\n\n");
                    } catch (Exception e) {
                        transcriptionFailed = true;
                        publish("Transcription failed for " + clip.filename() + ": " + e.getMessage(),
                            (double) idx / total);
                        appendTranscriptLog("[" + clip.filename() + "] — transcription failed: "
                            + e.getMessage() + "\n\n");
                    }

                    // 2. Choose matcher. Anything with <5 stripped words gets
                    //    the visual path — covers B-roll, plates, insert shots
                    //    and any clip whisper couldn't make sense of.
                    boolean useVisual = transcriptionFailed || !hasUsableTranscript(transcript);
                    MatchType matchType = useVisual ? MatchType.VISUAL : MatchType.DIALOGUE;

                    MatchResult result;
                    try {
                        if (useVisual) {
                            publish("Visually analysing " + clip.filename()
                                    + " (" + idx + " of " + total + ")…",
                                (double) i / total + 0.5 / total);
                            result = visualMatcher.match(clip, scenesSnapshot);
                        } else {
                            publish("Matching " + clip.filename() + " (" + idx + " of " + total + ")…",
                                (double) i / total + 0.5 / total);
                            result = dialogueMatcher.match(clip, transcript, scenesSnapshot);
                        }
                    } catch (Exception e) {
                        publish("Match failed for " + clip.filename() + ": " + e.getMessage(),
                            (double) idx / total);
                        result = new MatchResult(0, 0.0);
                    }

                    rows.add(new ClipMatchViewModel(clip, transcript,
                        result.sceneNumber(), scenesSnapshot, matchType));
                    publish(clip.filename() + " → "
                            + (result.sceneNumber() > 0 ? "Scene " + result.sceneNumber() : "Unmatched")
                            + (useVisual ? " (visual)" : ""),
                        (double) idx / total);
                }
                publish("Analysis complete. Review matches, then click Export.", 1.0);
                return rows;
            }

            private void publish(String status, double progress) {
                Platform.runLater(() -> {
                    statusLabel.setText(status);
                    progressBar.setProgress(progress);
                });
            }
        };

        task.setOnSucceeded(ev -> {
            runButton.setDisable(false);
            List<ClipMatchViewModel> result = task.getValue();
            clipTable.setItems(FXCollections.observableArrayList(result));
            exportButton.setDisable(result.isEmpty());
            refreshExportNames();
            refreshScriptView();
        });
        task.setOnFailed(ev -> {
            runButton.setDisable(false);
            Throwable t = task.getException();
            statusLabel.setText("Analysis failed: " + (t != null ? t.getMessage() : "unknown error"));
            if (t != null) {
                t.printStackTrace();
            }
        });

        Thread worker = new Thread(task, "bintro-analysis");
        worker.setDaemon(true);
        worker.start();
    }

    /** Phase 2: read scene numbers out of the table (user may have edited some) and export. */
    @FXML
    private void onExport() {
        if (clipTable.getItems().isEmpty()) {
            statusLabel.setText("Nothing to export — run analysis first.");
            return;
        }
        if (footageFolder == null) {
            statusLabel.setText("Footage folder is not set.");
            return;
        }

        final List<ClipMatch> matches = clipTable.getItems().stream()
            .map(vm -> new ClipMatch(vm.clip(), vm.sceneNumber(),
                vm.isExportNameCustomized() && vm.getExportName() != null
                    && !vm.getExportName().isBlank()
                    ? vm.getExportName()
                    : null))
            .toList();
        final List<Scene> scenesSnapshot = List.copyOf(scenes);
        final File outputDir = new File(footageFolder.getParentFile(), "Bintro_Output");

        runButton.setDisable(true);
        exportButton.setDisable(true);
        statusLabel.setText("Exporting to " + outputDir.getAbsolutePath() + "…");
        progressBar.setProgress(-1); // indeterminate

        Task<File> task = new Task<>() {
            @Override
            protected File call() throws Exception {
                new Exporter().export(matches, scenesSnapshot, outputDir);
                return outputDir;
            }
        };

        task.setOnSucceeded(ev -> {
            runButton.setDisable(false);
            exportButton.setDisable(false);
            progressBar.setProgress(1.0);
            File out = task.getValue();
            statusLabel.setText("Export complete: " + out.getAbsolutePath());
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Export complete");
            alert.setHeaderText("Export complete");
            alert.setContentText("Output: " + out.getAbsolutePath());
            alert.showAndWait();
        });
        task.setOnFailed(ev -> {
            runButton.setDisable(false);
            exportButton.setDisable(false);
            progressBar.setProgress(0);
            Throwable t = task.getException();
            statusLabel.setText("Export failed: " + (t != null ? t.getMessage() : "unknown error"));
            if (t != null) {
                t.printStackTrace();
            }
        });

        Thread worker = new Thread(task, "bintro-export");
        worker.setDaemon(true);
        worker.start();
    }

    private void appendTranscriptLog(String entry) {
        Platform.runLater(() -> {
            transcriptLog.appendText(entry);
            transcriptLog.setScrollTop(Double.MAX_VALUE);
        });
    }

    /**
     * Walks the clip table in display order and assigns each row the computed
     * export base ({@code 01_001}, {@code Unmatched_001}, …). Rows the user
     * has customised keep their typed name. Re-run after Phase 1 succeeds
     * and after any scene-number edit so the column reflects the current
     * grouping.
     */
    private void refreshExportNames() {
        java.util.Map<Integer, Integer> counterByScene = new java.util.HashMap<>();
        for (ClipMatchViewModel vm : clipTable.getItems()) {
            int sceneNumber = vm.sceneNumber();
            int counter = counterByScene.merge(sceneNumber, 1, Integer::sum);
            // merge returns the new value, so the first call returns 1.
            vm.setExportNameComputed(
                com.bintro.export.Exporter.computeBase(sceneNumber, counter));
        }
        // Nudge the column to repaint italics/normal style for cells whose
        // VM customised flag may have changed via an edit elsewhere.
        clipTable.refresh();
    }

    /**
     * Rebuilds and reloads the script-viewer HTML, grouping the current table's
     * VMs by scene number for gutter-bar placement. Safe to call before a script
     * is loaded — the empty scene list yields an empty document. Skips VMs with
     * no scene assigned.
     */
    private void refreshScriptView() {
        if (scenes == null || scenes.isEmpty()) {
            return;
        }
        Map<Integer, List<ClipMatchViewModel>> byScene = clipTable.getItems().stream()
            .filter(vm -> vm.sceneNumber() > 0)
            .collect(Collectors.groupingBy(ClipMatchViewModel::sceneNumber));
        if (isPdfScript && pdfPageViewer != null) {
            pdfPageViewer.updateGutterBars(scenes, byScene);
        } else {
            scriptWebView.getEngine().loadContent(
                ScriptRenderer.render(scenes, byScene), "text/html");
        }
    }

    /**
     * Lazily creates the PDF viewer (and wires its click-to-select callback)
     * the first time a PDF script is loaded, then makes it the active viewer
     * inside the script-tab StackPane.
     */
    private void showPdfViewer() {
        if (pdfPageViewer == null) {
            pdfPageViewer = new PdfPageViewer();
            pdfPageViewer.setOnClipSelected(filename -> {
                if (filename == null) {
                    return;
                }
                for (ClipMatchViewModel vm : clipTable.getItems()) {
                    if (filename.equals(vm.getFilename())) {
                        clipTable.getSelectionModel().select(vm);
                        clipTable.scrollTo(vm);
                        return;
                    }
                }
            });
            scriptStack.getChildren().add(pdfPageViewer);
        }
        pdfPageViewer.setVisible(true);
        pdfPageViewer.setManaged(true);
        scriptWebView.setVisible(false);
        scriptWebView.setManaged(false);
    }

    private void showWebView() {
        if (pdfPageViewer != null) {
            pdfPageViewer.setVisible(false);
            pdfPageViewer.setManaged(false);
        }
        scriptWebView.setVisible(true);
        scriptWebView.setManaged(true);
    }

    /**
     * Renders the PDF on a background thread to avoid stalling the UI on long
     * scripts. The viewer marshals scene-graph mutations back to the FX thread
     * itself, so this Task only needs to call {@code loadPdf} and report status.
     */
    private void loadPdfInBackground(File pdfFile) {
        statusLabel.setText("Rendering PDF…");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                pdfPageViewer.loadPdf(pdfFile);
                return null;
            }
        };
        task.setOnSucceeded(ev ->
            statusLabel.setText("Loaded " + scenes.size() + " scene(s) from "
                + pdfFile.getName() + "."));
        task.setOnFailed(ev -> {
            Throwable t = task.getException();
            statusLabel.setText("Failed to render PDF: "
                + (t != null ? t.getMessage() : "unknown error"));
            if (t != null) {
                t.printStackTrace();
            }
        });
        Thread worker = new Thread(task, "bintro-pdf-render");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Indexes scene + dialogue positions on a background thread so the gutter
     * bars can use pixel-accurate placement when ready. If indexing fails the
     * viewer just keeps the heuristic placement — the app stays functional.
     */
    private void buildPositionIndexInBackground(File pdfFile) {
        final List<Scene> scenesSnapshot = List.copyOf(scenes);
        Task<ScenePositionIndex> indexTask = new Task<>() {
            @Override
            protected ScenePositionIndex call() throws Exception {
                return ScenePositionIndex.build(pdfFile, scenesSnapshot);
            }
        };
        indexTask.setOnSucceeded(ev -> {
            if (pdfPageViewer != null) {
                pdfPageViewer.setPositionIndex(indexTask.getValue());
                // If the user already ran Phase 1 before indexing finished,
                // re-draw bars now using the fresh positions.
                if (!clipTable.getItems().isEmpty()) {
                    refreshScriptView();
                }
                statusLabel.setText("Script indexed.");
            }
        });
        indexTask.setOnFailed(ev -> {
            Throwable t = indexTask.getException();
            System.err.println("MainController: position indexing failed: "
                + (t != null ? t.getMessage() : "unknown error"));
            if (t != null) {
                t.printStackTrace();
            }
            // Intentionally don't surface this to the user — heuristic
            // gutter placement still works.
        });
        Thread worker = new Thread(indexTask, "pdf-indexer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Refreshes the host {@code Stage}'s title to reflect the currently loaded
     * footage folder + script. No-op until the scene is attached to a window.
     */
    private void updateWindowTitle() {
        if (clipTable == null || clipTable.getScene() == null) {
            return;
        }
        Stage stage = (Stage) clipTable.getScene().getWindow();
        if (stage == null) {
            return;
        }
        StringBuilder title = new StringBuilder("Bintro");
        if (footageFolder != null) {
            title.append(" — ").append(footageFolder.getName());
            if (scriptFile != null) {
                title.append(" / ").append(scriptFile.getName());
            }
        }
        stage.setTitle(title.toString());
    }

    /**
     * Updates the toolbar count badge, the footage-panel count, and the
     * status-bar path label so they reflect whatever footage + script are
     * currently loaded. Empty strings when nothing is loaded yet.
     */
    private void updateCountsAndPath() {
        int clipsN = clips == null ? 0 : clips.size();
        int scenesN = scenes == null ? 0 : scenes.size();
        if (countBadge != null) {
            countBadge.setText(clipsN == 0 && scenesN == 0
                ? ""
                : clipsN + " clips · " + scenesN + " scenes");
        }
        if (footageCountLabel != null) {
            footageCountLabel.setText(clipsN == 0
                ? ""
                : clipsN + (clipsN == 1 ? " clip" : " clips"));
        }
        if (pathLabel != null) {
            StringBuilder p = new StringBuilder();
            if (footageFolder != null) {
                p.append(footageFolder.getName());
                if (scriptFile != null) {
                    p.append(" / ").append(scriptFile.getName());
                }
            }
            pathLabel.setText(p.toString());
        }
    }

    /**
     * Builds the Theme menu's {@link javafx.scene.control.ToggleGroup}
     * programmatically (instead of in FXML via {@code <fx:define>}) because
     * the macOS system-menu adapter chokes on the latter — it sees a
     * Theme submenu it considers empty and suppresses the entire View menu.
     *
     * <p>Each radio item also has a keyboard accelerator (⌘1/⌘2/⌘3/⌘4) so
     * the user can still switch themes even if the menu ever fails to
     * render in the system menu strip.
     */
    private void wireThemeMenu() {
        if (themeLight == null) {
            return;
        }
        javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
        themeLight.setToggleGroup(group);
        themeDark.setToggleGroup(group);
        themeNavy.setToggleGroup(group);
        themeForest.setToggleGroup(group);

        group.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) {
                return;
            }
            if (newT.getUserData() instanceof String themeName) {
                App.setActiveTheme(themeName);
                applyThemeToCurrentScene();
            }
        });

        // Mirror the persisted active theme back into the menu so freshly
        // opened project windows show the correct radio selection.
        javafx.scene.control.RadioMenuItem target = switch (App.getActiveTheme()) {
            case "dark"   -> themeDark;
            case "navy"   -> themeNavy;
            case "forest" -> themeForest;
            default       -> themeLight;
        };
        target.setSelected(true);
    }

    /**
     * Pushes {@link App#getActiveTheme()} onto the scene root. Defers via
     * {@code Platform.runLater} when the scene isn't attached yet (the
     * theme menu fires during {@code initialize()}, before the FXML loader
     * finishes wiring the scene graph).
     */
    private void applyThemeToCurrentScene() {
        if (clipTable == null) {
            return;
        }
        if (clipTable.getScene() == null) {
            Platform.runLater(this::applyThemeToCurrentScene);
            return;
        }
        App.applyActiveTheme(clipTable.getScene());
    }

    /**
     * Opens a fresh main window. Each menu invocation gets its own independent
     * project state — the existing window stays open.
     */
    @FXML
    private void onMenuNewProject() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(MAIN_FXML));
            Parent root = loader.load();
            Stage stage = new Stage();
            stage.setTitle("Bintro");
            javafx.scene.Scene scene = new javafx.scene.Scene(root, 900, 600);
            App.attachThemeStylesheet(scene);
            App.applyActiveTheme(scene);
            stage.setScene(scene);
            stage.show();
        } catch (IOException e) {
            System.err.println("MainController: failed to open new project window: " + e);
            e.printStackTrace();
        }
    }

    /** Open Project menu — stub mirroring the welcome screen's stub. */
    @FXML
    private void onMenuOpenProject() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Bintro Project");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Bintro Project", "*.bintro")
        );
        File chosen = chooser.showOpenDialog(
            selectFootageButton.getScene().getWindow());
        if (chosen != null) {
            System.out.println("MainController: open project (stub): "
                + chosen.getAbsolutePath());
        }
    }

    @FXML
    private void onMenuQuit() {
        Platform.exit();
    }

    @FXML
    private void onMenuAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About Bintro");
        alert.setHeaderText("Bintro");
        alert.setContentText(VERSION_STRING);

        URL logoUrl = getClass().getResource(LOGO_RESOURCE);
        if (logoUrl != null) {
            try {
                ImageView graphic = new ImageView(new Image(logoUrl.toExternalForm()));
                graphic.setFitWidth(160);
                graphic.setPreserveRatio(true);
                alert.setGraphic(graphic);
            } catch (Exception ignored) {
                // Logo is optional — fall back to text-only.
            }
        }
        alert.showAndWait();
    }

    private void scrollScriptTo(int sceneNumber) {
        if (isPdfScript && pdfPageViewer != null) {
            pdfPageViewer.scrollToScene(sceneNumber);
            return;
        }
        // executeScript fails if the page hasn't finished loading; swallow.
        try {
            scriptWebView.getEngine().executeScript(
                "var el = document.getElementById('scene-" + sceneNumber + "');"
                    + " if (el) el.scrollIntoView({behavior:'smooth'});");
        } catch (Exception ignored) {
            // No script loaded yet — nothing to scroll.
        }
    }

    /**
     * Some PDF parsers glue the scene number to the heading text (e.g.
     * {@code "EXT. STREET - DAY 1"} for scene 1, or worse {@code "DAY1 1"}
     * when whitespace collapses). Strips trailing instances of the actual
     * scene number — and only the scene number — so legitimate trailing
     * digits ({@code "INT. ROOM 247"}) survive.
     */
    /**
     * Returns true when the transcript has enough signal to attempt dialogue
     * matching. Five words is the threshold from the feature spec — short
     * enough to keep utterances ("Help me!") on the dialogue path while
     * routing silent clips and one-word noises through the visual matcher.
     */
    private static boolean hasUsableTranscript(String transcript) {
        if (transcript == null) {
            return false;
        }
        String stripped = transcript.strip();
        if (stripped.isEmpty()) {
            return false;
        }
        return stripped.split("\\s+").length >= 5;
    }

    private static String cleanHeading(Scene s) {
        if (s == null || s.heading() == null) {
            return "";
        }
        String h = s.heading();
        String pattern = "\\s*" + java.util.regex.Pattern.quote(String.valueOf(s.sceneNumber())) + "\\s*$";
        String previous;
        do {
            previous = h;
            h = h.replaceAll(pattern, "");
        } while (!h.equals(previous));
        return h.strip();
    }
}
