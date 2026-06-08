package com.bintro.ui;

import com.bintro.media.Clip;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

/**
 * In-app video preview for the clip review table. Selecting a row in the
 * table loads that clip into this panel; the user can play, scrub, and
 * adjust volume to verify the match before exporting.
 *
 * <p>Built on JavaFX's {@code javafx.media} module, which uses platform-
 * native decoders. Supports the common consumer formats (H.264/H.265 MOV,
 * MP4) on macOS. Pro/cinema formats (MXF, R3D, ProRes) generally aren't
 * decodable — for those a friendly fallback message is shown in place of
 * the player.
 *
 * <p>Threading: every public method must be called on the FX application
 * thread. Internally, {@link MediaPlayer} callbacks (currentTimeProperty
 * listener, onEndOfMedia, onError) are already FX-thread-safe.
 */
public class VideoPlayerPanel extends VBox {

    private static final double MEDIA_HEIGHT = 220;
    private static final String PLAY_GLYPH = "▶";
    private static final String PAUSE_GLYPH = "⏸";

    private final MediaView mediaView = new MediaView();
    private final Slider scrubBar = new Slider();
    private final Slider volumeSlider = new Slider();
    private final Label timecodeLabel = new Label("00:00:00:00");
    private final Button playPauseButton = new Button(PLAY_GLYPH);
    private final Label clipNameLabel = new Label("");
    private final Label fallbackLabel = new Label("");

    private MediaPlayer player;

