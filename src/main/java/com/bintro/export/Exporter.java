package com.bintro.export;

import com.bintro.parser.Scene;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Copies clips into per-scene subfolders under an output directory, renaming
 * each one to an editor-friendly {@code {sceneNN}_{counterNNN}.{ext}} form.
 *
 * <p>Matched clips land in {@code Scene_NN/} (zero-padded); unmatched clips
 * (scene 0) land in {@code Unmatched/}. Originals are left in place.
 *
 * <p>After all files are copied, a {@code rename_log.csv} is written to the
 * root of the output directory so the original filenames can be recovered
 * later.
 */
public class Exporter {

    /** Default extension when a clip's filename has no dot. */
    private static final String DEFAULT_EXTENSION = "mp4";

    /**
     * Copies each match into its scene folder under {@code outputDir} and
     * writes the rename log. If {@link ClipMatch#exportName()} is set on a
     * row, that user-typed name wins; otherwise a counter-driven name is
     * computed and incremented to avoid collisions with files already in the
     * target directory.
     */
    public void export(List<ClipMatch> matches, List<Scene> scenes, File outputDir)
            throws IOException {
        Path root = outputDir.toPath();
        Files.createDirectories(root);

        Map<Integer, String> headingByScene = new HashMap<>();
        if (scenes != null) {
            for (Scene s : scenes) {
                headingByScene.put(s.sceneNumber(), s.heading());
            }
        }

        // Group preserves table order within each scene so the counter
        // assignment mirrors what the UI showed.
        Map<Integer, List<ClipMatch>> byScene = matches.stream()
            .collect(Collectors.groupingBy(ClipMatch::sceneNumber,
                LinkedHashMap::new, Collectors.toList()));

        String exportTime = LocalDateTime.now()
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        List<LogRow> logRows = new ArrayList<>(matches.size());

        for (Map.Entry<Integer, List<ClipMatch>> entry : byScene.entrySet()) {
            int sceneNumber = entry.getKey();
            List<ClipMatch> sceneClips = entry.getValue();
            Path bucket = root.resolve(folderFor(sceneNumber));
            Files.createDirectories(bucket);

            int counter = 1;
            for (ClipMatch match : sceneClips) {
                String ext = getExtension(match.clip().filename()).toLowerCase();
                String userBase = match.exportName();
                String newBase;
                Path target;
                if (userBase != null && !userBase.isBlank()) {
                    // User-typed name — trust it. Sanitise just enough that
                    // the filesystem accepts it.
                    newBase = sanitise(userBase.trim());
                    target = bucket.resolve(newBase + "." + ext);
                } else {
                    newBase = computeBase(sceneNumber, counter);
                    target = bucket.resolve(newBase + "." + ext);
                    while (Files.exists(target)) {
                        counter++;
                        newBase = computeBase(sceneNumber, counter);
                        target = bucket.resolve(newBase + "." + ext);
                    }
                    counter++;
                }

                Files.copy(match.clip().file().toPath(), target,
                    StandardCopyOption.REPLACE_EXISTING);

                String heading = sceneNumber > 0
                    ? headingByScene.getOrDefault(sceneNumber, "Unknown")
                    : "Unmatched";
                logRows.add(new LogRow(
                    match.clip().filename(),
                    target.getFileName().toString(),
                    sceneNumber,
                    heading,
                    formatDuration(match.clip().duration()),
                    exportTime));
            }
        }

        writeRenameLog(root, logRows);
    }

    /** Back-compat overload — passes an empty scene list so the log uses
     *  "Unmatched"/"Unknown" but the copy still works. */
    public void export(List<ClipMatch> matches, File outputDir) throws IOException {
        export(matches, List.of(), outputDir);
    }

    static String folderFor(int sceneNumber) {
        if (sceneNumber <= 0) {
            return "Unmatched";
        }
        return String.format("Scene_%02d", sceneNumber);
    }

    /** Spec-form base: {@code 01_001} or {@code Unmatched_001}. No extension. */
    public static String computeBase(int sceneNumber, int counter) {
        if (sceneNumber <= 0) {
            return String.format("Unmatched_%03d", counter);
        }
        return String.format("%02d_%03d", sceneNumber, counter);
    }

    private static String getExtension(String filename) {
        if (filename == null) {
            return DEFAULT_EXTENSION;
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot < filename.length() - 1
            ? filename.substring(dot + 1)
            : DEFAULT_EXTENSION;
    }

    /** Drops slashes / nul bytes / leading dots from user-typed names. */
    private static String sanitise(String name) {
        String cleaned = name.replaceAll("[\\\\/\\u0000]", "_");
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.isBlank() ? "clip" : cleaned;
    }

    /** Formats a {@link Duration} as {@code HH:MM:SS.mmm}. */
    static String formatDuration(Duration d) {
        if (d == null || d.isNegative()) {
            return "00:00:00.000";
        }
        long totalMs = d.toMillis();
        long h = totalMs / 3_600_000;
        long m = (totalMs % 3_600_000) / 60_000;
        long s = (totalMs % 60_000) / 1000;
        long ms = totalMs % 1000;
        return String.format("%02d:%02d:%02d.%03d", h, m, s, ms);
    }

    private static void writeRenameLog(Path root, List<LogRow> rows) throws IOException {
        Path log = root.resolve("rename_log.csv");
        try (BufferedWriter w = Files.newBufferedWriter(log)) {
            w.write("Original Filename,New Filename,Scene Number,Scene Heading,Clip Duration,Export Time");
            w.newLine();
            for (LogRow r : rows) {
                w.write(csvEscape(r.original()));
                w.write(',');
                w.write(csvEscape(r.newName()));
                w.write(',');
                w.write(Integer.toString(r.sceneNumber()));
                w.write(',');
                w.write(csvEscape(r.heading()));
                w.write(',');
                w.write(csvEscape(r.duration()));
                w.write(',');
                w.write(csvEscape(r.exportTime()));
                w.newLine();
            }
        }
    }

    private static String csvEscape(String s) {
        if (s == null) {
            return "";
        }
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
            || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuote) {
            return s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** Internal value type for the rename log. */
    private record LogRow(String original, String newName, int sceneNumber,
                          String heading, String duration, String exportTime) {
    }
}
