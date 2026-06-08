# Feature 19 — Slate Detection via Gemma 4

> Paste this into a Claude Code session opened at the Bintro project root.
> Requires feature 17 (Msty backend) and feature 18 (smart rename) to be built first.

---

## Context

A slate (clapperboard) at the head of a take contains the most reliable metadata
in the production: scene number, take number, camera ID, roll, and sometimes
director/DP names. This feature extracts that data automatically by sending the
first few frames of each clip to Gemma 4 via Msty, then uses it to build
precise export filenames like `01A_T4_C2.mp4`.

If no slate is detected, the app falls back to the feature 18 rename format
(`01_001.mp4`) or a manual entry from the editor.

---

## New data model

Add `SlateData` record in `com.bintro.matching`:
```java
public record SlateData(
    String scene,       // e.g. "1A" or "42B"
    String take,        // e.g. "4"
    String camera,      // e.g. "A", "B", "C2"
    String roll,        // e.g. "R14" — may be empty
    boolean detected    // false if no slate found
) {
    public static SlateData notDetected() {
        return new SlateData("", "", "", "", false);
    }

    /** Builds the export filename stem from slate data. e.g. "01A_T4_CA" */
    public String toFilenameStem() {
        String s = scene.isBlank() ? "??" : scene.replaceAll("[^A-Za-z0-9]", "");
        String t = take.isBlank()  ? ""   : "_T" + take.replaceAll("[^A-Za-z0-9]", "");
        String c = camera.isBlank()? ""   : "_C" + camera.replaceAll("[^A-Za-z0-9]", "");
        return s + t + c;
    }
}
```

Add `slateData` field to `ClipMatchViewModel`:
```java
private SlateData slateData = SlateData.notDetected();
```

---

## Task

### 1. Create `SlateDetector.java`

Create `src/main/java/com/bintro/matching/SlateDetector.java`.

```java
public class SlateDetector {
    public SlateData detect(Clip clip) { ... }
}
```

**Step 1 — Extract frames from the first 10 seconds only:**

Slates appear at the very start of a take. Only look at the first 10 seconds
(or the full clip if shorter). Extract 6 evenly-spaced frames from this window
using `FFmpegFrameGrabber`:

```java
long tenSecFrames = Math.min(
    (long)(grabber.getFrameRate() * 10),
    grabber.getLengthInVideoFrames()
);
```

Convert each frame to a base64 JPEG (same approach as `VisualMatcher`).

**Step 2 — Send to Gemma 4 via Msty:**

Use the OpenAI vision format (same as `VisualMatcher`). Send all 6 frames
in a single request for efficiency.

**Prompt:**
```
You are analyzing film production footage. Look at these frames carefully.

If you can see a clapperboard or slate in any of these frames, extract:
- Scene: the scene number/letter (e.g. "1A", "42", "7B")
- Take: the take number (e.g. "4", "12")  
- Camera: the camera identifier (e.g. "A", "B", "C2")
- Roll: the roll/reel number if visible (e.g. "R14") — omit if not visible

Respond ONLY with a JSON object in exactly this format:
{"scene":"1A","take":"4","camera":"A","roll":"R14","detected":true}

If there is no slate visible, respond with exactly:
{"detected":false}

Do not include any other text.
```

**Step 3 — Parse the JSON response:**

Parse the response manually (no new JSON library — hand-roll it):
```java
private static SlateData parseResponse(String json) {
    if (json.contains("\"detected\":false")) {
        return SlateData.notDetected();
    }
    String scene  = extractJsonString(json, "scene");
    String take   = extractJsonString(json, "take");
    String camera = extractJsonString(json, "camera");
    String roll   = extractJsonString(json, "roll");
    boolean detected = !scene.isBlank() || !take.isBlank();
    return new SlateData(scene, take, camera, roll, detected);
}

private static String extractJsonString(String json, String key) {
    String search = "\"" + key + "\":\"";
    int start = json.indexOf(search);
    if (start < 0) return "";
    start += search.length();
    int end = json.indexOf('"', start);
    return end > start ? json.substring(start, end) : "";
}
```

