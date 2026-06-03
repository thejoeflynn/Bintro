package com.bintro.ui;

import com.bintro.matching.MatchType;
import com.bintro.parser.Scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Renders the parsed screenplay as a self-contained HTML document for display
 * in a {@code WebView}. Each scene becomes an addressable
 * {@code <div id="scene-N">} so the controller can {@code scrollIntoView()}
 * when the user picks a row.
 *
 * <p>For each {@link ClipMatchViewModel} attached to a scene, the renderer
 * locates the contiguous region of the scene's body that the clip covers via
 * an in-order subsequence match of transcript words against scene-body words
 * (see {@link #findSpan}). The character range from the first matched word's
 * start to the last matched word's end is highlighted as a single
 * {@code <span class="clip-highlight clip-N">}. Spans from different clips on
 * the same scene may overlap — the renderer linearises overlaps so each
 * region carries the union of its covering clip slot classes and the emitted
 * HTML is always well-formed.
 *
 * <p>{@link MatchType#VISUAL} clips contribute no text-level highlight;
 * instead the scene heading is tagged with extra classes
 * ({@code visual-match clip-visual}) so the heading itself signals the assignment.
 */
public final class ScriptRenderer {

    private static final int PALETTE_SIZE = 6;

    private static final String CSS = """
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
        """;

    /** A word and its character span in the source string. */
    private record Token(String word, int start, int end) {
    }

    /** A clip's highlighted character range within a scene body, with its colour slot. */
    private record Span(int start, int end, int slot) {
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

        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">");
        html.append("<style>").append(CSS).append("</style></head><body>");

        for (Scene scene : scenes) {
            List<ClipMatchViewModel> sceneClips = clipsByScene.getOrDefault(
                scene.sceneNumber(), List.of());

            String body = scene.fullText() == null ? "" : scene.fullText();
            List<Token> sceneTokens = tokenize(body);

            List<Span> spans = new ArrayList<>();
            boolean hasVisual = false;
            int dialogueIdx = 0;
            for (ClipMatchViewModel vm : sceneClips) {
                if (vm.matchType() == MatchType.VISUAL) {
                    hasVisual = true;
                    continue;
                }
                int slot = dialogueIdx % PALETTE_SIZE;
                dialogueIdx++;
                int[] range = findSpan(sceneTokens, vm.transcript());
                if (range != null) {
                    spans.add(new Span(range[0], range[1], slot));
                }
            }

            html.append("<div class=\"scene\" id=\"scene-")
                .append(scene.sceneNumber())
                .append("\">");

            String headingClass = hasVisual
                ? "scene-heading visual-match clip-visual"
                : "scene-heading";
            String heading = scene.heading() == null ? "" : scene.heading();
            html.append("<h3 class=\"").append(headingClass).append("\">")
                .append(escapeHtml(heading))
                .append("</h3>");

            html.append("<p class=\"scene-body\">")
                .append(renderBody(body, spans))
                .append("</p>");

            html.append("</div>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    /**
     * Subsequence-matches {@code transcript}'s words against the scene token
     * list and returns the {@code [startChar, endChar]} of the smallest span
     * that covers all the matches, or {@code null} if fewer than half the
     * transcript words could be aligned.
     *
     * <p>Greedy: for each transcript word, scan forward from the position just
     * after the previous match. Words the scene doesn't contain are silently
     * skipped — they don't break the alignment, they just don't count toward
     * the matched total.
     */
    static int[] findSpan(List<Token> sceneTokens, String transcript) {
        if (sceneTokens.isEmpty() || transcript == null || transcript.isBlank()) {
            return null;
        }
        List<String> transcriptWords = tokenizeWords(transcript);
        if (transcriptWords.isEmpty()) {
            return null;
        }

        int sceneIdx = 0;
        int matched = 0;
        int firstStart = -1;
        int lastEnd = -1;

        for (String tw : transcriptWords) {
            for (int j = sceneIdx; j < sceneTokens.size(); j++) {
                if (sceneTokens.get(j).word().equals(tw)) {
                    if (firstStart < 0) {
                        firstStart = sceneTokens.get(j).start();
                    }
                    lastEnd = sceneTokens.get(j).end();
                    sceneIdx = j + 1;
                    matched++;
                    break;
                }
            }
        }

        // Fewer than half the transcript words aligned → not a reliable span.
        if (firstStart < 0 || matched * 2 < transcriptWords.size()) {
            return null;
        }
        return new int[]{firstStart, lastEnd};
    }

    /**
     * Convenience wrapper that retokenises the scene text from scratch — used
     * by tests and any caller that doesn't already have a token list.
     */
    static int[] findSpan(String sceneText, String transcript) {
        return findSpan(tokenize(sceneText == null ? "" : sceneText), transcript);
    }

    /**
     * Walks the scene body once, splitting it at every span boundary. Each
     * resulting region is wrapped in a single {@code <span>} whose class list
     * is {@code clip-highlight} plus every clip slot whose span covers it.
     * Overlap regions therefore carry multiple {@code clip-N} classes (later
     * classes' CSS wins for properties like {@code background}, which is the
     * intended "stack visually" behaviour).
     */
    private static String renderBody(String body, List<Span> spans) {
        if (body.isEmpty()) {
            return "";
        }
        if (spans.isEmpty()) {
            return escapeHtml(body);
        }

        TreeSet<Integer> boundaries = new TreeSet<>();
        boundaries.add(0);
        boundaries.add(body.length());
        for (Span sp : spans) {
            boundaries.add(Math.max(0, Math.min(body.length(), sp.start())));
            boundaries.add(Math.max(0, Math.min(body.length(), sp.end())));
        }

        StringBuilder out = new StringBuilder(body.length() + 64);
        Integer prev = null;
        for (Integer pos : boundaries) {
            if (prev == null) {
                prev = pos;
                continue;
            }
            int segStart = prev;
            int segEnd = pos;
            prev = pos;
            if (segStart >= segEnd) {
                continue;
            }
            String segment = body.substring(segStart, segEnd);

            List<Integer> activeSlots = new ArrayList<>();
            for (Span sp : spans) {
                if (sp.start() <= segStart && segEnd <= sp.end()) {
                    activeSlots.add(sp.slot());
                }
            }

            if (activeSlots.isEmpty()) {
                out.append(escapeHtml(segment));
            } else {
                StringBuilder classes = new StringBuilder("clip-highlight");
                for (int slot : activeSlots) {
                    classes.append(" clip-").append(slot);
                }
                out.append("<span class=\"").append(classes).append("\">")
                   .append(escapeHtml(segment))
                   .append("</span>");
            }
        }
        return out.toString();
    }

    /** Tokens with original character positions, used for span construction. */
    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (isWordChar(text.charAt(i))) {
                int start = i;
                StringBuilder sb = new StringBuilder();
                while (i < n && isWordChar(text.charAt(i))) {
                    sb.append(Character.toLowerCase(text.charAt(i)));
                    i++;
                }
                tokens.add(new Token(sb.toString(), start, i));
            } else {
                i++;
            }
        }
        return tokens;
    }

    /** Plain word list (no positions), used for the transcript side. */
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

    /**
     * Word boundary: Unicode letters, digits, and apostrophes (so contractions
     * like {@code don't} stay whole and align with what whisper.cpp emits).
     */
    private static boolean isWordChar(char c) {
        return Character.isLetter(c) || Character.isDigit(c) || c == '\'';
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
}
