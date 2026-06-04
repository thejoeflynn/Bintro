# Feature 12 — Script Viewer Fixes: ALL CAPS Detection, White Page, PDF Gutter Bars

> Paste this into a Claude Code session opened at the Bintro project root.
> This builds on top of features 10 and 11 — run those first.

---

## Context

The script viewer has three issues to fix:

1. **ALL CAPS over-detection** — the PDF heuristic treats any short ALL CAPS line
   as a character name, but screenplays use ALL CAPS for emphasis in action lines,
   sound effects, prop callouts, and transitions too.

2. **Script appearance** — the viewer should look like a physical printed page:
   white page, black text, proper margins, sitting on a neutral background.
   Not a dark-themed code editor.

3. **PDF gutter bars** — PDFs lose element structure during parsing, so gutter bars
   need to fall back gracefully to word-level span detection on the flat scene text
   rather than relying on `ScriptElement` data that may be empty.

---

## Fix 1 — Smarter ALL CAPS character detection in `ScriptParser.parseFromText()`

Replace the single ALL CAPS heuristic with a multi-condition check.
A line is classified as `CHARACTER` only if ALL of the following are true:

1. The line is ALL CAPS (no lowercase letters)
2. Length is between 2 and 40 characters (after trimming)
3. Does not match the slugline regex (already have this)
4. Does not end with `"TO:"` — rules out transitions like `CUT TO:`, `FADE TO:`
5. Does not start with common action-line ALL CAPS keywords:
   `"TITLE:", "SUPER:", "TITLE CARD:", "SMASH CUT", "MATCH CUT",
    "TIME CUT", "INTERCUT", "BACK TO:", "LATER", "CONTINUOUS",
    "SAME TIME", "MOMENTS LATER"`
6. The **next non-empty line** exists and is either:
   - A parenthetical (starts with `(`, ends with `)`)
   - Shorter than 60 characters (likely dialogue, not a full action line)
7. The **previous non-empty line** is blank or is an action line
   (not another CHARACTER — two consecutive character names is a sign of misdetection)

If any condition fails → classify as `ACTION` instead.

Also add a post-processing pass after all lines are classified: if a `CHARACTER`
element is not followed within 3 elements by a `DIALOGUE` or `PARENTHETICAL`
element, reclassify it as `ACTION`.

---

## Fix 2 — White printed-page appearance in `ScriptRenderer.java`

Replace the dark theme CSS with a printed-page look:

```css
/* Page background — neutral gray so the white page sits on something */
body {
  background: #e8e8e8;
  margin: 0;
  padding: 32px 24px;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
}

/* The "page" — white card with drop shadow, fixed screenplay width */
.page {
  background: #ffffff;
  color: #111111;
  max-width: 680px;
  margin: 0 auto;
  padding: 72px 80px 72px 100px; /* screenplay margins: wider left for binding */
  box-shadow: 0 2px 12px rgba(0,0,0,0.18);
  min-height: 100vh;
  box-sizing: border-box;
}

/* Two-column layout per scene: gutter | content */
.scene {
  display: grid;
  grid-template-columns: auto 1fr;
  margin-bottom: 32px;
}

.gutter {
  display: flex;
  flex-direction: row;
  gap: 2px;
  padding-right: 10px;
  min-width: 8px;
  /* Gutter sits outside the left margin of the page */
  margin-left: -52px;
}

.gutter-bar {
  width: 10px;
  border-radius: 3px;
  cursor: pointer;
  opacity: 0.75;
  transition: opacity 0.15s;
}
.gutter-bar:hover { opacity: 1.0; }

.script-content { }

/* Screenplay element formatting — black text on white */
.scene-heading {
  color: #000000;
  font-weight: bold;
  text-transform: uppercase;
  margin-bottom: 16px;
  padding-top: 8px;
  border-top: 1px solid #cccccc;
}

.action {
  color: #111111;
  margin-bottom: 12px;
}

.character {
  color: #111111;
  text-align: center;
  margin-top: 12px;
  margin-bottom: 0;
  text-transform: uppercase;
  font-weight: bold;
}

.parenthetical {
  color: #333333;
  text-align: center;
  margin: 0;
}

.dialogue {
  color: #111111;
  margin: 0 80px 12px 80px;
}

.transition {
  color: #333333;
  text-align: right;
  margin-top: 8px;
}

/* PDF fallback — unstructured scene body */
.scene-body-flat {
  color: #111111;
  white-space: pre-wrap;
}

/* Gutter bar palette */
.clip-0 { background: #4a90d9; }
.clip-1 { background: #e07b54; }
.clip-2 { background: #5aaa6a; }
.clip-3 { background: #b07dd4; }
.clip-4 { background: #c8a830; }
.clip-5 { background: #4abcbc; }

.clip-visual { background: transparent; border: 2px dashed #c8a830; }
```

Wrap all scene `<div>`s inside a `<div class="page">` in the rendered HTML.

---

## Fix 3 — PDF gutter bar fallback in `ScriptRenderer.java`

When a scene has no `ScriptElement` data (i.e. `scene.elements().isEmpty()` —
the PDF case), the gutter bar span detection must use the flat `fullText` instead:

**Flat-text span detection:**
1. Split `scene.fullText()` into lines (by `\n`)
2. Tokenize each line into words (lowercase, strip punctuation)
3. Walk through lines in order, matching transcript words as a subsequence
4. Track which line index each transcript word first matches
5. Result: `firstLine` and `lastLine` indices within the scene's lines
6. Render the scene body as a `<div class="scene-body-flat">` with the text
   split into `<span>` blocks per line group:
   - Lines before `firstLine`: plain
   - Lines `firstLine` through `lastLine`: wrapped in
     `<span class="gutter-span-region">` (no color change — the gutter bar
     communicates coverage, not inline highlighting)
   - Lines after `lastLine`: plain
7. The gutter bar `height` is set inline to match the span region proportionally:
   use percentage of total scene line count.
   e.g. if span covers lines 4–9 of 20 total lines:
   `margin-top: 20%; height: 30%;` on the gutter bar div.

If fewer than 30% of transcript words match, show the bar for the full scene height
(no reliable span found).

For scenes WITH `ScriptElement` data (FDX), keep the existing element-based
span detection from feature 10 unchanged.

---

## Constraints

- Java 21, JavaFX 21 only — no new dependencies.
- Modify only: `parser/ScriptParser.java`, `ui/ScriptRenderer.java`
- Do not touch: `Scene.java`, `MainController.java`, `ScriptViewBridge.java`,
  or any matching/export classes.
- After implementing: `./gradlew compileJava` passes, `./gradlew run` launches.

## Done when

- Loading a PDF screenplay no longer misclassifies emphasized words, sound effects,
  or transitions as character names.
- The script viewer shows a white page with black text and a drop shadow,
  sitting on a gray background — like a printed screenplay.
- Gutter bars appear correctly for PDF-sourced scenes, spanning the approximate
  region of the scene the clip's transcript covers.
- FDX scenes continue to work as before with element-level span detection.
- `./gradlew compileJava` passes with no errors.
