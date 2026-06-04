package com.bintro.ui;

import com.bintro.matching.MatchType;
import com.bintro.parser.Scene;
import com.bintro.parser.ScriptElement;
import com.bintro.parser.ScriptElement.ElementType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the parsed screenplay as a self-contained HTML document for display
 * in a {@code WebView}.
 *
 * <p>Each scene is a CSS Grid: clip-bar columns on the left, screenplay
 * element rows on the right. Bars sit in their own narrow columns and use
 * {@code grid-row} to span only the element rows their transcript covers
 * (subsequence match against per-element word lists). Scenes are addressable
 * as {@code <div id="scene-N">} so the controller can {@code scrollIntoView()}.
 *
 * <p>An inline {@code <script>} wires hover tooltips and click handlers on
 * each gutter bar; the click handler calls
 * {@code window.bintro.selectClip(filename)} on the Java-side
 * {@link ScriptViewBridge}.
 */
public final class ScriptRenderer {

    private static final int PALETTE_SIZE = 6;
    private static final int BAR_WIDTH_PX = 10;
    private static final int BAR_GAP_PX = 2;
    private static final int GUTTER_RIGHT_PAD_PX = 8;

    private static final String CSS = """
        body {
          font-family: 'Courier New', monospace;
          font-size: 13px;
          background: #1e1e1e;
          color: #d4d4d4;
          margin: 0;
          padding: 16px 0;
        }

        .scene {
          display: grid;
          margin-bottom: 28px;
          padding: 0 16px;
        }

        .gutter-bar {
          width: 10px;
          border-radius: 3px;
          cursor: pointer;
          opacity: 0.85;
          transition: opacity 0.15s;
          align-self: stretch;
        }
        .gutter-bar:hover { opacity: 1.0; }

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

        .other {
          color: #bbbbbb;
          margin-bottom: 8px;
        }

        /* Gutter bar color palette — kept in sync with -clip-N in theme.css */
        .clip-0 { background: #4a90d9; }
        .clip-1 { background: #e07b54; }
        .clip-2 { background: #5aaa6a; }
        .clip-3 { background: #b07dd4; }
        .clip-4 { background: #c8a830; }
        .clip-5 { background: #4abcbc; }

        /* Visual clip indicator */
        .clip-visual { background: transparent;
                       border: 1px dashed #f0c040; }
        """;

    private static final String INLINE_JS = """
        document.addEventListener('DOMContentLoaded', wireBars);
        // DOMContentLoaded may have already fired by the time the script runs.
        wireBars();
        function wireBars() {
          document.querySelectorAll('.gutter-bar').forEach(function(bar) {
            if (bar.dataset.wired) return;
            bar.dataset.wired = '1';
            var clip = bar.getAttribute('data-clip') || '';
            var dur = bar.getAttribute('data-duration') || '';
            bar.title = dur ? clip + '\\n' + dur : clip;
            bar.addEventListener('click', function() {
              if (window.bintro && window.bintro.selectClip) {
                window.bintro.selectClip(clip);
              }
            });
          });
        }
        """;

    /** A clip's row-range coverage within a scene, with its colour slot. */
    private record BarSpan(int firstRow, int lastRow, int slot, MatchType matchType,
                           String filename, String durationLabel) {
    }

    private ScriptRenderer() {
    }

    public static String render(List<Scene> scenes,
                                Map<Integer, List<ClipMatchViewModel>> clipsByScene) {
        if (scenes == null) {
            scenes = List.of();
        }
        if (clipsByScene == null) {
            clipsByScene = Map.of();
        }

        StringBuilder html = new StringBuilder(8192);
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">");
        html.append("<style>").append(CSS).append("</style></head><body>");

        for (Scene scene : scenes) {
            List<ScriptElement> elements = effectiveElements(scene);
            List<ClipMatchViewModel> sceneClips = clipsByScene.getOrDefault(
                scene.sceneNumber(), List.of());

            renderScene(html, scene, elements, sceneClips);
        }

