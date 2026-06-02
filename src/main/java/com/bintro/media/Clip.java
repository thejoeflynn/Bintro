package com.bintro.media;

import java.io.File;
import java.time.Duration;

public record Clip(File file, String filename, Duration duration, String timecode) {
}
