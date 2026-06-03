# Feature 5 — MainController + UI (Wire the Full Workflow)

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

Bintro is a JavaFX 21 desktop app (Mac/Windows) for video editors.
It scans a folder of raw footage clips, parses a screenplay, transcribes each clip
via whisper.cpp (local subprocess), matches clips to scenes via the Claude API,
and sorts footage into labeled output folders.

The following classes are **already implemented** — do not rewrite them:

| Class | Location | Status |
|---|---|---|
| `ScriptParser` | `parser/ScriptParser.java` | Full — parses PDF + FDX into `List<Scene>` |
| `Scene` | `parser/Scene.java` | Record: `int sceneNumber, String heading, String fullText` |
| `MediaScanner` | `media/MediaScanner.java` | Full — scans folder, returns `List<Clip>` |
| `Clip` | `media/Clip.java` | Record: `File file, String filename, Duration duration, String timecode` |
| `ThumbnailExtractor` | `media/ThumbnailExtractor.java` | Exists — extract thumbnail if needed |
| `Transcriber` | `transcription/Transcriber.java` | **Stub** — implement here |
| `SceneMatcher` | `matching/SceneMatcher.java` | **Stub** — implement here |
| `Exporter` | `export/Exporter.java` | **Stub** — implement here |
| `MainController` | `ui/MainController.java` | **Stub** — implement here |
| `MainView.fxml` | `resources/fxml/MainView.fxml` | Shell — extend here |

`config.properties` (gitignored) lives at the project root with:
```
anthropic.api.key=<key>
whisper.binary=/path/to/whisper
whisper.model=/path/to/ggml-base.en.bin
```

---

## Task

Build the complete MVP UI and wire it to all backend classes.
Work top-down: stubs first (so it compiles), then fill logic.

### 1. `Transcriber.java`

Implement `String transcribe(Clip clip)`:
- Extract audio from `clip.file()` to a temp WAV (16 kHz mono) using
  `FFmpegFrameGrabber` + `FFmpegFrameRecorder` (JavaCV — already on classpath).
- Run whisper.cpp via `ProcessBuilder`:
  - binary path from `Config.get("whisper.binary")`
  - model path from `Config.get("whisper.model")`
  - args: `--output-txt --no-timestamps <wav_file>`
- Read the resulting `.txt` output file and return its contents as a String.
- Clean up temp files in a `finally` block.
- Throw `IOException` on failure.

### 2. `SceneMatcher.java`

Implement `MatchResult match(Clip clip, String transcript, List<Scene> scenes)`:
- Build a prompt that includes:
  - The transcript text
  - A numbered list of scene headings + first ~200 chars of each scene's `fullText`
- POST to `https://api.anthropic.com/v1/messages` using Java's `HttpClient`.
  - Header: `x-api-key` from `Config.get("anthropic.api.key")`
  - Header: `anthropic-version: 2023-06-01`
  - Model: `claude-haiku-4-5-20251001` (fast + cheap for matching)
  - Ask Claude to respond with **only** a scene number (integer) or `0` if no match.
- Parse the response and return `MatchResult(int sceneNumber, double confidence)`.
  - `confidence` = 1.0 if a valid scene number was returned, 0.0 if no match.
- If the HTTP call fails or the response can't be parsed, return scene 0 (Unmatched).

### 3. `Exporter.java`

Implement `void export(List<ClipMatch> matches, File outputDir)`:
- `ClipMatch` is a simple record: `Clip clip, int sceneNumber`.
- For each match:
  - If `sceneNumber > 0`: copy `clip.file()` into `outputDir/Scene_NN/` (zero-padded to 2 digits).
  - If `sceneNumber == 0`: copy into `outputDir/Unmatched/`.
- Use `Files.copy` with `REPLACE_EXISTING`. Do not move originals.
- Create directories if they don't exist.

### 4. `Config.java` (new utility)

Create `com.bintro.Config` with a static `get(String key)` method that reads
`config.properties` from the project root (or classpath fallback).
Cache the `Properties` object after first load.

### 5. `MainView.fxml` + `MainController.java`

Build a functional MVP UI. The layout should have:

**Top toolbar:**
- "Select Footage Folder" button → opens `DirectoryChooser`
- "Select Script" button → opens `FileChooser` (filter: *.pdf, *.fdx)
- "Run" button (disabled until both folder and script are selected)

**Center — two-pane `SplitPane`:**
- Left pane: `ListView<String>` showing scanned clip filenames
- Right pane: `ListView<String>` showing parsed scene headings

**Bottom status bar:**
- `Label` showing current status (e.g., "Ready", "Transcribing clip 3 of 12…", "Done.")
- `ProgressBar` (0–1) that updates during the Run pipeline

**Run pipeline (in `MainController`):**

Wire "Run" to execute this pipeline on a background thread (`Task<Void>`):
1. `MediaScanner.scanFolder(footageFolder)` → `List<Clip>`
2. `ScriptParser.parse(scriptFile)` → `List<Scene>`
3. For each clip: `Transcriber.transcribe(clip)` → transcript String
4. For each clip: `SceneMatcher.match(clip, transcript, scenes)` → `MatchResult`
5. `Exporter.export(matches, outputDir)` where `outputDir` = footageFolder's parent + `/Bintro_Output/`
6. Update status label and progress bar on the JavaFX thread via `Platform.runLater`.

After the pipeline completes, show an `Alert` dialog: "Export complete. Output: \<path\>".

**Error handling:** wrap each pipeline step in try/catch; on failure update the status
label with the error message and re-enable the Run button.

---

## Constraints

- Java 21. Use records, var, and modern APIs freely.
- No new Gradle dependencies — use only what's already in `build.gradle`
  (JavaFX, JavaCV/FFmpeg, PDFBox).
- All whisper.cpp transcription runs **locally** — no external transcription APIs.
- Keep all new classes in the existing package structure (`com.bintro.*`).
- After implementing, confirm the project compiles with `./gradlew compileJava`.

---

## Done when

- `./gradlew compileJava` passes with no errors.
- The UI launches (`./gradlew run`) and shows the two-pane layout with toolbar and status bar.
- Selecting a footage folder populates the clip list.
- Selecting a script file populates the scene list.
- "Run" button becomes enabled and kicks off the pipeline.
