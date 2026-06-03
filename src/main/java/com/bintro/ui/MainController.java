package com.bintro.ui;

import com.bintro.export.ClipMatch;
import com.bintro.export.Exporter;
import com.bintro.matching.MatchResult;
import com.bintro.matching.MatchType;
import com.bintro.matching.SceneMatcher;
import com.bintro.media.Clip;
import com.bintro.media.MediaScanner;
import com.bintro.parser.Scene;
import com.bintro.parser.ScriptParser;
import com.bintro.transcription.Transcriber;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.web.WebView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.converter.IntegerStringConverter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Drives the Bintro UI in two distinct phases:
 *
 * <ol>
 *   <li><b>Phase 1 — Analysis (Run button):</b> transcribe each clip, ask Claude
 *       for a scene match, populate the review table.</li>
 *   <li><b>Phase 2 — Export (Export button):</b> read the (possibly user-corrected)
 *       scene numbers out of the table and copy footage into per-scene folders.</li>
 * </ol>
 *
 * <p>Export is disabled until Phase 1 succeeds, and is disabled again whenever
 * the user re-selects footage or a script (which invalidates Phase 1 results).
 */
public class MainController {

    @FXML private Button selectFootageButton;
    @FXML private Button selectScriptButton;
    @FXML private Button runButton;
    @FXML private Button exportButton;

    @FXML private TableView<ClipMatchViewModel> clipTable;
    @FXML private TableColumn<ClipMatchViewModel, String> clipCol;
    @FXML private TableColumn<ClipMatchViewModel, String> transcriptCol;
    @FXML private TableColumn<ClipMatchViewModel, MatchType> typeCol;
    @FXML private TableColumn<ClipMatchViewModel, Integer> sceneNumberCol;
    @FXML private TableColumn<ClipMatchViewModel, String> sceneHeadingCol;

    @FXML private ListView<String> sceneList;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private TextArea transcriptLog;
    @FXML private WebView scriptWebView;

    private File footageFolder;
    private File scriptFile;
    private List<Clip> clips = new ArrayList<>();
    private List<Scene> scenes = new ArrayList<>();

    @FXML
    private void initialize() {
        runButton.setDisable(true);
        exportButton.setDisable(true);
        statusLabel.setText("Ready");
        progressBar.setProgress(0);
        setupClipTable();

        // Scroll the script viewer to the selected row's scene.
        clipTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVm, newVm) -> {
            if (newVm == null || newVm.sceneNumber() <= 0) {
                return;
            }
            scrollScriptTo(newVm.sceneNumber());
        });
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
            renderScriptWithHighlights();
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
                scenes.stream().map(s -> s.sceneNumber() + ". " + s.heading()).toList()
            ));
            // Render the script with no highlights — clips haven't been matched yet.
            scriptWebView.getEngine().loadContent(
                ScriptRenderer.render(scenes, Map.of()), "text/html");
            // Rebuild table so new VMs reference the freshly loaded scenes for heading lookup.
            rebuildClipTableFromScan();
            statusLabel.setText("Loaded " + scenes.size() + " scene(s) from " + chosen.getName() + ".");
        } catch (Exception e) {
            statusLabel.setText("Failed to parse script: " + e.getMessage());
        }
        // Selecting a new script invalidates any previous analysis.
        exportButton.setDisable(true);
        updateRunEnabled();
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
                SceneMatcher matcher = new SceneMatcher();
                List<ClipMatchViewModel> rows = new ArrayList<>();
                int total = clipsSnapshot.size();

                for (int i = 0; i < total; i++) {
                    Clip clip = clipsSnapshot.get(i);
                    int idx = i + 1;
                    publish("Transcribing " + clip.filename() + " (" + idx + " of " + total + ")…",
                        (double) i / total);

                    String transcript;
                    try {
                        transcript = transcriber.transcribe(clip);
                        appendTranscriptLog("[" + clip.filename() + "]\n" + transcript + "\n\n");
                    } catch (Exception e) {
                        publish("Transcription failed for " + clip.filename() + ": " + e.getMessage(),
                            (double) idx / total);
                        appendTranscriptLog("[" + clip.filename() + "] — transcription failed: "
                            + e.getMessage() + "\n\n");
                        rows.add(new ClipMatchViewModel(clip, "", 0, scenesSnapshot));
                        continue;
                    }

                    publish("Matching " + clip.filename() + " (" + idx + " of " + total + ")…",
                        (double) i / total + 0.5 / total);

                    MatchResult result;
                    try {
                        result = matcher.match(clip, transcript, scenesSnapshot);
                    } catch (Exception e) {
                        publish("Match failed for " + clip.filename() + ": " + e.getMessage(),
                            (double) idx / total);
                        result = new MatchResult(0, 0.0);
                    }

                    rows.add(new ClipMatchViewModel(clip, transcript, result.sceneNumber(), scenesSnapshot));
                    publish(clip.filename() + " → "
                            + (result.sceneNumber() > 0 ? "Scene " + result.sceneNumber() : "Unmatched"),
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
            renderScriptWithHighlights();
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
            .map(vm -> new ClipMatch(vm.clip(), vm.sceneNumber()))
            .toList();
        final File outputDir = new File(footageFolder.getParentFile(), "Bintro_Output");

        runButton.setDisable(true);
        exportButton.setDisable(true);
        statusLabel.setText("Exporting to " + outputDir.getAbsolutePath() + "…");
        progressBar.setProgress(-1); // indeterminate

        Task<File> task = new Task<>() {
            @Override
            protected File call() throws Exception {
                new Exporter().export(matches, outputDir);
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
     * Re-renders the script viewer with the current table's match data.
     * Safe to call before a script is loaded — the empty scene list yields an
     * empty HTML document. Skips VMs with no scene assigned.
     */
    private void renderScriptWithHighlights() {
        if (scenes == null || scenes.isEmpty()) {
            return;
        }
        Map<Integer, List<ClipMatchViewModel>> byScene = clipTable.getItems().stream()
            .filter(vm -> vm.sceneNumber() > 0)
            .collect(Collectors.groupingBy(ClipMatchViewModel::sceneNumber));
        scriptWebView.getEngine().loadContent(
            ScriptRenderer.render(scenes, byScene), "text/html");
    }

    private void scrollScriptTo(int sceneNumber) {
        // executeScript fails if the page hasn't finished loading; swallow.
        try {
            scriptWebView.getEngine().executeScript(
                "var el = document.getElementById('scene-" + sceneNumber + "');"
                    + " if (el) el.scrollIntoView({behavior:'smooth'});");
        } catch (Exception ignored) {
            // No script loaded yet — nothing to scroll.
        }
    }
}
