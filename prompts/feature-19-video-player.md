# Feature 19 — In-App Video Player

> Paste this into a Claude Code session opened at the Bintro project root.
> Note: previous feature-19-slate-detection.md is now renumbered to feature-20.

---

## Context

Bintro needs an in-app video player so editors can verify clip matches before
exporting. Clicking a row in the clip TableView loads that clip into a player
panel below the table.

JavaFX has a built-in media player (`javafx.media`) that supports common video
formats via platform-native decoders. This is the right tool — no new
dependencies needed.

---

## Task

### 1. Add `javafx.media` to `build.gradle`

```groovy
javafx {
    modules = ['javafx.controls', 'javafx.fxml', 'javafx.swing', 'javafx.web', 'javafx.media']
}
```

### 2. Create `VideoPlayerPanel.java`

Create `src/main/java/com/bintro/ui/VideoPlayerPanel.java`.

A JavaFX `VBox` containing:
- A `MediaView` for video display
- A control bar below it

```java
public class VideoPlayerPanel extends VBox {
    private MediaPlayer player;
    private MediaView mediaView;
    private Slider scrubBar;
    private Label timecodeLabel;
    private Button playPauseButton;
    private Slider volumeSlider;
    private Label clipNameLabel;

    public VideoPlayerPanel() { ... }
    public void load(Clip clip) { ... }
    public void stop() { ... }
    public void dispose() { ... }
}
```

**Layout (top to bottom):**

```
[ clip name label — left aligned, muted, 11px ]
[ MediaView — black background, 16:9 aspect ratio, max height 220px ]
[ control bar HBox ]
  [ ▶ play/pause ] [ 00:00:00:00 timecode ] [ ─────── scrub bar ─────── ] [ 🔊 volume ]
```

**`load(Clip clip)` implementation:**

```java
public void load(Clip clip) {
    stop(); // stop and dispose any existing player
    clipNameLabel.setText(clip.filename());

    Media media = new Media(clip.file().toURI().toString());
    player = new MediaPlayer(media);

    mediaView.setMediaPlayer(player);

    // Scrub bar — update as clip plays
    player.currentTimeProperty().addListener((obs, old, now) -> {
        if (!scrubBar.isValueChanging()) {
            Duration total = player.getTotalDuration();
            if (total != null && total.greaterThan(Duration.ZERO)) {
                scrubBar.setValue(now.toSeconds() / total.toSeconds() * 100);
            }
            updateTimecode(now);
        }
    });

    // Scrub bar — seek on drag
    scrubBar.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> {
        if (!isChanging) {
            Duration total = player.getTotalDuration();
            if (total != null) {
                player.seek(total.multiply(scrubBar.getValue() / 100.0));
            }
        }
    });

    // Volume
    player.volumeProperty().bind(volumeSlider.valueProperty().divide(100));

    // Reset on end
    player.setOnEndOfMedia(() -> {
        player.seek(Duration.ZERO);
        player.pause();
        playPauseButton.setText("▶");
    });

    player.setOnReady(() -> {
        updateTimecode(Duration.ZERO);
    });
}
```

**Play/pause toggle:**
```java
private void togglePlayPause() {
    if (player == null) return;
    if (player.getStatus() == MediaPlayer.Status.PLAYING) {
        player.pause();
        playPauseButton.setText("▶");
    } else {
        player.play();
        playPauseButton.setText("⏸");
    }
}
```

**Timecode display (`HH:MM:SS:FF` at 24fps):**
```java
private void updateTimecode(Duration d) {
    long totalSeconds = (long) d.toSeconds();
    long hours   = totalSeconds / 3600;
    long minutes = (totalSeconds % 3600) / 60;
    long seconds = totalSeconds % 60;
    long frames  = Math.round((d.toSeconds() - totalSeconds) * 24);
    timecodeLabel.setText(String.format("%02d:%02d:%02d:%02d",
        hours, minutes, seconds, frames));
}
```

**`stop()` and `dispose()`:**
```java
public void stop() {
    if (player != null) {
        player.stop();
        player.dispose();
        player = null;
    }
    scrubBar.setValue(0);
    timecodeLabel.setText("00:00:00:00");
    playPauseButton.setText("▶");
}
```

**Styling:**