    public VideoPlayerPanel() {
        setStyle("-fx-background-color: #1a1a1a; -fx-padding: 0;");

        clipNameLabel.setStyle(
            "-fx-font-size: 10px; -fx-text-fill: #666666;"
                + " -fx-padding: 4 10 2 10; -fx-font-family: 'Courier New';");

        // Media area: a black StackPane that hosts the MediaView, plus a
        // hidden fallback label that appears when the format is unsupported.
        StackPane mediaContainer = new StackPane(mediaView, fallbackLabel);
        mediaContainer.setStyle("-fx-background-color: #000000;");
        mediaContainer.setPrefHeight(MEDIA_HEIGHT);
        mediaContainer.setMaxHeight(MEDIA_HEIGHT);
        mediaContainer.setMinHeight(MEDIA_HEIGHT);

        mediaView.setPreserveRatio(true);
        mediaView.fitWidthProperty().bind(mediaContainer.widthProperty());
        mediaView.setFitHeight(MEDIA_HEIGHT);

        fallbackLabel.setStyle(
            "-fx-text-fill: #cccccc; -fx-font-size: 12px;"
                + " -fx-text-alignment: center; -fx-wrap-text: true;");
        fallbackLabel.setVisible(false);
        fallbackLabel.setManaged(false);

        // Controls row.
        playPauseButton.setStyle(
            "-fx-background-color: transparent; -fx-text-fill: #ffffff;"
                + " -fx-font-size: 14px; -fx-cursor: hand; -fx-padding: 0 8 0 8;");
        playPauseButton.setOnAction(e -> togglePlayPause());

        timecodeLabel.setStyle(
            "-fx-font-family: 'Courier New'; -fx-font-size: 11px;"
                + " -fx-text-fill: #aaaaaa; -fx-min-width: 80px;");

        scrubBar.setMin(0);
        scrubBar.setMax(100);
        scrubBar.setValue(0);
        scrubBar.setStyle("-fx-accent: #ffffff;");
        // Seek when the user releases the scrub bar after dragging.
        scrubBar.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
            if (!isChanging && player != null) {
                Duration total = player.getTotalDuration();
                if (total != null && !total.isUnknown()) {
                    player.seek(total.multiply(scrubBar.getValue() / 100.0));
                }
            }
        });

        volumeSlider.setMin(0);
        volumeSlider.setMax(100);
        volumeSlider.setValue(80);
        volumeSlider.setPrefWidth(80);
        volumeSlider.setStyle("-fx-accent: #ffffff;");

        Label volumeIcon = new Label("🔊"); // 🔊
        volumeIcon.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 12px;");

        HBox controls = new HBox(8, playPauseButton, timecodeLabel, scrubBar,
            volumeIcon, volumeSlider);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setStyle("-fx-background-color: #111111; -fx-padding: 6 10 6 10;");
        HBox.setHgrow(scrubBar, Priority.ALWAYS);

        getChildren().addAll(clipNameLabel, mediaContainer, controls);
    }

    /**
     * Loads a clip into the player. Any previously loaded clip is stopped and
     * disposed first. Unsupported formats display a friendly fallback message
     * instead of crashing.
     */
    public void load(Clip clip) {
        stop();
        if (clip == null) {
            clipNameLabel.setText("");
            return;
        }
        clipNameLabel.setText(clip.filename());

        try {
            Media media = new Media(clip.file().toURI().toString());
            MediaPlayer p = new MediaPlayer(media);
            this.player = p;
            mediaView.setMediaPlayer(p);
            hideFallback();
            wireUpPlayer(p, clip);
        } catch (RuntimeException e) {
            // MediaException (a RuntimeException) covers most unsupported-format
            // cases; an IllegalArgumentException can also fire for bad URIs.
            System.err.println("VideoPlayerPanel: failed to open '"
                + clip.filename() + "': " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
            showUnsupportedFallback(clip);
        }
    }

    private void wireUpPlayer(MediaPlayer p, Clip clip) {
        // Drive the scrub bar from playback progress (unless the user is
        // actively dragging it).
        p.currentTimeProperty().addListener((obs, oldT, now) -> {
            if (now == null) {
                return;
            }
            if (!scrubBar.isValueChanging()) {
                Duration total = p.getTotalDuration();
                if (total != null && total.greaterThan(Duration.ZERO)
                    && !total.isUnknown()) {
                    scrubBar.setValue(now.toSeconds() / total.toSeconds() * 100);
                }
            }
            updateTimecode(now);
        });

        // Bind volume slider to player volume (0..1).
        p.volumeProperty().bind(volumeSlider.valueProperty().divide(100));

        p.setOnEndOfMedia(() -> {
            p.seek(Duration.ZERO);
            p.pause();
            playPauseButton.setText(PLAY_GLYPH);
        });

        p.setOnReady(() -> updateTimecode(Duration.ZERO));

        // Some formats survive Media construction but fail asynchronously.
        p.setOnError(() -> {
            MediaException err = p.getError();
            System.err.println("VideoPlayerPanel: media error for '"
                + clip.filename() + "': "
                + (err == null ? "unknown" : err.getMessage()));
            showUnsupportedFallback(clip);
        });
    }

    private void togglePlayPause() {
        if (player == null) {
            return;
        }
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            player.pause();
            playPauseButton.setText(PLAY_GLYPH);
        } else {
            player.play();
            playPauseButton.setText(PAUSE_GLYPH);
        }
    }

    /** Formats a {@link Duration} as {@code HH:MM:SS:FF} at 24 fps. */
    private void updateTimecode(Duration d) {
        if (d == null) {
            timecodeLabel.setText("00:00:00:00");
            return;
        }
        long totalSeconds = (long) d.toSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        long frames = Math.round((d.toSeconds() - totalSeconds) * 24);
        if (frames >= 24) {
            frames = 23;
        }
        timecodeLabel.setText(String.format("%02d:%02d:%02d:%02d",
            hours, minutes, seconds, frames));
    }

    /**
     * Tears down the current player and resets the UI to its idle state.
     * Safe to call when nothing is loaded.
     */
    public void stop() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
                // Best-effort during teardown.
            }
            try {
                player.dispose();
            } catch (Exception ignored) {
                // Best-effort during teardown.
            }
            player = null;
        }
        mediaView.setMediaPlayer(null);
        scrubBar.setValue(0);
        timecodeLabel.setText("00:00:00:00");
        playPauseButton.setText(PLAY_GLYPH);
    }

    /** Releases all player resources. Call from the host stage's close-request. */
    public void dispose() {
        stop();
    }

    private void showUnsupportedFallback(Clip clip) {
        // Drop the failed player; the fallback label takes the foreground.
        mediaView.setMediaPlayer(null);
        if (player != null) {
            try {
                player.dispose();
            } catch (Exception ignored) {
                // Best-effort.
            }
            player = null;
        }
        String ext = extensionOf(clip == null ? "" : clip.filename());
        fallbackLabel.setText("Preview not available for ." + ext + " files."
            + System.lineSeparator()
            + "Open in your system player to view.");
        fallbackLabel.setVisible(true);
        fallbackLabel.setManaged(true);
    }

    private void hideFallback() {
        fallbackLabel.setVisible(false);
        fallbackLabel.setManaged(false);
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "?";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot < filename.length() - 1
            ? filename.substring(dot + 1).toLowerCase()
            : "?";
    }
}
