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
 * <p>The page itself is a white card sitting on a neutral grey background,
 * styled to evoke a printed screenplay. Each scene is a two-column CSS grid:
 * a flex {@code .gutter} column on the left holding clip bars, and the
 * {@code .script-content} column on the right with the screenplay text.
 *
 * <p>Two render paths:
 * <ul>
 *   <li><b>Structured (FDX, and PDFs after Feature-12 heuristic):</b> each
 *       {@link ScriptElement} becomes its own typed {@code <div>}. Gutter bars
 *       are positioned by percentage over the element count, using
 *       {@link #findElementSpan}'s in-order subsequence match for the
 *       first/last element a clip covers.</li>
 *   <li><b>Flat fallback (legacy 3-arg {@code Scene} with no elements):</b>
 *       the scene body is rendered as a {@code .scene-body-flat} block of
 *       line-separated text. Gutter bars are positioned by percentage over
 *       the total line count via {@link #findFlatSpan}. The union of all
 *       dialogue clip ranges is wrapped in a {@code .gutter-span-region}
 *       marker span.</li>
 * </ul>
 *
 * <p>An inline {@code <script>} wires hover tooltips and click handlers; the
 * click handler invokes {@code window.bintro.selectClip(filename)} on the
 * Java-side {@link ScriptViewBridge}.
 */
public final class ScriptRenderer {

    private static final int PALETTE_SIZE = 6;

    /** Element-based span: clip is dropped if fewer than half of its
     *  transcript words align. */
    private static final double ELEMENT_MATCH_MIN_RATIO = 0.5;

    /** Flat-text span: clip falls back to full-scene if fewer than 30 % of
     *  transcript words align. */
    private static final double FLAT_MATCH_MIN_RATIO = 0.3;

    private static final String CSS = """
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
          padding: 72px 80px 72px 100px;
          box-shadow: 0 2px 12px rgba(0,0,0,0.18);
          min-height: 100vh;
          box-sizing: border-box;
        }

        /* Two-column layout per scene: gutter | content */
        .scene {
          display: grid;
          grid-template-columns: auto 1fr;
          margin-bottom: 32px;
          position: relative;
        }

        .gutter {
          display: flex;
          flex-direction: row;
          align-items: flex-start;
          gap: 2px;
          padding-right: 10px;
          min-width: 8px;
          /* Gutter sits outside the left margin of the page */
          margin-left: -52px;
          position: relative;
        }

        .gutter-bar {
          width: 10px;
          border-radius: 3px;
          cursor: pointer;
          opacity: 0.75;
          transition: opacity 0.15s;
          /* margin-top + height are set inline as percentages of the scene height */
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

        .other {
          color: #222222;
          margin-bottom: 10px;
        }

        /* PDF fallback — unstructured scene body */
        .scene-body-flat {
          color: #111111;
          white-space: pre-wrap;
        }

        /* Marker for the line region a clip covers in the flat fallback —
         * intentionally no visual change; the gutter bar communicates coverage. */
        .gutter-span-region { }

        /* Gutter bar palette — kept in sync with -clip-N in theme.css */
        .clip-0 { background: #4a90d9; }
        .clip-1 { background: #e07b54; }
        .clip-2 { background: #5aaa6a; }
        .clip-3 { background: #b07dd4; }
        .clip-4 { background: #c8a830; }
        .clip-5 { background: #4abcbc; }

        .clip-visual { background: transparent; border: 2px dashed #c8a830; }
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

    /** A clip's bar coverage expressed as percentages of the scene height. */
    private record BarPlacement(double marginTopPct, double heightPct, int slot,
                                 MatchType matchType, String filename,
                                 String durationLabel) {
    }

    /** Line-range coverage in the flat fallback path. */
    private record FlatRange(int firstLine, int lastLine) {
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
        html.append("<div class=\"page\">");

        for (Scene scene : scenes) {
            List<ClipMatchViewModel> sceneClips = clipsByScene.getOrDefault(
                scene.sceneNumber(), List.of());

            if (scene.elements() == null || scene.elements().isEmpty()) {
                renderFlatScene(html, scene, sceneClips);
            } else {
                renderStructuredScene(html, scene, scene.elements(), sceneClips);
            }
        }

        html.append("</div>");
        html.append("<script>").append(INLINE_JS).append("</script>");
        html.append("</body></html>");
        return html.toString();
    }

    // ─── Structured scene path (FDX / heuristically-typed PDF) ─────────────

    private static void renderStructuredScene(StringBuilder html, Scene scene,
                                              List<ScriptElement> elements,
                                              List<ClipMatchViewModel> sceneClips) {
        int total = Math.max(1, elements.size());
        List<BarPlacement> bars = new ArrayList<>();
        int dialogueIdx = 0;
        for (ClipMatchViewModel vm : sceneClips) {
            int slot = dialogueIdx % PALETTE_SIZE;
            dialogueIdx++;
            int[] range;
            if (vm.matchType() == MatchType.VISUAL) {
                range = new int[]{0, elements.size() - 1};
            } else {
                range = findElementSpan(elements, vm.transcript());
                if (range == null) {
                    // Per spec: fall back to full-scene bar when span unreliable.
                    range = new int[]{0, elements.size() - 1};
                }
            }
            double marginTopPct = 100.0 * range[0] / total;
            double heightPct = 100.0 * (range[1] - range[0] + 1) / total;
            String filename = vm.getFilename();
            String dur = formatDuration(vm.clip() == null ? null : vm.clip().duration());
            bars.add(new BarPlacement(marginTopPct, heightPct, slot, vm.matchType(),
                filename, dur));
        }

        html.append("<div class=\"scene\" id=\"scene-").append(scene.sceneNumber()).append("\">");
        appendGutter(html, bars, scene.sceneNumber());

        html.append("<div class=\"script-content\">");
        for (ScriptElement el : elements) {
            html.append("<div class=\"").append(cssClassFor(el.type())).append("\">")
                .append(escapeHtml(el.text() == null ? "" : el.text()))
                .append("</div>");
        }
        html.append("</div>");

        html.append("</div>");
    }

    // ─── Flat scene path (legacy / empty-elements fallback) ────────────────

    private static void renderFlatScene(StringBuilder html, Scene scene,
                                        List<ClipMatchViewModel> sceneClips) {
        String body = scene.fullText() == null ? "" : scene.fullText();
        String[] lines = body.split("\n", -1);
        int totalLines = Math.max(1, lines.length);

        // Compute each clip's flat range + collect for union-region wrapping.
        List<FlatRange> dialogueRanges = new ArrayList<>();
        List<BarPlacement> bars = new ArrayList<>();
        boolean hasVisual = false;
        int dialogueIdx = 0;
        for (ClipMatchViewModel vm : sceneClips) {
            int slot = dialogueIdx % PALETTE_SIZE;
            dialogueIdx++;
            FlatRange range;
            if (vm.matchType() == MatchType.VISUAL) {
                hasVisual = true;
                range = new FlatRange(0, lines.length - 1);
            } else {
                range = findFlatSpan(lines, vm.transcript());
                if (range == null) {
                    range = new FlatRange(0, lines.length - 1);
                } else {
                    dialogueRanges.add(range);
                }
            }
            double marginTopPct = 100.0 * range.firstLine() / totalLines;
            double heightPct = 100.0 * (range.lastLine() - range.firstLine() + 1) / totalLines;
            bars.add(new BarPlacement(marginTopPct, heightPct, slot, vm.matchType(),
                vm.getFilename(),
                formatDuration(vm.clip() == null ? null : vm.clip().duration())));
        }

        // Union region for the in-body <span class="gutter-span-region"> marker.
        int unionStart = -1;
        int unionEnd = -1;
        if (hasVisual) {
            unionStart = 0;
            unionEnd = lines.length - 1;
        } else {
            for (FlatRange r : dialogueRanges) {
                if (unionStart < 0 || r.firstLine() < unionStart) {
                    unionStart = r.firstLine();
                }
                if (r.lastLine() > unionEnd) {
                    unionEnd = r.lastLine();
                }
            }
        }

        html.append("<div class=\"scene\" id=\"scene-").append(scene.sceneNumber()).append("\">");
        appendGutter(html, bars, scene.sceneNumber());

        html.append("<div class=\"script-content\">");
        if (scene.heading() != null && !scene.heading().isBlank()) {
            html.append("<div class=\"scene-heading\">")
                .append(escapeHtml(scene.heading()))
                .append("</div>");
        }
        html.append("<div class=\"scene-body-flat\">");
        appendFlatBody(html, lines, unionStart, unionEnd);
        html.append("</div>");
        html.append("</div>");

        html.append("</div>");
    }

    /**
     * Emits the body line-by-line with a single {@code <span class="gutter-span-region">}
     * wrapping the lines in [unionStart, unionEnd]. If no region is active, lines
     * are emitted as plain text.
     */
    private static void appendFlatBody(StringBuilder html, String[] lines,
                                       int unionStart, int unionEnd) {
        boolean regionActive = unionStart >= 0 && unionEnd >= unionStart;
        boolean inside = false;
        for (int i = 0; i < lines.length; i++) {
            if (regionActive && i == unionStart) {
                html.append("<span class=\"gutter-span-region\">");
                inside = true;
            }
            html.append(escapeHtml(lines[i]));
            if (i < lines.length - 1) {
                html.append('\n');
            }
            if (regionActive && i == unionEnd) {
                html.append("</span>");
                inside = false;
            }
        }
        // Safety net for edge cases (shouldn't trigger).
        if (inside) {
            html.append("</span>");
        }
    }

    // ─── Shared gutter rendering ───────────────────────────────────────────

    private static void appendGutter(StringBuilder html, List<BarPlacement> bars,
                                     int sceneNumber) {
        html.append("<div class=\"gutter\">");
        for (BarPlacement bar : bars) {
            String classes = "gutter-bar " + (bar.matchType() == MatchType.VISUAL
                ? "clip-visual"
                : "clip-" + bar.slot());
            html.append("<div class=\"").append(classes).append("\"")
                .append(" style=\"margin-top:")
                .append(String.format("%.2f", bar.marginTopPct())).append("%;height:")
                .append(String.format("%.2f", bar.heightPct())).append("%\"")
                .append(" data-clip=\"").append(escapeAttr(bar.filename())).append("\"")
                .append(" data-scene=\"").append(sceneNumber).append("\"")
                .append(" data-duration=\"").append(escapeAttr(bar.durationLabel())).append("\"")
                .append("></div>");
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

    // ─── Span detection ────────────────────────────────────────────────────

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

        if (firstElem < 0
            || matched < transcriptWords.size() * ELEMENT_MATCH_MIN_RATIO) {
            return null;
        }
        return new int[]{firstElem, lastElem};
    }

    /**
     * Same subsequence algorithm as {@link #findElementSpan} but over the flat
     * line list of an unstructured scene body. Returns the inclusive
     * {@code [firstLine, lastLine]} pair, or {@code null} if fewer than 30 %
     * of transcript words could be aligned (per spec).
     */
    static FlatRange findFlatSpan(String[] lines, String transcript) {
        if (lines == null || lines.length == 0
            || transcript == null || transcript.isBlank()) {
            return null;
        }

        List<String> sceneWords = new ArrayList<>();
        List<Integer> sceneLineIdx = new ArrayList<>();
        for (int li = 0; li < lines.length; li++) {
            for (String w : tokenizeWords(lines[li])) {
                sceneWords.add(w);
                sceneLineIdx.add(li);
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
        int firstLine = -1;
        int lastLine = -1;

        for (String tw : transcriptWords) {
            for (int j = cursor; j < sceneWords.size(); j++) {
                if (sceneWords.get(j).equals(tw)) {
                    int li = sceneLineIdx.get(j);
                    if (firstLine < 0) {
                        firstLine = li;
                    }
                    lastLine = li;
                    cursor = j + 1;
                    matched++;
                    break;
                }
            }
        }

        if (firstLine < 0
            || matched < transcriptWords.size() * FLAT_MATCH_MIN_RATIO) {
            return null;
        }
        return new FlatRange(firstLine, lastLine);
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
     * clips over an hour. Empty string for null/non-positive durations.
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

    private static String escapeAttr(String s) {
        return s == null ? "" : escapeHtml(s);
    }
}
