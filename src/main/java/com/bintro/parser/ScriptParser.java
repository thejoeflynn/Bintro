package com.bintro.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ScriptParser {

    private static final Pattern SLUGLINE = Pattern.compile(
        "^\\s*(?:\\d+[A-Za-z]?\\s+)?" +
        "(?:INT\\.?/EXT\\.?|EXT\\.?/INT\\.?|I/E\\.?|INT\\.?|EXT\\.?)\\s+\\S.*$",
        Pattern.CASE_INSENSITIVE
    );

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
        int nextSceneNumber = 1;

        for (String line : rawText.split("\\r?\\n", -1)) {
            if (SLUGLINE.matcher(line).matches()) {
                if (currentHeading != null) {
                    scenes.add(new Scene(nextSceneNumber++, currentHeading, currentBody.toString().strip()));
                    currentBody.setLength(0);
                }
                currentHeading = line.strip();
            } else if (currentHeading != null) {
                currentBody.append(line).append('\n');
            }
        }

        if (currentHeading != null) {
            scenes.add(new Scene(nextSceneNumber, currentHeading, currentBody.toString().strip()));
        }

        return scenes;
    }
}
