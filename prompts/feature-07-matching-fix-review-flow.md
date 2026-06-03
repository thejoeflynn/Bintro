# Feature 7 — Fix Scene Matching + Review Before Export

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

Bintro is a JavaFX 21 desktop app for video editors. The pipeline:
1. Scans a footage folder → `List<Clip>`
2. Parses a screenplay → `List<Scene>`
3. Transcribes each clip via whisper.cpp → transcript String
4. Matches each clip to a scene via Claude API → `MatchResult`
5. Exports sorted footage into scene-labeled folders

Two problems need fixing:

---

## Problem 1 — Scene matching silently fails

`SceneMatcher.match()` returns `MatchResult(0, 0.0)` (Unmatched) on any error
without surfacing why. The likely cause: `Config.get("claude.api.key")` reads from
`config.properties` using a relative path (`Path.of("config.properties")`), which
resolves relative to the JVM's working directory — not the project root. When
launched via `./gradlew run`, the working directory may not be the project root.

### Fix

In `Config.java`, update `ensureLoaded()` to look for `config.properties` in these
locations in order, using the first one found:
1. `System.getProperty("user.dir")` + `/config.properties`
2. The directory containing the running JAR (for packaged distributions)
3. Classpath fallback (`/config.properties`)

Also add debug logging so misses are visible:
```java
System.err.println("Config: looking for config.properties at " + path.toAbsolutePath());
System.err.println("Config: loaded " + loaded.size() + " properties");
```

In `SceneMatcher.match()`, replace the silent `return unmatched()` on HTTP errors
with a logged error that includes the full response body, so the user can see
what went wrong (wrong API key, quota exceeded, etc.).

Also add logging at the start of `match()`:
```java
System.out.println("SceneMatcher: matching clip '" + clip.filename() + 
    "' against " + scenes.size() + " scenes, transcript length=" + 
    (transcript == null ? 0 : transcript.length()) + " chars");
```

---

## Problem 2 — Export runs automatically without user review

Currently `MainController` runs the full pipeline (transcribe → match → export)
in one background task and exports immediately. The user needs to review and
optionally correct matches before anything is exported.

### New flow

Split the pipeline into two phases:

**Phase 1 — Analysis (runs automatically on "Run"):**
1. Transcribe all clips
2. Match all clips to scenes
3. Show results to the user for review

**Phase 2 — Export (runs only after user confirms):**
4. User reviews matches, corrects any that are wrong
5. User clicks "Export" → footage is sorted into output folders

### Implementation

**New data model:** Add `ClipMatchViewModel` (in `ui/` package) — a JavaFX-friendly
wrapper around a match result for display and editing:
```java
public class ClipMatchViewModel {
    private final Clip clip;
    private final StringProperty filename;      // display only
    private final IntegerProperty sceneNumber;  // user can edit
    private final StringProperty transcript;    // display only
    private final StringProperty sceneHeading;  // derived from sceneNumber
}
```

**Replace the clip `ListView` with a `TableView<ClipMatchViewModel>`** with columns:
- "Clip" (filename, not editable)
- "Transcript" (first 80 chars of transcript, not editable)  
- "Scene #" (editable `IntegerProperty` — user can type to correct)
- "Scene Heading" (auto-updates when Scene # changes, not editable)

**Add an "Export" button** to the toolbar (disabled until Phase 1 completes).

**Phase 1 background task** (`onRun()`):
- Runs transcribe + match for all clips
- On completion, populates the `TableView` with results
- Enables the Export button
- Does NOT call `Exporter`

**Phase 2** (`onExport()`):
- Reads the current `sceneNumber` from each `ClipMatchViewModel` (user may have
  changed some)
- Calls `Exporter.export(matches, outputDir)` on a background thread
- Shows the completion `Alert` with output path

**Scene heading lookup:** When the user edits a Scene # cell, look up the heading
from the loaded `List<Scene>` and update the "Scene Heading" column automatically.
If the entered number doesn't match any scene, show "Unknown" in red.

---

## Constraints

- Java 21, JavaFX 21 only — no new Gradle dependencies.
- Modify: `Config.java`, `SceneMatcher.java`, `MainController.java`, `MainView.fxml`
- Add: `ui/ClipMatchViewModel.java`
- Do not modify: `Transcriber.java`, `Exporter.java`, `ScriptParser.java`,
  `MediaScanner.java`, or any parser/media/export classes.
- After implementing, confirm: `./gradlew compileJava` passes with no errors.

## Done when

- Running the app and clicking Run prints config loading info and matcher logs
  to the terminal, making it clear whether the API key is loading correctly.
- After Phase 1 completes, a `TableView` shows each clip with its transcript
  snippet and matched scene number.
- The user can click any "Scene #" cell and type a correction.
- The "Export" button is only enabled after Phase 1 completes.
- Clicking Export sorts the footage using the (possibly user-corrected) matches.