```java
// Panel background
setStyle("-fx-background-color: #1a1a1a; -fx-padding: 0;");

// MediaView container — black, fixed height
StackPane mediaContainer = new StackPane(mediaView);
mediaContainer.setStyle("-fx-background-color: #000000;");
mediaContainer.setPrefHeight(220);
mediaContainer.setMaxHeight(220);

// MediaView fills container, preserves ratio
mediaView.setPreserveRatio(true);
mediaView.fitWidthProperty().bind(mediaContainer.widthProperty());
mediaView.setFitHeight(220);

// Control bar
HBox controls = new HBox(8);
controls.setStyle("-fx-background-color: #111111; -fx-padding: 6 10 6 10;");
controls.setAlignment(Pos.CENTER_LEFT);

// Play/pause button
playPauseButton.setStyle(
    "-fx-background-color: transparent; -fx-text-fill: #ffffff;" +
    "-fx-font-size: 14px; -fx-cursor: hand; -fx-padding: 0 8 0 8;");

// Timecode
timecodeLabel.setStyle(
    "-fx-font-family: 'Courier New'; -fx-font-size: 11px;" +
    "-fx-text-fill: #aaaaaa; -fx-min-width: 80px;");

// Scrub bar — grows to fill available space
HBox.setHgrow(scrubBar, Priority.ALWAYS);
scrubBar.setMin(0);
scrubBar.setMax(100);
scrubBar.setValue(0);
scrubBar.setStyle("-fx-accent: #ffffff;");

// Volume slider — fixed width
volumeSlider.setMin(0);
volumeSlider.setMax(100);
volumeSlider.setValue(80);
volumeSlider.setPrefWidth(80);
volumeSlider.setStyle("-fx-accent: #ffffff;");

// Clip name label
clipNameLabel.setStyle(
    "-fx-font-size: 10px; -fx-text-fill: #666666;" +
    "-fx-padding: 4 10 2 10; -fx-font-family: 'Courier New';");
```

---

### 3. Update `MainView.fxml`

Replace the center `SplitPane` with a `VBox` that stacks:
1. The existing `SplitPane` (footage table + scene/script panels) — `VBox.vgrow="ALWAYS"`
2. A `Separator`
3. The `VideoPlayerPanel` — fixed height, initially hidden

```xml
<VBox>
  <SplitPane VBox.vgrow="ALWAYS" ...existing.../>
  <Separator/>
  <fx:include source="VideoPlayerPanel.fxml" fx:id="videoPlayer"/>
</VBox>
```

Or instantiate `VideoPlayerPanel` programmatically in `MainController.initialize()`
and add it to the bottom of the center `VBox`.

Initially set `videoPlayer.setVisible(false)` and `videoPlayer.setManaged(false)`.

---

### 4. Update `MainController.java`

Add field:
```java
private VideoPlayerPanel videoPlayer;
```

In `initialize()`, create and add the panel to the layout, hidden:
```java
videoPlayer = new VideoPlayerPanel();
videoPlayer.setVisible(false);
videoPlayer.setManaged(false);
centerVBox.getChildren().add(videoPlayer);
```

**Wire row selection to the player:**
```java
clipTable.getSelectionModel().selectedItemProperty().addListener(
    (obs, old, selected) -> {
        if (selected != null) {
            videoPlayer.setVisible(true);
            videoPlayer.setManaged(true);
            videoPlayer.load(selected.getClip());
        }
    }
);
```

**On app close**, dispose the player:
```java
// In App.java or MainController, on stage close:
stage.setOnCloseRequest(e -> {
    if (videoPlayer != null) videoPlayer.dispose();
});
```

---

## Format support note

JavaFX's `javafx.media` uses platform-native decoders:
- **Mac**: H.264/H.265 MOV, MP4 — fully supported
- **MXF, R3D, ProRes**: NOT supported natively by JavaFX media

For unsupported formats, show a `Label` in the media container:
```
"Preview not available for .<ext> files.\nOpen in your system player to view."
```
Catch `MediaException` in `load()` and display this message instead of crashing.

---

## Constraints

- Java 21, JavaFX 21 — no new Gradle dependencies (`javafx.media` is part of JavaFX)
- Create: `ui/VideoPlayerPanel.java`
- Modify: `MainView.fxml`, `MainController.java`, `build.gradle`, `App.java`
- Player must be disposed on app close to avoid resource leaks
- After implementing: `./gradlew compileJava` passes, `./gradlew run` launches

## Done when

- Selecting a clip row in the table shows the video player panel below
- Video plays with correct aspect ratio on a black background
- Play/pause, scrub bar, timecode (`HH:MM:SS:FF`), and volume all work
- Timecode updates as the clip plays
- Unsupported formats show a friendly message instead of crashing
- Player is hidden when no clip is selected
- `./gradlew compileJava` passes with no errors