        html.append("<script>").append(INLINE_JS).append("</script>");
        html.append("</body></html>");
        return html.toString();
    }

    /**
     * Produces the element list the renderer should walk. Real parser output
     * already populates {@link Scene#elements()}; the fallback synthesises a
     * SCENE_HEADING + single ACTION row from the legacy fields so a
     * 3-arg-constructed {@code Scene} still lays out sensibly.
     */
    private static List<ScriptElement> effectiveElements(Scene scene) {
        if (scene.elements() != null && !scene.elements().isEmpty()) {
            return scene.elements();
        }
        List<ScriptElement> synth = new ArrayList<>(2);
        if (scene.heading() != null && !scene.heading().isBlank()) {
            synth.add(new ScriptElement(ElementType.SCENE_HEADING, scene.heading()));
        }
        if (scene.fullText() != null && !scene.fullText().isBlank()) {
            synth.add(new ScriptElement(ElementType.ACTION, scene.fullText()));
        }
        return synth;
    }

    private static void renderScene(StringBuilder html, Scene scene,
                                    List<ScriptElement> elements,
                                    List<ClipMatchViewModel> sceneClips) {
        // Build bar specs first so we know the gutter column count.
        List<BarSpan> bars = new ArrayList<>();
        int dialogueIdx = 0;
        for (ClipMatchViewModel vm : sceneClips) {
            int slot = dialogueIdx % PALETTE_SIZE;
            dialogueIdx++;
            int[] range;
            if (vm.matchType() == MatchType.VISUAL) {
                // Visual clips always span the whole scene — no transcript to align.
                range = elements.isEmpty() ? null : new int[]{0, elements.size() - 1};
            } else {
                range = findElementSpan(elements, vm.transcript());
                if (range == null && !elements.isEmpty()) {
                    // Per spec: no reliable span → fall back to full scene.
                    range = new int[]{0, elements.size() - 1};
                }
            }
            if (range == null) {
                continue;
            }
            String filename = vm.getFilename();
            String dur = formatDuration(vm.clip() == null ? null : vm.clip().duration());
            bars.add(new BarSpan(range[0], range[1], slot, vm.matchType(), filename, dur));
        }

        int numBars = bars.size();

        // grid-template-columns: <NC>x BAR_WIDTH_PX + (gap col if NC>0) + 1fr
        StringBuilder cols = new StringBuilder();
        for (int i = 0; i < numBars; i++) {
            if (i > 0) {
                cols.append(' ').append(BAR_GAP_PX).append("px");
            }
            cols.append(' ').append(BAR_WIDTH_PX).append("px");
        }
        if (numBars > 0) {
            cols.append(' ').append(GUTTER_RIGHT_PAD_PX).append("px");
        }
        cols.append(" 1fr");

        html.append("<div class=\"scene\" id=\"scene-")
            .append(scene.sceneNumber())
            .append("\" style=\"grid-template-columns:")
            .append(cols)
            .append("\">");

        // Emit bars first (grid placement is explicit, so DOM order is purely
        // for source readability).
        for (int i = 0; i < bars.size(); i++) {
            BarSpan bar = bars.get(i);
            // Each bar gets its own column. Columns are 1-indexed and the bar
            // columns alternate with gap columns: col 1, 3, 5, ...
            int gridCol = i * 2 + 1;
            // grid-row is 1-indexed; lastRow is inclusive, grid-row end is exclusive.
            int rowStart = bar.firstRow() + 1;
            int rowEnd = bar.lastRow() + 2;

            String classes = "gutter-bar " + (bar.matchType() == MatchType.VISUAL
                ? "clip-visual"
                : "clip-" + bar.slot());

            html.append("<div class=\"").append(classes).append("\"")
                .append(" style=\"grid-column:").append(gridCol)
                .append(";grid-row:").append(rowStart).append('/').append(rowEnd)
                .append("\"")
                .append(" data-clip=\"").append(escapeAttr(bar.filename())).append("\"")
                .append(" data-scene=\"").append(scene.sceneNumber()).append("\"")
                .append(" data-duration=\"").append(escapeAttr(bar.durationLabel())).append("\"")
                .append("></div>");
        }

        // Content column is always the last column. `grid-column: -1` selects
        // the final implicit/explicit column line.
        for (int i = 0; i < elements.size(); i++) {
            ScriptElement el = elements.get(i);
            html.append("<div class=\"").append(cssClassFor(el.type())).append("\"")
                .append(" style=\"grid-column:-2 / -1;grid-row:").append(i + 1).append("\">")
                .append(escapeHtml(el.text() == null ? "" : el.text()))
                .append("</div>");
        }

        html.append("</div>");
    }

    private static String cssClassFor(ElementType type) {
        return switch (type) {
            case SCENE_HEADING -> "scene-heading";
            case ACTION -> "action";
            case CHARACTER -> "character";
            case DIALOGUE -> "dialogue";
            case PARENTHETICAL -> "parenthetical";
            case TRANSITION -> "transition";
            case OTHER -> "other";
        };
    }

    /**
     * Greedy subsequence match of transcript words against the per-element
     * word lists. Returns the inclusive {@code [firstElementIdx, lastElementIdx]}
     * range covering the first and last matched word's element, or
     * {@code null} if fewer than half the transcript words could be aligned.
     */
    static int[] findElementSpan(List<ScriptElement> elements, String transcript) {
        if (elements == null || elements.isEmpty()
            || transcript == null || transcript.isBlank()) {
            return null;
        }

        // Build a flat scene-word stream tagged with each word's element index.
        List<String> sceneWords = new ArrayList<>();
        List<Integer> sceneElemIdx = new ArrayList<>();
        for (int eIdx = 0; eIdx < elements.size(); eIdx++) {
            String t = elements.get(eIdx).text();
            if (t == null) {
                continue;
            }
            for (String w : tokenizeWords(t)) {
                sceneWords.add(w);
                sceneElemIdx.add(eIdx);
            }
        }
        if (sceneWords.isEmpty()) {
            return null;
        }

        List<String> transcriptWords = tokenizeWords(transcript);
        if (transcriptWords.isEmpty()) {
            return null;
        }

        int cursor = 0;
        int matched = 0;
        int firstElem = -1;
        int lastElem = -1;

        for (String tw : transcriptWords) {
            for (int j = cursor; j < sceneWords.size(); j++) {
                if (sceneWords.get(j).equals(tw)) {
                    int eIdx = sceneElemIdx.get(j);
                    if (firstElem < 0) {
                        firstElem = eIdx;
                    }
                    lastElem = eIdx;
                    cursor = j + 1;
                    matched++;
                    break;
                }
            }
        }

        if (firstElem < 0 || matched * 2 < transcriptWords.size()) {
            return null;
        }
        return new int[]{firstElem, lastElem};
    }

    private static List<String> tokenizeWords(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isWordChar(c)) {
                cur.append(Character.toLowerCase(c));
            } else if (cur.length() > 0) {
                words.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            words.add(cur.toString());
        }
        return words;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetter(c) || Character.isDigit(c) || c == '\'';
    }

    /**
     * Compact human-readable duration: {@code mm:ss}, or {@code h:mm:ss} for
     * clips over an hour. Empty string for null/negative durations.
     */
    static String formatDuration(Duration d) {
        if (d == null || d.isNegative() || d.isZero()) {
            return "";
        }
        long total = d.getSeconds();
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%d:%02d", m, s);
    }

    private static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Attribute-safe escape: like {@link #escapeHtml} but optimised for the
     *  attribute value context (no need to escape {@code >} but we do it anyway
     *  for safety). */
    private static String escapeAttr(String s) {
        return s == null ? "" : escapeHtml(s);
    }
}
