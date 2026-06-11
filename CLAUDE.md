# Bintro — Project Context

> This file is read automatically at the start of every Claude Code and Cowork session.
> It reflects the **current** state of the codebase. Update it after each major feature.

---

## What is Bintro?

Bintro is a desktop application for Mac and Windows that helps video editors and DITs (Digital Image Technicians) organize raw footage during the import process. The user provides a folder of raw clips and a screenplay. Bintro transcribes each clip, matches it to the correct scene in the script, and sorts the footage into labeled folders — ready for import into an editing application.

## Core User Workflow

1. User opens Bintro → Welcome screen appears
2. User clicks "New Project" → Main window opens
3. User selects a folder of raw footage clips (Select Footage)
4. User uploads a screenplay (.pdf or .fdx) (Select Script)
5. User clicks Run → pipeline begins:
   - Extracts clip metadata (filename, timecode, duration) via JavaCV
   - Transcribes audio using whisper.cpp (local, no internet)
   - Clips with usable dialogue → matched via Qwen 2.5 (Msty)
   - Clips with no/minimal dialogue → matched visually via Gemma 4 (Msty, frame extraction)
   - Falls back to keyword overlap matching if Msty is unreachable
6. Results populate the review table (clip → scene number, editable)
7. User reviews matches, corrects mismatches, edits export names if needed
8. User clicks Export → clips copied to scene folders, rename_log.csv written

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| UI | JavaFX 17.0.11 |
| Build tool | Maven (`mvn javafx:run` to run, `mvn compile` to compile) |
| Video / metadata | JavaCV (FFmpeg bindings) |
| Transcription | whisper.cpp — local subprocess via ProcessBuilder |
| Script parsing — PDF | Apache PDFBox 3.0.5 |
| Script parsing — FDX | Custom XML parser |
| AI matching (dialogue) | Qwen 2.5 via Msty local backend (OpenAI-compatible API) |
| AI matching (visual) | Gemma 4 via Msty local backend (vision API) |
| AI fallback | LocalSceneMatcher — keyword overlap, no network required |

## Build Commands

```bash
mvn javafx:run       # run the app
mvn compile          # compile only
mvn test             # run tests
mvn package          # build fat JAR
mvn clean javafx:run # clean + run
```

**Not Gradle.** There is no `build.gradle`. The project was migrated from Gradle to Maven. Do not reference Gradle commands.

## Project Structure

```
bintro/
├── pom.xml
├── mvnw / mvnw.cmd              # Maven wrapper
├── config.properties            # gitignored — copy from config.properties.example
├── config.properties.example
├── whisper/
│   ├── whisper                  # compiled whisper.cpp binary (Mac)
│   └── models/
│       └── ggml-base.en.bin     # whisper model (gitignored — too large)
├── prompts/                     # Claude Code session prompts (feature history)
└── src/main/
    ├── java/com/bintro/
    │   ├── App.java             # JavaFX entry point + theme management
    │   ├── Config.java          # static property loader
    │   ├── export/
    │   │   ├── ClipMatch.java
    │   │   └── Exporter.java
    │   ├── matching/
    │   │   ├── LocalSceneMatcher.java
    │   │   ├── MatchResult.java
    │   │   ├── MatchType.java
    │   │   ├── SceneMatcher.java
    │   │   └── VisualMatcher.java
    │   ├── media/
    │   │   ├── Clip.java
    │   │   ├── MediaScanner.java
    │   │   └── ThumbnailExtractor.java
    │   ├── parser/
    │   │   ├── PositionAwareTextStripper.java
    │   │   ├── Scene.java
    │   │   ├── ScenePositionIndex.java
    │   │   ├── ScriptElement.java
    │   │   └── ScriptParser.java
    │   ├── transcription/
    │   │   └── Transcriber.java
    │   └── ui/
    │       ├── ClipMatchViewModel.java
    │       ├── MainController.java
    │       ├── PdfPageViewer.java
    │       ├── ScriptRenderer.java
    │       ├── ScriptViewBridge.java
    │       ├── VideoPlayerPanel.java
    │       └── WelcomeController.java
    └── resources/
        ├── css/theme.css
        ├── fonts/Fragmentcore.otf
        ├── fxml/
        │   ├── MainView.fxml
        │   └── WelcomeView.fxml
        └── images/bintro-logo.png
```

## Key Source Files

### `App.java`
JavaFX entry point. Also owns theme management (no separate ThemeManager class):
- `setActiveTheme(String)` / `getActiveTheme()` — process-wide theme state (`"light"`, `"dark"`, `"navy"`, `"forest"`)
- `applyActiveTheme(Scene)` — swaps theme class on root node, calls `applyCss()`
- `attachThemeStylesheet(Scene)` — attaches `theme.css` to a scene if not already present
- Loads `Fragmentcore.otf` brand font on startup

