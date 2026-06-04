package com.bintro.parser;

import com.bintro.parser.PositionAwareTextStripper.TextChunk;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pixel-accurate position lookup for screenplay scenes. Walks a PDF with
 * {@link PositionAwareTextStripper} to record each scene heading's page +
 * Y coordinate (PDF user-space points, top-down) and the position of every
 * line that follows the heading until the next one.
 *
 * <p>After Phase-1 matching completes, {@link #findTranscriptSpan} runs a
 * subsequence match of the clip's transcript against the scene's lines to
 * return the exact {@code [startPage, startY, endPage, endY]} the clip
 * covers. The viewer translates those points into pixel offsets on the
 * rendered page images and draws gutter-bar segments that align with the
 * actual dialogue rather than a uniform-distribution estimate.
 */
public class ScenePositionIndex {

    /**
     * Local copy of the slugline regex from {@link ScriptParser}. The
     * constraint not to modify {@code ScriptParser} forbids exposing the
     * pattern there, so we mirror it here. Any change to the canonical
     * pattern needs to be reflected in both places.
     */
    private static final Pattern SLUGLINE = Pattern.compile(
        "^\\s*(?:\\d+[A-Za-z]?\\s+)?" +
        "(?:INT\\.?/EXT\\.?|EXT\\.?/INT\\.?|I/E\\.?|INT\\.?|EXT\\.?)\\s+\\S.*$",
        Pattern.CASE_INSENSITIVE
    );

    /** Two chunks within this many PDF points on the same page count as
     *  belonging to the same logical line. */
    private static final float LINE_GROUPING_TOL_PT = 2.0f;

    /** Below this fraction of transcript words aligned, the span is rejected. */
    private static final double TRANSCRIPT_MATCH_MIN_RATIO = 0.3;

    public record LinePosition(String text, int page, float y) {
    }

    public record ScenePosition(int sceneNumber,
                                int headingPage,
                                float headingY,
                                List<LinePosition> lines) {
    }

    private final Map<Integer, ScenePosition> index = new LinkedHashMap<>();

    /**
     * Builds the index by running a {@link PositionAwareTextStripper} over
     * the PDF and matching detected scene headings to the supplied parsed
     * {@code scenes} list (by normalised heading text).
     */
    public static ScenePositionIndex build(File pdfFile, List<Scene> scenes)
            throws IOException {
        if (scenes == null) {
            scenes = List.of();
        }
        ScenePositionIndex result = new ScenePositionIndex();

        List<TextChunk> chunks;
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PositionAwareTextStripper stripper = new PositionAwareTextStripper();
            chunks = stripper.extract(doc);
        }
        List<LinePosition> lines = groupChunksIntoLines(chunks);

        // Walk lines, demarcating scene blocks at each slugline. Lines between
        // one slugline and the next belong to that scene.
        List<LinePosition> detectedHeadings = new ArrayList<>();
        List<List<LinePosition>> detectedBodies = new ArrayList<>();
        List<LinePosition> currentBody = null;

        for (LinePosition line : lines) {
            if (SLUGLINE.matcher(line.text()).matches()) {
                detectedHeadings.add(line);
                currentBody = new ArrayList<>();
                detectedBodies.add(currentBody);
            } else if (currentBody != null) {
                currentBody.add(line);
            }
        }

        // Match each parsed Scene to a detected heading by normalised text.
        // Each detected heading can only be consumed once; we walk both lists
        // in order and skip mismatches gracefully.
        int detectedCursor = 0;
        for (Scene scene : scenes) {
            String want = normalise(scene.heading());
            int matchedAt = -1;
            for (int j = detectedCursor; j < detectedHeadings.size(); j++) {
                if (normalise(detectedHeadings.get(j).text()).equals(want)) {
                    matchedAt = j;
                    break;
                }
            }
            if (matchedAt < 0) {
                // Parsed scene heading not found in PDF text — likely a
                // formatting quirk. Skip; updateGutterBars falls back to
                // the uniform heuristic for this scene.
                continue;
            }
            LinePosition heading = detectedHeadings.get(matchedAt);
            List<LinePosition> body = detectedBodies.get(matchedAt);
            result.index.put(scene.sceneNumber(),
                new ScenePosition(scene.sceneNumber(),
                    heading.page(), heading.y(), List.copyOf(body)));
            detectedCursor = matchedAt + 1;
        }
        return result;
    }

    /** Returns the indexed position for {@code sceneNumber}, or {@code null}. */
    public ScenePosition get(int sceneNumber) {
        return index.get(sceneNumber);
    }

    /**
     * Subsequence-matches {@code transcript}'s words against the scene's
     * lines. Returns the spanning rectangle as
     * {@code [startPage, startY, endPage, endY]} in PDF points, or
     * {@code null} if fewer than 30 % of transcript words align.
     */
    public float[] findTranscriptSpan(int sceneNumber, String transcript) {
        ScenePosition pos = index.get(sceneNumber);
        if (pos == null || pos.lines().isEmpty()
            || transcript == null || transcript.isBlank()) {
            return null;
        }

        List<String> transcriptWords = tokenize(transcript);
        if (transcriptWords.isEmpty()) {
            return null;
        }

        // Build a flat (word, lineIdx) stream over the scene's lines.
        List<String> sceneWords = new ArrayList<>();
        List<Integer> sceneLineIdx = new ArrayList<>();
        List<LinePosition> lines = pos.lines();
        for (int li = 0; li < lines.size(); li++) {
            for (String w : tokenize(lines.get(li).text())) {
                sceneWords.add(w);
                sceneLineIdx.add(li);
            }
        }
        if (sceneWords.isEmpty()) {
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
            || matched < transcriptWords.size() * TRANSCRIPT_MATCH_MIN_RATIO) {
            return null;
        }
        LinePosition first = lines.get(firstLine);
        LinePosition last = lines.get(lastLine);
        return new float[]{first.page(), first.y(), last.page(), last.y()};
    }

    /**
     * Coalesces chunks emitted at the same Y on the same page into a single
     * {@link LinePosition} whose text is the chunks joined with spaces.
     */
    private static List<LinePosition> groupChunksIntoLines(List<TextChunk> chunks) {
        List<LinePosition> lines = new ArrayList<>();
        int curPage = -1;
        float curY = Float.NaN;
        StringBuilder curText = new StringBuilder();

        for (TextChunk chunk : chunks) {
            boolean sameLine = chunk.page() == curPage
                && !Float.isNaN(curY)
                && Math.abs(chunk.y() - curY) < LINE_GROUPING_TOL_PT;
            if (sameLine) {
                if (curText.length() > 0) {
                    curText.append(' ');
                }
                curText.append(chunk.text());
            } else {
                if (curText.length() > 0) {
                    lines.add(new LinePosition(curText.toString().strip(), curPage, curY));
                }
                curPage = chunk.page();
                curY = chunk.y();
                curText.setLength(0);
                curText.append(chunk.text());
            }
        }
        if (curText.length() > 0) {
            lines.add(new LinePosition(curText.toString().strip(), curPage, curY));
        }
        return lines;
    }

    private static String normalise(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase().replaceAll("\\s+", " ").strip();
    }

    private static List<String> tokenize(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) || Character.isDigit(c) || c == '\'') {
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
}
