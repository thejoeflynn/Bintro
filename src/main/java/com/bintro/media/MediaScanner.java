package com.bintro.media;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;

import java.io.File;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class MediaScanner {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
        "mp4", "mov", "mxf", "avi", "r3d"
    );

    /**
     * Scans {@code folder} for video files (non-recursive) and probes each with
     * FFmpeg to extract duration and timecode. Files that fail to open are
     * logged to stderr and skipped — they do not abort the scan.
     */
    public List<Clip> scanFolder(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return List.of();
        }
        File[] children = folder.listFiles();
        if (children == null) {
            return List.of();
        }

        List<Clip> clips = new ArrayList<>();
        for (File f : children) {
            if (!f.isFile() || !isVideoFile(f)) {
                continue;
            }
            try {
                clips.add(probe(f));
            } catch (Exception e) {
                System.err.println("Skipping " + f.getName() + ": " + e.getMessage());
            }
        }
        clips.sort(Comparator.comparing(Clip::filename, String.CASE_INSENSITIVE_ORDER));
        return clips;
    }

    Clip probe(File file) throws FrameGrabber.Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file)) {
            grabber.start();
            long lengthMicros = grabber.getLengthInTime();
            Duration duration = lengthMicros > 0
                ? Duration.of(lengthMicros, ChronoUnit.MICROS)
                : Duration.ZERO;
            String timecode = lookupTimecode(grabber);
            return new Clip(file, file.getName(), duration, timecode);
        }
    }

    private static String lookupTimecode(FFmpegFrameGrabber grabber) {
        String tc = grabber.getVideoMetadata("timecode");
        if (tc != null && !tc.isBlank()) {
            return tc.trim();
        }
        tc = grabber.getMetadata("timecode");
        if (tc != null && !tc.isBlank()) {
            return tc.trim();
        }
        return "";
    }

    private static boolean isVideoFile(File f) {
        String name = f.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return VIDEO_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /**
     * CLI entry point: prints each clip's filename, duration, and timecode.
     * <p>Usage: {@code MediaScanner <folder>}
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: MediaScanner <folder>");
            System.exit(1);
        }
        File folder = new File(args[0]);
        if (!folder.isDirectory()) {
            System.err.println("Not a directory: " + folder.getAbsolutePath());
            System.exit(2);
        }

        List<Clip> clips = new MediaScanner().scanFolder(folder);
        System.out.println("Scanned " + folder.getAbsolutePath());
        System.out.println("Found " + clips.size() + " video file(s):");
        for (Clip c : clips) {
            System.out.printf("  %-50s  duration=%s  timecode=%s%n",
                c.filename(),
                formatDuration(c.duration()),
                c.timecode().isEmpty() ? "<none>" : c.timecode());
        }
    }

    static String formatDuration(Duration d) {
        long totalSeconds = d.toSeconds();
        return String.format("%02d:%02d:%02d.%03d",
            totalSeconds / 3600,
            (totalSeconds % 3600) / 60,
            totalSeconds % 60,
            d.toMillisPart());
    }
}
