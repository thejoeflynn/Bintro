package com.bintro.export;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Copies clips into per-scene subfolders under an output directory.
 *
 * <p>Matched clips land in {@code Scene_NN/} (zero-padded); unmatched clips
 * (scene 0) land in {@code Unmatched/}. Originals are left in place.
 */
public class Exporter {

    public void export(List<ClipMatch> matches, File outputDir) throws IOException {
        Path root = outputDir.toPath();
        Files.createDirectories(root);

        for (ClipMatch match : matches) {
            Path bucket = root.resolve(folderFor(match.sceneNumber()));
            Files.createDirectories(bucket);

            Path source = match.clip().file().toPath();
            Path target = bucket.resolve(match.clip().filename());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String folderFor(int sceneNumber) {
        if (sceneNumber <= 0) {
            return "Unmatched";
        }
        return String.format("Scene_%02d", sceneNumber);
    }
}
