# Feature 9 — Script Viewer: Proper Formatting + Gutter Bar Clip Indicators

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

Bintro has a script viewer (`WebView` in a "Script" tab) that currently renders
the screenplay as flat monospace text. Two things need fixing:

1. The script should look like a real printed screenplay (proper formatting per element type)
2. After matching, each clip's coverage should be shown as a colored vertical bar
   in a gutter to the left of the script — not by highlighting the text itself

---

## Task

### 1. Extend the Scene model to store structured paragraphs

Currently `Scene` stores `heading` and `fullText` as flat strings. To render proper
screenplay formatting, we need to know which lines are action, character cues,
dialogue, parentheticals, etc.

**Add a new record** `ScriptElement` in `com.bintro.parser`:
```java
public record ScriptElement(ElementType type, String text) {
    public enum ElementType {
        SCENE_HEADING, ACTION, CHARACTER, DIALOGUE, PARENTHETICAL, TRANSITION, OTHER
    }
}
```

**Update `Scene`** to add a `List<ScriptElement> elements` field alongside the
existing `heading` and `fullText` (keep those for backward compatibility with
`ScriptParser` and `LocalSceneMatcher`):
```java
public record Scene(int sceneNumber, String heading, String fullText, List<ScriptElement> elements) {
    // Convenience constructor for existing callers that don't pass elements:
    public Scene(int sceneNumber, String heading, String fullText) {
        this(sceneNumber, heading, fullText, List.of());
    }
}
```

**Update `ScriptParser.parseFromFDX()`** — the FDX parser already knows element
types from the `Type` attribute. Map them to `ScriptElement.ElementType`:
- `"Scene Heading"` → `SCENE_HEADING`
- `"Action"` → `ACTION`
- `"Character"` → `CHARACTER`
- `"Dialogue"` → `DIALOGUE`
- `"Parenthetical"` → `PARENTHETICAL`
- `"Transition"` → `TRANSITION`
- anything else → `OTHER`

Build the `elements` list alongside the existing `currentBody` accumulation.

**Update `ScriptParser.parseFromText()` (PDF)** — do a best-effort classification
of each line using these heuristics (in order):
- Line matches the slugline regex → `SCENE_HEADING`
- Line is ALL CAPS, 2–40 chars, no lowercase, not a slugline → `CHARACTER`
- Line starts with `(` and ends with `)` → `PARENTHETICAL`
- Previous element was `CHARACTER` or `PARENTHETICAL` → `DIALOGUE`
- Otherwise → `ACTION`

---

### 2. Rewrite `ScriptRenderer.java` — proper formatting + gutter bars

Replace the existing renderer entirely.

#### Layout structure

The page uses a two-column CSS grid:
```
[ gutter (dynamic width) ] [ script text ]
```

The gutter width grows automatically as more clips are assigned to the same scene
(each clip gets its own 10px bar column with 2px gap between bars).

```html
<div class="scene" id="scene-N">
  <div class="gutter" id="gutter-N">
    <!-- colored bars injected here, one per matched clip -->
  </div>
  <div class="script-content">
    <div class="scene-heading">INT. COFFEE SHOP - DAY</div>
    <div class="action">A busy morning. Customers crowd the counter.</div>
    <div class="character">SARAH</div>
    <div class="parenthetical">(quietly)</div>
    <div class="dialogue">I never asked for this.</div>
  </div>
</div>
```

#### Gutter bars

For each clip matched to a scene:
- Render a `<div class="gutter-bar clip-N" data-clip="FILENAME" data-scene="N">`
  inside `<div class="gutter-N">`
- The bar's height matches the scene content height via CSS (`height: 100%`)
- Each clip gets its own column in the gutter (stacked side by side, not overlapping)
- `data-clip` holds the filename for the tooltip; `data-scene` for click-to-select

Use JavaScript (inline `<script>` in the HTML) to:
- Show a tooltip on hover: `element.title = data-clip value + "\n" + duration`
- On click: call `window.bintro.selectClip(filename)` — a Java-to-JS bridge
  (see MainController section below)

#### CSS