### `Config.java`
Static property loader. Lookup order: `$user.dir/config.properties` → JAR directory → classpath. Methods: `get(key)`, `get(key, default)`.

### `parser/Scene.java`
```java
public record Scene(int sceneNumber, String heading, String fullText, List<ScriptElement> elements)
```

### `parser/ScriptElement.java`
```java
public record ScriptElement(ElementType type, String text)
// ElementType: SCENE_HEADING, ACTION, CHARACTER, DIALOGUE, PARENTHETICAL, TRANSITION, OTHER
```

### `parser/ScriptParser.java`
Dispatches by extension. PDF: PDFBox text extraction + heuristic classification. FDX: XML parsing. Character name detection: ALL CAPS, 2–40 chars, not a slugline, doesn't end with "TO:", next line is dialogue/parenthetical.

### `parser/PositionAwareTextStripper.java`
Extends PDFBox `PDFTextStripper`. Overrides `writeString()` to capture `TextChunk(page, y, x, text)` for every text string, used to position gutter bars at exact script line positions.

### `parser/ScenePositionIndex.java`
Built from PDF + parsed scenes. Maps scene numbers to `{headingPage, headingY, List<LinePosition>}`. Has `findTranscriptSpan(sceneNumber, transcript)` → `float[]{startPage, startY, endPage, endY}`.

### `media/Clip.java`
```java
public record Clip(File file, String filename, Duration duration, String timecode)
```

### `media/MediaScanner.java`
Scans folder for video files (`.mp4`, `.mov`, `.mxf`, `.avi`, `.r3d`). Uses `FFmpegFrameGrabber` to probe duration and timecode metadata.

### `transcription/Transcriber.java`
Extracts 16kHz mono WAV via JavaCV → runs whisper.cpp subprocess with `--output-txt --no-timestamps` → reads `.txt` output → cleans up temp files.

### `matching/SceneMatcher.java`
Calls Msty at `POST {ai.backend.url}/chat/completions` with Qwen 2.5. OpenAI-compatible request format. Falls back to `LocalSceneMatcher` if backend unreachable or config missing. Parses scene number from `choices[0].message.content`.

### `matching/LocalSceneMatcher.java`
Keyword overlap scoring (stop words filtered). Returns highest-scoring scene. Used as offline fallback — always available.

### `matching/VisualMatcher.java`
Extracts N evenly-spaced frames via `FFmpegFrameGrabber`, encodes as base64 JPEG, sends to Gemma 4 via Msty using OpenAI vision format. Used for clips with no usable transcript (blank or < 5 words).

### `matching/MatchType.java`
```java
public enum MatchType { DIALOGUE, VISUAL }
```

### `matching/MatchResult.java`
```java
public record MatchResult(int sceneNumber, double confidence)
```

### `export/ClipMatch.java`
```java
public record ClipMatch(Clip clip, int sceneNumber, String exportName)
```

### `export/Exporter.java`
Groups clips by scene, copies to `Scene_NN/` or `Unmatched/` folders. Renames to `01_001.mp4` format (or user-typed name if customized). Writes `rename_log.csv` with original name, new name, scene number, heading, duration, export time.

### `ui/MainController.java`
Two-phase pipeline controller. Phase 1: scan → transcribe → match → populate TableView. Phase 2: export on user confirmation. Has `@FXML` fields for toolbar buttons, clip TableView, scene ListView, status label, progress bar, WebView/PdfPageViewer, VideoPlayerPanel, theme radio menu items (`themeLight`, `themeDark`, `themeNavy`, `themeForest`).

