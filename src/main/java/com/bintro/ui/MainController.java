package com.bintro.ui;

import com.bintro.export.ClipMatch;
import com.bintro.export.Exporter;
import com.bintro.matching.MatchResult;
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
import javafx.scene.control.TextArea;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the Bintro pipeline from the JavaFX UI:
 * <ol>
 *   <li>User picks a footage folder → scan via {@link MediaScanner}.</li>
 *   <li>User picks a screenplay → parse via {@link ScriptParser}.</li>
 *   <li>Run → background {@link Task} transcribes, matches, and exports.</li>
 * </ol>
 */
public class MainController {

    @FXML private Button selectFootageButton;
    @FXML private Button selectScriptButton;
    @FXML private Button runButton;
    @FXML private ListView<String> clipList;
    @FXML private ListView<String> sceneList;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private TextArea transcriptLog;
    @FXML private TextArea scriptViewer;

    private File footageFolder;
    private File scriptFile;
    private List<Clip> clips = new ArrayList<>();
    private List<Scene> scenes = new ArrayList<>();

    @FXML
    private void initialize() {
        runButton.setDisable(true);
        statusLabel.setText("Ready");
        progressBar.setProgress(0);
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
            clipList.setItems(FXCollections.observableArrayList(
                clips.stream().map(Clip::filename).toList()
            ));
            statusLabel.setText("Loaded " + clips.size() + " clip(s) from " + chosen.getName() + ".");
        } catch (Exception e) {
            statusLabel.setText("Failed to scan footage: " + e.getMessage());
        }
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
            scriptViewer.setText(buildScriptText(scenes));
            scriptViewer.setScrollTop(0);
            statusLabel.setText("Loaded " + scenes.size() + " scene(s) from " + chosen.getName() + ".");
        } catch (Exception e) {
            statusLabel.setText("Failed to parse script: " + e.getMessage());
        }
        updateRunEnabled();
    }

    private void updateRunEnabled() {
        runButton.setDisable(footageFolder == null || scriptFile == null || clips.isEmpty() || scenes.isEmpty());
    }

    @FXML
    private void onRun() {
        runButton.setDisable(true);
        progressBar.setProgress(0);
        transcriptLog.clear();
        statusLabel.setText("Starting pipeline…");

        // Snapshot inputs so the worker isn't affected by UI changes.
        final File footage = footageFolder;
        final List<Clip> clipsSnapshot = List.copyOf(clips);
        final List<Scene> scenesSnapshot = List.copyOf(scenes);
        final File outputDir = new File(footage.getParentFile(), "Bintro_Output");

        Task<File> task = new Task<>() {
            @Override
            protected File call() {
                Transcriber transcriber = new Transcriber();
                SceneMatcher matcher = new SceneMatcher();
                List<ClipMatch> matches = new ArrayList<>();
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
                        matches.add(new ClipMatch(clip, 0));
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

                    matches.add(new ClipMatch(clip, result.sceneNumber()));
                    publish(clip.filename() + " → "
                            + (result.sceneNumber() > 0 ? "Scene " + result.sceneNumber() : "Unmatched"),
                        (double) idx / total);
                }

                publish("Exporting…", 0.98);
                try {
                    new Exporter().export(matches, outputDir);
                } catch (Exception e) {
                    throw new RuntimeException("Export failed: " + e.getMessage(), e);
                }
                publish("Done.", 1.0);
                return outputDir;
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
            File out = task.getValue();
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Export complete");
            alert.setHeaderText("Export complete");
            alert.setContentText("Output: " + (out != null ? out.getAbsolutePath() : "(unknown)"));
            alert.showAndWait();
        });

        task.setOnFailed(ev -> {
            runButton.setDisable(false);
            Throwable t = task.getException();
            statusLabel.setText("Pipeline failed: " + (t != null ? t.getMessage() : "unknown error"));
            if (t != null) {
                t.printStackTrace();
            }
        });

        Thread worker = new Thread(task, "bintro-pipeline");
        worker.setDaemon(true);
        worker.start();
    }

    private void appendTranscriptLog(String entry) {
        Platform.runLater(() -> {
            transcriptLog.appendText(entry);
            transcriptLog.setScrollTop(Double.MAX_VALUE);
        });
    }

    private static String buildScriptText(List<Scene> scenes) {
        StringBuilder sb = new StringBuilder();
        for (Scene s : scenes) {
            sb.append("SCENE ").append(s.sceneNumber())
              .append(" — ")
              .append(s.heading()).append('\n');
            String body = s.fullText() == null ? "" : s.fullText();
            sb.append(body).append("\n\n");
        }
        return sb.toString();
    }
}