```css
body {
  font-family: 'Courier New', monospace;
  font-size: 13px;
  background: #1e1e1e;
  color: #d4d4d4;
  margin: 0;
  padding: 0;
}

.scene {
  display: grid;
  grid-template-columns: auto 1fr;
  margin-bottom: 28px;
}

.gutter {
  display: flex;
  flex-direction: row;
  gap: 2px;
  padding-right: 8px;
  min-width: 8px;
}

.gutter-bar {
  width: 10px;
  border-radius: 3px;
  cursor: pointer;
  opacity: 0.85;
  transition: opacity 0.15s;
}
.gutter-bar:hover { opacity: 1.0; }

.script-content { padding: 0 24px 0 0; }

/* Screenplay element formatting */
.scene-heading {
  color: #ffffff;
  font-weight: bold;
  text-transform: uppercase;
  margin-bottom: 12px;
  padding-top: 4px;
}

.action {
  color: #cccccc;
  margin-bottom: 10px;
  line-height: 1.5;
}

.character {
  color: #e8e8e8;
  text-align: center;
  margin-top: 10px;
  margin-bottom: 0;
  text-transform: uppercase;
}

.parenthetical {
  color: #aaaaaa;
  text-align: center;
  margin: 0;
  font-style: italic;
}

.dialogue {
  color: #d4d4d4;
  margin: 0 80px 10px 80px;
  line-height: 1.5;
}

.transition {
  color: #888888;
  text-align: right;
  margin-top: 8px;
  font-style: italic;
}

/* Gutter bar color palette */
.clip-0 { background: #4a90d9; }
.clip-1 { background: #e07b54; }
.clip-2 { background: #6abf69; }
.clip-3 { background: #b07dd4; }
.clip-4 { background: #e8c84a; }
.clip-5 { background: #4ac4c4; }

/* Visual clip indicator (future use) */
.clip-visual { background: transparent;
               border: 1px dashed #f0c040; }
```

#### Gutter bar span detection (where the bar starts and ends)

For DIALOGUE clips: use the subsequence span-finding algorithm to determine which
`ScriptElement` indices the clip's transcript covers:
- Walk through the scene's `elements` list
- Find the range `[firstElementIdx, lastElementIdx]` where the transcript words
  appear in order (subsequence match across element text, lowercased, punctuation stripped)
- The gutter bar covers only those elements, not the whole scene

Render the scene as a series of element rows. Each element row is a `<div>`.
The gutter bar's `grid-row` span in CSS covers only the matched element rows.

If span detection finds no match (e.g. no dialogue, or local matcher was used),
fall back to showing the bar for the full scene height.

For VISUAL clips: always show the bar for the full scene height (no span detection).

---

### 3. Update `MainController.java`

**JS bridge for click-to-select:**
After loading HTML into the WebView, register a Java object on the JS bridge:
```java
scriptWebView.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
    if (state == Worker.State.SUCCEEDED) {
        JSObject window = (JSObject) scriptWebView.getEngine().executeScript("window");
        window.setMember("bintro", new ScriptViewBridge(viewModels, clipTable));
    }
});
```

Create `ui/ScriptViewBridge.java`:
```java
public class ScriptViewBridge {
    private final List<ClipMatchViewModel> viewModels;
    private final TableView<ClipMatchViewModel> table;

    public ScriptViewBridge(List<ClipMatchViewModel> viewModels,
                            TableView<ClipMatchViewModel> table) { ... }

    // Called from JS: window.bintro.selectClip(filename)
    public void selectClip(String filename) {
        Platform.runLater(() -> {
            viewModels.stream()
                .filter(vm -> vm.getFilename().equals(filename))
                .findFirst()
                .ifPresent(vm -> {
                    table.getSelectionModel().select(vm);
                    table.scrollTo(vm);
                });
        });
    }
}
```

**Re-render on scene number edit:** after any `sceneNumber` change in the table,
call `refreshScriptView()` which rebuilds and reloads the HTML.

**Scroll to scene on row selection:**
```java
scriptWebView.getEngine().executeScript(
    "document.getElementById('scene-" + sceneNum + "').scrollIntoView({behavior:'smooth'})");
```

---

## Constraints

- Java 21, JavaFX 21 — no new Gradle dependencies.
- `javafx.web` must be in the `build.gradle` modules list.
- Create: `ui/ScriptRenderer.java`, `ui/ScriptViewBridge.java`,
  `matching/MatchType.java`, `parser/ScriptElement.java`
- Modify: `parser/Scene.java`, `parser/ScriptParser.java`,
  `MainView.fxml`, `MainController.java`, `ui/ClipMatchViewModel.java`,
  `build.gradle`
- Keep `Scene(int, String, String)` 3-arg constructor working — do not break
  `LocalSceneMatcher` or any existing tests.
- After implementing: `./gradlew compileJava` passes, `./gradlew run` launches.

## Done when

- Script tab renders a properly formatted screenplay: scene headings bold/caps,
  character names centered, dialogue indented, action lines full width.
- Each matched clip shows a narrow colored gutter bar to the left of its matched
  scene, spanning only the lines its transcript covers (or full scene if no span found).
- Multiple clips on the same scene stack as side-by-side bars.
- Hovering a gutter bar shows a tooltip with the clip filename.
- Clicking a gutter bar selects that clip's row in the TableView.
- `./gradlew compileJava` passes with no errors.