If parsing fails or Msty is unreachable: return `SlateData.notDetected()` and
log the error. Never crash — slate detection is always best-effort.

**Timeout:** use a 30-second HTTP timeout for slate detection (frames are larger
than text requests).

---

### 2. Integrate into the pipeline in `MainController`

Add slate detection as a new pipeline step, running **before** transcription:

```
For each clip:
  1. Detect slate (SlateDetector)       ← NEW
  2. Transcribe audio (Transcriber)
  3. Match to scene (SceneMatcher / VisualMatcher)
  4. Build export name
```

Status label updates:
```
"Checking slate for " + clip.filename() + " (" + idx + " of " + total + ")…"
```

Store `SlateData` on the `ClipMatchViewModel`:
```java
vm.setSlateData(slateData);
```

**If slate is detected**, use the slate's scene number for matching instead of
running the full AI matcher — it's already ground truth:
```java
if (slateData.detected() && !slateData.scene().isBlank()) {
    int sceneNum = parseSceneNumber(slateData.scene()); // strip letters, parse int
    result = new MatchResult(sceneNum, 1.0);
    vm.setMatchType(MatchType.DIALOGUE); // slate = confirmed match
} else {
    // run normal transcript/visual matching
}
```

Helper to parse scene number from slate (e.g. "1A" → 1, "42B" → 42):
```java
private static int parseSceneNumber(String scene) {
    String digits = scene.replaceAll("[^0-9]", "");
    try { return Integer.parseInt(digits); }
    catch (NumberFormatException e) { return 0; }
}
```

---

### 3. Update export naming in `Exporter` / `ClipMatchViewModel`

**Priority order for export filename:**

1. **Slate detected** → use `slateData.toFilenameStem()` → e.g. `01A_T4_CA`
2. **User manually entered** a custom export name → use that
3. **No slate, no manual entry** → use feature 18 computed name → `01_001`

In `ClipMatchViewModel.computeExportName()`:
```java
public String computeExportName() {
    if (exportNameCustomized) return exportName.get();
    if (slateData != null && slateData.detected()) return slateData.toFilenameStem();
    return computedFallbackName; // from feature 18
}
```

Update the "Export Name" column in the `TableView` to re-compute after slate
detection completes, so the editor sees the slate-derived name immediately.

---

### 4. Show slate info in the review table

Add a **"Slate"** column to the `TableView` (read-only, narrow):
- If slate detected: show `slateData.scene() + "/" + slateData.take()`
  e.g. `"1A/T4"` in green text (`-fx-text-fill: #4a8f4a`)
- If not detected: show `"—"` in muted gray

This gives the editor instant confirmation of which clips had a readable slate.

---

### 5. Add to `config.properties`

```properties
# Set to false to skip slate detection (speeds up pipeline if slates aren't used)
ai.slate.enabled=true
```

Check this flag at the start of `SlateDetector.detect()` — if `false`, return
`SlateData.notDetected()` immediately without making any API call.

---

## Constraints

- Java 21 — no new Gradle dependencies
- Slate detection must never block or crash the pipeline — always falls back
- Create: `matching/SlateData.java`, `matching/SlateDetector.java`
- Modify: `MainController.java`, `ClipMatchViewModel.java`, `Exporter.java`,
  `config.properties`, `config.properties.example`
- After implementing: `./gradlew compileJava` passes, `./gradlew run` launches

## Done when

- Pipeline runs slate detection on the first 10 seconds of each clip
- Clips with a readable slate export as `01A_T4_CA.mp4` (or similar)
- Clips without a slate fall back to `01_001.mp4` from feature 18
- The review table shows a "Slate" column with detected scene/take in green
- Setting `ai.slate.enabled=false` in config skips slate detection entirely
- `./gradlew compileJava` passes with no errors
