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

    /** Heuristic CHARACTER cue: ALL CAPS, 2–40 chars, no lowercase letters. */
    private static final Pattern CHARACTER_CUE = Pattern.compile(
        "^[^a-z]{2,40}$"
    );

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
        List<ScriptElement> currentElements = new ArrayList<>();
        ElementType previousType = null;
        int nextSceneNumber = 1;

        for (String line : rawText.split("\\r?\\n", -1)) {
            if (SLUGLINE.matcher(line).matches()) {
                if (currentHeading != null) {
                    scenes.add(new Scene(nextSceneNumber++, currentHeading,
                        currentBody.toString().strip(), List.copyOf(currentElements)));
                    currentBody.setLength(0);
                    currentElements = new ArrayList<>();
                }
                currentHeading = line.strip();
                currentElements.add(new ScriptElement(ElementType.SCENE_HEADING, currentHeading));
                previousType = ElementType.SCENE_HEADING;
            } else if (currentHeading != null) {
                // Preserve existing fullText behaviour: append every non-slugline
                // line (including blank lines) so existing matchers and tests
                // see byte-identical content.
                currentBody.append(line).append('\n');

                String stripped = line.strip();
                if (stripped.isEmpty()) {
                    continue;
                }
                ElementType type = classifyTextLine(stripped, previousType);
                currentElements.add(new ScriptElement(type, stripped));
                previousType = type;
            }
        }

        if (currentHeading != null) {
            scenes.add(new Scene(nextSceneNumber, currentHeading,
                currentBody.toString().strip(), List.copyOf(currentElements)));
        }

        return scenes;
    }

    /**
     * Heuristic line classification for the PDF path (FDX paragraphs carry
     * explicit types). Order matters — earlier rules take precedence.
     */
    private static ElementType classifyTextLine(String stripped, ElementType previous) {
        if (SLUGLINE.matcher(stripped).matches()) {
            return ElementType.SCENE_HEADING;
        }
        if (stripped.startsWith("(") && stripped.endsWith(")")) {
            return ElementType.PARENTHETICAL;
        }
        if (CHARACTER_CUE.matcher(stripped).matches() && hasLetter(stripped)) {
            return ElementType.CHARACTER;
        }
        if (previous == ElementType.CHARACTER || previous == ElementType.PARENTHETICAL) {
            return ElementType.DIALOGUE;
        }
        return ElementType.ACTION;
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