### `ui/WelcomeController.java`
Dark welcome screen (#111111 background), logo image, "Footage Organizer" subtitle, primary + ghost buttons. "New Project" opens main window.

### `ui/ClipMatchViewModel.java`
JavaFX-friendly table row wrapper. Properties: `filename`, `transcript`, `sceneNumber` (editable), `sceneHeading` (auto-derived from sceneNumber), `matchType`, `exportName` (user-overridable). Has `setExportNameComputed()` (no-op if user customized) and `setExportNameByUser()` (sets customized flag).

### `ui/ScriptRenderer.java`
Renders FDX scenes as HTML with screenplay formatting (scene headings, action, character, dialogue). Used for `.fdx` files only.

### `ui/ScriptViewBridge.java`
Java object exposed to WebView JS via `window.bintro`. Has `selectClip(String filename)` called from JS gutter bar clicks.

### `ui/PdfPageViewer.java`
Lazy-loading PDF viewer. Fast first pass creates placeholders from page dimensions. Viewport scroll listener triggers background rendering of visible pages (`RENDER_SCALE = 1.0f`). Gutter bars are JavaFX `Rectangle` shapes positioned using `ScenePositionIndex` pixel coordinates. Has `dispose()` method.

### `ui/VideoPlayerPanel.java`
JavaFX VBox with `MediaView`, play/pause button, scrub `Slider`, timecode `Label` (HH:MM:SS:FF at 24fps), volume `Slider`. Black background. Handles `MediaException` for unsupported formats (MXF, R3D, ProRes) with friendly fallback message. Initially hidden; shown when a clip row is selected.

## Theming

Four themes: **Light** (default), **Dark**, **Navy**, **Forest**.

`theme.css` uses `-bintro-*` CSS custom properties. Theme class applied to scene root:
- Light: no class (base `.root` definition)
- Dark: `.theme-dark`
- Navy: `.theme-navy`
- Forest: `.theme-forest`

Theme management is in `App.java` — no separate `ThemeManager.java` exists. The methods `setActiveTheme()`, `applyActiveTheme()`, and `attachThemeStylesheet()` handle all theme logic.

**Known issue:** Themes stopped working after features 19+20 were merged. This is a pending bug fix.

## config.properties

```properties
# Local AI backend (Msty, Ollama, or any OpenAI-compatible server)
ai.backend.url=http://localhost:11434/v1

# Model for dialogue matching
ai.match.model=qwen2.5

# Model for visual matching
ai.visual.model=gemma4

# Key frames to extract per clip for visual matching
ai.visual.frames=4

# whisper.cpp binary
whisper.binary.path=whisper/whisper

# whisper model
whisper.model.path=whisper/models/ggml-base.en.bin
```

`config.properties` is gitignored. Copy from `config.properties.example`.

## Output Folder Format

```
output_dir/
├── Scene_01/
│   ├── 01_001.mp4
│   └── 01_002.mp4
├── Scene_03/
│   └── 03_001.mxf
├── Unmatched/
│   └── Unmatched_001.mp4
└── rename_log.csv
```

## Feature Prompt History

All features were built by pasting prompts from `prompts/` into Claude Code sessions:

| Prompt file | Feature | Status |
|---|---|---|
| feature-05-ui-maincontroller.md | Main UI + controller | Done |
| feature-06-transcript-log-script-viewer.md | Transcript log + script viewer | Done |
| feature-07-matching-fix-review-flow.md | Matching fix + review before export | Done |
| feature-08-local-fallback-matcher.md | LocalSceneMatcher offline fallback | Done |
| feature-10-script-formatting-gutter-bars.md | Script formatting + gutter bars | Done |
| feature-11-welcome-screen-menu-theming.md | Welcome screen + menu | Done |
| feature-12-script-viewer-fixes.md | Script viewer fixes | Done |
| feature-13-pdf-page-renderer.md | PDF rendered as images | Done |
| feature-14-pdf-lazy-rendering.md | Lazy PDF rendering | Done |
| feature-15-accurate-gutter-positioning.md | Pixel-accurate gutter bar positions | Done |
| feature-16-ui-redesign.md | Full UI redesign | Done |
| feature-17-msty-local-backend.md | Replace Claude API with Msty | Done |
| feature-18-smart-rename-export.md | Smart rename on export + CSV log | Done |
| feature-19-video-player.md | In-app video player | Done |
| feature-20-themes.md | Light/Dark/Navy/Forest themes | Done (themes have a bug — pending fix) |
| feature-21-slate-detection.md | Slate/clapperboard detection | **NOT YET BUILT** |
| feature-22-java17-maven-port.md | Migrate to Java 17 + Maven | Done |

## Next Feature

**Feature 21 — Slate Detection** (`prompts/feature-21-slate-detection.md`)

Reads clapperboard from the first 10 seconds of each clip using Gemma 4. Extracts scene/take/camera/roll as JSON. If detected, treated as ground truth and drives export naming (`01A_T4_CA.mp4`). Falls back gracefully if no slate found.

Classes to create: `matching/SlateData.java`, `matching/SlateDetector.java`

## Key Constraints

- **All transcription runs locally.** whisper.cpp must be bundled or set up manually. No paid transcription APIs.
- **Msty is the AI backend.** Runs locally at `http://localhost:11434/v1`. No cloud AI.
- **Java 17 + Maven only.** Run with `mvn javafx:run`. Do not add Gradle files.
- **Original files are never moved or deleted.** Only copied to the output directory.
- **Output folder format:** `Scene_01/`, `Scene_02/`, `Unmatched/` inside a user-chosen output directory.
