# Feature 9 — Script Viewer Highlighting

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

Bintro has a script viewer panel (a `TextArea` inside a "Script" tab) that shows
the full parsed screenplay. After Phase 1 (transcribe + match) completes, each clip
is matched to a scene. We want to highlight the region of that scene the clip covers —
found by locating the transcript words in order within the scene text, then highlighting
the contiguous span from first match to last.

The current `TextArea` cannot do per-character styling. Replace it with a `WebView`
that renders the script as HTML with CSS-based highlighting.

---

## Task

### 1. Replace the Script `TextArea` with a `WebView`

In `MainView.fxml`, in the "Script" tab, replace the `TextArea` with a `WebView`:
```xml
<WebView fx:id="scriptWebView" />
```

Add the necessary import: `<?import javafx.scene.web.WebView?>`

### 2. Create `ScriptRenderer.java`

Create `src/main/java/com/bintro/ui/ScriptRenderer.java`.

It takes a `List<Scene>` and a `Map<Integer, List<ClipMatchViewModel>>` (scene number
→ clips matched to that scene) and returns a complete HTML string.

**Span-finding algorithm (core of this feature):**

For each clip matched to a scene, find the contiguous span of the scene's text that
the clip's transcript covers, in order:

```
findSpan(String sceneText, String transcript) → int[2] {startChar, endChar}
```

1. Tokenize both `sceneText` and `transcript` into words:
   - Lowercase, strip punctuation, split on whitespace
   - Keep track of each word's character position in the original `sceneText`
2. Walk through the scene's word list trying to match transcript words **in order**
   (subsequence match — not necessarily consecutive, allows for action lines between
   dialogue words):
   - For each transcript word, find its next occurrence in the scene word list
     after the previous match position
   - Record the character positions of the first and last matched words
3. Return `[startChar, endChar]` of the matched span in the original scene text.
   If fewer than half the transcript words match, return `null` (no reliable span).

**Rendering:**

For each scene:
- Wrap the text in `<div class="scene" id="scene-N">`
- Scene heading → `<h3 class="scene-heading">`
- Scene body: insert the clip highlight span into the text as an HTML `<mark>`:
  - Everything before the span: plain text
  - The span itself: `<span class="clip-highlight clip-N">…</span>`
  - Everything after the span: plain text
- If multiple clips match the same scene, their spans may overlap — render each
  span separately, allowing them to stack visually
- HTML-escape all text before inserting (`<`, `>`, `&`, `"`)

**CSS to embed in the `<head>`:**
```css
body { font-family: 'Courier New', monospace; font-size: 13px;
       background: #1e1e1e; color: #d4d4d4; padding: 20px; line-height: 1.6; }
.scene { margin-bottom: 32px; border-left: 3px solid #444; padding-left: 12px; }
.scene-heading { color: #ffffff; font-weight: bold; text-transform: uppercase;
                 font-size: 14px; margin-bottom: 8px; }
.scene-body { color: #cccccc; white-space: pre-wrap; }

/* Highlight palette — one color per clip slot (cycle if more than 6) */
.clip-highlight { border-radius: 3px; padding: 1px 2px; }
.clip-0 { background: #264f78; color: #fff; }
.clip-1 { background: #4b3832; color: #fff; }
.clip-2 { background: #2d4a1e; color: #fff; }
.clip-3 { background: #5a2d82; color: #fff; }
.clip-4 { background: #7a4419; color: #fff; }
.clip-5 { background: #1a4a4a; color: #fff; }

/* Groundwork for future visual/insert shot highlighting */
.clip-visual { background: transparent; border-bottom: 2px solid #f0c040;
               color: inherit; border-radius: 0; padding: 0; }
```

### 3. Update `MainController.java`

- Change `scriptTextArea` field to `scriptWebView` (`WebView`).
- On script load (`onSelectScript()`): render with empty clip map, load into WebView:
  ```java
  scriptWebView.getEngine().loadContent(
      ScriptRenderer.render(scenes, Map.of()), "text/html");
  ```
- After Phase 1 completes: rebuild clip map and re-render with highlights:
  ```java
  Map<Integer, List<ClipMatchViewModel>> byScene = viewModels.stream()
      .filter(vm -> vm.getSceneNumber() > 0)
      .collect(Collectors.groupingBy(ClipMatchViewModel::getSceneNumber));
  scriptWebView.getEngine().loadContent(
      ScriptRenderer.render(scenes, byScene), "text/html");
  ```
- When the user edits a scene number in the table, re-render highlights.
- When a row is selected in the `TableView`, scroll the WebView to that scene:
  ```java
  scriptWebView.getEngine().executeScript(
      "document.getElementById('scene-" + sceneNum + "').scrollIntoView({behavior:'smooth'})");
  ```

### 4. Groundwork for visual matching (no dialogue)

Add `MatchType` enum in `com.bintro.matching`:
```java
public enum MatchType { DIALOGUE, VISUAL }
```

Add `matchType` field to `ClipMatchViewModel` (default `DIALOGUE`).

Add a "Type" column to the `TableView` — not editable, always "Dialogue" for now.

In `ScriptRenderer`, when building highlights:
- `DIALOGUE` → run the span-finding algorithm, highlight matched span
- `VISUAL` → skip span finding; instead add a subtle left-border highlight on the
  entire scene heading (`class="scene-heading visual-match"`) to indicate a visual
  clip is assigned to this scene, with no text-level highlighting

This stubs in the visual path so the future feature just sets `matchType = VISUAL`.

---

## Constraints

- Java 21, JavaFX 21 only — no new Gradle dependencies.
- Add `javafx.web` to `build.gradle` modules list.
- Create: `ui/ScriptRenderer.java`, `matching/MatchType.java`
- Modify: `MainView.fxml`, `MainController.java`, `ui/ClipMatchViewModel.java`,
  `build.gradle`
- After implementing: `./gradlew compileJava` passes, `./gradlew run` launches.

## Done when

- Script tab shows screenplay in a dark-themed monospace WebView.
- After Run completes, each matched scene shows a highlighted span covering the
  lines the clip's transcript corresponds to — in the order they appear in the
  scene, not scattered word-by-word.
- Multiple clips matched to the same scene each show their own colored span.
- Selecting a clip row scrolls the script to the matched scene.
- Editing a scene number updates the highlights in real time.
- Visual clips (stubbed, Type = "Dialogue" for now) have their path wired up.
