package com.bintro.parser;

import com.bintro.parser.ScriptElement.ElementType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ScriptParser {

    private static final Pattern SLUGLINE = Pattern.compile(
        "^\\s*(?:\\d+[A-Za-z]?\\s+)?" +
        "(?:INT\\.?/EXT\\.?|EXT\\.?/INT\\.?|I/E\\.?|INT\\.?|EXT\\.?)\\s+\\S.*$",
        Pattern.CASE_INSENSITIVE
    );

    /** Prefixes that look like ALL CAPS character cues but are actually action
     *  callouts, titles, or in-line scene-time markers. */
    private static final String[] NON_CHARACTER_PREFIXES = {
        "TITLE:", "SUPER:", "TITLE CARD:", "SMASH CUT", "MATCH CUT",
        "TIME CUT", "INTERCUT", "BACK TO:", "LATER", "CONTINUOUS",
        "SAME TIME", "MOMENTS LATER"
    };

    /** Max chars on the line *following* a CHARACTER cue for it to still
     *  look like dialogue rather than a full-width action paragraph. */
    private static final int DIALOGUE_LINE_MAX = 60;

    /** Forward-window size for confirming a CHARACTER must be followed by
     *  DIALOGUE/PARENTHETICAL relatively soon. */
    private static final int CHARACTER_LOOKAHEAD = 3;

    /**
     * Parses any supported screenplay format, dispatching by file extension.
     *
     * @throws IllegalArgumentException if the extension is neither .pdf nor .fdx
     */
    public List<Scene> parse(File file) throws IOException {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".pdf")) {
            return parseFromPDF(file);
        }
        if (name.endsWith(".fdx")) {
            return parseFromFDX(file);
        }
        throw new IllegalArgumentException("Unsupported script format: " + file.getName());
    }

    public List<Scene> parseFromPDF(File pdfFile) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            String text = new PDFTextStripper().getText(doc);
            return parseFromText(text);
        }
    }

    public List<Scene> parseFromText(String rawText) {
        List<Scene> scenes = new ArrayList<>();
        String currentHeading = null;
        StringBuilder currentBody = new StringBuilder();
        // Buffer stripped non-empty body lines so we can do multi-pass
        // classification with lookahead/lookbehind once the scene is complete.
        List<String> sceneBodyLines = new ArrayList<>();
        int nextSceneNumber = 1;

        for (String line : rawText.split("\\r?\\n", -1)) {
            if (SLUGLINE.matcher(line).matches()) {
                if (currentHeading != null) {
                    scenes.add(buildScene(nextSceneNumber++, currentHeading,
                        currentBody.toString(), sceneBodyLines));
                    currentBody.setLength(0);
                    sceneBodyLines = new ArrayList<>();
                }
                currentHeading = line.strip();
            } else if (currentHeading != null) {
                // Preserve existing fullText behaviour: append every non-slugline
                // line (including blank lines) so existing matchers and tests
                // see byte-identical content.
                currentBody.append(line).append('\n');
                String stripped = line.strip();
                if (!stripped.isEmpty()) {
                    sceneBodyLines.add(stripped);
                }
            }
        }

        if (currentHeading != null) {
            scenes.add(buildScene(nextSceneNumber, currentHeading,
                currentBody.toString(), sceneBodyLines));
        }

        return scenes;
    }

    /**
     * Builds a {@link Scene} with multi-pass classification of body lines.
     * Pass order:
     * <ol>
     *   <li>Tentative typing: PARENTHETICAL, tentative CHARACTER (syntactic
     *       checks only), or {@code null} = pending.</li>
     *   <li>Verify each tentative CHARACTER against neighbouring lines
     *       (must-have-dialogue-next, must-not-follow-character).</li>
     *   <li>Fill pending lines: previous-was-CHARACTER/PARENTHETICAL → DIALOGUE,
     *       else → ACTION.</li>
     *   <li>Post-filter: a CHARACTER without DIALOGUE/PARENTHETICAL within the
     *       next {@code CHARACTER_LOOKAHEAD} elements is demoted to ACTION.</li>
     *   <li>Repair orphaned DIALOGUE lines whose preceding CHARACTER got
     *       demoted.</li>
     * </ol>
     */
    private static Scene buildScene(int number, String heading, String fullText,
                                    List<String> bodyLines) {
        int n = bodyLines.size();
        ElementType[] types = new ElementType[n];

        // Pass 1 — tentative syntactic typing.
        for (int i = 0; i < n; i++) {
            String s = bodyLines.get(i);
            if (s.startsWith("(") && s.endsWith(")")) {
                types[i] = ElementType.PARENTHETICAL;
            } else if (looksLikeCharacterCue(s)) {
                types[i] = ElementType.CHARACTER;
            } else {
                types[i] = null;
            }
        }

        // Pass 2 — verify tentative CHARACTERs against neighbours.
        for (int i = 0; i < n; i++) {
            if (types[i] != ElementType.CHARACTER) {
                continue;
            }
            String next = i + 1 < n ? bodyLines.get(i + 1) : null;
            // Next line must exist AND be a parenthetical OR short enough to
            // plausibly be dialogue rather than an action paragraph.
            boolean nextOk = next != null
                && (isParenthetical(next) || next.length() < DIALOGUE_LINE_MAX);
            // Previous element must not be another CHARACTER candidate —
            // two stacked CHARACTERs is almost always a misclassification.
            boolean prevOk = i == 0 || types[i - 1] != ElementType.CHARACTER;
            if (!nextOk || !prevOk) {
                types[i] = null;
            }
        }

        // Pass 3 — fill pending lines based on the now-stable previous type.
        for (int i = 0; i < n; i++) {
            if (types[i] != null) {
                continue;
            }
            ElementType prev = i > 0 ? types[i - 1] : null;
            types[i] = (prev == ElementType.CHARACTER || prev == ElementType.PARENTHETICAL)
                ? ElementType.DIALOGUE
                : ElementType.ACTION;
        }

        // Pass 4 — CHARACTER must be followed by DIALOGUE/PARENTHETICAL within
        // CHARACTER_LOOKAHEAD elements, else demote.
        for (int i = 0; i < n; i++) {
            if (types[i] != ElementType.CHARACTER) {
                continue;
            }
            int limit = Math.min(n, i + 1 + CHARACTER_LOOKAHEAD);
            boolean found = false;
            for (int j = i + 1; j < limit; j++) {
                if (types[j] == ElementType.DIALOGUE || types[j] == ElementType.PARENTHETICAL) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                types[i] = ElementType.ACTION;
            }
        }

        // Pass 5 — repair orphaned DIALOGUE whose preceding CHARACTER was
        // demoted in pass 4.
        for (int i = 0; i < n; i++) {
            if (types[i] != ElementType.DIALOGUE) {
                continue;
            }
            ElementType prev = i > 0 ? types[i - 1] : null;
            if (prev != ElementType.CHARACTER && prev != ElementType.PARENTHETICAL) {
                types[i] = ElementType.ACTION;
            }
        }

        List<ScriptElement> elements = new ArrayList<>(n + 1);
        elements.add(new ScriptElement(ElementType.SCENE_HEADING, heading));
        for (int i = 0; i < n; i++) {
            elements.add(new ScriptElement(types[i], bodyLines.get(i)));
        }
        return new Scene(number, heading, fullText.strip(), List.copyOf(elements));
    }

    /**
     * Pure syntactic checks for a CHARACTER cue (conditions 1–5 from the spec).
     * Higher-level structural checks (lookahead, lookbehind, demotion) happen
     * in the multi-pass {@link #buildScene} flow.
     */
    private static boolean looksLikeCharacterCue(String stripped) {
        int len = stripped.length();
        if (len < 2 || len > 40) {
            return false;
        }
        if (SLUGLINE.matcher(stripped).matches()) {
            return false;
        }
        if (!hasLetter(stripped)) {
            return false;
        }
        // No lowercase letters at all.
        for (int i = 0; i < len; i++) {
            if (Character.isLowerCase(stripped.charAt(i))) {
                return false;
            }
        }
        // Transitions like "CUT TO:" / "FADE TO:".
        if (stripped.endsWith("TO:")) {
            return false;
        }
        for (String prefix : NON_CHARACTER_PREFIXES) {
            if (stripped.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isParenthetical(String stripped) {
        return stripped.startsWith("(") && stripped.endsWith(")");
    }

    private static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public List<Scene> parseFromFDX(File fdxFile) throws IOException {
        try (InputStream in = new FileInputStream(fdxFile)) {
            return parseFromFDX(in);
        }
    }

    public List<Scene> parseFromFDX(InputStream xmlInput) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Harden against XXE — .fdx is a user-supplied file
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(xmlInput);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse FDX XML", e);
        }

        NodeList paragraphs = doc.getElementsByTagName("Paragraph");
        List<Scene> scenes = new ArrayList<>();
        String currentHeading = null;
        StringBuilder currentBody = new StringBuilder();
        List<ScriptElement> currentElements = new ArrayList<>();
        int nextSceneNumber = 1;

        for (int i = 0; i < paragraphs.getLength(); i++) {
            Element p = (Element) paragraphs.item(i);
            String type = p.getAttribute("Type");
            String text = textOf(p);

            if ("Scene Heading".equals(type)) {
                if (currentHeading != null) {
                    scenes.add(new Scene(nextSceneNumber++, currentHeading,
                        currentBody.toString().strip(), List.copyOf(currentElements)));
                    currentBody.setLength(0);
                    currentElements = new ArrayList<>();
                }
                currentHeading = text.strip();
                currentElements.add(new ScriptElement(ElementType.SCENE_HEADING, currentHeading));
            } else if (currentHeading != null) {
                // fullText keeps the legacy "Action + Dialogue only" contract.
                if ("Action".equals(type) || "Dialogue".equals(type)) {
                    currentBody.append(text).append('\n');
                }
                // elements is the full structured view of every paragraph type.
                currentElements.add(new ScriptElement(mapFdxType(type), text.strip()));
            }
        }

        if (currentHeading != null) {
            scenes.add(new Scene(nextSceneNumber, currentHeading,
                currentBody.toString().strip(), List.copyOf(currentElements)));
        }

        return scenes;
    }

    private static ElementType mapFdxType(String fdxType) {
        return switch (fdxType) {
            case "Scene Heading" -> ElementType.SCENE_HEADING;
            case "Action" -> ElementType.ACTION;
            case "Character" -> ElementType.CHARACTER;
            case "Dialogue" -> ElementType.DIALOGUE;
            case "Parenthetical" -> ElementType.PARENTHETICAL;
            case "Transition" -> ElementType.TRANSITION;
            default -> ElementType.OTHER;
        };
    }

    private static String textOf(Element paragraph) {
        NodeList texts = paragraph.getElementsByTagName("Text");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.getLength(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(texts.item(i).getTextContent());
        }
        return sb.toString();
    }
}
