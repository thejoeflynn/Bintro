# Feature 6 — Transcript Log + Script Viewer

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

Bintro is a JavaFX 21 desktop app for video editors. The pipeline scans footage,
transcribes each clip via whisper.cpp, matches clips to screenplay scenes via the
Claude API, and sorts footage into output folders.

The UI (`MainView.fxml` + `MainController.java`) is already built with:
- Top toolbar: "Select Footage Folder", "Select Script", "Run" buttons
- Center: `SplitPane` with a clip `ListView` (left) and scene `ListView` (right)
- Bottom: status `Label` + `ProgressBar`

The pipeline runs on a background thread and updates the status label per clip,
but there is no way to see transcript text or read the loaded screenplay.

---

## Task

Make two UI additions. Do not break any existing functionality.

### 1. Transcript Log

Add a scrolling log area that shows transcript output in real time as the pipeline runs.

**Where:** Below the center `SplitPane`, above the bottom status bar.
Use a `TitledPane` labelled "Transcript Log" that is collapsed by default
(so it doesn't dominate the layout when not needed).

**Inside the `TitledPane`:** A `TextArea` (read-only, wrap text, ~120px tall)
that appends a new entry for each clip as it's transcribed, in this format:
```
[clip_filename]
<transcript text>

```
- Append entries in `MainController` immediately after `transcriber.transcribe(clip)` succeeds.
- Use `Platform.runLater` to update the `TextArea` from the background thread.
- Auto-scroll to the bottom after each append (`textArea.setScrollTop(Double.MAX_VALUE)`).
- If transcription fails, append: `[clip_filename] — transcription failed: <error message>`

### 2. Script Viewer

Add a read-only panel that displays the full text of the loaded screenplay,
so the user can read the script alongside their footage.

**Where:** Replace the right pane of the center `SplitPane`.
The right pane currently shows a `ListView` of scene headings.
Change it to a `TabPane` with two tabs:

- **"Scenes" tab** — keep the existing scene heading `ListView` exactly as-is.
- **"Script" tab** — a `TextArea` (read-only, wrap text) that shows the full
  parsed script text, formatted as:

```
SCENE 1 — INT. COFFEE SHOP - DAY
<scene full text>

SCENE 2 — EXT. PARKING LOT - NIGHT
<scene full text>
```

Populate the Script tab in `onSelectScript()` after `ScriptParser.parse()` succeeds,
by joining all `Scene` objects in order.

---

## Constraints

- Java 21, JavaFX 21 only — no new Gradle dependencies.
- Keep all changes within `MainView.fxml` and `MainController.java`.
  Do not modify any other files.
- After implementing, confirm the project compiles: `./gradlew compileJava`
- Confirm the app launches: `./gradlew run`

## Done when

- The transcript log `TitledPane` appears below the split pane, collapsed by default,
  and expands to show clip transcripts as the pipeline runs.
- The right pane has two tabs: "Scenes" (existing list) and "Script" (full script text).
- The script text populates immediately when a screenplay is loaded.
- `./gradlew compileJava` passes with no errors.
